package com.smarttalk.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    var showChats by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }
    var listening by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val speech = remember { SpeechInput(context) }

    // ------------------------------------------------------------ launchers

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            listening = true
            speech.start(
                onPartial = { draft = it },
                onFinal = { draft = it; listening = false },
                onError = { vm.dismissError(); listening = false }
            )
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.attach(it) } }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { vm.scanFolder(it) } }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { saved ->
        if (saved) cameraUri?.let { vm.capturePhoto(it) }
    }

    fun takePhoto() {
        // The camera writes into a file this app owns, handed over as a
        // FileProvider URI. Writing straight to a file:// path would be refused
        // on modern Android.
        val file: File = vm.newCameraFile()
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
        if (uri == null) {
            vm.dismissError()
            return
        }
        cameraUri = uri
        cameraLauncher.launch(uri)
    }

    LaunchedEffect(vm.conversation.messages.size) {
        if (vm.conversation.messages.isNotEmpty()) {
            listState.animateScrollToItem(vm.conversation.messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ------------------------------------------------------------- header
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = vm.conversation.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() }
                )
                TextButton(onClick = { showChats = true }) { Text("Chats") }
                TextButton(onClick = { vm.newChat() }) { Text(stringResource(R.string.new_chat)) }
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.open_settings)) }
            }
        }
        HorizontalDivider()

        // ----------------------------------------------------------- messages
        Box(modifier = Modifier.weight(1f)) {
            if (vm.conversation.messages.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(vm.conversation.messages, key = { it.id }) { message ->
                        MessageBubble(
                            message = message,
                            isStreaming = vm.isStreaming &&
                                message.id == vm.conversation.messages.lastOrNull()?.id,
                            isSpeaking = vm.speakingMessageId == message.id,
                            onToggleSpeak = { vm.toggleSpeak(message) }
                        )
                    }
                }
            }
        }

        // ------------------------------------------------- status and errors
        if (vm.statusText.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = vm.statusText + "\u2026",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        vm.lastNotice?.let { notice ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { vm.dismissNotice() }) { Text("OK") }
            }
        }

        vm.errorText?.let { error ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Assertive }
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { vm.dismissError() }) { Text("Dismiss") }
                }
            }
        }

        HorizontalDivider()

        // ---------------------------------------------------- staged attachments
        if (vm.pendingAttachments.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Attached to your next message:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { vm.clearAttachments() }) { Text("Clear all") }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    vm.pendingAttachments.forEachIndexed { index, attachment ->
                        AttachmentChip(
                            attachment = attachment,
                            onRemove = { vm.removeAttachment(index) }
                        )
                    }
                }
            }
        }

        // -------------------------------------------------------------- input
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.input_hint)) },
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if ((draft.isNotBlank() || vm.pendingAttachments.isNotEmpty()) &&
                            !vm.busy
                        ) {
                            vm.send(draft)
                            draft = ""
                        }
                    }
                )
            )
            Spacer(Modifier.width(8.dp))
            if (vm.busy) {
                Button(onClick = { vm.stop() }) { Text(stringResource(R.string.stop)) }
            } else {
                Button(
                    onClick = {
                        vm.send(draft)
                        draft = ""
                    },
                    enabled = draft.isNotBlank() || vm.pendingAttachments.isNotEmpty()
                ) { Text(stringResource(R.string.send)) }
            }
        }

        // -------------------------------------------------------- action strip
        // Two rows, not one scrolling row. Six controls do not fit a narrow
        // phone, and anything scrolled off the edge is effectively invisible on
        // a touch device - there is no hover and no scrollbar to hint at it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (speech.isAvailable) {
                TextButton(
                    onClick = {
                        if (listening) {
                            speech.stop()
                            listening = false
                        } else {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                listening = true
                                speech.start(
                                    onPartial = { draft = it },
                                    onFinal = { draft = it; listening = false },
                                    onError = { vm.dismissError(); listening = false }
                                )
                            } else {
                                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    }
                ) { Text(if (listening) "Stop voice" else "Voice") }
            }

            TextButton(onClick = { filePicker.launch(ATTACH_MIME_TYPES) }) {
                Text("Attach file")
            }
            TextButton(onClick = { takePhoto() }) { Text("Camera") }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { folderPicker.launch(null) }) { Text("Scan folder") }
            TextButton(
                onClick = {
                    if (draft.isNotBlank()) {
                        vm.generateImage(draft)
                        draft = ""
                    }
                },
                enabled = draft.isNotBlank() && !vm.busy
            ) { Text("Draw image") }
        }

        if (vm.isStreaming) {
            TextButton(
                onClick = { vm.regenerate() },
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
            ) { Text(stringResource(R.string.regenerate)) }
        }
    }

    // ------------------------------------------------------------- dialogs
    if (showChats) {
        AlertDialog(
            onDismissRequest = { showChats = false },
            title = { Text("Saved chats") },
            text = {
                if (vm.savedChats.isEmpty()) {
                    Text("No saved chats yet.")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                        items(vm.savedChats, key = { it.id }) { chat ->
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = chat.title,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    TextButton(onClick = {
                                        vm.openChat(chat.id)
                                        showChats = false
                                    }) { Text("Open") }
                                    TextButton(onClick = { pendingDelete = chat }) { Text("Delete") }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showChats = false }) { Text("Close") }
            }
        )
    }

    // Deletion is confirmed, because it cannot be undone.
    pendingDelete?.let { chat ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this chat?") },
            text = {
                Text(
                    "This permanently removes \u201C${chat.title}\u201D from SmartTalk on this " +
                        "device. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteChat(chat.id)
                    pendingDelete = null
                }) { Text("Delete chat") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Keep it") }
            }
        )
    }
}

