/**
 * SmartTalk - Unified AI Streaming SSE Client
 * Supports DeepSeek, TokenHarbor, Google Gemini, OpenAI, Anthropic Claude, OpenRouter, Ollama,
 * and ANY Custom OpenAI-compatible provider with automatic endpoint normalization.
 */

class SmartTalkApiClient {
  constructor() {
    this.currentAbortController = null;
  }

  /**
   * Intelligently normalize chat completion endpoints.
   * Auto-appends /chat/completions when user enters a Base URL like https://.../v1
   */
  normalizeEndpoint(url) {
    if (!url) return 'https://api.deepseek.com/chat/completions';
    let clean = url.trim().replace(/\/+$/, '');

    // Already ends with chat/completions
    if (clean.endsWith('/chat/completions')) {
      return clean;
    }

    // Common standard base URLs ending in /v1 or /v1beta/openai
    if (clean.endsWith('/v1') || clean.endsWith('/v1beta/openai')) {
      return clean + '/chat/completions';
    }

    // If it has /v1/something-else
    if (clean.includes('/v1/')) {
      return clean.replace(/\/v1\/.*$/, '/v1/chat/completions');
    }

    // Bare domain or unknown path e.g. https://tokenharbor.ai or http://localhost:8000
    if (!clean.includes('/chat/completions')) {
      return clean + '/v1/chat/completions';
    }

    return clean;
  }

  /**
   * Send chat messages with streaming response
   */
  async streamChat({
    provider = 'deepseek',
    baseUrl = 'https://api.deepseek.com/chat/completions',
    apiKey = '',
    model = 'deepseek-chat',
    temperature = 0.7,
    topP = 1.0,
    messages = []
  }, { onChunk, onReasoning, onDone, onError }) {
    this.cancelStream();
    this.currentAbortController = new AbortController();
    const signal = this.currentAbortController.signal;

    // Automatically normalize endpoint URL
    const targetUrl = this.normalizeEndpoint(baseUrl);

    // Headers setup
    const headers = {
      'Content-Type': 'application/json',
      'Accept': 'application/json, text/event-stream, */*'
    };

    if (apiKey && provider !== 'ollama') {
      headers['Authorization'] = 'Bearer ' + apiKey.trim();
      if (provider === 'apinex') {
        headers['x-api-key'] = apiKey.trim();
      }
    }

    // Ensure clean messages for OpenAI / TokenHarbor schema:
    // 1. Remove empty assistant messages
    // 2. Ensure last message is role === 'user'
    let cleanMessages = (messages || [])
      .filter(m => m && m.role && (m.role !== 'assistant' || (m.content && m.content.trim().length > 0)))
      .map(m => ({ role: m.role, content: m.content || '' }));

    while (cleanMessages.length > 0 && cleanMessages[cleanMessages.length - 1].role !== 'user') {
      cleanMessages.pop();
    }

    if (cleanMessages.length === 0) {
      cleanMessages = [{ role: 'user', content: 'Hello' }];
    }

    const payload = {
      model: model,
      messages: cleanMessages,
      temperature: parseFloat(temperature),
      top_p: parseFloat(topP),
      stream: true
    };

    let accumulatedContent = '';
    let accumulatedReasoning = '';
    let response;

    try {
      response = await fetch(targetUrl, {
        method: 'POST',
        headers: headers,
        body: JSON.stringify(payload),
        signal: signal
      });
    } catch (fetchErr) {
      if (fetchErr.name === 'AbortError') {
        onDone({ content: accumulatedContent, reasoning: accumulatedReasoning, aborted: true });
        return;
      }

      // If browser CORS or network blocked direct fetch, try the local proxy
      if (window.location.protocol.startsWith('http')) {
        try {
          response = await fetch('/api/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ targetUrl: targetUrl, apiKey: apiKey.trim(), payload: payload }),
            signal: signal
          });
        } catch (proxyErr) {
          onError(new Error('Connection failed: ' + fetchErr.message + '. Please ensure the local server is running.'));
          return;
        }
      } else {
        onError(new Error('Direct browser request failed (' + fetchErr.message + '). Please open SmartTalk via the desktop shortcut or http://localhost:5500.'));
        return;
      }
    }

    if (!response.ok) {
      let errorDetail = 'HTTP ' + response.status + ' ' + response.statusText;
      try {
        const errorJson = await response.json();
        if (errorJson.error && errorJson.error.message) {
          errorDetail = errorJson.error.message;
        } else if (errorJson.message) {
          errorDetail = errorJson.message;
        } else if (typeof errorJson.error === 'string') {
          errorDetail = errorJson.error;
        }
      } catch (e) {
        // Fallback
      }

      if (response.status === 404 && !errorDetail.includes('ended') && !errorDetail.includes('model') && !errorDetail.includes('offer')) {
        errorDetail += ' (Endpoint ' + targetUrl + ' not found. Ensure the base URL is correct).';
      }
      onError(new Error('API Error: ' + errorDetail));
      return;
    }

