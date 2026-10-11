package com.smarttalk.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** What a folder scan produced. */
data class ScanOutcome(
    val attachment: Attachment? = null,
    val filesRead: Int = 0,
    val filesSkipped: Int = 0,
    val error: String? = null
)

/**
 * Walks a folder the user chose and builds one text attachment from it.
 *
 * The desktop app scanned the raw filesystem with file-scanner.js. Android has
 * no such thing: a folder arrives as a content:// tree URI, and every child is
 * another content URI reached through DocumentsContract. This is the Storage
 * Access Framework equivalent, and it is why the scanner is a port rather than
 * a copy.
 *
 * The scan is bounded on purpose - a phone folder can hold thousands of files,
 * and pasting all of them into one prompt would exceed every model's context
 * window and cost real money.
 */
object FolderScanner {

    const val MAX_FILES = 40
    const val MAX_TOTAL_CHARS = 60_000
    const val MAX_FILE_CHARS = 12_000
    const val MAX_DEPTH = 4

    private val SKIP_DIRECTORIES = setOf(
        "node_modules", ".git", ".gradle", "build", "dist", "__pycache__",
        ".idea", ".vscode", "venv", ".venv", "cache", "Android", "obb"
    )

    fun scan(context: Context, treeUri: Uri): ScanOutcome {
        val resolver = context.contentResolver

        // Keep access across restarts; without this the grant dies with the activity.
        runCatching {
            resolver.takePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return ScanOutcome(error = "That folder could not be opened.")

        val collected = ArrayList<Pair<String, String>>()
        var skipped = 0
        var totalChars = 0
        var truncated = false

        val queue = ArrayDeque<Pair<String, Int>>() // documentId to depth
        queue.add(rootId to 0)
        val visited = HashSet<String>()

        while (queue.isNotEmpty()) {
            if (collected.size >= MAX_FILES || truncated) break
            val (documentId, depth) = queue.removeFirst()
            if (!visited.add(documentId)) continue

            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            )

            val rows = runCatching {
                resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idIndex = cursor.getColumnIndex(projection[0])
                    val nameIndex = cursor.getColumnIndex(projection[1])
                    val mimeIndex = cursor.getColumnIndex(projection[2])
                    val sizeIndex = cursor.getColumnIndex(projection[3])

                    val batch = ArrayList<Triple<String, String, String>>()
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(idIndex) ?: continue
                        val name = cursor.getString(nameIndex) ?: continue
                        val mime = cursor.getString(mimeIndex).orEmpty()
                        val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
                        batch.add(Triple(id, name, mime))
                    }
                    batch
                }
            }.getOrNull().orEmpty()

            for ((id, name, mime) in rows) {
                if (collected.size >= MAX_FILES || truncated) break
                val isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR

                if (isDirectory) {
                    if (depth < MAX_DEPTH && name.lowercase() !in SKIP_DIRECTORIES) {
                        queue.add(id to depth + 1)
                    }
                    continue
                }
                if (name.startsWith(".")) {
                    skipped++
                    continue
                }

                val extension = name.substringAfterLast('.', "").lowercase()
                val readable = extension in READABLE_EXTENSIONS || mime.startsWith("text/")
                if (!readable) {
                    skipped++
                    continue
                }

                val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                val text = runCatching {
                    resolver.openInputStream(fileUri)?.use { stream ->
                        String(stream.readBytes(), Charsets.UTF_8)
                    }
                }.getOrNull()

                if (text.isNullOrBlank() || text.count { it.isLetterOrDigit() } < 4) {
                    skipped++
                    continue
                }

                val clipped = if (text.length > MAX_FILE_CHARS) {
                    text.take(MAX_FILE_CHARS) + "\n[... file truncated ...]"
                } else {
                    text
                }

                if (totalChars + clipped.length > MAX_TOTAL_CHARS) {
                    truncated = true
                    break
                }
                totalChars += clipped.length
                collected.add(name to clipped)
            }
        }

        if (collected.isEmpty()) {
            return ScanOutcome(
                error = "No readable text files were found in that folder " +
                    "($skipped files skipped). Try a folder of .txt, .md, .csv or .docx files."
            )
        }

        val folderName = runCatching {
            DocumentsContract.getTreeDocumentId(treeUri).substringAfterLast(':').ifBlank { "folder" }
        }.getOrDefault("folder")

        val body = buildString {
            appendLine("<scanned_folder name=\"$folderName\">")
            appendLine("Files included: ${collected.size}")
            if (truncated || skipped > 0) {
                appendLine(
                    "Files skipped or omitted: $skipped" +
                        if (truncated) " (size limit reached)" else ""
                )
            }
            appendLine()
            collected.forEach { (name, text) ->
                appendLine("--- $name ---")
                appendLine(text)
                appendLine()
            }
            append("</scanned_folder>")
        }

        return ScanOutcome(
            attachment = Attachment(
                kind = AttachmentKind.Text,
                displayName = "$folderName (${collected.size} files scanned)",
                mimeType = "text/plain",
                text = body,
                byteSize = body.length.toLong(),
                origin = "scan"
            ),
            filesRead = collected.size,
            filesSkipped = skipped
        )
    }

    private val READABLE_EXTENSIONS = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml", "log",
        "ini", "cfg", "conf", "html", "htm", "css", "js", "ts", "kt", "kts",
        "java", "py", "rb", "go", "rs", "c", "h", "cpp", "cs", "php", "sh",
        "sql", "srt", "vtt"
    )
}