/** Types the document picker will show. Anything else is filtered out early. */
private val ATTACH_MIME_TYPES = arrayOf(
    "text/*",
    "application/json",
    "application/xml",
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "image/*"
)

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.empty_state_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Ask a question, attach a file, scan a folder, photograph a page, or " +
                "describe an image to draw. Long answers can be read aloud.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AttachmentChip(attachment: Attachment, onRemove: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when (attachment.origin) {
                    "camera" -> "Photo"
                    "scan" -> "Scan"
                    else -> "File"
                } + ": " + attachment.displayName,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
            TextButton(onClick = onRemove) { Text("Remove") }
        }
    }
}

/** Decode a local image at a sane size. Full-resolution photos would OOM. */
@Composable
private fun LocalImage(path: String, maxDimension: Int = 900, label: String) {
    val bitmap = remember(path) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            var w = bounds.outWidth
            var h = bounds.outHeight
            while (w / 2 >= maxDimension && h / 2 >= maxDimension) {
                w /= 2
                h /= 2
                sample *= 2
            }
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    if (bitmap == null) {
        Text(
            text = "[image no longer available on this device]",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = label,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
        )
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    isStreaming: Boolean,
    isSpeaking: Boolean,
    onToggleSpeak: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val isUser = message.role == Role.User

    val container = when {
        message.failed -> MaterialTheme.colorScheme.errorContainer
        isUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val onContainer = when {
        message.failed -> MaterialTheme.colorScheme.onErrorContainer
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val speaker = if (isUser) "You" else "SmartTalk"
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val copyLabel = stringResource(R.string.copy_message)

    val displayContent = remember(message.content, message.sources) {
        if (isUser) message.content else replaceUrlsWithLabels(message.content, message.sources)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                customActions = listOf(
                    CustomAccessibilityAction(copyLabel) {
                        clipboard.setText(AnnotatedString(message.content))
                        true
                    }
                )
            },
        colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = speaker,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))

            // Images the user attached to this turn.
            message.attachments.filter { it.kind == AttachmentKind.Image }
                .forEach { attachment ->
                    LocalImage(
                        path = attachment.path,
                        label = "Attached image: ${attachment.displayName}"
                    )
                    Spacer(Modifier.height(6.dp))
                }

            // Images generated by the model in this turn.
            if (message.generatedImages.isNotEmpty()) {
                message.generatedImages.forEachIndexed { index, path ->
                    LocalImage(path = path, label = "Generated image ${index + 1}")
                    Spacer(Modifier.height(6.dp))
                }
            }

            if (message.content.isBlank() && isStreaming) {
                Text(
                    text = stringResource(R.string.thinking) + "\u2026",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            } else if (message.content.isNotBlank()) {
                MarkdownContent(
                    // Answers show source NAMES in the prose; the real links are
                    // buttons underneath rather than pasted addresses.
                    text = displayContent,
                    textColor = onContainer,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Citations as real buttons: far easier for TalkBack than spans.
            if (message.sources.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Sources",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                message.sources.forEach { source ->
                    TextButton(
                        onClick = { runCatching { uriHandler.openUri(source.url) } },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = "${source.source}: ${source.title}",
                            style = MaterialTheme.typography.bodySmall,
                            textDecoration = TextDecoration.Underline,
                            maxLines = 2
                        )
                    }
                }
            }

            val urls = remember(message.content) {
                if (isUser) emptyList() else extractUrls(message.content)
            }
            if (urls.isNotEmpty() && message.sources.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Links in this answer",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                urls.forEach { url ->
                    TextButton(
                        onClick = { runCatching { uriHandler.openUri(url) } },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodySmall,
                            textDecoration = TextDecoration.Underline,
                            maxLines = 1
                        )
                    }
                }
            }

            // Read aloud: only for answers with something worth hearing.
            if (!isUser && message.content.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onToggleSpeak) {
                    Text(if (isSpeaking) "Stop reading" else "Read aloud")
                }
            }
        }
    }
}

