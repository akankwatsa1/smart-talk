package com.smarttalk.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
    val speech = remember { SpeechInput(context) }

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

    // Follow new messages. Keyed on count alone: keying on the streaming text too
    // would fight a user who scrolls up to read while an answer is still arriving.
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
                                message.id == vm.conversation.messages.lastOrNull()?.id
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

        // -------------------------------------------------------------- input
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
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
                        if (draft.isNotBlank() && !vm.isStreaming) {
                            vm.send(draft)
                            draft = ""
                        }
                    }
                )
            )
            Spacer(Modifier.width(8.dp))
            Column {
                if (vm.isStreaming) {
                    Button(onClick = { vm.stop() }) { Text(stringResource(R.string.stop)) }
                } else {
                    Button(
                        onClick = {
                            if (draft.isNotBlank()) {
                                vm.send(draft)
                                draft = ""
                            }
                        },
                        enabled = draft.isNotBlank()
                    ) { Text(stringResource(R.string.send)) }
                }
                if (speech.isAvailable) {
                    Spacer(Modifier.height(6.dp))
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
                    ) {
                        Text(if (listening) "Stop voice" else "Voice")
                    }
                }
            }
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
            text = stringResource(R.string.empty_state_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, isStreaming: Boolean) {
    val context = LocalContext.current
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

    val copiedNote = stringResource(R.string.copy_message)
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                customActions = listOf(
                    CustomAccessibilityAction(copiedNote) {
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

            if (message.content.isBlank() && isStreaming) {
                // Announced once, politely, rather than on every token.
                Text(
                    text = stringResource(R.string.thinking) + "\u2026",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            } else {
                MarkdownContent(
                    text = message.content,
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
