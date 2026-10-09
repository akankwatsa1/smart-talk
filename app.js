/**
 * SmartTalk - Core Application Controller
 * Handles conversation state, triple-layer persistence (LocalStorage + IndexedDB + Server disk),
 * prompt management, Gemini-styled markdown rendering, live accessibility announcements,
 * message action controls (Copy, Edit, Regenerate), and universal custom endpoint normalization.
 */

class SmartTalkApp {
  getDefaultProviderConfigs() {
    return {
      tokenharbor: {
        baseUrl: 'https://tokenharbor.ai/v1/chat/completions',
        apiKey: '',
        model: 'deepseek-v4.1-flash:free',
        maxTokens: 131072
      },
      apinex: {
        baseUrl: 'https://api.apinex.bond/v1/chat/completions',
        apiKey: '',
        model: 'deepseek-v4.1-flash',
        maxTokens: 1000000
      },
      deepseek: {
        baseUrl: 'https://api.deepseek.com/chat/completions',
        apiKey: '',
        model: 'deepseek-chat',
        maxTokens: 128000
      },
      gemini: {
        baseUrl: 'https://generativelanguage.googleapis.com/v1beta/openai/chat/completions',
        apiKey: '',
        model: 'gemini-1.5-flash',
        maxTokens: 1000000
      },
      openai: {
        baseUrl: 'https://api.openai.com/v1/chat/completions',
        apiKey: '',
        model: 'gpt-4o',
        maxTokens: 128000
      },
      anthropic: {
        baseUrl: 'https://openrouter.ai/api/v1/chat/completions',
        apiKey: '',
        model: 'anthropic/claude-3.5-sonnet',
        maxTokens: 200000
      },
      openrouter: {
        baseUrl: 'https://openrouter.ai/api/v1/chat/completions',
        apiKey: '',
        model: 'anthropic/claude-3.5-sonnet',
        maxTokens: 128000
      },
      ollama: {
        baseUrl: 'http://localhost:11434/v1/chat/completions',
        apiKey: '',
        model: 'llama3.2:latest',
        maxTokens: 32768
      },
      custom: {
        baseUrl: '',
        apiKey: '',
        model: '',
        maxTokens: 1000000
      }
    };
  }

  getModelContextWindow(provider, modelId) {
    if (provider === 'apinex') {
      if (modelId === 'grok-4.7') return 500000;
      return 1000000; // All other APInex models support 1M context
    }
    if (provider === 'gemini') {
      if (modelId && (modelId.includes('1.5-pro') || modelId.includes('2.0-pro'))) return 2000000;
      return 1000000;
    }
    if (provider === 'anthropic') return 200000;
    if (provider === 'openai') return 128000;
    if (provider === 'deepseek') return 128000;
    if (provider === 'tokenharbor') return 131072;
    if (provider === 'ollama') return 32768;
    return 1000000;
  }

  constructor() {
    this.conversations = [];
    this.activeConversationId = null;
    this.attachedFiles = [];
    this.isStreaming = false;
    this.activeDeletingId = null;
    this.activeRenamingId = null;
    this.currentModalProvider = null;
    this.isListening = false;
    this.speechRecognition = null;
    this.currentSpeakingButton = null;
    this.availableVoices = [];
    this.audioCtx = null;

    // Web Search & Continuous Audio Recording State
    this.isWebSearchEnabled = false;
    this.mediaRecorder = null;
    this.voiceMediaStream = null;
    this.voiceAudioChunks = [];
    this.voiceRecordingTimer = null;
    this.voiceRecordingSeconds = 0;
    this.voiceBasePrompt = '';
    this.voiceAccumulatedText = '';

    // Default configuration settings
    this.settings = {
      provider: 'tokenharbor',
      baseUrl: 'https://tokenharbor.ai/v1/chat/completions',
      apiKey: '',
      model: 'deepseek-v4.1-flash:free',
      temperature: 0.7,
      topP: 1.0,
      maxTokens: 1000000,
      voiceName: 'default',
      voiceRate: 1.0,
      systemPrompt: 'You are SmartTalk, a knowledgeable, concise, and helpful conversational AI assistant. You answer thoroughly with structured formatting, clear hierarchical markdown headings (use ## and ### where appropriate to structure your responses for readability and screen readers), clear code blocks, and precise explanations.',
      theme: 'dark',
      providerConfigs: this.getDefaultProviderConfigs()
    };

    this.init();
  }

  async init() {
    this.cacheDomElements();
    this.setupMarkdownParser();
    this.bindEvents();
    this.initSpeechSynthesis();

    try {
      await this.initStorage();
    } catch (err) {
      console.warn('Storage init fallback:', err);
    }

    this.applyTheme(this.settings.theme);
    this.updateModelBadge();

    // Cleanly ensure all modals start closed without trapping screen reader or blocking interaction
    this.closeAllModals();

    // Establish active focus on prompt input so screen readers immediately identify the chat interface
    setTimeout(() => {
      if (this.dom.promptInput) {
        this.dom.promptInput.focus();
      }
      this.announceToScreenReader('SmartTalk ready. Type a prompt, click microphone or press Alt+M to speak, or press Alt+S for settings.');
    }, 120);
  }

  /* ---------------------------------------------------------
   * Markdown & Syntax Highlighter Setup
   * --------------------------------------------------------- */
  setupMarkdownParser() {
    if (window.marked) {
      window.marked.setOptions({
        gfm: true,
        breaks: true,
        headerIds: false,
        mangle: false,
        highlight: (code, lang) => {
          if (window.hljs) {
            const validLanguage = hljs.getLanguage(lang) ? lang : 'plaintext';
            return hljs.highlight(code, { language: validLanguage }).value;
          }
          return code;
        }
      });
    }
  }

  /* ---------------------------------------------------------
   * DOM Elements Caching
   * --------------------------------------------------------- */
  cacheDomElements() {
    this.dom = {
      sidebar: document.getElementById('sidebar'),
      btnSidebarCollapse: document.getElementById('btn-sidebar-collapse'),
      btnSidebarExpand: document.getElementById('btn-sidebar-expand'),
      sidebarChatList: document.getElementById('sidebar-chat-list'),
      btnNewChat: document.getElementById('btn-new-chat'),
      chatSearchInput: document.getElementById('chat-search-input'),
      chatMessagesInner: document.getElementById('chat-messages-inner'),
      chatThreadContainer: document.getElementById('chat-thread'),
      welcomeHero: document.getElementById('welcome-hero'),
      promptInput: document.getElementById('prompt-input'),
      btnSend: document.getElementById('btn-send'),
      fileInput: document.getElementById('file-input'),
      btnAttach: document.getElementById('btn-attach'),
      btnWebSearch: document.getElementById('btn-web-search'),
      btnVoiceInput: document.getElementById('btn-voice-input'),
      voiceStatusBar: document.getElementById('voice-status-bar'),
      voiceStatusTranscript: document.getElementById('voice-status-transcript'),
      voiceStatusLabel: document.getElementById('voice-status-label'),
      voiceStatusText: document.getElementById('voice-status-text'),
      btnVoiceStop: document.getElementById('btn-voice-stop'),
      promptChipsBar: document.getElementById('prompt-chips-bar'),
      dragOverlay: document.getElementById('drag-overlay'),
      srAnnouncer: document.getElementById('sr-announcer'),
      tokenCountDisplay: document.getElementById('token-count-display'),
      tokenIndicatorFill: document.getElementById('token-indicator-fill'),
      headerModelName: document.getElementById('header-model-name'),
      btnModelMenu: document.getElementById('btn-model-menu'),
      btnClearChat: document.getElementById('btn-clear-chat'),
      btnExportChat: document.getElementById('btn-export-chat'),
      btnOpenSettings: document.getElementById('btn-open-settings'),
      btnThemeToggle: document.getElementById('btn-theme-toggle'),
      themeIconDark: document.getElementById('theme-icon-dark'),
      themeIconLight: document.getElementById('theme-icon-light'),

      // Settings Modal
      settingsModal: document.getElementById('settings-modal'),
      btnCloseSettings: document.getElementById('btn-close-settings'),
      btnCancelSettings: document.getElementById('btn-cancel-settings'),
      settingProvider: document.getElementById('setting-provider'),
      settingBaseUrl: document.getElementById('setting-base-url'),
      settingApiKey: document.getElementById('setting-api-key'),
      settingModelSelect: document.getElementById('setting-model-select'),
      settingModelCustom: document.getElementById('setting-model-custom'),
      settingTemperature: document.getElementById('setting-temperature'),
      settingTopP: document.getElementById('setting-top-p'),
      tempValDisplay: document.getElementById('temp-val-display'),
      toppValDisplay: document.getElementById('topp-val-display'),
      settingMaxTokens: document.getElementById('setting-max-tokens'),
      contextWindowBadge: document.getElementById('context-window-badge'),
      settingSystemPrompt: document.getElementById('setting-system-prompt'),
      settingVoiceSelect: document.getElementById('setting-voice-select'),
      settingVoiceRate: document.getElementById('setting-voice-rate'),
      voiceRateVal: document.getElementById('voice-rate-val'),
      savedAccountsChips: document.getElementById('saved-accounts-chips'),
      savedAccountsCounter: document.getElementById('saved-accounts-counter'),
      btnSaveSettings: document.getElementById('btn-save-settings'),
      btnTestConnection: document.getElementById('btn-test-connection'),

      // Confirm Delete Modal
      confirmModal: document.getElementById('confirm-modal'),
      btnCloseConfirm: document.getElementById('btn-close-confirm'),
      btnCancelDelete: document.getElementById('btn-cancel-delete'),
      btnConfirmDelete: document.getElementById('btn-confirm-delete'),

      // Rename Modal
      renameModal: document.getElementById('rename-modal'),
      btnCloseRename: document.getElementById('btn-close-rename'),
      btnCancelRename: document.getElementById('btn-cancel-rename'),
      btnConfirmRename: document.getElementById('btn-confirm-rename'),
      renameInput: document.getElementById('rename-input')
    };
  }

