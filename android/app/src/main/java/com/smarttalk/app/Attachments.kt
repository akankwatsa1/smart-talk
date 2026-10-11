package com.smarttalk.app

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

enum class AttachmentKind { Text, Image }

/**
 * Something the user attached to a message.
 *
 * Text attachments carry their extracted [text], so the model receives content
 * rather than a filename. Image attachments carry a local [path] into the app's
 * private media directory, because on Android a picked file's content:// URI
 * permission is transient and cannot be relied on later.
 */
data class Attachment(
    val kind: AttachmentKind,
    val displayName: String,
    val mimeType: String,
    val text: String = "",
    val path: String = "",
    val byteSize: Long = 0,
    /** "file", "camera" or "scan" - shown to the user so they know where it came from. */
    val origin: String = "file"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind.name)
        put("displayName", displayName)
        put("mimeType", mimeType)
        if (text.isNotEmpty()) put("text", text)
        if (path.isNotEmpty()) put("path", path)
        put("byteSize", byteSize)
        put("origin", origin)
    }

    companion object {
        fun fromJson(o: JSONObject) = Attachment(
            kind = runCatching { AttachmentKind.valueOf(o.optString("kind", "Text")) }
                .getOrDefault(AttachmentKind.Text),
            displayName = o.optString("displayName"),
            mimeType = o.optString("mimeType"),
            text = o.optString("text"),
            path = o.optString("path"),
            byteSize = o.optLong("byteSize"),
            origin = o.optString("origin", "file")
        )
    }
}

/** Result of trying to turn a picked file into an [Attachment]. */
data class AttachOutcome(
    val attachment: Attachment? = null,
    /** A message for the user when the file could not be used. */
    val error: String? = null
)

/**
 * Converts a picked or captured file into something the model can consume.
 *
 * Files become content:// URIs on Android and their read permission is granted
 * only for the moment of picking, so everything is read eagerly here and copied
 * into app-private storage where it survives.
 */
object AttachmentReader {

