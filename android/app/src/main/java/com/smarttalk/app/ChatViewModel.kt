package com.smarttalk.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Holds all conversation, attachment, account and speech state. Kept
 * deliberately small: one place owns the active conversation, and every mutation
 * that must survive a restart goes straight to [SmartTalkStore].
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SmartTalkStore(app)
    private val chatApi = ChatApi()
    private val search = WebSearch()
    private val imageGen = ImageGenApi(store)

    var settings by mutableStateOf(store.loadSettings())
        private set

    var conversation by mutableStateOf(Conversation(Conversation.newId(), "New chat"))
        private set

    var savedChats by mutableStateOf(store.listConversations())
        private set

    var isStreaming by mutableStateOf(false)
        private set

    var isGeneratingImage by mutableStateOf(false)
        private set

    /** Transient progress line, e.g. "Searching the web". Announced politely. */
    var statusText by mutableStateOf("")
        private set

    var errorText by mutableStateOf<String?>(null)
        private set

    /** Files staged for the next message. */
    var pendingAttachments by mutableStateOf<List<Attachment>>(emptyList())
        private set

    var isSpeaking by mutableStateOf(false)
        private set

    /** Message id currently being read aloud, so only one shows "Stop reading". */
    var speakingMessageId by mutableStateOf<Long?>(null)
        private set

    // Declared after the state it writes to, so the callbacks can never run
    // before those properties exist.
    private val speaker = Speaker(app).apply {
        onStateChange = { speaking ->
            // Qualify the receiver: inside apply{}, a bare `isSpeaking` would
            // resolve to Speaker's own private property instead of this one.
            this@ChatViewModel.isSpeaking = speaking
            if (!speaking) this@ChatViewModel.speakingMessageId = null
        }
        onMessage = { message -> this@ChatViewModel.errorText = message }
    }

    /** Used only when the cloud read-aloud capability is routed at an account. */
    private val cloudVoice = CloudVoice(store).apply {
        onStateChange = { speaking ->
            this@ChatViewModel.isSpeaking = speaking
            if (!speaking) this@ChatViewModel.speakingMessageId = null
        }
    }

    init {
        // Bring forward a key typed into an earlier build of this app.
        store.migrateLegacySettingsIfNeeded()?.let { settings = it }
    }

    val busy: Boolean get() = isStreaming || isGeneratingImage

    // --------------------------------------------------------------- sending

    fun send(text: String) {
        val prompt = text.trim()
        val attachments = pendingAttachments
        if ((prompt.isEmpty() && attachments.isEmpty()) || busy) return
        errorText = null

        val effectivePrompt = prompt.ifEmpty {
            if (attachments.any { it.kind == AttachmentKind.Image }) {
                "Describe or read this image."
            } else {
                "Summarise the attached file."
            }
        }

        if (conversation.messages.none { it.role == Role.User }) {
            conversation = conversation.copy(title = titleFrom(effectivePrompt))
        }

        conversation = conversation.copy(
            messages = conversation.messages + ChatMessage(
                role = Role.User,
                content = effectivePrompt,
                attachments = attachments
            ),
            updatedAt = System.currentTimeMillis()
        )
        pendingAttachments = emptyList()
        respond()
    }

    /** Re-answer the last user turn, discarding the previous answer. */
    fun regenerate() {
        if (busy) return
        val messages = conversation.messages
        val lastUser = messages.indexOfLast { it.role == Role.User }
        if (lastUser < 0) return
        conversation = conversation.copy(messages = messages.take(lastUser + 1))
        errorText = null
        respond()
    }

    private fun respond() {
        val history = conversation.messages
        val latestUser = history.lastOrNull { it.role == Role.User }
        val needsVision = latestUser?.attachments?.any { it.kind == AttachmentKind.Image } == true

        // Route to the vision account when the message carries an image, provided
        // one is actually configured. Otherwise fall back to the chat account,
        // which is still correct for text-only turns.
        val visionAccount = settings.accountFor(Capability.Vision)
        val account = if (needsVision && settings.hasDedicatedAccount(Capability.Vision) &&
            visionAccount != null
        ) {
            visionAccount
        } else {
            settings.chatAccount()
        }

        if (!account.isConfigured) {
            errorText = if (account.apiKey.isBlank() && !account.isLocalEndpoint) {
                getApplication<Application>().getString(R.string.error_no_api_key)
            } else {
                "The account \"${account.label}\" is incomplete. Set its base URL and model in Settings."
            }
            return
        }

        conversation = conversation.copy(
            messages = conversation.messages + ChatMessage(Role.Assistant, ""),
            updatedAt = System.currentTimeMillis()
        )
        isStreaming = true
        statusText = "Thinking"

        viewModelScope.launch {
            val requestHistory = conversation.messages.dropLast(1)
            val question = requestHistory.lastOrNull { it.role == Role.User }?.content.orEmpty()

            var grounding = ""
            var sources: List<SearchResult> = emptyList()

            val shouldSearch = when (settings.searchMode) {
                SearchMode.Off -> false
                SearchMode.Always -> true
                SearchMode.Auto -> looksTimeSensitive(question)
            }

            if (shouldSearch && question.isNotBlank() && !needsVision) {
                statusText = "Searching the web"
                val results = withContext(Dispatchers.IO) {
                    runCatching { search.search(question, settings.maxSearchResults) }
                        .getOrDefault(emptyList())
                }
                sources = results

                val pages = if (settings.fetchPageContent && results.isNotEmpty()) {
                    statusText = "Reading the top pages"
                    withContext(Dispatchers.IO) {
                        runCatching {
                            search.fetchPages(results, settings.maxPagesToFetch, PAGE_CHAR_BUDGET)
                        }.getOrDefault(emptyList())
                    }
                } else {
                    emptyList()
                }

                grounding = search.buildGrounding(results, pages, todayIso())
                statusText = "Thinking"
            }

            val systemPrompt = buildString {
                append(settings.systemPrompt)
                append("\n\nThe current date is ${todayIso()}. ")
                append("Treat that as today when the user refers to now or the present.")
                if (grounding.isNotBlank()) {
                    append("\n\n")
                    append(grounding)
                }
            }

            val builder = StringBuilder()
            var lastFlush = 0L

            withContext(Dispatchers.Main) {
                chatApi.streamChat(
                    account = account,
                    systemPrompt = systemPrompt,
                    history = requestHistory,
                    temperature = settings.temperature,
                    topP = settings.topP,
                    onDelta = { piece ->
                        synchronized(builder) {
                            builder.append(piece)
                            val now = System.currentTimeMillis()
                            // Throttle recomposition: streaming can emit hundreds of
                            // tiny deltas per second, and each one is a redraw.
                            if (now - lastFlush >= FLUSH_INTERVAL_MS) {
                                lastFlush = now
                                val snapshot = builder.toString()
                                viewModelScope.launch(Dispatchers.Main) {
                                    replaceLastAssistant(snapshot, sources)
                                }
                            }
                        }
                    },
                    onComplete = {
                        val finalText = synchronized(builder) { builder.toString() }
                        viewModelScope.launch(Dispatchers.Main) {
                            isStreaming = false
                            statusText = ""
                            replaceLastAssistant(
                                finalText.ifBlank { "No response was returned." },
                                sources
                            )
                            persist()
                        }
                    },
                    onError = { message ->
                        val partial = synchronized(builder) { builder.toString() }
                        viewModelScope.launch(Dispatchers.Main) {
                            isStreaming = false
                            statusText = ""
                            errorText = message
                            if (partial.isBlank()) {
                                replaceLastAssistant(message, emptyList(), failed = true)
                            } else {
                                replaceLastAssistant(partial, sources)
                            }
                            persist()
                        }
                    }
                )
            }
        }
    }

    fun stop() {
        chatApi.cancel()
        imageGen.cancel()
        isStreaming = false
        isGeneratingImage = false
        statusText = ""
        val last = conversation.messages.lastOrNull()
        if (last != null && last.role == Role.Assistant && last.content.isBlank() &&
            last.generatedImages.isEmpty()
        ) {
            conversation = conversation.copy(messages = conversation.messages.dropLast(1))
        }
        persist()
    }

    // ------------------------------------------------------- image generation

    /** Generate an image from [prompt] using the image-generation account. */
    fun generateImage(prompt: String) {
        val text = prompt.trim()
        if (text.isEmpty() || busy) return

        val account = settings.accountFor(Capability.ImageGen)
        if (account == null || !account.isConfigured) {
            errorText = "Image generation needs an account. Open Settings, create one, and " +
                "point \"Image generation\" at it."
            return
        }
        errorText = null

        if (conversation.messages.none { it.role == Role.User }) {
            conversation = conversation.copy(title = titleFrom(text))
        }
        conversation = conversation.copy(
            messages = conversation.messages + ChatMessage(Role.User, text),
            updatedAt = System.currentTimeMillis()
        )

        val placeholder = ChatMessage(Role.Assistant, "")
        conversation = conversation.copy(messages = conversation.messages + placeholder)
        isGeneratingImage = true
        statusText = "Drawing with ${account.model}"

        viewModelScope.launch {
            withContext(Dispatchers.Main) {
                imageGen.generate(
                    account = account,
                    prompt = text,
                    size = settings.imageSize,
                    count = 1,
                    onDone = { paths ->
                        viewModelScope.launch(Dispatchers.Main) {
                            isGeneratingImage = false
                            statusText = ""
                            replaceLastAssistantImages(
                                "Here is the image I generated for: $text",
                                paths
                            )
                            persist()
                        }
                    },
                    onError = { message ->
                        viewModelScope.launch(Dispatchers.Main) {
                            isGeneratingImage = false
                            statusText = ""
                            errorText = message
                            replaceLastAssistant(message, emptyList(), failed = true)
                            persist()
                        }
                    }
                )
            }
        }
    }

    // -------------------------------------------------------------- speaking

    /** Read [message] aloud, or stop if it is already being read. */
    fun toggleSpeak(message: ChatMessage) {
        if (speakingMessageId == message.id) {
            stopSpeaking()
            return
        }
        if (message.content.isBlank()) return
        errorText = null

        // Prefer the free on-device voice unless the user has both turned that
        // off and routed a cloud voice account.
        val ttsAccount = settings.accountById(settings.routes[Capability.Tts.id])
        val useCloud = !settings.useDeviceVoice && ttsAccount != null && ttsAccount.isConfigured

        speakingMessageId = message.id
        if (useCloud && ttsAccount != null) {
            isSpeaking = true
            cloudVoice.speak(ttsAccount, markdownToSpeech(message.content)) { problem ->
                errorText = problem
                isSpeaking = false
                speakingMessageId = null
            }
        } else {
            speaker.speak(message.content)
        }
    }

    fun stopSpeaking() {
        speaker.stop()
        cloudVoice.stop()
        speakingMessageId = null
    }

    // ----------------------------------------------------------- attachments

    fun attach(uri: Uri) {
        val outcome = AttachmentReader.read(getApplication<Application>(), uri, store)
        applyOutcome(outcome)
    }

    fun capturePhoto(uri: Uri) {
        val outcome = AttachmentReader.read(
            getApplication<Application>(),
            uri,
            store,
            origin = "camera"
        )
        applyOutcome(outcome)
    }

    fun scanFolder(treeUri: Uri) {
        statusText = "Scanning the folder"
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { FolderScanner.scan(getApplication<Application>(), treeUri) }
                    .getOrElse { ScanOutcome(error = it.message ?: "The scan failed.") }
            }
            statusText = ""
            if (outcome.error != null) {
                errorText = outcome.error
            } else {
                outcome.attachment?.let { pendingAttachments = pendingAttachments + it }
                if (outcome.filesRead > 0) {
                    statusText = ""
                    errorText = null
                    lastNotice = "Scanned ${outcome.filesRead} files" +
                        if (outcome.filesSkipped > 0) ", ${outcome.filesSkipped} skipped" else ""
                }
            }
        }
    }

    private fun applyOutcome(outcome: AttachOutcome) {
        if (outcome.error != null) {
            errorText = outcome.error
            return
        }
        outcome.attachment?.let { pendingAttachments = pendingAttachments + it }
    }

    fun removeAttachment(index: Int) {
        pendingAttachments = pendingAttachments.filterIndexed { i, _ -> i != index }
    }

    fun clearAttachments() {
        pendingAttachments = emptyList()
    }

    /** Create a destination file for the camera before launching the capture intent. */
    fun newCameraFile(): java.io.File = store.newImageFile("photo")

    /** A short confirmation line shown under the composer, e.g. after a scan. */
    var lastNotice by mutableStateOf<String?>(null)

    fun dismissNotice() {
        lastNotice = null
    }

    // ------------------------------------------------------------ conversation

    fun newChat() {
        stop()
        conversation = Conversation(Conversation.newId(), "New chat")
        pendingAttachments = emptyList()
        errorText = null
    }

    fun openChat(id: String) {
        stop()
        store.loadConversation(id)?.let {
            conversation = it
            errorText = null
        }
    }

    /** Destructive, and only called after the UI has confirmed it. */
    fun deleteChat(id: String) {
        store.deleteConversation(id)
        savedChats = store.listConversations()
        if (conversation.id == id) newChat()
    }

    fun exportCurrentChat(): String = store.exportAsText(conversation)

    // ---------------------------------------------------------------- settings

    fun updateSettings(next: AppSettings) {
        settings = next
        store.saveSettings(next)
    }

    fun dismissError() {
        errorText = null
    }

    override fun onCleared() {
        super.onCleared()
        chatApi.cancel()
        imageGen.cancel()
        speaker.shutdown()
        cloudVoice.stop()
    }

    // ----------------------------------------------------------------- helpers

    private fun persist() {
        store.saveConversation(conversation)
        savedChats = store.listConversations()
    }

    private fun replaceLastAssistant(
        text: String,
        sources: List<SearchResult>,
        failed: Boolean = false
    ) {
        val messages = conversation.messages.toMutableList()
        val index = messages.indexOfLast { it.role == Role.Assistant }
        if (index >= 0) {
            messages[index] = messages[index].copy(
                content = text,
                sources = sources,
                failed = failed
            )
        }
        conversation = conversation.copy(
            messages = messages,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun replaceLastAssistantImages(caption: String, paths: List<String>) {
        val messages = conversation.messages.toMutableList()
        val index = messages.indexOfLast { it.role == Role.Assistant }
        if (index >= 0) {
            messages[index] = messages[index].copy(
                content = caption,
                generatedImages = paths
            )
        }
        conversation = conversation.copy(
            messages = messages,
            updatedAt = System.currentTimeMillis()
        )
    }

    companion object {
        private const val FLUSH_INTERVAL_MS = 60L

        /** Per-page character budget handed to the model. */
        const val PAGE_CHAR_BUDGET = 7_000

        fun todayIso(): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }
}