  /* ---------------------------------------------------------
   * Event Listeners & Keyboard Accessibility (WCAG 2.1 AA)
   * --------------------------------------------------------- */
  bindEvents() {
    this.dom.btnSidebarCollapse.addEventListener('click', () => this.toggleSidebar(true));
    this.dom.btnSidebarExpand.addEventListener('click', () => this.toggleSidebar(false));

    this.dom.btnNewChat.addEventListener('click', () => this.createNewConversation());
    this.dom.chatSearchInput.addEventListener('input', (e) => this.filterChatHistory(e.target.value));

    this.dom.promptInput.addEventListener('input', () => this.handlePromptInputResize());
    this.dom.promptInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        this.submitPrompt();
      }
    });

    this.dom.btnSend.addEventListener('click', () => this.submitPrompt());
    this.dom.btnAttach.addEventListener('click', () => this.dom.fileInput.click());
    this.dom.fileInput.addEventListener('change', (e) => this.handleFileSelection(e.target.files));

    if (this.dom.btnWebSearch) {
      this.dom.btnWebSearch.addEventListener('click', () => this.toggleWebSearch());
    }

    if (this.dom.btnVoiceInput) {
      this.dom.btnVoiceInput.addEventListener('click', () => this.toggleVoiceInput());
    }
    if (this.dom.btnVoiceStop) {
      this.dom.btnVoiceStop.addEventListener('click', () => this.stopVoiceInput());
    }
    if (this.dom.settingVoiceRate) {
      this.dom.settingVoiceRate.addEventListener('input', (e) => {
        if (this.dom.voiceRateVal) {
          this.dom.voiceRateVal.textContent = parseFloat(e.target.value).toFixed(1) + 'x';
        }
      });
    }

    window.addEventListener('dragover', (e) => {
      e.preventDefault();
      this.dom.dragOverlay.classList.add('active');
    });

    this.dom.dragOverlay.addEventListener('dragleave', (e) => {
      e.preventDefault();
      this.dom.dragOverlay.classList.remove('active');
    });

    this.dom.dragOverlay.addEventListener('drop', (e) => {
      e.preventDefault();
      this.dom.dragOverlay.classList.remove('active');
      if (e.dataTransfer && e.dataTransfer.files) {
        this.handleFileSelection(e.dataTransfer.files);
      }
    });

    document.querySelectorAll('.suggestion-card').forEach(card => {
      card.addEventListener('click', () => {
        const prompt = card.getAttribute('data-prompt');
        this.dom.promptInput.value = prompt + ' ';
        this.dom.promptInput.focus();
        this.handlePromptInputResize();
      });
    });

    this.dom.btnClearChat.addEventListener('click', () => this.clearActiveChat());
    this.dom.btnExportChat.addEventListener('click', () => this.exportCurrentChat());
    this.dom.btnModelMenu.addEventListener('click', () => this.openSettingsModal());
    this.dom.btnThemeToggle.addEventListener('click', () => this.toggleTheme());

    // Settings Modal Open & Close Listeners
    this.dom.btnOpenSettings.addEventListener('click', () => this.openSettingsModal());
    if (this.dom.btnCloseSettings) {
      this.dom.btnCloseSettings.addEventListener('click', () => this.closeSettingsModal());
    }
    if (this.dom.btnCancelSettings) {
      this.dom.btnCancelSettings.addEventListener('click', () => this.closeSettingsModal());
    }
    this.dom.btnSaveSettings.addEventListener('click', () => this.saveSettingsFromModal());
    this.dom.btnTestConnection.addEventListener('click', () => this.testConnectionSettings());

    // Auto-normalize Base URL on blur if user enters /v1 without /chat/completions
    this.dom.settingBaseUrl.addEventListener('blur', () => {
      const currentVal = this.dom.settingBaseUrl.value.trim();
      if (currentVal && window.smartTalkApi) {
        this.dom.settingBaseUrl.value = window.smartTalkApi.normalizeEndpoint(currentVal);
      }
    });

    // Close when clicking modal backdrop overlay
    this.dom.settingsModal.addEventListener('click', (e) => {
      if (e.target === this.dom.settingsModal) {
        this.closeSettingsModal();
      }
    });

    this.dom.settingProvider.addEventListener('change', (e) => this.switchProviderInModal(e.target.value));
    this.dom.settingModelSelect.addEventListener('change', (e) => {
      if (e.target.value === 'custom') {
        this.dom.settingModelCustom.style.display = 'block';
        this.dom.settingModelCustom.focus();
      } else {
        this.dom.settingModelCustom.style.display = 'none';
        const prov = this.currentModalProvider || this.dom.settingProvider.value;
        const ctx = this.getModelContextWindow(prov, e.target.value);
        this.dom.settingMaxTokens.value = ctx;
        this.updateContextWindowBadge(ctx);
      }
    });

    if (this.dom.settingMaxTokens) {
      this.dom.settingMaxTokens.addEventListener('input', (e) => {
        this.updateContextWindowBadge(parseInt(e.target.value, 10) || 64000);
      });
      this.dom.settingMaxTokens.addEventListener('change', (e) => {
        this.updateContextWindowBadge(parseInt(e.target.value, 10) || 64000);
      });
    }

    this.dom.settingTemperature.addEventListener('input', (e) => {
      this.dom.tempValDisplay.textContent = e.target.value;
    });

    this.dom.settingTopP.addEventListener('input', (e) => {
      this.dom.toppValDisplay.textContent = e.target.value;
    });

    // Confirmation Modal Listeners
    this.dom.btnCloseConfirm.addEventListener('click', () => this.closeConfirmModal());
    this.dom.btnCancelDelete.addEventListener('click', () => this.closeConfirmModal());
    this.dom.btnConfirmDelete.addEventListener('click', () => this.deleteActiveTargetConversation());
    this.dom.confirmModal.addEventListener('click', (e) => {
      if (e.target === this.dom.confirmModal) this.closeConfirmModal();
    });

    // Rename Modal Listeners
    this.dom.btnCloseRename.addEventListener('click', () => this.closeRenameModal());
    this.dom.btnCancelRename.addEventListener('click', () => this.closeRenameModal());
    this.dom.btnConfirmRename.addEventListener('click', () => this.saveRenamedTitle());
    this.dom.renameModal.addEventListener('click', (e) => {
      if (e.target === this.dom.renameModal) this.closeRenameModal();
    });
    this.dom.renameInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        this.saveRenamedTitle();
      }
    });

    // Global Keyboard Shortcuts
    window.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        if (this.isListening) {
          this.stopVoiceInput();
          return;
        }
        if (this.isStreaming) {
          this.cancelStreaming();
          return;
        }
        this.closeAllModals();
      }

      if (e.altKey && (e.key === 'w' || e.key === 'W')) {
        e.preventDefault();
        this.toggleWebSearch();
      }

      if (e.altKey && (e.key === 'm' || e.key === 'M' || e.key === 'v' || e.key === 'V')) {
        e.preventDefault();
        this.toggleVoiceInput();
      }

      if (e.altKey && (e.key === 'n' || e.key === 'N')) {
        e.preventDefault();
        this.createNewConversation();
      }

      if (e.altKey && (e.key === 's' || e.key === 'S')) {
        e.preventDefault();
        this.openSettingsModal();
      }

      if (e.altKey && (e.key === 'c' || e.key === 'C')) {
        e.preventDefault();
        this.clearActiveChat();
      }

      if (e.altKey && (e.key === 'b' || e.key === 'B')) {
        e.preventDefault();
        this.toggleSidebar(!this.dom.sidebar.classList.contains('collapsed'));
      }
    });
  }

  /* ---------------------------------------------------------
   * Storage & Persistence (Triple Layer: Disk + IDB + LocalStorage)
   * --------------------------------------------------------- */
  openSettingsDb() {
    return new Promise((resolve) => {
      if (!window.indexedDB) {
        resolve(null);
        return;
      }
      let resolved = false;
      const timer = setTimeout(() => {
        if (!resolved) {
          resolved = true;
          resolve(null);
        }
      }, 1500);

      try {
        const request = indexedDB.open('SmartTalkDB', 1);
        request.onblocked = () => {
          if (!resolved) {
            resolved = true;
            clearTimeout(timer);
            resolve(null);
          }
        };
        request.onupgradeneeded = (e) => {
          const db = e.target.result;
          if (!db.objectStoreNames.contains('settings')) {
            db.createObjectStore('settings');
          }
        };
        request.onsuccess = () => {
          if (!resolved) {
            resolved = true;
            clearTimeout(timer);
            resolve(request.result);
          }
        };
        request.onerror = () => {
          if (!resolved) {
            resolved = true;
            clearTimeout(timer);
            resolve(null);
          }
        };
      } catch (err) {
        if (!resolved) {
          resolved = true;
          clearTimeout(timer);
          resolve(null);
        }
      }
    });
  }

  async initStorage() {
    await this.loadSettings();

    const saved = localStorage.getItem('smarttalk_conversations');
    if (saved) {
      try {
        this.conversations = JSON.parse(saved);
      } catch (e) {
        this.conversations = [];
      }
    }

    if (this.conversations.length === 0) {
      this.createNewConversation(false);
    } else {
      this.activeConversationId = this.conversations[0].id;
      this.renderSidebarHistory();
      this.renderActiveConversation();
    }
  }

  async loadSettings() {
    // 1. Try local server config file on disk with safe timeout
    if (window.location.protocol.startsWith('http')) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 2000);
        const res = await fetch('/api/settings', { signal: controller.signal });
        clearTimeout(timeoutId);
        if (res.ok) {
          const data = await res.json();
          if (data && typeof data === 'object' && Object.keys(data).length > 0) {
            this.mergeLoadedSettings(data);
            return;
          }
        }
      } catch (e) {}
    }

    // 2. Try IndexedDB
    try {
      const db = await this.openSettingsDb();
      if (db) {
        const tx = db.transaction('settings', 'readonly');
        const store = tx.objectStore('settings');
        const req = store.get('config');
        const val = await new Promise(r => { req.onsuccess = () => r(req.result); req.onerror = () => r(null); });
        if (val && typeof val === 'object' && Object.keys(val).length > 0) {
          this.mergeLoadedSettings(val);
          return;
        }
      }
    } catch (e) {}

    // 3. Fallback to localStorage
    const saved = localStorage.getItem('smarttalk_settings');
    if (saved) {
      try {
        this.mergeLoadedSettings(JSON.parse(saved));
      } catch (e) {}
    }
  }

  mergeLoadedSettings(data) {
    if (!data || typeof data !== 'object') return;
    this.settings = Object.assign({}, this.settings, data);

    // Auto-migrate expired TokenHarbor promotional models to active standing free models
    if (this.settings.model === 'qwen3.8-flash:free') {
      this.settings.model = 'deepseek-v4.1-flash:free';
    }
    if (this.settings.providerConfigs && this.settings.providerConfigs.tokenharbor) {
      if (this.settings.providerConfigs.tokenharbor.model === 'qwen3.8-flash:free') {
        this.settings.providerConfigs.tokenharbor.model = 'deepseek-v4.1-flash:free';
      }
    }

    const defaults = this.getDefaultProviderConfigs();
    if (!this.settings.providerConfigs) {
      this.settings.providerConfigs = defaults;
    } else {
      this.settings.providerConfigs = Object.assign(defaults, this.settings.providerConfigs);
    }

    const p = this.settings.provider || 'tokenharbor';
    if (this.settings.apiKey && (!this.settings.providerConfigs[p] || !this.settings.providerConfigs[p].apiKey)) {
      if (!this.settings.providerConfigs[p]) this.settings.providerConfigs[p] = {};
      this.settings.providerConfigs[p].apiKey = this.settings.apiKey;
      this.settings.providerConfigs[p].baseUrl = this.settings.baseUrl;
      this.settings.providerConfigs[p].model = this.settings.model;
    }
  }

  async saveSettings() {
    // Ensure baseUrl is normalized before saving
    if (window.smartTalkApi && this.settings.baseUrl) {
      this.settings.baseUrl = window.smartTalkApi.normalizeEndpoint(this.settings.baseUrl);
    }

    // 1. LocalStorage
    try {
      localStorage.setItem('smarttalk_settings', JSON.stringify(this.settings));
      localStorage.setItem('smarttalk_has_visited', 'true');
    } catch (e) {}

    // 2. IndexedDB
    try {
      const db = await this.openSettingsDb();
      if (db) {
        const tx = db.transaction('settings', 'readwrite');
        tx.objectStore('settings').put(this.settings, 'config');
      }
    } catch (e) {}

    // 3. Server settings.json on disk
    if (window.location.protocol.startsWith('http')) {
      try {
        await fetch('/api/settings', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(this.settings)
        });
      } catch (e) {}
    }

    this.updateModelBadge();
  }

  saveConversations() {
    try {
      localStorage.setItem('smarttalk_conversations', JSON.stringify(this.conversations));
    } catch (e) {
      console.warn('LocalStorage limit reached.', e);
    }
  }

  getActiveConversation() {
    return this.conversations.find(c => c.id === this.activeConversationId);
  }

  createNewConversation(render = true) {
    const newConv = {
      id: 'conv_' + Date.now(),
      title: 'New Conversation',
      createdAt: Date.now(),
      updatedAt: Date.now(),
      messages: []
    };

    this.conversations.unshift(newConv);
    this.activeConversationId = newConv.id;
    this.saveConversations();

    if (render) {
      this.renderSidebarHistory();
      this.renderActiveConversation();
      this.dom.promptInput.focus();
      this.announceToScreenReader('Started new conversation.');
    }
  }

  selectConversation(id) {
    if (this.isStreaming) {
      this.cancelStreaming();
    }
    this.activeConversationId = id;
    this.renderSidebarHistory();
    this.renderActiveConversation();
    const conv = this.getActiveConversation();
    if (conv) {
      this.announceToScreenReader('Switched to conversation: ' + conv.title);
    }
  }

  openRenameModal(id, currentTitle) {
    this.activeRenamingId = id;
    this.dom.renameInput.value = currentTitle;
    if (this.dom.renameModal) {
      this.dom.renameModal.style.display = 'flex';
      this.dom.renameModal.classList.add('active');
    }
    this.dom.renameInput.focus();
  }

  closeRenameModal() {
    if (this.dom.renameModal) {
      this.dom.renameModal.classList.remove('active');
      this.dom.renameModal.style.display = 'none';
    }
    this.activeRenamingId = null;
  }

  saveRenamedTitle() {
    const val = this.dom.renameInput.value.trim();
    if (val && this.activeRenamingId) {
      const conv = this.conversations.find(c => c.id === this.activeRenamingId);
      if (conv) {
        conv.title = val;
        conv.updatedAt = Date.now();
        this.saveConversations();
        this.renderSidebarHistory();
        this.announceToScreenReader('Conversation renamed to ' + val);
      }
    }
    this.closeRenameModal();
  }

  openConfirmDeleteModal(id) {
    this.activeDeletingId = id;
    if (this.dom.confirmModal) {
      this.dom.confirmModal.style.display = 'flex';
      this.dom.confirmModal.classList.add('active');
    }
  }

  closeConfirmModal() {
    if (this.dom.confirmModal) {
      this.dom.confirmModal.classList.remove('active');
      this.dom.confirmModal.style.display = 'none';
    }
    this.activeDeletingId = null;
  }

  deleteActiveTargetConversation() {
    if (!this.activeDeletingId) return;

    this.conversations = this.conversations.filter(c => c.id !== this.activeDeletingId);

    if (this.conversations.length === 0) {
      this.createNewConversation(false);
    } else if (this.activeConversationId === this.activeDeletingId) {
      this.activeConversationId = this.conversations[0].id;
    }

    this.saveConversations();
    this.renderSidebarHistory();
    this.renderActiveConversation();
    this.closeConfirmModal();
    this.announceToScreenReader('Conversation deleted.');
  }

  clearActiveChat() {
    const conv = this.getActiveConversation();
    if (!conv) return;

    if (conv.messages.length === 0) return;

    if (confirm('Clear all messages in this conversation?')) {
      conv.messages = [];
      conv.updatedAt = Date.now();
      this.saveConversations();
      this.renderActiveConversation();
      this.announceToScreenReader('Active chat cleared.');
    }
  }

  exportCurrentChat() {
    const conv = this.getActiveConversation();
    if (!conv || conv.messages.length === 0) {
      alert('No messages to export.');
      return;
    }

    let md = '# ' + conv.title + '\n\n';
    conv.messages.forEach(m => {
      const roleLabel = m.role === 'user' ? '### User' : '### SmartTalk (' + (this.settings.model || 'Assistant') + ')';
      md += roleLabel + '\n\n' + m.content + '\n\n---\n\n';
    });

    const blob = new Blob([md], { type: 'text/markdown;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = (conv.title.replace(/[^a-zA-Z0-9_-]/g, '_') || 'chat') + '.md';
    a.click();
    URL.revokeObjectURL(url);
    this.announceToScreenReader('Chat exported to Markdown.');
  }

  /* ---------------------------------------------------------
   * Sidebar History List Rendering
   * --------------------------------------------------------- */
  renderSidebarHistory(filteredList = null) {
    const list = filteredList || this.conversations;
    const container = this.dom.sidebarChatList;
    container.innerHTML = '';

    if (list.length === 0) {
      container.innerHTML = '<div style="padding: 16px; color: var(--text-tertiary); font-size: 0.85rem; text-align: center;">No conversations found</div>';
      return;
    }

    const now = Date.now();
    const oneDay = 24 * 60 * 60 * 1000;

    const today = [];
    const prev7Days = [];
    const older = [];

    list.forEach(c => {
      const diff = now - (c.updatedAt || c.createdAt);
      if (diff < oneDay) {
        today.push(c);
      } else if (diff < 7 * oneDay) {
        prev7Days.push(c);
      } else {
        older.push(c);
      }
    });

    const renderGroup = (label, items) => {
      if (items.length === 0) return;

      const groupHeader = document.createElement('div');
      groupHeader.className = 'history-group-header';
      groupHeader.textContent = label;
      container.appendChild(groupHeader);

      items.forEach(c => {
        const item = document.createElement('div');
        item.className = 'sidebar-chat-item' + (c.id === this.activeConversationId ? ' active' : '');
        item.setAttribute('role', 'button');
        item.setAttribute('tabindex', '0');
        item.setAttribute('aria-label', c.title);

        item.innerHTML = `
          <svg class="chat-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
          </svg>
          <span class="chat-title">${this.escapeHtml(c.title)}</span>
          <div class="chat-actions">
            <button class="icon-btn btn-rename" title="Rename (R)" aria-label="Rename conversation">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M17 3a2.828 2.828 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z"/>
              </svg>
            </button>
            <button class="icon-btn btn-delete" title="Delete" aria-label="Delete conversation">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>
              </svg>
            </button>
          </div>
        `;

        const actionsDiv = item.querySelector('.chat-actions');

        item.addEventListener('click', (e) => {
          if (!actionsDiv.contains(e.target)) {
            this.selectConversation(c.id);
          }
        });

        item.addEventListener('keydown', (e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            if (!actionsDiv.contains(e.target)) {
              e.preventDefault();
              this.selectConversation(c.id);
            }
          }
        });

        actionsDiv.querySelector('.btn-rename').addEventListener('click', (e) => {
          e.stopPropagation();
          this.openRenameModal(c.id, c.title);
        });

        actionsDiv.querySelector('.btn-delete').addEventListener('click', (e) => {
          e.stopPropagation();
          this.openConfirmDeleteModal(c.id);
        });

        container.appendChild(item);
      });
    };

    renderGroup('Today', today);
    renderGroup('Previous 7 Days', prev7Days);
    renderGroup('Older', older);
  }

  filterChatHistory(query) {
    const q = query.trim().toLowerCase();
    if (!q) {
      this.renderSidebarHistory();
      return;
    }
    const filtered = this.conversations.filter(c =>
      c.title.toLowerCase().includes(q) ||
      c.messages.some(m => m.content.toLowerCase().includes(q))
    );
    this.renderSidebarHistory(filtered);
  }

  /* ---------------------------------------------------------
   * Main Chat Canvas & Messages Rendering
   * --------------------------------------------------------- */
  renderActiveConversation() {
    const conv = this.getActiveConversation();
    const container = this.dom.chatMessagesInner;
    container.innerHTML = '';

    if (!conv || conv.messages.length === 0) {
      this.dom.welcomeHero.style.display = 'flex';
      container.appendChild(this.dom.welcomeHero);
      this.updateContextTokens();
      return;
    }

    this.dom.welcomeHero.style.display = 'none';

    conv.messages.forEach((msg, idx) => {
      const msgRow = this.createMessageRowElement(msg, idx);
      container.appendChild(msgRow);
    });

    this.renderKaTeXMath();
    this.scrollToBottom();
    this.updateContextTokens();
  }

  createMessageRowElement(msg, idx) {
    const row = document.createElement('div');
    row.className = 'message-row ' + (msg.role === 'user' ? 'user' : 'ai');
    row.id = 'msg-' + idx;

    if (msg.role === 'user') {
      let attachmentPills = '';
      if (msg.attachments && msg.attachments.length > 0) {
        attachmentPills = '<div class="attached-chip-row">' +
          msg.attachments.map(att => `
            <div class="file-chip">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                <polyline points="14 2 14 8 20 8"/>
              </svg>
              <span>${this.escapeHtml(att.name)}</span>
            </div>
          `).join('') +
        '</div>';
      }

      row.innerHTML = `
        <div class="message-content">
          ${attachmentPills}
          <div class="message-bubble">${this.escapeHtml(msg.content)}</div>
          <div class="message-actions-bar user-actions-bar">
            <button class="msg-action-btn btn-edit-msg" data-index="${idx}" aria-label="Edit prompt" title="Edit prompt">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
              </svg>
              <span>Edit</span>
            </button>
            <button class="msg-action-btn btn-copy-msg" data-content="${encodeURIComponent(msg.content)}" aria-label="Copy prompt" title="Copy prompt">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect width="14" height="14" x="8" y="8" rx="2" ry="2"/>
                <path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>
              </svg>
              <span>Copy</span>
            </button>
          </div>
        </div>
      `;

      const btnEdit = row.querySelector('.btn-edit-msg');
      if (btnEdit) {
        btnEdit.addEventListener('click', () => this.editUserPrompt(idx));
      }

      const btnCopy = row.querySelector('.btn-copy-msg');
      if (btnCopy) {
        btnCopy.addEventListener('click', () => {
          navigator.clipboard.writeText(decodeURIComponent(btnCopy.getAttribute('data-content')));
          const span = btnCopy.querySelector('span');
          span.textContent = 'Copied!';
          setTimeout(() => span.textContent = 'Copy', 2000);
        });
      }
    } else {
      let reasoningHtml = '';
      if (msg.reasoning) {
        reasoningHtml = `
          <div class="reasoning-block collapsed" id="reasoning-${idx}">
            <div class="reasoning-header" role="button" tabindex="0" aria-label="Toggle thinking process">
              <span>Thinking Process</span>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="6 9 12 15 18 9"/>
              </svg>
            </div>
            <div class="reasoning-content">${this.escapeHtml(msg.reasoning)}</div>
          </div>
        `;
      }

      let searchSourcesHtml = '';
      if (msg.searchSources && msg.searchSources.length > 0) {
        searchSourcesHtml = `
          <div class="search-sources-card">
            <div class="search-sources-header">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <circle cx="12" cy="12" r="10"/>
                <line x1="2" y1="12" x2="22" y2="12"/>
                <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/>
              </svg>
              <span>Sources Consulted & Web References (${msg.searchSources.length})</span>
            </div>
            <div class="search-sources-list">
              ${msg.searchSources.map(s => `
                <a href="${this.escapeHtml(s.url)}" target="_blank" rel="noopener noreferrer" class="search-source-chip" title="${this.escapeHtml(s.snippet || s.title)}">
                  <span class="source-domain">${this.escapeHtml(s.source || 'Web')}</span>
                  <span class="source-title">${this.escapeHtml(s.title)}</span>
                </a>
              `).join('')}
            </div>
          </div>
        `;
      }

      const parsedMarkdown = this.renderMarkdown(msg.content);

      row.innerHTML = `
        <div class="message-avatar ai" aria-hidden="true">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
            <path d="M12 2L15.09 8.26L22 9.27L17 14.14L18.18 21.02L12 17.77L5.82 21.02L7 14.14L2 9.27L8.91 8.26L12 2Z"/>
          </svg>
        </div>
        <div class="message-content" style="flex: 1;">
          ${reasoningHtml}
          <div class="message-bubble markdown-body">${parsedMarkdown}</div>
          ${searchSourcesHtml}
          <div class="message-actions-bar">
            <button class="msg-action-btn btn-play-msg" data-index="${idx}" aria-label="Read response aloud" title="Read response aloud (Play/Stop)">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor">
                <polygon points="5 3 19 12 5 21 5 3"/>
              </svg>
              <span>Read Aloud</span>
            </button>
            <button class="msg-action-btn btn-copy-msg" data-content="${encodeURIComponent(msg.content)}" aria-label="Copy response" title="Copy response">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect width="14" height="14" x="8" y="8" rx="2" ry="2"/>
                <path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>
              </svg>
              <span>Copy</span>
            </button>
            <button class="msg-action-btn btn-regenerate-msg" data-index="${idx}" aria-label="Regenerate response" title="Regenerate response">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M21 2v6h-6"/>
                <path d="M3 12a9 9 0 0 1 15-6.7L21 8"/>
                <path d="M3 22v-6h6"/>
                <path d="M21 12a9 9 0 0 1-15 6.7L3 16"/>
              </svg>
              <span>Regenerate</span>
            </button>
          </div>
        </div>
      `;

      const reasoningBlock = row.querySelector('.reasoning-block');
      if (reasoningBlock) {
        const header = reasoningBlock.querySelector('.reasoning-header');
        header.addEventListener('click', () => reasoningBlock.classList.toggle('collapsed'));
        header.addEventListener('keydown', (e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            reasoningBlock.classList.toggle('collapsed');
          }
        });
      }

      const btnPlay = row.querySelector('.btn-play-msg');
      if (btnPlay) {
        btnPlay.addEventListener('click', () => {
          this.toggleReadAloud(btnPlay, msg.content);
        });
      }

      const btnCopy = row.querySelector('.btn-copy-msg');
      if (btnCopy) {
        btnCopy.addEventListener('click', () => {
          navigator.clipboard.writeText(decodeURIComponent(btnCopy.getAttribute('data-content')));
          const span = btnCopy.querySelector('span');
          span.textContent = 'Copied!';
          setTimeout(() => span.textContent = 'Copy', 2000);
        });
      }

      const btnRegenerate = row.querySelector('.btn-regenerate-msg');
      if (btnRegenerate) {
        btnRegenerate.addEventListener('click', () => this.regenerateResponse(idx));
      }

      row.querySelectorAll('.btn-copy-code').forEach(btn => {
        btn.addEventListener('click', () => {
          const codeEl = btn.closest('.code-block-wrapper').querySelector('code');
          if (codeEl) {
            navigator.clipboard.writeText(codeEl.innerText);
            btn.innerHTML = '<span>Copied!</span>';
            setTimeout(() => {
              btn.innerHTML = `
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <rect width="14" height="14" x="8" y="8" rx="2" ry="2"/>
                  <path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>
                </svg>
                <span>Copy</span>`;
            }, 2000);
          }
        });
      });
    }

    return row;
  }

  editUserPrompt(idx) {
    if (this.isStreaming) return;
    const conv = this.getActiveConversation();
    if (!conv || !conv.messages[idx]) return;
    const targetMsg = conv.messages[idx];

    this.dom.promptInput.value = targetMsg.content === '(Attached Document Query)' ? '' : targetMsg.content;
    if (targetMsg.attachments && targetMsg.attachments.length > 0) {
      this.attachedFiles = [...targetMsg.attachments];
      this.renderPromptChips();
    }

    // Truncate conversation from this message onwards
    conv.messages = conv.messages.slice(0, idx);
    this.saveConversations();
    this.renderActiveConversation();
    this.dom.promptInput.focus();
    this.handlePromptInputResize();
    this.announceToScreenReader('Prompt loaded for editing.');
  }

  regenerateResponse(assistantIdx) {
    if (this.isStreaming) return;
    const conv = this.getActiveConversation();
    if (!conv || !conv.messages[assistantIdx]) return;

    // Find preceding user message
    let userIdx = assistantIdx - 1;
    while (userIdx >= 0 && conv.messages[userIdx].role !== 'user') {
      userIdx--;
    }
    if (userIdx < 0) return;

    const userMsg = conv.messages[userIdx];
    const userContent = userMsg.content;
    const userAttachments = userMsg.attachments || [];

    // Slice up to userIdx so user message is not duplicated when triggerStreamingForPrompt re-adds it
    conv.messages = conv.messages.slice(0, userIdx);
    this.saveConversations();
    this.renderActiveConversation();

    // Trigger streaming generation again for that user message
    this.triggerStreamingForPrompt(userContent, userAttachments);
  }

  renderMarkdown(text) {
    if (!text) return '';
    let html = text;
    if (window.marked) {
      try {
        html = window.marked.parse(text);
      } catch (e) {
        html = '<p>' + this.escapeHtml(text) + '</p>';
      }
    } else {
      html = '<p>' + this.escapeHtml(text) + '</p>';
    }

    // Wrap code blocks with copy buttons
    const tempDiv = document.createElement('div');
    tempDiv.innerHTML = html;

    tempDiv.querySelectorAll('pre').forEach(pre => {
      const code = pre.querySelector('code');
      const lang = code && code.className ? code.className.replace('language-', '') : 'code';

      const wrapper = document.createElement('div');
      wrapper.className = 'code-block-wrapper';
      wrapper.innerHTML = `
        <div class="code-header">
          <span>${lang}</span>
          <button class="btn-copy-code" aria-label="Copy code block">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <rect width="14" height="14" x="8" y="8" rx="2" ry="2"/>
              <path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>
            </svg>
            <span>Copy</span>
          </button>
        </div>
      `;
      pre.parentNode.insertBefore(wrapper, pre);
      wrapper.appendChild(pre);
    });

    return tempDiv.innerHTML;
  }

  renderKaTeXMath() {
    if (window.renderMathInElement) {
      try {
        window.renderMathInElement(this.dom.chatMessagesInner, {
          delimiters: [
            { left: '$$', right: '$$', display: true },
            { left: '$', right: '$', display: false },
            { left: '\\(', right: '\\)', display: false },
            { left: '\\[', right: '\\]', display: true }
          ],
          throwOnError: false
        });
      } catch (e) {
        console.warn('KaTeX rendering notice:', e);
      }
    }
  }

  scrollToBottom() {
    this.dom.chatThreadContainer.scrollTop = this.dom.chatThreadContainer.scrollHeight;
  }

  /* ---------------------------------------------------------
   * Sliding-Window Context & Token Management
   * --------------------------------------------------------- */
  updateContextTokens() {
    const conv = this.getActiveConversation();
    let totalText = this.settings.systemPrompt || '';

    if (conv && conv.messages) {
      conv.messages.forEach(m => {
        totalText += (m.content || '') + (m.reasoning || '');
      });
    }

    this.attachedFiles.forEach(f => {
      totalText += f.formattedContent || '';
    });

    const tokens = window.fileScanner ? window.fileScanner.estimateTokens(totalText) : Math.ceil(totalText.length / 4);
    const max = this.settings.maxTokens || 1000000;
    const percentage = Math.min(100, Math.round((tokens / max) * 100));

    this.dom.tokenCountDisplay.textContent = tokens.toLocaleString() + ' / ' + max.toLocaleString() + ' tokens (' + percentage + '%)';
    this.dom.tokenIndicatorFill.style.width = percentage + '%';

    const statusDesc = tokens.toLocaleString() + ' of ' + max.toLocaleString() + ' tokens used (' + percentage + '% context window occupancy)';
    if (this.dom.tokenIndicatorFill.parentElement) {
      this.dom.tokenIndicatorFill.parentElement.setAttribute('title', statusDesc);
      this.dom.tokenIndicatorFill.parentElement.setAttribute('aria-label', statusDesc);
    }

    if (percentage > 85) {
      this.dom.tokenIndicatorFill.style.backgroundColor = '#d32f2f';
    } else if (percentage > 60) {
      this.dom.tokenIndicatorFill.style.backgroundColor = '#f57c00';
    } else {
      this.dom.tokenIndicatorFill.style.backgroundColor = 'var(--accent-primary)';
    }
  }

  getSlidingWindowMessages(conv, currentUserPrompt = null) {
    const maxTokens = this.settings.maxTokens || 1000000;
    const messages = [];

    if (this.settings.systemPrompt) {
      messages.push({ role: 'system', content: this.settings.systemPrompt });
    }

    let tokenAcc = window.fileScanner ? window.fileScanner.estimateTokens(this.settings.systemPrompt) : 50;

    // Filter out messages that are empty assistant placeholders
    const history = (conv.messages || []).filter(m => {
      if (!m || !m.role) return false;
      if (m.role === 'assistant' && (!m.content || !m.content.trim())) return false;
      return true;
    });

    const selected = [];
    for (let i = history.length - 1; i >= 0; i--) {
      const msg = history[i];
      const msgContent = msg.content || '';
      const msgTokens = window.fileScanner ? window.fileScanner.estimateTokens(msgContent) : Math.ceil(msgContent.length / 4);
      if (tokenAcc + msgTokens > maxTokens && selected.length > 0) {
        break;
      }
      tokenAcc += msgTokens;
      selected.unshift(msg);
    }

    const result = messages.concat(selected.map(m => ({ role: m.role, content: m.content || '' })));

    // Ensure the last message in result is the current user prompt
    if (currentUserPrompt) {
      if (result.length > 0 && result[result.length - 1].role === 'user') {
        result[result.length - 1].content = currentUserPrompt;
      } else {
        result.push({ role: 'user', content: currentUserPrompt });
      }
    }

    // Strict validation: Provider requires the last message to have role=user
    while (result.length > 0 && result[result.length - 1].role !== 'user') {
      result.pop();
    }

    // If somehow all messages were popped (e.g. empty conversation), push user prompt
    if (result.length === 0 || result[result.length - 1].role !== 'user') {
      result.push({ role: 'user', content: currentUserPrompt || 'Hello' });
    }

    return result;
  }

  /* ---------------------------------------------------------
   * Prompt Submission & Streaming Response
   * --------------------------------------------------------- */
  async submitPrompt() {
    if (this.isStreaming) {
      this.cancelStreaming();
      return;
    }

    const text = this.dom.promptInput.value.trim();
    if (!text && this.attachedFiles.length === 0) return;

    let conv = this.getActiveConversation();
    if (!conv) {
      this.createNewConversation(false);
      conv = this.getActiveConversation();
    }

    const filesToAttach = [...this.attachedFiles];
    this.attachedFiles = [];
    this.renderPromptChips();
    this.dom.promptInput.value = '';
    this.handlePromptInputResize();

    await this.triggerStreamingForPrompt(text, filesToAttach);
  }

  async triggerStreamingForPrompt(text, filesToAttach) {
    let conv = this.getActiveConversation();
    if (!conv) return;

    let finalPromptContent = text;
    if (filesToAttach && filesToAttach.length > 0) {
      const attachmentsBlock = filesToAttach.map(f => f.formattedContent).join('\n\n');
      finalPromptContent = (attachmentsBlock + '\n\n' + text).trim();
    }

    // Set title on first message
    if (conv.messages.length === 0 || conv.title === 'New Conversation') {
      conv.title = text.slice(0, 36) || (filesToAttach[0] ? filesToAttach[0].name : 'New Conversation');
    }

    const userMessage = {
      role: 'user',
      content: text || '(Attached Document Query)',
      attachments: filesToAttach,
      timestamp: Date.now()
    };
    conv.messages.push(userMessage);

    const aiMessage = {
      role: 'assistant',
      content: '',
      reasoning: '',
      searchSources: null,
      timestamp: Date.now()
    };
    conv.messages.push(aiMessage);
    conv.updatedAt = Date.now();

    // If Web Search is active, perform live web search before calling the model
    if (this.isWebSearchEnabled && text && text.trim().length > 0) {
      let searchBanner = document.createElement('div');
      searchBanner.className = 'search-status-banner';
      searchBanner.innerHTML = `<span class="search-status-spinner"></span> Searching live web for: <strong>${this.escapeHtml(text.slice(0, 60))}</strong>...`;
      if (this.dom.chatMessagesInner) {
        this.dom.chatMessagesInner.appendChild(searchBanner);
        this.scrollToBottom();
      }
      this.announceToScreenReader('Searching the web for ' + text.trim());

      try {
        const sRes = await fetch(`/api/search?q=${encodeURIComponent(text.trim())}`);
        if (sRes.ok) {
          const sData = await sRes.json();
          if (sData && sData.results && sData.results.length > 0) {
            aiMessage.searchSources = sData.results;
            let searchBlock = '[ONLINE REAL-TIME WEB SEARCH RESULTS & CONSULTED SOURCES]\n' +
              'The following verified, real-time web search results were gathered from diverse online sources for this query:\n';
            sData.results.forEach((item, idx) => {
              searchBlock += `\n[Source ${idx + 1}]: "${item.title}"\nPublisher / Domain: ${item.source || 'Web'}\nURL: ${item.url}\nExcerpt: ${item.snippet}\n`;
            });
            searchBlock += '\n=== CRITICAL COMPILATION & SYNTHESIS INSTRUCTIONS ===\n' +
              '1. Thoroughly synthesize, compile, and summarize the findings from these consulted sources into a comprehensive, cohesive, well-structured article/response.\n' +
              '2. Do NOT simply list search links or repeat raw snippets like a search engine. Provide a deep, informative narrative with hierarchical markdown headings (## and ###).\n' +
              '3. Naturally weave and cite the consulted sources into your narrative (e.g., "According to reporting by...", "Recent analysis indicates...").\n' +
              '4. At the conclusion of your response, provide a clean "### Sources Consulted" section summarizing the primary references consulted.\n';
            finalPromptContent = searchBlock + '\n\n' + finalPromptContent;
          }
        }
      } catch (sErr) {
        console.warn('Web search request notice:', sErr);
      } finally {
        if (searchBanner && searchBanner.parentNode) {
          searchBanner.parentNode.removeChild(searchBanner);
        }
      }
    }

    this.saveConversations();
    this.renderSidebarHistory();
    this.renderActiveConversation();

    const messageIndex = conv.messages.length - 1;
    const aiRowEl = document.getElementById('msg-' + messageIndex);
    const bubbleEl = aiRowEl ? aiRowEl.querySelector('.markdown-body') : null;
    let reasoningBlockEl = null;
    let reasoningContentEl = null;

    this.setStreamingState(true);
    this.announceToScreenReader('Streaming response started.');

    const apiMessages = this.getSlidingWindowMessages(conv, finalPromptContent);

    await window.smartTalkApi.streamChat({
      provider: this.settings.provider,
      baseUrl: this.settings.baseUrl,
      apiKey: this.settings.apiKey,
      model: this.settings.model,
      temperature: this.settings.temperature,
      topP: this.settings.topP,
      messages: apiMessages
    }, {
      onReasoning: (chunk, accumulated) => {
        aiMessage.reasoning = accumulated;
        if (!reasoningBlockEl && aiRowEl) {
          const contentContainer = aiRowEl.querySelector('.message-content');
          reasoningBlockEl = document.createElement('div');
          reasoningBlockEl.className = 'reasoning-block';
          reasoningBlockEl.innerHTML = `
            <div class="reasoning-header" role="button" tabindex="0">
              <span>Thinking Process...</span>
            </div>
            <div class="reasoning-content"></div>
          `;
          contentContainer.insertBefore(reasoningBlockEl, contentContainer.firstChild);
          reasoningContentEl = reasoningBlockEl.querySelector('.reasoning-content');
        }
        if (reasoningContentEl) {
          reasoningContentEl.textContent = accumulated;
        }
        this.scrollToBottom();
      },
      onChunk: (chunk, accumulated) => {
        aiMessage.content = accumulated;
        if (bubbleEl) {
          bubbleEl.innerHTML = this.renderMarkdown(accumulated) + '<span class="streaming-cursor" aria-hidden="true"></span>';
        }
        this.scrollToBottom();
      },
      onDone: ({ content, reasoning, aborted }) => {
        aiMessage.content = content;
        aiMessage.reasoning = reasoning;
        this.setStreamingState(false);
        this.saveConversations();
        this.renderActiveConversation();
        this.announceToScreenReader('Response completed.');
      },
      onError: (err) => {
        aiMessage.content = '⚠️ ' + err.message;
        this.setStreamingState(false);
        this.saveConversations();
        this.renderActiveConversation();
        this.announceToScreenReader('Error receiving response: ' + err.message);
      }
    });
  }

  setStreamingState(streaming) {
    this.isStreaming = streaming;
    if (streaming) {
      this.dom.btnSend.classList.add('btn-stop-generating');
      this.dom.btnSend.setAttribute('aria-label', 'Stop generating response');
      this.dom.btnSend.setAttribute('title', 'Stop generating');
      this.dom.btnSend.innerHTML = `
        <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
          <rect x="6" y="6" width="12" height="12" rx="2"/>
        </svg>
      `;
    } else {
      this.dom.btnSend.classList.remove('btn-stop-generating');
      this.dom.btnSend.setAttribute('aria-label', 'Send prompt');
      this.dom.btnSend.setAttribute('title', 'Send (Enter)');
      this.dom.btnSend.innerHTML = `
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="m5 12 7-7 7 7"/>
          <path d="M12 19V5"/>
        </svg>
      `;
    }
  }

  cancelStreaming() {
    window.smartTalkApi.cancelStream();
    this.setStreamingState(false);
    this.announceToScreenReader('Generation stopped.');
  }

  /* ---------------------------------------------------------
   * File Ingestion & Preprocessing
   * --------------------------------------------------------- */
  async handleFileSelection(files) {
    if (!files || files.length === 0) return;

    for (const file of Array.from(files)) {
      try {
        this.announceToScreenReader('Processing file ' + file.name + '...');
        if (!window.fileScanner) {
          throw new Error('File scanner is initializing. Please try again.');
        }

        this.showToast('Reading ' + file.name + '...', true);

        const processed = await window.fileScanner.processFile(file, (progressInfo) => {
          if (progressInfo && progressInfo.status) {
            this.showToast(progressInfo.status, true);
            this.announceToScreenReader(progressInfo.status);
          }
        });

        this.attachedFiles.push(processed);
        const msg = processed.wasOcrUsed
          ? 'File ' + file.name + ' scanned with OCR successfully.'
          : 'File ' + file.name + ' attached successfully.';
        this.showToast(msg, true);
        this.announceToScreenReader(msg);
      } catch (err) {
        this.showToast(err.message, false);
        this.announceToScreenReader('Error processing file ' + file.name + ': ' + err.message);
      }
    }

    this.renderPromptChips();
    this.updateContextTokens();
    this.dom.fileInput.value = '';
  }

  renderPromptChips() {
    const container = this.dom.promptChipsBar;
    container.innerHTML = '';

    this.attachedFiles.forEach((file, idx) => {
      const chip = document.createElement('div');
      chip.className = 'file-chip';
      const isOcr = file.wasOcrUsed;
      const isImg = window.fileScanner && window.fileScanner.isImageExtension(file.extension);
      const isPdf = file.extension === 'pdf';
      const iconSvg = isPdf 
        ? `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>`
        : (isImg 
          ? `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect width="18" height="18" x="3" y="3" rx="2" ry="2"/><circle cx="9" cy="9" r="2"/><path d="m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21"/></svg>`
          : `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>`);

      let badgeHtml = '';
      if (isOcr) {
        badgeHtml = `<span class="file-chip-badge ocr" title="Content extracted via client-side OCR">OCR Extracted</span>`;
      } else if (file.pageCount > 1) {
        badgeHtml = `<span class="file-chip-badge">${file.pageCount} pages</span>`;
      } else if (file.tokens) {
        badgeHtml = `<span class="file-chip-badge">~${file.tokens.toLocaleString()} tokens</span>`;
      }

      chip.innerHTML = `
        <span class="file-chip-icon" aria-hidden="true">${iconSvg}</span>
        <span class="file-chip-name" title="${this.escapeHtml(file.name)}">${this.escapeHtml(file.name)}</span>
        ${badgeHtml}
        <button type="button" class="file-chip-remove" aria-label="Remove ${this.escapeHtml(file.name)}" title="Remove attachment">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="18" y1="6" x2="6" y2="18"/>
            <line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
        </button>
      `;

      const removeBtn = chip.querySelector('.file-chip-remove');
      if (removeBtn) {
        removeBtn.addEventListener('click', () => {
          this.attachedFiles.splice(idx, 1);
          this.renderPromptChips();
          this.updateContextTokens();
          this.announceToScreenReader('Removed ' + file.name);
        });
      }

      container.appendChild(chip);
    });
  }

  /* ---------------------------------------------------------
   * UI Layout, Sidebar & Modals
   * --------------------------------------------------------- */
  toggleSidebar(collapse) {
    if (collapse) {
      this.dom.sidebar.classList.add('collapsed');
      this.dom.btnSidebarExpand.style.display = 'flex';
      this.announceToScreenReader('Sidebar collapsed.');
    } else {
      this.dom.sidebar.classList.remove('collapsed');
      this.dom.btnSidebarExpand.style.display = 'none';
      this.announceToScreenReader('Sidebar expanded.');
    }
  }

  handlePromptInputResize() {
    const input = this.dom.promptInput;
    input.style.height = 'auto';
    const newHeight = Math.min(input.scrollHeight, 180);
    input.style.height = (newHeight || 24) + 'px';
  }

  toggleTheme() {
    const nextTheme = this.settings.theme === 'dark' ? 'light' : 'dark';
    this.applyTheme(nextTheme);
    this.settings.theme = nextTheme;
    this.saveSettings();
    this.announceToScreenReader('Switched to ' + nextTheme + ' theme.');
  }

  applyTheme(theme) {
    document.body.setAttribute('data-theme', theme);
    if (this.dom.themeIconDark && this.dom.themeIconLight) {
      if (theme === 'dark') {
        this.dom.themeIconDark.style.display = 'block';
        this.dom.themeIconLight.style.display = 'none';
      } else {
        this.dom.themeIconDark.style.display = 'none';
        this.dom.themeIconLight.style.display = 'block';
      }
    }
  }

  updateModelBadge() {
    const model = this.settings.model || 'deepseek-chat';
    let friendly = model;
    if (model === 'deepseek-chat') friendly = 'DeepSeek Chat (V3)';
    else if (model === 'deepseek-reasoner') friendly = 'DeepSeek Reasoner (R1)';
    else if (model === 'deepseek-v4-flash') friendly = 'DeepSeek V4 Flash';
    else if (model === 'deepseek-v4-pro') friendly = 'DeepSeek V4 Pro';
    else if (model === 'deepseek-v4.1-flash') friendly = 'DeepSeek V4.1 Flash';
    else if (model === 'deepseek-v4.1-flash:free') friendly = 'DeepSeek V4.1 Flash (Free)';
    else if (model === 'qwen3.8-flash:free') friendly = 'Qwen 3.8 Flash (Free)';
    else if (model === 'mimo-v2.6-flash:free') friendly = 'MiMo V2.6 Flash (Free)';
    else if (model === 'deepseek-v4.1-flash') friendly = 'DeepSeek V4.1 Flash';
    else if (model === 'deepseek-v4.1-pro') friendly = 'DeepSeek V4.1 Pro';
    else if (model === 'deepseek-v4') friendly = 'DeepSeek V4';
    else if (model === 'deepseek-v4.1') friendly = 'DeepSeek V4.1';
    else if (model === 'deepseek-v4.1-flash') friendly = 'DeepSeek V4.1 Flash';
    else if (model === 'free/deepseek-v4.1-flash') friendly = 'DeepSeek V4.1 Flash (Free)';
    else if (model === 'grok-imagine-2') friendly = 'Grok Imagine 2 (Image)';
    else if (model === 'nano-banana-2') friendly = 'Nano Banana 2 (Image)';
    else if (model === 'free/nano-banana-2') friendly = 'Nano Banana 2 (Free Image)';
    else if (model === 'gpt-image-2.5-flare') friendly = 'GPT Image Flare';
    else if (model === 'gpt-image-2.5-sunburst') friendly = 'GPT Image Sunburst';
    else if (model === 'seedream-5') friendly = 'Seedream 5 (Image)';
    else if (model === 'gpt-5.6-terra' || model === 'gpt-5-6-terra') friendly = 'GPT 5.6 Terra (APInex)';
    else if (model === 'claude-sonnet-5.5') friendly = 'Claude Sonnet 5.5';
    else if (model === 'gpt-6-luna') friendly = 'GPT 6 Luna';
    else if (model === 'free/gpt-6-luna') friendly = 'GPT 6 Luna (Free)';
    else if (model === 'gemini-3.8-flash') friendly = 'Gemini 3.8 Flash';
    else if (model === 'grok-4.7') friendly = 'Grok 4.7';
    else if (model === 'glm-5.3-flash') friendly = 'GLM 5.3 Flash';
    else if (model === 'claude-opus-5.5') friendly = 'Claude Opus 5.5';
    else if (model === 'claude-sonnet-5.5') friendly = 'Claude Sonnet 5.5';
    else if (model === 'claude-opus-5') friendly = 'Claude Opus 5';
    else if (model === 'gemini-1.5-flash') friendly = 'Gemini 1.5 Flash';
    else if (model === 'gemini-1.5-pro') friendly = 'Gemini 1.5 Pro';
    else if (model === 'gemini-2.0-flash') friendly = 'Gemini 2.0 Flash';
    else if (model === 'gemini-2.0-pro-exp-02-05') friendly = 'Gemini 2.0 Pro';
    else if (model === 'gpt-4o') friendly = 'OpenAI GPT-4o';
    else if (model === 'gpt-4o-mini') friendly = 'GPT-4o Mini';
    else if (model === 'o3-mini') friendly = 'OpenAI o3-mini';
    else if (model.includes('claude-3.5-sonnet')) friendly = 'Claude 3.5 Sonnet';
    else if (model.includes('claude-3.5-haiku')) friendly = 'Claude 3.5 Haiku';

    if (this.dom.headerModelName) {
      this.dom.headerModelName.textContent = friendly;
    }
  }

  getProviderDisplayName(provider) {
    const map = {
      tokenharbor: 'TokenHarbor',
      apinex: 'APInex',
      deepseek: 'DeepSeek',
      gemini: 'Google Gemini',
      openai: 'OpenAI',
      anthropic: 'Claude (OpenRouter)',
      openrouter: 'OpenRouter',
      ollama: 'Local Ollama',
      custom: 'Custom Endpoint'
    };
    return map[provider] || provider;
  }

  switchProviderInModal(newProvider) {
    if (!newProvider) return;
    const oldProvider = this.currentModalProvider || this.settings.provider;

    if (!this.settings.providerConfigs) {
      this.settings.providerConfigs = this.getDefaultProviderConfigs();
    }
    if (!this.settings.providerConfigs[oldProvider]) {
      this.settings.providerConfigs[oldProvider] = {};
    }

    // Save previous provider inputs
    const currentKey = this.dom.settingApiKey.value.trim();
    const currentUrl = this.dom.settingBaseUrl.value.trim();
    const currentModel = this.dom.settingModelSelect.value === 'custom'
      ? this.dom.settingModelCustom.value.trim()
      : this.dom.settingModelSelect.value;

    this.settings.providerConfigs[oldProvider].apiKey = currentKey;
    this.settings.providerConfigs[oldProvider].baseUrl = currentUrl;
    this.settings.providerConfigs[oldProvider].model = currentModel;

    // Switch to target provider
    this.currentModalProvider = newProvider;
    this.dom.settingProvider.value = newProvider;

    const newConfig = this.settings.providerConfigs[newProvider] || this.getDefaultProviderConfigs()[newProvider] || {};
    this.handleProviderChange(newProvider, !newConfig.baseUrl);

    if (newConfig.baseUrl) {
      this.dom.settingBaseUrl.value = newConfig.baseUrl;
    }
    this.dom.settingApiKey.value = newConfig.apiKey || '';

    if (newConfig.model) {
      const optExists = Array.from(this.dom.settingModelSelect.options).some(o => o.value === newConfig.model);
      if (optExists) {
        this.dom.settingModelSelect.value = newConfig.model;
        this.dom.settingModelCustom.style.display = 'none';
      } else {
        this.dom.settingModelSelect.value = 'custom';
        this.dom.settingModelCustom.value = newConfig.model;
        this.dom.settingModelCustom.style.display = 'block';
      }
    }

    const targetMax = newConfig.maxTokens || this.getModelContextWindow(newProvider, newConfig.model || this.dom.settingModelSelect.value);
    this.dom.settingMaxTokens.value = targetMax;
    this.updateContextWindowBadge(targetMax);

    this.renderSavedAccountChips();

    const provName = this.getProviderDisplayName(newProvider);
    if (newConfig.apiKey) {
      this.announceToScreenReader('Switched to ' + provName + '. Restored saved credentials.');
    } else {
      this.announceToScreenReader('Switched to ' + provName + '. Please enter your API key.');
    }
  }

  updateContextWindowBadge(tokens) {
    if (this.dom.contextWindowBadge) {
      const num = parseInt(tokens, 10) || 0;
      let label = num.toLocaleString() + ' tokens';
      if (num >= 1000000) {
        label += ` (${(num / 1000000).toFixed(num % 1000000 === 0 ? 0 : 1)}M max)`;
      } else if (num >= 1000) {
        label += ` (${Math.round(num / 1000)}K max)`;
      }
      this.dom.contextWindowBadge.textContent = label;
    }
  }

  renderSavedAccountChips() {
    if (!this.dom.savedAccountsChips) return;
    this.dom.savedAccountsChips.innerHTML = '';

    if (!this.settings.providerConfigs) {
      this.settings.providerConfigs = this.getDefaultProviderConfigs();
    }

    const providers = ['tokenharbor', 'apinex', 'deepseek', 'gemini', 'openai', 'anthropic', 'openrouter', 'ollama', 'custom'];
    let configuredCount = 0;

    providers.forEach(prov => {
      const config = this.settings.providerConfigs[prov] || {};
      const hasKey = !!(config.apiKey && config.apiKey.trim().length > 0) || prov === 'ollama';
      if (hasKey) configuredCount++;

      const isCurrent = (this.currentModalProvider || this.settings.provider) === prov;
      const chip = document.createElement('button');
      chip.type = 'button';
      chip.className = 'saved-account-chip' + (isCurrent ? ' active' : '') + (!hasKey ? ' unconfigured' : '');
      chip.setAttribute('aria-label', 'Select ' + this.getProviderDisplayName(prov) + (hasKey ? ' (credentials saved)' : ''));

      const badgeTxt = hasKey ? '✓' : '+';
      const labelTxt = this.getProviderDisplayName(prov);
      chip.innerHTML = '<span class="chip-dot" aria-hidden="true"></span>' +
                       '<span>' + this.escapeHtml(labelTxt) + '</span>' +
                       '<span class="chip-badge">' + badgeTxt + '</span>';

      chip.addEventListener('click', () => {
        this.switchProviderInModal(prov);
      });

      this.dom.savedAccountsChips.appendChild(chip);
    });

    if (this.dom.savedAccountsCounter) {
      this.dom.savedAccountsCounter.textContent = configuredCount + ' configured';
    }
  }

  openSettingsModal() {
    if (!this.dom.settingsModal) return;

    this.currentModalProvider = this.settings.provider || 'tokenharbor';
    this.dom.settingProvider.value = this.currentModalProvider;

    // Load active provider configs
    const curConfig = (this.settings.providerConfigs && this.settings.providerConfigs[this.currentModalProvider]) || {};
    this.dom.settingBaseUrl.value = this.settings.baseUrl || curConfig.baseUrl || '';
    this.dom.settingApiKey.value = this.settings.apiKey || curConfig.apiKey || '';

    // Populate model dropdown matching current provider
    this.handleProviderChange(this.currentModalProvider, false);

    // Set model
    let found = false;
    for (let opt of this.dom.settingModelSelect.options) {
      if (opt.value === this.settings.model) {
        opt.selected = true;
        found = true;
        break;
      }
    }
    if (!found) {
      this.dom.settingModelSelect.value = 'custom';
      this.dom.settingModelCustom.value = this.settings.model;
      this.dom.settingModelCustom.style.display = 'block';
    } else {
      this.dom.settingModelCustom.style.display = 'none';
    }

    this.dom.settingTemperature.value = this.settings.temperature;
    this.dom.tempValDisplay.textContent = this.settings.temperature;
    this.dom.settingTopP.value = this.settings.topP;
    this.dom.toppValDisplay.textContent = this.settings.topP;

    const activeMax = this.settings.maxTokens || curConfig.maxTokens || this.getModelContextWindow(this.currentModalProvider, this.settings.model);
    this.dom.settingMaxTokens.value = activeMax;
    this.updateContextWindowBadge(activeMax);

    this.dom.settingSystemPrompt.value = this.settings.systemPrompt;

    if (this.dom.settingVoiceSelect) {
      this.dom.settingVoiceSelect.value = this.settings.voiceName || 'default';
    }
    if (this.dom.settingVoiceRate) {
      this.dom.settingVoiceRate.value = this.settings.voiceRate || 1.0;
      if (this.dom.voiceRateVal) {
        this.dom.voiceRateVal.textContent = (parseFloat(this.settings.voiceRate) || 1.0).toFixed(1) + 'x';
      }
    }

    this.renderSavedAccountChips();

    this.dom.settingsModal.style.display = 'flex';
    this.dom.settingsModal.classList.add('active');
    this.dom.settingsModal.removeAttribute('aria-hidden');
    this.dom.settingsModal.setAttribute('aria-modal', 'true');
    this.dom.settingApiKey.focus();
    this.announceToScreenReader('Settings dialog opened. Use Tab to navigate options.');
  }

  closeSettingsModal() {
    if (this.dom.settingsModal) {
      this.dom.settingsModal.classList.remove('active');
      this.dom.settingsModal.style.display = 'none';
      this.dom.settingsModal.setAttribute('aria-hidden', 'true');
      this.dom.settingsModal.removeAttribute('aria-modal');
    }
    if (this.dom.promptInput) {
      this.dom.promptInput.focus();
    }
    this.announceToScreenReader('Settings dialog closed.');
  }

  handleProviderChange(provider, updateBaseUrl = true) {
    const customInput = this.dom.settingModelCustom;
    customInput.style.display = 'none';

    if (provider === 'deepseek') {
      if (updateBaseUrl) this.dom.settingBaseUrl.value = 'https://api.deepseek.com/chat/completions';
      this.populateModelOptions([
        { value: 'deepseek-chat', label: 'deepseek-chat (V3)' },
        { value: 'deepseek-reasoner', label: 'deepseek-reasoner (R1 Reasoning)' },
        { value: 'deepseek-v4-flash', label: 'deepseek-v4-flash (V4 Flash)' },
        { value: 'deepseek-v4-pro', label: 'deepseek-v4-pro (V4 Pro)' },
        { value: 'deepseek-v4.1-flash', label: 'deepseek-v4.1-flash (V4.1 Flash)' },
        { value: 'deepseek-v4.1-pro', label: 'deepseek-v4.1-pro (V4.1 Pro)' },
        { value: 'deepseek-v4', label: 'deepseek-v4' },
        { value: 'deepseek-v4.1', label: 'deepseek-v4.1' },
        { value: 'custom', label: 'Enter custom model name...' }
      ], 'deepseek-chat');
    } else if (provider === 'apinex') {
      if (updateBaseUrl) this.dom.settingBaseUrl.value = 'https://api.apinex.bond/v1/chat/completions';
      this.populateModelOptions([
        {
          optgroup: 'APInex — Standard Models (1M Context - No Check-in Needed)',
          options: [
            { value: 'deepseek-v4.1-flash', label: 'deepseek-v4.1-flash (Default - 1M Context)' },
            { value: 'deepseek-v4-flash', label: 'deepseek-v4-flash (Fast - 1M Context)' },
            { value: 'deepseek-v4-pro', label: 'deepseek-v4-pro (High Intelligence - 1M Context)' },
            { value: 'gemini-3.8-flash', label: 'gemini-3.8-flash (Gemini - 1M Context)' },
            { value: 'gemini-3.1-pro', label: 'gemini-3.1-pro (Gemini Pro - 1M Context)' },
            { value: 'gpt-5.6-terra', label: 'gpt-5.6-terra (APInex Flagship - 1M Context)' },
            { value: 'claude-sonnet-5.5', label: 'claude-sonnet-5.5 (Claude Sonnet - 1M Context)' },
            { value: 'claude-opus-5.5', label: 'claude-opus-5.5 (Claude Opus - 1M Context)' },
            { value: 'claude-opus-5', label: 'claude-opus-5 (1M Context)' },
            { value: 'claude-fable-5.1', label: 'claude-fable-5.1 (1M Context)' },
            { value: 'gpt-6-luna', label: 'gpt-6-luna (1M Context)' },
            { value: 'gpt-6.1-sol', label: 'gpt-6.1-sol (1M Context)' },
            { value: 'gpt-6-astra', label: 'gpt-6-astra (1M Context)' },
            { value: 'grok-4.7', label: 'grok-4.7 (500K Context)' },
            { value: 'glm-5.3-flash', label: 'glm-5.3-flash (1M Context)' },
            { value: 'glm-5.3', label: 'glm-5.3 (1M Context)' },
            { value: 'kimi-k3', label: 'kimi-k3 (1M Context)' }
          ]
        },
        {
          optgroup: 'APInex — Image Generation Models',
          options: [
            { value: 'grok-imagine-2', label: 'grok-imagine-2 (xAI Grok Imagine)' },
            { value: 'nano-banana-2', label: 'nano-banana-2 (Gemini Image)' },
            { value: 'free/nano-banana-2', label: 'free/nano-banana-2 (Free Image Model)' },
            { value: 'gpt-image-2.5-flare', label: 'gpt-image-2.5-flare (OpenAI Flare)' },
            { value: 'gpt-image-2.5-sunburst', label: 'gpt-image-2.5-sunburst (OpenAI Sunburst)' },
            { value: 'seedream-5', label: 'seedream-5 (Seedance Image)' }
          ]
        },
        {
          optgroup: 'APInex — Free Models (Requires Daily Check-in)',
          options: [
            { value: 'free/deepseek-v4.1-flash', label: 'free/deepseek-v4.1-flash (Daily Quest Check-in)' },
            { value: 'free/gpt-6-luna', label: 'free/gpt-6-luna (Daily Quest Check-in)' },
            { value: 'free/glm-5.3-flash', label: 'free/glm-5.3-flash (Daily Quest Check-in)' },
            { value: 'free/deepseek-v4-pro-0813', label: 'free/deepseek-v4-pro-0813 (Daily Quest Check-in)' },
            { value: 'free/mimo-v2.6-pro', label: 'free/mimo-v2.6-pro (Daily Quest Check-in)' }
          ]
        },
        {
          optgroup: 'APInex — Subscription Models (Requires Sub)',
          options: [
            { value: 'free/gemini-3.8-flash', label: 'free/gemini-3.8-flash (Subscription Required)' },
            { value: 'free/gemini-3.1-pro', label: 'free/gemini-3.1-pro (Subscription Required)' },
            { value: 'free/claude-sonnet-4.6', label: 'free/claude-sonnet-4.6 (Subscription Required)' },
            { value: 'free/claude-opus-4.6', label: 'free/claude-opus-4.6 (Subscription Required)' },
            { value: 'free/qwen-3.8-max', label: 'free/qwen-3.8-max (Subscription Required)' },
            { value: 'free/deepseek-v4-flash-0731', label: 'free/deepseek-v4-flash-0731 (Subscription Required)' },
            { value: 'free/kimi-k3', label: 'free/kimi-k3 (Subscription Required)' }
          ]
        },
        { value: 'custom', label: 'Enter custom model name...' }
      ], 'deepseek-v4.1-flash');
    } else if (provider === 'tokenharbor') {
      if (updateBaseUrl) this.dom.settingBaseUrl.value = 'https://tokenharbor.ai/v1/chat/completions';
      this.populateModelOptions([
        { value: 'deepseek-v4.1-flash:free', label: 'deepseek-v4.1-flash:free (Standing Free Tier - Fast & Smart)' },
        { value: 'deepseek-v4-flash:free', label: 'deepseek-v4-flash:free (Standing Free Tier)' },
        { value: 'mimo-v2.6-flash:free', label: 'mimo-v2.6-flash:free (Standing Free Tier)' },
        { value: 'mimo-v2.5:free', label: 'mimo-v2.5:free (Standing Free Tier)' },
        { value: 'deepseek-v4.1-flash', label: 'deepseek-v4.1-flash (Paid)' },
        { value: 'deepseek-v4.1-pro', label: 'deepseek-v4.1-pro (Paid)' },
        { value: 'deepseek-v4-flash', label: 'deepseek-v4-flash (Paid)' },
        { value: 'claude-opus-5.5', label: 'claude-opus-5.5 (Paid)' },
        { value: 'claude-sonnet-5.5', label: 'claude-sonnet-5.5 (Paid)' },
        { value: 'custom', label: 'Enter custom model name...' }
      ], 'deepseek-v4.1-flash:free');
    } else if (provider === 'gemini') {
      if (updateBaseUrl) this.dom.settingBaseUrl.value = 'https://generativelanguage.googleapis.com/v1beta/openai/chat/completions';
      this.populateModelOptions([
        { value: 'gemini-1.5-flash', label: 'gemini-1.5-flash (Fast & lightweight - 1M)' },
        { value: 'gemini-1.5-pro', label: 'gemini-1.5-pro (High intelligence - 2M)' },
        { value: 'gemini-2.0-flash', label: 'gemini-2.0-flash (Next-gen speed - 1M)' },
        { value: 'gemini-2.0-pro-exp-02-05', label: 'gemini-2.0-pro (Experimental - 2M)' },
        { value: 'custom', label: 'Enter custom model name...' }
      ], 'gemini-1.5-flash');
    } else if (provider === 'openai') {
      if (updateBaseUrl) this.dom.settingBaseUrl.value = 'https://api.openai.com/v1/chat/completions';
      this.populateModelOptions([
        { value: 'gpt-4o', label: 'gpt-4o (Omni flagship - 128K)' },
        { value: 'gpt-4o-mini', label: 'gpt-4o-mini (Fast reasoning - 128K)' },
        { value: 'o3-mini', label: 'o3-mini (Reasoning - 128K)' },
        { value: 'custom', label: 'Enter custom model name...' }
      ], 'gpt-4o');
    } else if (provider === 'anthropic') {
      if (updateBaseUrl) this.dom.settingBaseUrl.value = 'https://openrouter.ai/api/v1/chat/completions';
      this.populateModelOptions([
        { value: 'anthropic/claude-3.5-sonnet', label: 'claude-3.5-sonnet (High capability - 200K)' },
        { value: 'anthropic/claude-3.5-haiku', label: 'claude-3.5-haiku (Lightning fast - 200K)' },
        { value: 'custom', label: 'Enter custom model name...' }
      ], 'anthropic/claude-3.5-sonnet');
    } else if (provider === 'openrouter') {
      if (updateBaseUrl) this.dom.settingBaseUrl.value = 'https://openrouter.ai/api/v1/chat/completions';
      this.populateModelOptions([
        { value: 'deepseek/deepseek-chat', label: 'deepseek/deepseek-chat (V3)' },
        { value: 'deepseek/deepseek-r1', label: 'deepseek/deepseek-r1' },
        { value: 'google/gemini-flash-1.5', label: 'google/gemini-flash-1.5' },
        { value: 'google/gemini-pro-1.5', label: 'google/gemini-pro-1.5' },
        { value: 'anthropic/claude-3.5-sonnet', label: 'anthropic/claude-3.5-sonnet' },
        { value: 'custom', label: 'Enter custom model name...' }
      ], 'deepseek/deepseek-chat');
    } else if (provider === 'ollama') {
      if (updateBaseUrl) this.dom.settingBaseUrl.value = 'http://localhost:11434/v1/chat/completions';
      this.populateModelOptions([
        { value: 'deepseek-r1:8b', label: 'deepseek-r1:8b' },
        { value: 'llama3.2:latest', label: 'llama3.2:latest' },
        { value: 'custom', label: 'Enter custom model name...' }
      ], 'deepseek-r1:8b');
    } else {
      this.populateModelOptions([
        { value: 'custom', label: 'Enter custom model name...' }
      ], 'custom');
      customInput.style.display = 'block';
    }

    const currentSelectedModel = this.dom.settingModelSelect.value;
    const matchedTokens = this.getModelContextWindow(provider, currentSelectedModel);
    this.dom.settingMaxTokens.value = matchedTokens;
    this.updateContextWindowBadge(matchedTokens);
  }

  populateModelOptions(options, selectedVal) {
    const sel = this.dom.settingModelSelect;
    sel.innerHTML = '';
    options.forEach(item => {
      if (item.optgroup) {
        const group = document.createElement('optgroup');
        group.label = item.optgroup;
        (item.options || []).forEach(opt => {
          const el = document.createElement('option');
          el.value = opt.value;
          el.textContent = opt.label;
          if (opt.value === selectedVal) el.selected = true;
          group.appendChild(el);
        });
        sel.appendChild(group);
      } else {
        const el = document.createElement('option');
        el.value = item.value;
        el.textContent = item.label;
        if (item.value === selectedVal) el.selected = true;
        sel.appendChild(el);
      }
    });
  }

  async saveSettingsFromModal() {
    const provider = this.dom.settingProvider.value;
    this.settings.provider = provider;

    let targetBaseUrl = this.dom.settingBaseUrl.value.trim();
    if (window.smartTalkApi) {
      targetBaseUrl = window.smartTalkApi.normalizeEndpoint(targetBaseUrl);
    }
    this.dom.settingBaseUrl.value = targetBaseUrl;
    this.settings.baseUrl = targetBaseUrl;

    this.settings.apiKey = this.dom.settingApiKey.value.trim();

    if (this.dom.settingModelSelect.value === 'custom') {
      this.settings.model = this.dom.settingModelCustom.value.trim() || 'deepseek-chat';
    } else {
      this.settings.model = this.dom.settingModelSelect.value;
    }

    this.settings.temperature = parseFloat(this.dom.settingTemperature.value);
    this.settings.topP = parseFloat(this.dom.settingTopP.value);
    this.settings.maxTokens = parseInt(this.dom.settingMaxTokens.value, 10) || 1000000;
    this.settings.systemPrompt = this.dom.settingSystemPrompt.value.trim();

    if (this.dom.settingVoiceSelect) {
      this.settings.voiceName = this.dom.settingVoiceSelect.value;
    }
    if (this.dom.settingVoiceRate) {
      this.settings.voiceRate = parseFloat(this.dom.settingVoiceRate.value) || 1.0;
    }

    if (!this.settings.providerConfigs) {
      this.settings.providerConfigs = this.getDefaultProviderConfigs();
    }
    this.settings.providerConfigs[provider] = {
      apiKey: this.settings.apiKey,
      baseUrl: this.settings.baseUrl,
      model: this.settings.model,
      maxTokens: this.settings.maxTokens
    };

    this.closeSettingsModal();
    this.updateModelBadge();
    this.updateContextTokens();
    const displayProv = this.getProviderDisplayName(provider);
    this.showToast('Saved! Credentials for ' + displayProv + ' remembered.', true);
    this.announceToScreenReader('Settings saved. Active provider is ' + displayProv + '.');

    await this.saveSettings();
  }

  showStatusBanner(message, isSuccess) {
    const box = document.getElementById('connection-status-box');
    if (box) {
      box.style.display = 'block';
      let safeHtml = this.escapeHtml(message);

      // Turn web links into clickable target=_blank links
      safeHtml = safeHtml.replace(/(https?:\/\/[^\s]+)/g, '<a href="$1" target="_blank" rel="noopener noreferrer" style="color: inherit; text-decoration: underline; font-weight: 600;">$1</a>');

      if (!isSuccess) {
        if (message.includes('Daily check-in required')) {
          safeHtml += '<div style="margin-top: 6px; font-size: 0.85rem; opacity: 0.95;">💡 <b>Tip:</b> Free tier models require daily quest check-in. You can check in at the link above, or choose a standard balance model like <b>deepseek-v4.1-flash</b> or <b>gemini-3.8-flash</b> which do not require check-ins.</div>';
        } else if (message.includes('available only with a subscription')) {
          safeHtml += '<div style="margin-top: 6px; font-size: 0.85rem; opacity: 0.95;">💡 <b>Tip:</b> This promotional model is subscription-gated. Switch to standard models like <b>deepseek-v4.1-flash</b>, <b>gemini-3.8-flash</b>, or <b>gpt-5.6-terra</b> which run pay-as-you-go from account balance.</div>';
        }
      }

      box.innerHTML = safeHtml;
      if (isSuccess) {
        box.style.backgroundColor = 'rgba(46, 125, 50, 0.18)';
        box.style.color = '#4caf50';
        box.style.border = '1px solid #4caf50';
      } else {
        box.style.backgroundColor = 'rgba(211, 47, 47, 0.18)';
        box.style.color = '#ef5350';
        box.style.border = '1px solid #ef5350';
      }
    }
  }

  showToast(message, isSuccess = true) {
    const toast = document.getElementById('app-toast');
    if (toast) {
      toast.textContent = message;
      toast.className = 'app-toast active ' + (isSuccess ? 'success' : 'error');
      setTimeout(() => {
        toast.className = 'app-toast';
      }, 3500);
    }
  }

  async testConnectionSettings() {
    const btn = this.dom.btnTestConnection;
    const origText = btn.textContent;
    btn.textContent = 'Testing...';
    btn.disabled = true;

    const box = document.getElementById('connection-status-box');
    if (box) box.style.display = 'none';

    const provider = this.dom.settingProvider.value;
    let baseUrl = this.dom.settingBaseUrl.value.trim();
    if (window.smartTalkApi) {
      baseUrl = window.smartTalkApi.normalizeEndpoint(baseUrl);
      this.dom.settingBaseUrl.value = baseUrl;
    }

    const apiKey = this.dom.settingApiKey.value.trim();
    let model = this.dom.settingModelSelect.value === 'custom'
      ? this.dom.settingModelCustom.value.trim()
      : this.dom.settingModelSelect.value;
    if (!model) {
      model = provider === 'apinex' ? 'deepseek-v4.1-flash' : (provider === 'tokenharbor' ? 'deepseek-v4.1-flash:free' : 'deepseek-chat');
    }

    if (!apiKey && provider !== 'ollama') {
      const errTxt = 'Please enter your API Key before testing.';
      this.showStatusBanner(errTxt, false);
      this.showToast(errTxt, false);
      btn.textContent = origText;
      btn.disabled = false;
      return;
    }

    try {
      this.showStatusBanner('Pinging ' + baseUrl + ' with model ' + (model || 'default') + '...', true);
      await window.smartTalkApi.testConnection({ baseUrl, apiKey, model, provider });
      const ctxWin = this.getModelContextWindow(provider, model);
      const ctxLabel = ctxWin >= 1000000 ? (ctxWin / 1000000).toFixed(0) + 'M' : Math.round(ctxWin / 1000) + 'K';
      const succTxt = 'Connection successful! Successfully reached ' + (model || provider) + ' (Context Window: ' + ctxWin.toLocaleString() + ' tokens / ' + ctxLabel + ').';
      this.showStatusBanner(succTxt, true);
      this.showToast(succTxt, true);
      this.announceToScreenReader('Connection test succeeded. Context limit is ' + ctxLabel + '.');
    } catch (err) {
      const failTxt = 'Connection failed: ' + err.message;
      this.showStatusBanner(failTxt, false);
      this.showToast(failTxt, false);
      this.announceToScreenReader(failTxt);
    } finally {
      btn.textContent = origText;
      btn.disabled = false;
    }
  }

  closeAllModals() {
    this.closeSettingsModal();
    this.closeConfirmModal();
    this.closeRenameModal();
  }

  /* ---------------------------------------------------------
   * Accessibility Helper (Screen Reader Announcements)
   * --------------------------------------------------------- */
  announceToScreenReader(message) {
    if (this.dom.srAnnouncer) {
      this.dom.srAnnouncer.textContent = '';
      setTimeout(() => {
        this.dom.srAnnouncer.textContent = message;
      }, 50);
    }
  }

  escapeHtml(str) {
    if (!str) return '';
    return str
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }

  /* ---------------------------------------------------------
   * Auditory Feedback (Web Audio API Synthetic Chimes)
   * --------------------------------------------------------- */
  playChime(type = 'start') {
    try {
      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      if (!AudioCtx) return;
      if (!this.audioCtx) {
        this.audioCtx = new AudioCtx();
      }
      if (this.audioCtx.state === 'suspended') {
        this.audioCtx.resume();
      }
      const ctx = this.audioCtx;
      const now = ctx.currentTime;
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();

      osc.type = 'sine';
      if (type === 'start') {
        // High ascending pleasant chime
        osc.frequency.setValueAtTime(520, now);
        osc.frequency.exponentialRampToValueAtTime(780, now + 0.12);
        gain.gain.setValueAtTime(0.08, now);
        gain.gain.exponentialRampToValueAtTime(0.001, now + 0.25);
        osc.connect(gain);
        gain.connect(ctx.destination);
        osc.start(now);
        osc.stop(now + 0.25);
      } else {
        // Descending pleasant chime
        osc.frequency.setValueAtTime(680, now);
        osc.frequency.exponentialRampToValueAtTime(440, now + 0.14);
        gain.gain.setValueAtTime(0.08, now);
        gain.gain.exponentialRampToValueAtTime(0.001, now + 0.28);
        osc.connect(gain);
        gain.connect(ctx.destination);
        osc.start(now);
        osc.stop(now + 0.28);
      }
    } catch (e) {
      // AudioContext optional fallback
    }
  }

  /* ---------------------------------------------------------
   * Web Search Toggle
   * --------------------------------------------------------- */
  toggleWebSearch(forcedState = null) {
    if (forcedState !== null) {
      this.isWebSearchEnabled = !!forcedState;
    } else {
      this.isWebSearchEnabled = !this.isWebSearchEnabled;
    }

    if (this.dom.btnWebSearch) {
      this.dom.btnWebSearch.classList.toggle('active', this.isWebSearchEnabled);
      this.dom.btnWebSearch.setAttribute('aria-pressed', this.isWebSearchEnabled ? 'true' : 'false');
      this.dom.btnWebSearch.setAttribute('title', this.isWebSearchEnabled ? 'Web Search active (Alt+W to toggle off)' : 'Search Web: Enhance queries with live web search (Alt+W)');
    }

    if (this.isWebSearchEnabled) {
      this.showToast('🌐 Web Search enabled. Queries will include live web results.', true);
      this.announceToScreenReader('Web search enabled. Live web sources will be retrieved for your questions.');
    } else {
      this.showToast('Web Search disabled.', false);
      this.announceToScreenReader('Web search disabled.');
    }
  }

  /* ---------------------------------------------------------
   * Voice Input (Continuous Web Speech API STT + Resilient Fallback)
   * --------------------------------------------------------- */
  toggleVoiceInput() {
    if (this.isListening) {
      this.stopVoiceInput();
    } else {
      this.startVoiceInput();
    }
  }

  async startVoiceInput() {
    this.voiceBasePrompt = this.dom.promptInput ? this.dom.promptInput.value : '';
    this.voiceCurrentTranscript = '';
    this.voiceAccumulatedText = '';
    this.voicePendingInterim = '';
    this.voiceRecordingSeconds = 0;
    this.voiceAudioChunks = [];
    this.isListening = true;

    const SpeechRec = window.SpeechRecognition || window.webkitSpeechRecognition;

    if (this.dom.btnVoiceInput) {
      this.dom.btnVoiceInput.classList.add('recording');
      this.dom.btnVoiceInput.setAttribute('aria-label', 'Stop voice recording (listening)');
      this.dom.btnVoiceInput.title = 'Stop voice recording (Alt+M)';
    }
    if (this.dom.voiceStatusBar) {
      this.dom.voiceStatusBar.style.display = 'flex';
    }
    if (this.dom.voiceStatusLabel) {
      this.dom.voiceStatusLabel.textContent = 'Listening (0:00)';
    }
    if (this.dom.voiceStatusTranscript) {
      this.dom.voiceStatusTranscript.textContent = 'Listening... Speak now';
    }

    // Start timer counter so user sees recording is active
    clearInterval(this.voiceRecordingTimer);
    this.voiceRecordingTimer = setInterval(() => {
      this.voiceRecordingSeconds++;
      const mins = Math.floor(this.voiceRecordingSeconds / 60);
      const secs = (this.voiceRecordingSeconds % 60).toString().padStart(2, '0');
      if (this.dom.voiceStatusLabel) {
        this.dom.voiceStatusLabel.textContent = `Recording (${mins}:${secs})`;
      }
    }, 1000);

    this.playChime('start');
    this.announceToScreenReader('Microphone active. Speak your prompt now. Press Alt+M or click Stop when finished.');

    if (SpeechRec) {
      // Primary: Web Speech API (continuous streaming, zero audio device conflict)
      this.startSpeechRecognitionSession();
    } else {
      // Fallback: MediaRecorder + Server-side transcription
      await this.startMediaRecorderFallback();
    }
  }

  startSpeechRecognitionSession() {
    if (!this.isListening) return;

    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SpeechRecognition) return;

    try {
      if (this.speechRecognition) {
        try {
          this.speechRecognition.abort();
        } catch (e) {}
        this.speechRecognition = null;
      }

      const recognition = new SpeechRecognition();
      recognition.continuous = true;
      recognition.interimResults = true;
      recognition.lang = navigator.language || 'en-US';
      recognition.maxAlternatives = 1;

      recognition.onresult = (event) => {
        let interimTranscript = '';
        for (let i = event.resultIndex; i < event.results.length; ++i) {
          const res = event.results[i];
          if (res.isFinal) {
            const transcript = res[0].transcript.trim();
            if (transcript) {
              this.voiceAccumulatedText += transcript + ' ';
            }
            this.voicePendingInterim = '';
          } else {
            interimTranscript += res[0].transcript;
            this.voicePendingInterim = interimTranscript;
          }
        }

        const currentWords = (this.voiceAccumulatedText + ' ' + interimTranscript).trim();
        this.voiceCurrentTranscript = currentWords;

        if (this.dom.voiceStatusTranscript) {
          this.dom.voiceStatusTranscript.textContent = currentWords || 'Listening...';
        }

        // Live stream words into the prompt input in real time
        if (currentWords && this.dom.promptInput) {
          const base = (this.voiceBasePrompt || '').trim();
          this.dom.promptInput.value = base ? `${base} ${currentWords}` : currentWords;
          this.handlePromptInputResize();
        }
      };

      recognition.onerror = (event) => {
        console.warn('Speech recognition notice:', event.error);
        if (event.error === 'no-speech' || event.error === 'aborted') {
          return;
        }
        if (event.error === 'not-allowed') {
          this.showToast('Microphone access denied. Please allow microphone permission in your browser.', false);
          this.stopVoiceInput();
        }
      };

      recognition.onend = () => {
        // If interim words were spoken before the session ended, commit them so nothing is lost
        if (this.voicePendingInterim && this.voicePendingInterim.trim()) {
          this.voiceAccumulatedText += this.voicePendingInterim.trim() + ' ';
          this.voicePendingInterim = '';
        }

        // Automatically resume listening while user hasn't explicitly stopped
        if (this.isListening) {
          clearTimeout(this.speechRestartTimer);
          this.speechRestartTimer = setTimeout(() => {
            if (this.isListening) {
              try {
                this.startSpeechRecognitionSession();
              } catch (e) {}
            }
          }, 80);
        }
      };

      this.speechRecognition = recognition;
      recognition.start();
    } catch (err) {
      console.warn('SpeechRecognition session start note:', err);
    }
  }

  async startMediaRecorderFallback() {
    try {
      if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
        this.voiceMediaStream = await navigator.mediaDevices.getUserMedia({ audio: true });
        let mimeType = '';
        const types = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg', 'audio/wav'];
        if (window.MediaRecorder) {
          for (const t of types) {
            if (MediaRecorder.isTypeSupported(t)) { mimeType = t; break; }
          }
        }
        this.mediaRecorder = mimeType ? new MediaRecorder(this.voiceMediaStream, { mimeType }) : new MediaRecorder(this.voiceMediaStream);
        this.mediaRecorder.ondataavailable = (e) => {
          if (e.data && e.data.size > 0) this.voiceAudioChunks.push(e.data);
        };
        this.mediaRecorder.start(250);
      }
    } catch (err) {
      console.warn('Microphone fallback setup notice:', err);
      if (err.name === 'NotAllowedError' || err.name === 'PermissionDeniedError') {
        this.showToast('Microphone access denied. Please click the site permissions icon in your address bar to allow.', false);
        this.stopVoiceInput();
      }
    }
  }

  async stopVoiceInput() {
    if (!this.isListening) return;
    this.isListening = false;
    clearInterval(this.voiceRecordingTimer);
    clearTimeout(this.speechRestartTimer);

    if (this.dom.btnVoiceInput) {
      this.dom.btnVoiceInput.classList.remove('recording');
      this.dom.btnVoiceInput.setAttribute('aria-label', 'Voice input: Speak your prompt (Alt+M)');
      this.dom.btnVoiceInput.title = 'Voice input: Speak your prompt (Alt+M)';
    }

    this.playChime('stop');

    // Commit any pending interim speech
    if (this.voicePendingInterim && this.voicePendingInterim.trim()) {
      this.voiceAccumulatedText += this.voicePendingInterim.trim() + ' ';
      this.voicePendingInterim = '';
    }

    // 1. Stop Web Speech Recognition
    if (this.speechRecognition) {
      try {
        this.speechRecognition.stop();
      } catch (e) {}
    }

    // 2. Stop MediaRecorder fallback if active
    let audioBlob = null;
    let mimeType = 'audio/webm';
    if (this.mediaRecorder) {
      mimeType = this.mediaRecorder.mimeType || 'audio/webm';
      if (this.mediaRecorder.state !== 'inactive') {
        await new Promise((resolve) => {
          this.mediaRecorder.onstop = resolve;
          try { this.mediaRecorder.stop(); } catch (e) { resolve(); }
        });
      }
    }

    if (this.voiceMediaStream) {
      this.voiceMediaStream.getTracks().forEach(track => {
        try { track.stop(); } catch (e) {}
      });
      this.voiceMediaStream = null;
    }

    if (this.voiceAudioChunks && this.voiceAudioChunks.length > 0) {
      audioBlob = new Blob(this.voiceAudioChunks, { type: mimeType });
      this.voiceAudioChunks = [];
    }

    // 3. Final captured text from Web Speech API
    let capturedSpoken = (this.voiceAccumulatedText || this.voiceCurrentTranscript || '').trim();

    if (capturedSpoken) {
      const base = (this.voiceBasePrompt || '').trim();
      if (this.dom.promptInput) {
        this.dom.promptInput.value = base ? `${base} ${capturedSpoken}` : capturedSpoken;
        this.handlePromptInputResize();
        this.dom.promptInput.focus();
        this.dom.promptInput.selectionStart = this.dom.promptInput.value.length;
        this.dom.promptInput.selectionEnd = this.dom.promptInput.value.length;
      }
      if (this.dom.voiceStatusBar) {
        this.dom.voiceStatusBar.style.display = 'none';
      }
      this.announceToScreenReader('Voice query ready: ' + capturedSpoken + '. Press Enter to send.');
      return;
    }

    // 4. If Web Speech didn't capture text, transcribe via server fallback if audioBlob exists
    if (audioBlob && audioBlob.size > 200) {
      if (this.dom.voiceStatusLabel) {
        this.dom.voiceStatusLabel.textContent = 'Transcribing...';
      }
      if (this.dom.voiceStatusTranscript) {
        this.dom.voiceStatusTranscript.textContent = 'Processing speech with local engine...';
      }

      try {
        const ext = mimeType.includes('ogg') ? 'ogg' : (mimeType.includes('wav') ? 'wav' : 'webm');
        const formData = new FormData();
        formData.append('file', audioBlob, `speech.${ext}`);

        const resp = await fetch(`/api/transcribe?ext=${ext}`, {
          method: 'POST',
          body: formData
        });

        if (resp.ok) {
          const result = await resp.json();
          const serverText = (result.text || result.transcription || '').trim();
          if (serverText) {
            const base = (this.voiceBasePrompt || '').trim();
            if (this.dom.promptInput) {
              this.dom.promptInput.value = base ? `${base} ${serverText}` : serverText;
              this.handlePromptInputResize();
            }
            this.showToast(`Voice captured: "${serverText}"`, true);
            this.announceToScreenReader('Voice query ready: ' + serverText + '. Press Enter to send.');
          } else {
            this.showToast('No speech was detected. Please try speaking closer to your microphone.', false);
          }
        } else {
          this.showToast('Audio transcription service was unavailable.', false);
        }
      } catch (e) {
        console.warn('Transcription request error:', e);
        this.showToast('Transcription error: ' + (e.message || 'Network error'), false);
      }
    } else {
      this.showToast('No speech was detected. Please click the microphone and speak.', false);
    }

    if (this.dom.voiceStatusBar) {
      this.dom.voiceStatusBar.style.display = 'none';
    }

    if (this.dom.promptInput) {
      this.dom.promptInput.focus();
      this.dom.promptInput.selectionStart = this.dom.promptInput.value.length;
      this.dom.promptInput.selectionEnd = this.dom.promptInput.value.length;
    }
  }


  /* ---------------------------------------------------------
   * Voice Output (Web Speech Synthesis TTS & Read Aloud)
   * --------------------------------------------------------- */
  cleanTextForSpeech(text) {
    if (!text) return '';
    let clean = text;

    // Replace Markdown code blocks with a brief spoken descriptor
    clean = clean.replace(/```[\s\S]*?```/g, ' Code snippet omitted. ');

    // Remove inline code ticks
    clean = clean.replace(/`([^`]+)`/g, '$1');

    // Remove markdown images
    clean = clean.replace(/!\[([^\]]*)\]\([^)]+\)/g, '$1');

    // Replace markdown links with their link text
    clean = clean.replace(/\[([^\]]+)\]\([^)]+\)/g, '$1');

    // Remove markdown headers
    clean = clean.replace(/^#{1,6}\s+/gm, '');

    // Remove markdown bold / italic markers
    clean = clean.replace(/[*_]{1,3}([^*_]+)[*_]{1,3}/g, '$1');

    // Remove blockquote markers
    clean = clean.replace(/^>\s+/gm, '');

    // Remove list markers
    clean = clean.replace(/^[\s]*[-*+]\s+/gm, '');
    clean = clean.replace(/^[\s]*\d+\.\s+/gm, '');

    // Normalize multiple spaces and newlines
    clean = clean.replace(/\n{2,}/g, '. ');
    clean = clean.replace(/\s+/g, ' ');

    return clean.trim();
  }

  initSpeechSynthesis() {
    if (!window.speechSynthesis) return;

    const populateVoices = () => {
      this.availableVoices = window.speechSynthesis.getVoices() || [];
      if (this.dom.settingVoiceSelect) {
        const curVal = this.settings.voiceName || 'default';
        this.dom.settingVoiceSelect.innerHTML = '<option value="default">Default System Voice</option>';
        this.availableVoices.forEach((v) => {
          const opt = document.createElement('option');
          opt.value = v.name;
          opt.textContent = `${v.name} (${v.lang})`;
          if (v.name === curVal) opt.selected = true;
          this.dom.settingVoiceSelect.appendChild(opt);
        });
      }
    };

    populateVoices();
    if (typeof window.speechSynthesis.onvoiceschanged !== 'undefined') {
      window.speechSynthesis.onvoiceschanged = populateVoices;
    }
  }

  toggleReadAloud(buttonEl, rawContent) {
    if (!window.speechSynthesis) {
      this.showToast('Speech synthesis (Text-to-Speech) is not supported in this browser.', false);
      return;
    }

    // If currently speaking this exact button, toggle off
    if (this.currentSpeakingButton === buttonEl && window.speechSynthesis.speaking) {
      window.speechSynthesis.cancel();
      this.resetSpeakingButton(buttonEl);
      this.currentSpeakingButton = null;
      this.announceToScreenReader('Stopped reading aloud.');
      return;
    }

    // If speaking something else, cancel it first
    if (window.speechSynthesis.speaking || window.speechSynthesis.pending) {
      window.speechSynthesis.cancel();
      if (this.currentSpeakingButton) {
        this.resetSpeakingButton(this.currentSpeakingButton);
        this.currentSpeakingButton = null;
      }
    }

    const cleanedText = this.cleanTextForSpeech(rawContent);
    if (!cleanedText) {
      this.showToast('No readable text found in this response.', false);
      return;
    }

    const utterance = new SpeechSynthesisUtterance(cleanedText);
    utterance.rate = parseFloat(this.settings.voiceRate) || 1.0;
    utterance.pitch = 1.0;

    // Apply voice if selected
    if (this.settings.voiceName && this.settings.voiceName !== 'default' && this.availableVoices.length > 0) {
      const matched = this.availableVoices.find(v => v.name === this.settings.voiceName);
      if (matched) utterance.voice = matched;
    }

    // Set playing state on button
    this.setButtonSpeaking(buttonEl);
    this.currentSpeakingButton = buttonEl;

    utterance.onend = () => {
      this.resetSpeakingButton(buttonEl);
      if (this.currentSpeakingButton === buttonEl) {
        this.currentSpeakingButton = null;
      }
      this.announceToScreenReader('Finished reading response aloud.');
    };

    utterance.onerror = (e) => {
      this.resetSpeakingButton(buttonEl);
      if (this.currentSpeakingButton === buttonEl) {
        this.currentSpeakingButton = null;
      }
    };

    window.speechSynthesis.speak(utterance);
    this.announceToScreenReader('Reading response aloud.');
  }

  setButtonSpeaking(buttonEl) {
    buttonEl.classList.add('playing');
    buttonEl.setAttribute('aria-label', 'Stop reading response aloud');
    buttonEl.title = 'Stop reading response';
    buttonEl.innerHTML = `
      <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor">
        <rect x="6" y="6" width="12" height="12"/>
      </svg>
      <span>Stop</span>
    `;
  }

  resetSpeakingButton(buttonEl) {
    if (!buttonEl) return;
    buttonEl.classList.remove('playing');
    buttonEl.setAttribute('aria-label', 'Read response aloud');
    buttonEl.title = 'Read response aloud (Play/Stop)';
    buttonEl.innerHTML = `
      <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor">
        <polygon points="5 3 19 12 5 21 5 3"/>
      </svg>
      <span>Read Aloud</span>
    `;
  }
}

// Bootstrap application safely handling all document ready states
function bootstrapSmartTalk() {
  if (!window.smartTalk) {
    window.smartTalk = new SmartTalkApp();
  }
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', bootstrapSmartTalk);
} else {
  bootstrapSmartTalk();
}

// Keep desktop background server alive while window is active
setInterval(() => {
  fetch('/api/heartbeat').catch(() => {});
}, 3000);

window.addEventListener('beforeunload', () => {
  try {
    navigator.sendBeacon('/api/shutdown');
  } catch (e) {}
});