    /** Cap per-file text so one huge log cannot consume the whole context window. */
    const val MAX_TEXT_CHARS = 60_000

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml", "log",
        "ini", "cfg", "conf", "html", "htm", "css", "js", "ts", "jsx", "tsx",
        "kt", "kts", "java", "py", "rb", "go", "rs", "c", "h", "cpp", "cs",
        "php", "sh", "bat", "ps1", "sql", "srt", "vtt", "tex"
    )

    fun read(context: Context, uri: Uri, store: SmartTalkStore, origin: String = "file"): AttachOutcome {
        val resolver = context.contentResolver
        val name = displayName(context, uri)
        val mime = resolver.getType(uri).orEmpty()
        val size = sizeOf(context, uri)
        val extension = name.substringAfterLast('.', "").lowercase()

        val looksImage = mime.startsWith("image/") ||
            extension in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

        return try {
            if (looksImage) {
                importImage(context, uri, store, name, mime, size, origin)
            } else {
                importDocument(context, uri, name, mime, extension, size, origin)
            }
        } catch (t: Throwable) {
            AttachOutcome(error = "Could not read \"$name\": ${t.message ?: "unknown error"}")
        }
    }

    private fun importImage(
        context: Context,
        uri: Uri,
        store: SmartTalkStore,
        name: String,
        mime: String,
        size: Long,
        origin: String
    ): AttachOutcome {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return AttachOutcome(error = "Could not open \"$name\".")
        if (bytes.isEmpty()) return AttachOutcome(error = "\"$name\" is empty.")

        // Downscale before storing: a 12 MP photo base64-encoded would blow past
        // most providers' request limits and waste tokens for no extra detail.
        val scaled = downscale(bytes, MAX_IMAGE_DIMENSION) ?: bytes
        val file = store.writeMedia("attach_${System.currentTimeMillis()}.jpg", scaled)

        // Read the true pixel size, since providers bill and cap by it.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(scaled, 0, scaled.size, bounds)
        val dimensions = if (bounds.outWidth > 0) " (${bounds.outWidth}x${bounds.outHeight})" else ""

        return AttachOutcome(
            attachment = Attachment(
                kind = AttachmentKind.Image,
                displayName = name + dimensions,
                mimeType = "image/jpeg",
                path = file.absolutePath,
                byteSize = file.length().takeIf { it > 0 } ?: size,
                origin = origin
            )
        )
    }

    private fun importDocument(
        context: Context,
        uri: Uri,
        name: String,
        mime: String,
        extension: String,
        size: Long,
        origin: String
    ): AttachOutcome {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return AttachOutcome(error = "Could not open \"$name\".")
        if (bytes.isEmpty()) return AttachOutcome(error = "\"$name\" is empty.")

        val extracted: String = when {
            extension == "docx" || mime.contains("wordprocessingml") -> extractDocx(bytes)
            extension == "pdf" || mime == "application/pdf" -> return AttachOutcome(
                error = "\"$name\" is a PDF, which cannot be read as text on the device. " +
                    "Open it, take a screenshot of each page, and attach those images instead - " +
                    "the image understanding account can read them."
            )
            extension in setOf("xlsx", "pptx", "odt", "ods", "odp") -> return AttachOutcome(
                error = "\"$name\" is a ${extension.uppercase()} file. Export it as CSV or " +
                    "plain text first, then attach that."
            )
            extension in TEXT_EXTENSIONS || mime.startsWith("text/") -> {
                String(bytes, Charsets.UTF_8)
            }
            else -> String(bytes, Charsets.UTF_8)
        }

        if (extracted.isBlank() || extracted.count { it.isLetterOrDigit() } < 8) {
            return AttachOutcome(
                error = "\"$name\" did not contain readable text. If it is a scan, attach it " +
                    "as an image instead so the image understanding account can read it."
            )
        }

        val truncated = if (extracted.length > MAX_TEXT_CHARS) {
            extracted.take(MAX_TEXT_CHARS) +
                "\n\n[... truncated at ${MAX_TEXT_CHARS} of ${extracted.length} characters ...]"
        } else {
            extracted
        }

        return AttachOutcome(
            attachment = Attachment(
                kind = AttachmentKind.Text,
                displayName = name,
                mimeType = mime.ifBlank { "text/plain" },
                text = truncated,
                byteSize = size.takeIf { it > 0 } ?: bytes.size.toLong(),
                origin = origin
            )
        )
    }

    /**
     * Pull the text out of a .docx. A Word file is a zip whose body lives in
     * word/document.xml, so this needs no library: unzip, keep the text runs,
     * and turn paragraph and line breaks into newlines.
     */
    fun extractDocx(bytes: ByteArray): String {
        val out = StringBuilder()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    out.append(docxXmlToText(xml))
                    break
                }
                entry = zip.nextEntry
            }
        }
        return out.toString().trim()
    }

    /** Visible for testing: the XML-to-text rules for a Word document body. */
    fun docxXmlToText(xml: String): String {
        var s = xml
        s = s.replace(Regex("(?i)<w:tab\\s*/?>"), "\t")
        s = s.replace(Regex("(?i)<w:br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</w:p>"), "\n")
        s = s.replace(Regex("(?i)</w:tr>"), "\n")
        s = s.replace(Regex("(?i)</w:tc>"), " | ")
        s = s.replace(Regex("<[^>]+>"), "")
        s = WebSearch.decodeEntities(s)
        return s.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /** Best-effort JPEG downscale. Falls back to the original on any failure. */
    fun downscale(bytes: ByteArray, maxDimension: Int): ByteArray? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) {
            null
        } else {
            var sample = 1
            var w = bounds.outWidth
            var h = bounds.outHeight
            while (w / 2 >= maxDimension && h / 2 >= maxDimension) {
                w /= 2
                h /= 2
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            ByteArrayOutputStream().use { buffer ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, buffer)
                bitmap.recycle()
                buffer.toByteArray()
            }
        }
    } catch (t: Throwable) {
        null
    }

    private const val MAX_IMAGE_DIMENSION = 1280

    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(index) ?: "attachment"
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
    }

    private fun sizeOf(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                return cursor.getLong(index)
            }
        }
        0L
    }.getOrDefault(0L)
}
