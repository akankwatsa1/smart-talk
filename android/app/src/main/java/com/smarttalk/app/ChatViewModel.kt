package com.smarttalk.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds all conversation and settings state. Kept deliberately small: one place
 * owns the active conversation, and every mutation that must survive a restart
 * goes straight to [SmartTalkStore].
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SmartTalkStore(app)
    private val api = ChatApi()
    private val search = WebSearch()

    var settings by mutableStateOf(store.loadSettings())
        private set

    var conversation by mutableStateOf(Conversation(Conversation.newId(), "New chat"))
        private set

    var savedChats by mutableStateOf(store.listConversations())
        private set

    var isStreaming by mutableStateOf(false)
        private set

    /** Transient progress line, e.g. "Searching the web". Announced politely. */
    var statusText by mutableStateOf("")
        private set

    var errorText by mutableStateOf<String?>(null)
        private set

    // --------------------------------------------------------------- sending

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || isStreaming) return
        errorText = null

        if (conversation.messages.none { it.role == Role.User }) {
            conversation = conversation.copy(title = titleFrom(prompt))
        }

        conversation = conversation.copy(
            messages = conversation.messages + ChatMessage(Role.User, prompt),
            updatedAt = System.currentTimeMillis()
        )
        respond()
    }

    /** Re-answer the last user turn, discarding the previous answer. */
    fun regenerate() {
        if (isStreaming) return
        val messages = conversation.messages
        val lastUser = messages.indexOfLast { it.role == Role.User }
        if (lastUser < 0) return
        conversation = conversation.copy(messages = messages.take(lastUser + 1))
        errorText = null
        respond()
    }

    private fun respond() {
        val provider = settings.activeProvider()
        if (provider.apiKey.isBlank() && !provider.baseUrl.contains("localhost") &&
            !provider.baseUrl.contains("127.0.0.1")
        ) {
            errorText = getApplication<Application>()
                .getString(R.string.error_no_api_key)
            return
        }

        // Placeholder bubble that streaming fills in.
        val placeholder = ChatMessage(Role.Assistant, "")
        conversation = conversation.copy(
            messages = conversation.messages + placeholder,
            updatedAt = System.currentTimeMillis()
        )
        isStreaming = true
        statusText = ""

        viewModelScope.launch {
            val history = conversation.messages.dropLast(1)
            var grounding = ""
            var sources: List<SearchResult> = emptyList()

            if (settings.webSearchEnabled) {
                val question = history.lastOrNull { it.role == Role.User }?.content.orEmpty()
                statusText = "Searching the web"
                val found = withContext(Dispatchers.IO) {
                    runCatching { search.search(question, settings.maxSearchResults) }
                        .getOrDefault(emptyList())
                }
                sources = found
                grounding = search.formatForModel(found)
                statusText = ""
            }

            val systemPrompt = buildString {
                append(settings.systemPrompt)
                if (grounding.isNotBlank()) {
                    append("\n\n")
                    append(grounding)
                }
            }

            val builder = StringBuilder()
            var lastFlush = 0L

            withContext(Dispatchers.Main) {
                api.streamChat(
                    provider = provider,
                    systemPrompt = systemPrompt,
                    history = history,
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
                            store.saveConversation(conversation)
                            savedChats = store.listConversations()
                        }
                    },
                    onError = { message ->
                        val partial = synchronized(builder) { builder.toString() }
                        viewModelScope.launch(Dispatchers.Main) {
                            isStreaming = false
                            statusText = ""
                            errorText = message
                            if (partial.isBlank()) {
                                // Nothing arrived: replace the empty bubble with the failure.
                                replaceLastAssistant(message, emptyList(), failed = true)
                            } else {
                                replaceLastAssistant(partial, sources)
                            }
                            store.saveConversation(conversation)
                            savedChats = store.listConversations()
                        }
                    }
                )
            }
        }
    }

    fun stop() {
        api.cancel()
        isStreaming = false
        statusText = ""
        val last = conversation.messages.lastOrNull()
        if (last != null && last.role == Role.Assistant && last.content.isBlank()) {
            conversation = conversation.copy(messages = conversation.messages.dropLast(1))
        }
        store.saveConversation(conversation)
    }

    private fun replaceLastAssistant(
        text: String,
        sources: List<SearchResult>,
        failed: Boolean = false
    ) {
        val messages = conversation.messages.toMutableList()
        val index = messages.indexOfLast { it.role == Role.Assistant }
        if (index >= 0) {
            messages[index] = messages[index].copy(content = text, sources = sources, failed = failed)
        }
        conversation = conversation.copy(messages = messages, updatedAt = System.currentTimeMillis())
    }

    // ------------------------------------------------------------ conversation

    fun newChat() {
        stop()
        conversation = Conversation(Conversation.newId(), "New chat")
        errorText = null
    }

    fun openChat(id: String) {
        stop()
        val loaded = store.loadConversation(id)
        if (loaded != null) {
            conversation = loaded
            errorText = null
        }
    }

    /**
     * Destructive action, called only after the UI has confirmed it. This removes
     * the saved chat from the list; it does not touch anything else on the device.
     */
    fun deleteChat(id: String) {
        store.deleteConversation(id)
        savedChats = store.listConversations()
        if (conversation.id == id) {
            newChat()
        }
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
        api.cancel()
    }

    private companion object {
        const val FLUSH_INTERVAL_MS = 60L
    }
}
