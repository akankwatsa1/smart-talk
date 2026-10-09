/**
 * SmartTalk - File Scanner & Deep Document Extraction
 * Client-side parsing for PDF (Digital + Scanned OCR via PDF.js & Tesseract.js),
 * Images (.png, .jpg, .jpeg, .webp, .bmp), code (.py, .js, .html, .css), JSON, CSV, Markdown, text.
 * Metadata calculation and smart token chunking.
 */

class FileScanner {
  constructor() {
    this.supportedExtensions = [
      'txt', 'md', 'py', 'js', 'html', 'css', 'json', 'csv', 'docx', 'pdf',
      'png', 'jpg', 'jpeg', 'webp', 'bmp', 'gif'
    ];
  }

  isImageExtension(ext) {
    return ['png', 'jpg', 'jpeg', 'webp', 'bmp', 'gif'].includes(ext);
  }

  /**
   * Approximate token count calculation (~4 chars per token rule of thumb)
   */
  estimateTokens(text) {
    if (!text) return 0;
    return Math.ceil(text.length / 3.8);
  }

  /**
   * Process a single File object
   * @param {File} file
   * @param {Function} [onProgress] Callback for OCR/parsing status updates
   */
  async processFile(file, onProgress = null) {
    const ext = (file.name.split('.').pop() || '').toLowerCase();
    let content = '';
    let wasOcrUsed = false;
    let pageCount = 1;

    try {
      if (ext === 'pdf') {
        const result = await this.extractPdfText(file, onProgress);
        content = result.text;
        wasOcrUsed = result.wasOcrUsed;
        pageCount = result.pageCount;
      } else if (this.isImageExtension(ext)) {
        const result = await this.extractImageText(file, onProgress);
        content = result.text;
        wasOcrUsed = result.wasOcrUsed;
        pageCount = 1;
      } else {
        content = await this.readAsText(file);
      }

      // If content is empty or only whitespace/headers, provide descriptive fallback
      const cleanContentCheck = content.replace(/--- Page \d+.*?---/g, '').replace(/\s+/g, '');
      if (!cleanContentCheck) {
        if (ext === 'pdf' || this.isImageExtension(ext)) {
          content = `[Attached Scanned Document: ${file.name}]\n` +
                    `Note: This document appears to be an image scan with handwritten, low-contrast, or unreadable content. ` +
                    `OCR scan completed, but no legible text characters could be extracted.`;
        } else {
          content = `[Attached File: ${file.name}]\n(File contains no readable text characters)`;
        }
      }

      const lineCount = content ? content.split('\n').length : 0;
      const tokenEstimate = this.estimateTokens(content);

      return {
        id: 'file_' + Date.now() + '_' + Math.random().toString(36).substr(2, 5),
        name: file.name,
        size: file.size,
        extension: ext,
        content: content,
        lineCount: lineCount,
        tokens: tokenEstimate,
        wasOcrUsed: wasOcrUsed,
        pageCount: pageCount,
        formattedContent: this.formatPromptPayload(file.name, content, tokenEstimate, wasOcrUsed)
      };
    } catch (err) {
      console.error('File parsing error:', err);
      throw new Error('Failed to process file "' + file.name + '": ' + err.message);
    }
  }