    // Stream SSE Response
    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';

    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop(); // Retain remainder

        for (const line of lines) {
          const trimmed = line.trim();
          if (!trimmed || trimmed.startsWith(':')) continue;

          if (trimmed === 'data: [DONE]') {
            onDone({ content: accumulatedContent, reasoning: accumulatedReasoning, aborted: false });
            return;
          }

          if (trimmed.startsWith('data:')) {
            const jsonString = trimmed.replace(/^data:\s*/, '');
            try {
              const data = JSON.parse(jsonString);
              const choice = data.choices && data.choices[0];
              if (choice) {
                const delta = choice.delta;

                // Reasoning / thinking token handling
                if (delta && delta.reasoning_content) {
                  accumulatedReasoning += delta.reasoning_content;
                  if (onReasoning) {
                    onReasoning(delta.reasoning_content, accumulatedReasoning);
                  }
                }

                // Standard message content
                if (delta && delta.content) {
                  accumulatedContent += delta.content;
                  if (onChunk) {
                    onChunk(delta.content, accumulatedContent);
                  }
                }
              }
            } catch (jsonErr) {
              // Partial line chunk, continue
            }
          }
        }
      }

      onDone({ content: accumulatedContent, reasoning: accumulatedReasoning, aborted: false });
    } catch (readErr) {
      if (readErr.name === 'AbortError') {
        onDone({ content: accumulatedContent, reasoning: accumulatedReasoning, aborted: true });
      } else {
        onError(readErr);
      }
    } finally {
      this.currentAbortController = null;
    }
  }

  /**
   * Cancel ongoing streaming session
   */
  cancelStream() {
    if (this.currentAbortController) {
      this.currentAbortController.abort();
      this.currentAbortController = null;
    }
  }

  /**
   * Test API connectivity with a minimal ping
   */
  async testConnection({ baseUrl, apiKey, model, provider }) {
    // Automatically normalize endpoint URL
    const targetUrl = this.normalizeEndpoint(baseUrl);

    const headers = {
      'Content-Type': 'application/json',
      'Accept': 'application/json'
    };
    if (apiKey && provider !== 'ollama') {
      headers['Authorization'] = 'Bearer ' + apiKey.trim();
      if (provider === 'apinex') {
        headers['x-api-key'] = apiKey.trim();
      }
    }

    const defaultModel = provider === 'apinex' 
      ? 'deepseek-v4.1-flash' 
      : (provider === 'tokenharbor' ? 'deepseek-v4.1-flash:free' : 'deepseek-chat');

    const payload = {
      model: model || defaultModel,
      messages: [{ role: 'user', content: 'Hi' }],
      max_tokens: 5,
      stream: false
    };

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 14000);

    let res;
    try {
      res = await fetch(targetUrl, {
        method: 'POST',
        headers: headers,
        body: JSON.stringify(payload),
        signal: controller.signal
      });
    } catch (netErr) {
      if (netErr.name === 'AbortError') {
        clearTimeout(timeoutId);
        throw new Error('Connection timed out after 14 seconds. Endpoint is unreachable.');
      }

      // Try local proxy if browser CORS or network blocked it
      if (window.location.protocol.startsWith('http')) {
        try {
          res = await fetch('/api/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ targetUrl: targetUrl, apiKey: apiKey.trim(), payload: payload }),
            signal: controller.signal
          });
        } catch (proxyErr) {
          clearTimeout(timeoutId);
          throw new Error('Connection blocked by browser CORS and local proxy not responding: ' + netErr.message);
        }
      } else {
        clearTimeout(timeoutId);
        throw new Error('Browser blocked cross-origin request (' + netErr.message + '). Please open via http://localhost:5500 or the desktop launcher.');
      }
    }

    clearTimeout(timeoutId);

    if (!res.ok) {
      let msg = 'HTTP ' + res.status + ' ' + res.statusText;
      try {
        const data = await res.json();
        if (data.error && data.error.message) {
          msg = data.error.message;
        } else if (data.message) {
          msg = data.message;
        } else if (typeof data.error === 'string') {
          msg = data.error;
        }
      } catch (e) {}

      if (res.status === 404) {
        msg += ' (404 Not Found at ' + targetUrl + '. Please check your Base URL).';
      }
      throw new Error(msg);
    }

    return true;
  }
}

// Global instance
window.smartTalkApi = new SmartTalkApiClient();