/**
 * Renders the assistant's markdown. Headings carry real heading semantics so
 * TalkBack navigation by heading works, and lists are announced as list items
 * rather than one long run-on sentence.
 */
@Composable
private fun MarkdownContent(
    text: String,
    textColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    val blocks = remember(text) { parseMarkdown(text) }

    val codeBackground = MaterialTheme.colorScheme.surface
    val boldStyle = SpanStyle(fontWeight = FontWeight.Bold)
    val italicStyle = SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
    val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        background = codeBackground
    )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    Text(
                        text = annotated(block.text, boldStyle, italicStyle, codeStyle),
                        style = style,
                        color = textColor,
                        modifier = Modifier.semantics { heading() }
                    )
                }

                is MdBlock.Paragraph -> Text(
                    text = annotated(block.text, boldStyle, italicStyle, codeStyle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor
                )

                is MdBlock.Bullets -> Column(
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    block.items.forEachIndexed { index, item ->
                        Row {
                            Text(
                                text = if (block.ordered) "${index + 1}." else "\u2022",
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor,
                                modifier = Modifier.width(22.dp)
                            )
                            Text(
                                text = annotated(item, boldStyle, italicStyle, codeStyle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor
                            )
                        }
                    }
                }

                is MdBlock.Code -> Surface(
                    color = codeBackground,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        if (block.language.isNotBlank()) {
                            Text(
                                text = block.language,
                                style = MaterialTheme.typography.labelSmall,
                                color = textColor
                            )
                        }
                        Text(
                            text = block.code,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = textColor
                        )
                    }
                }

                is MdBlock.Quote -> Row {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .heightIn(min = 18.dp)
                            .background(MaterialTheme.colorScheme.outline)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = annotated(block.text, boldStyle, italicStyle, codeStyle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor
                    )
                }

                MdBlock.Rule -> HorizontalDivider()
            }
        }
    }
}

/** Applies inline emphasis ranges recorded by [parseInline]. */
private fun annotated(
    text: String,
    bold: SpanStyle,
    italic: SpanStyle,
    code: SpanStyle
): AnnotatedString {
    val parsed = parseInline(text)
    return buildAnnotatedString {
        append(parsed.text)
        val length = parsed.text.length
        parsed.spans.forEach { span ->
            val start = span.start.coerceIn(0, length)
            val end = span.end.coerceIn(start, length)
            if (start == end) return@forEach
            addStyle(
                when (span.kind) {
                    InlineSpan.Kind.Bold -> bold
                    InlineSpan.Kind.Italic -> italic
                    InlineSpan.Kind.Code -> code
                },
                start,
                end
            )
        }
    }
}