  /**
   * Read plain text or code file
   */
  readAsText(file) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result || '');
      reader.onerror = () => reject(reader.error);
      reader.readAsText(file);
    });
  }

  /**
   * Extract text from PDF client-side using PDF.js with OCR fallback for scanned pages
   */
  async extractPdfText(file, onProgress = null) {
    if (!window.pdfjsLib) {
      throw new Error('PDF.js library is not loaded. Cannot parse PDF client-side.');
    }

    const arrayBuffer = await file.arrayBuffer();
    const pdf = await window.pdfjsLib.getDocument({ data: arrayBuffer }).promise;
    let fullText = '';
    let anyOcrUsed = false;

    for (let pageNum = 1; pageNum <= pdf.numPages; pageNum++) {
      if (onProgress) {
        onProgress({
          status: `Reading PDF page ${pageNum} of ${pdf.numPages}...`,
          page: pageNum,
          totalPages: pdf.numPages,
          progress: Math.round((pageNum / pdf.numPages) * 100)
        });
      }

      const page = await pdf.getPage(pageNum);
      const textContent = await page.getTextContent();
      const pageStrings = textContent.items.map(item => item.str).join(' ').trim();
      const charCount = pageStrings.replace(/[^a-zA-Z0-9]/g, '').length;

      // If page has sufficient digital text (>= 25 alphanumeric chars), use digital text
      if (charCount >= 25) {
        fullText += `--- Page ${pageNum} ---\n${pageStrings}\n\n`;
      } else {
        // Scanned page detected! Attempt client-side OCR via Tesseract.js
        let pageOcrText = '';
        if (window.Tesseract) {
          try {
            if (onProgress) {
              onProgress({
                status: `Scanning Page ${pageNum}/${pdf.numPages} with OCR...`,
                page: pageNum,
                totalPages: pdf.numPages,
                progress: Math.round((pageNum / pdf.numPages) * 100)
              });
            }

            // Render high-DPI canvas for OCR
            const viewport = page.getViewport({ scale: 2.0 });
            const canvas = document.createElement('canvas');
            canvas.width = viewport.width;
            canvas.height = viewport.height;
            const ctx = canvas.getContext('2d');

            await page.render({ canvasContext: ctx, viewport }).promise;

            const ocrResult = await window.Tesseract.recognize(canvas, 'eng', {
              logger: m => {
                if (onProgress && m.status === 'recognizing text') {
                  const pct = Math.round((m.progress || 0) * 100);
                  onProgress({
                    status: `Scanning Page ${pageNum}/${pdf.numPages} with OCR (${pct}%)...`,
                    page: pageNum,
                    totalPages: pdf.numPages,
                    progress: pct
                  });
                }
              }
            });

            if (ocrResult && ocrResult.data && ocrResult.data.text) {
              pageOcrText = ocrResult.data.text.trim();
              if (pageOcrText) anyOcrUsed = true;
            }
          } catch (ocrErr) {
            console.warn(`OCR error on page ${pageNum}:`, ocrErr);
          }
        }

        if (pageOcrText) {
          fullText += `--- Page ${pageNum} [Scanned Page - OCR Extracted] ---\n${pageOcrText}\n\n`;
        } else if (pageStrings) {
          fullText += `--- Page ${pageNum} ---\n${pageStrings}\n\n`;
        } else {
          fullText += `--- Page ${pageNum} [Scanned Page] ---\n\n`;
        }
      }
    }

    return {
      text: fullText.trim(),
      wasOcrUsed: anyOcrUsed,
      pageCount: pdf.numPages
    };
  }

  /**
   * Extract text from standalone image file using Tesseract.js
   */
  async extractImageText(file, onProgress = null) {
    if (!window.Tesseract) {
      return {
        text: `[Attached Image: ${file.name} (${(file.size / 1024).toFixed(1)} KB)]\nOCR library unavailable.`,
        wasOcrUsed: false,
        pageCount: 1
      };
    }

    if (onProgress) {
      onProgress({
        status: `Scanning image with OCR (${file.name})...`,
        page: 1,
        totalPages: 1,
        progress: 10
      });
    }

    try {
      const ocrResult = await window.Tesseract.recognize(file, 'eng', {
        logger: m => {
          if (onProgress && m.status === 'recognizing text') {
            const pct = Math.round((m.progress || 0) * 100);
            onProgress({
              status: `Scanning image with OCR (${pct}%)...`,
              page: 1,
              totalPages: 1,
              progress: pct
            });
          }
        }
      });

      const extracted = (ocrResult && ocrResult.data && ocrResult.data.text) ? ocrResult.data.text.trim() : '';
      return {
        text: extracted || `[Attached Image: ${file.name}]\n(No readable text found in this image)`,
        wasOcrUsed: true,
        pageCount: 1
      };
    } catch (err) {
      console.warn('Image OCR error:', err);
      return {
        text: `[Attached Image: ${file.name}]\nFailed to run OCR: ${err.message}`,
        wasOcrUsed: false,
        pageCount: 1
      };
    }
  }

  /**
   * Formats file content with metadata and creates smart chunks if size is large
   */
  formatPromptPayload(fileName, content, tokens, wasOcrUsed = false) {
    const maxTokensPerFile = 24000;
    const tagDescriptor = wasOcrUsed ? ' (Scanned Document - OCR Extracted Text)' : '';

    if (tokens <= maxTokensPerFile) {
      return `[Attached File: ${fileName}${tagDescriptor}]\n${content}\n[/Attached File]`;
    }

    // Document chunker for large files
    const lines = content.split('\n');
    const outline = lines.slice(0, 80).join('\n');
    const sampleMiddle = lines.slice(Math.floor(lines.length / 2) - 40, Math.floor(lines.length / 2) + 40).join('\n');
    const sampleEnd = lines.slice(-80).join('\n');

    return `[Attached File: ${fileName}${tagDescriptor} (Large Document: ${lines.length} lines, ~${tokens} tokens - Structured Summary & Chunks)]\n` +
      '--- Beginning Outline ---\n' + outline + '\n\n' +
      '--- Middle Sample Chunk ---\n' + sampleMiddle + '\n\n' +
      '--- Concluding Chunk ---\n' + sampleEnd + '\n' +
      '[/Attached File]';
  }
}

// Global instance
window.fileScanner = new FileScanner();
