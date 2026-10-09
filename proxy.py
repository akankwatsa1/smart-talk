import sys
import os
import time

class NullWriter:
    def write(self, *args, **kwargs): pass
    def flush(self, *args, **kwargs): pass

if sys.stdout is None: sys.stdout = NullWriter()
if sys.stderr is None: sys.stderr = NullWriter()
import time
last_heartbeat = time.time()
active_server = None
import http.server
import socketserver
import urllib.request
import urllib.error
import json
import os
import socket
import sys

PORT = 5500
DIR = getattr(sys, '_MEIPASS', os.path.dirname(os.path.abspath(__file__)))

def get_user_data_dir():
    appdata = os.environ.get('APPDATA') or os.path.expanduser('~')
    data_dir = os.path.join(appdata, 'SmartTalk')
    os.makedirs(data_dir, exist_ok=True)
    return data_dir

def get_settings_path():
    user_settings = os.path.join(get_user_data_dir(), 'settings.json')
    if os.path.exists(user_settings):
        return user_settings
    bundle_settings = os.path.join(DIR, 'settings.json')
    if os.path.exists(bundle_settings):
        try:
            import shutil
            shutil.copy2(bundle_settings, user_settings)
            return user_settings
        except Exception:
            return bundle_settings
    return user_settings

def is_port_in_use(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        return s.connect_ex(('127.0.0.1', port)) == 0

def normalize_target_url(url):
    if not url:
        return 'https://api.deepseek.com/chat/completions'
    clean = url.strip().rstrip('/')
    if clean.endswith('/chat/completions'):
        return clean
    if clean.endswith('/v1') or clean.endswith('/v1beta/openai'):
        return clean + '/chat/completions'
    if '/v1/' in clean:
        import re
        return re.sub(r'/v1/.*$', '/v1/chat/completions', clean)
    if '/chat/completions' not in clean:
        return clean + '/v1/chat/completions'
    return clean

def local_transcribe_audio(audio_bytes, ext='webm'):
    import subprocess
    import tempfile
    import uuid

    if not audio_bytes or len(audio_bytes) < 100:
        return ''

    uid = uuid.uuid4().hex[:8]
    temp_dir = tempfile.gettempdir()
    in_file = os.path.join(temp_dir, f'smarttalk_{uid}.{ext}').replace('\\', '/')
    wav_file = os.path.join(temp_dir, f'smarttalk_{uid}.wav').replace('\\', '/')

    try:
        with open(in_file, 'wb') as f:
            f.write(audio_bytes)

        # Convert to 16kHz mono 16-bit PCM WAV using FFmpeg
        cmd_ffmpeg = [
            'ffmpeg', '-y', '-i', in_file,
            '-ar', '16000', '-ac', '1', '-c:a', 'pcm_s16le',
            wav_file
        ]
        subprocess.run(
            cmd_ffmpeg,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=15
        )
        if not os.path.exists(wav_file) or os.path.getsize(wav_file) == 0:
            return ''

        # Transcribe with Windows native System.Speech DictationGrammar
        ps_script = f"""
Add-Type -AssemblyName System.Speech
$rec = New-Object System.Speech.Recognition.SpeechRecognitionEngine
$grammar = New-Object System.Speech.Recognition.DictationGrammar
$rec.LoadGrammar($grammar)
$rec.SetInputToWaveFile('{wav_file}')
$allText = @()
while ($true) {{
    try {{
        $r = $rec.Recognize([TimeSpan]::FromSeconds(3))
        if ($r) {{
            $allText += $r.Text
        }} else {{
            break
        }}
    }} catch {{
        break
    }}
}}
$rec.Dispose()
Write-Output ($allText -join ' ')
"""
        proc = subprocess.run(
            ['powershell', '-NoProfile', '-NonInteractive', '-Command', ps_script],
            capture_output=True,
            text=True,
            timeout=25
        )
        return proc.stdout.strip()
    except Exception as e:
        print('Transcription notice:', e)
        return ''
    finally:
        for p in (in_file, wav_file):
            try:
                if os.path.exists(p):
                    os.remove(p)
            except Exception:
                pass

def search_ddg_lite(clean_query, max_results=8):
    import urllib.parse
    import urllib.request
    import re
    import html
    results = []
    try:
        url = 'https://lite.duckduckgo.com/lite/'
        data = urllib.parse.urlencode({'q': clean_query}).encode('utf-8')
        req = urllib.request.Request(url, data=data, headers={
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
            'Content-Type': 'application/x-www-form-urlencoded',
            'Accept': 'text/html,application/xhtml+xml',
            'Accept-Language': 'en-US,en;q=0.9',
        })
        with urllib.request.urlopen(req, timeout=8) as resp:
            raw_html = resp.read().decode('utf-8', errors='ignore')

        rows = re.findall(r'<tr>(.*?)</tr>', raw_html, re.DOTALL)
        for i, r in enumerate(rows):
            link_m = re.search(r'<a[^>]+class=[\"\']result-link[\"\'][^>]*href=[\"\']([^\"\']+)[\"\'][^>]*>(.*?)</a>', r)
            if not link_m:
                link_m = re.search(r'<a[^>]+rel=[\"\']nofollow[\"\'][^>]*href=[\"\']([^\"\']+)[\"\'][^>]*>(.*?)</a>', r)
            if link_m:
                raw_href = link_m.group(1)
                raw_title = re.sub(r'<[^>]+>', '', link_m.group(2)).strip()
                snip = ''
                if i + 1 < len(rows):
                    snip_tr = rows[i+1]
                    if 'result-snippet' in snip_tr:
                        snip = re.sub(r'<[^>]+>', '', snip_tr).strip()
                if raw_href and not 'duckduckgo.com' in raw_href:
                    if 'uddg=' in raw_href:
                        m_u = re.search(r'uddg=([^&]+)', raw_href)
                        if m_u:
                            raw_href = urllib.parse.unquote(m_u.group(1))
                    domain = urllib.parse.urlparse(raw_href).netloc.replace('www.', '')
                    clean_snip = re.sub(r'\s+', ' ', snip).strip()
                    results.append({
                        'title': html.unescape(raw_title),
                        'snippet': html.unescape(clean_snip),
                        'url': raw_href,
                        'source': domain or 'Web'
                    })
                    if len(results) >= max_results:
                        break
    except Exception as e:
        print('DDG Lite search notice:', e)
    return results

def search_google_news(clean_query, max_results=4):
    import urllib.parse
    import urllib.request
    import re
    import html
    results = []
    try:
        url = f'https://news.google.com/rss/search?q={urllib.parse.quote(clean_query)}&hl=en-US&gl=US&ceid=US:en'
        req = urllib.request.Request(url, headers={
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
        })
        with urllib.request.urlopen(req, timeout=7) as resp:
            content = resp.read().decode('utf-8', errors='ignore')
            items = re.findall(r'<item>(.*?)</item>', content, re.DOTALL)
            for item in items[:max_results]:
                title_m = re.search(r'<title>(.*?)</title>', item)
                link_m = re.search(r'<link>(.*?)</link>', item)
                source_m = re.search(r'<source[^>]*>(.*?)</source>', item)
                desc_m = re.search(r'<description>(.*?)</description>', item)

                raw_title = html.unescape(title_m.group(1)) if title_m else ''
                raw_link = link_m.group(1) if link_m else ''
                src_name = html.unescape(source_m.group(1)) if source_m else 'News'
                raw_desc = re.sub(r'<[^>]+>', '', html.unescape(desc_m.group(1))) if desc_m else ''

                if raw_title and raw_link:
                    results.append({
                        'title': raw_title,
                        'snippet': raw_desc or raw_title,
                        'url': raw_link,
                        'source': src_name
                    })
    except Exception as e:
        print('Google news notice:', e)
    return results

def unified_web_search(query):
    import urllib.parse
    import urllib.request
    import json
    import re
    import html
    clean_query = query.strip()
    if not clean_query:
        return []

    # 1. Fetch real-time news articles from Google News RSS
    news_results = search_google_news(clean_query, max_results=4)

    # 2. Fetch diverse live web results from DuckDuckGo Lite
    web_results = search_ddg_lite(clean_query, max_results=8)

    # Combine and deduplicate
    combined = []
    seen_urls = set()
    for item in news_results + web_results:
        u = item.get('url', '')
        if u and u not in seen_urls:
            seen_urls.add(u)
            combined.append(item)

    # 3. If very few results, add Wikipedia as a fallback reference
    if len(combined) < 2:
        try:
            wiki_url = 'https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=' + urllib.parse.quote(clean_query) + '&utf8=&format=json'
            req = urllib.request.Request(wiki_url, headers={'User-Agent': 'SmartTalk/1.0'})
            with urllib.request.urlopen(req, timeout=6) as resp:
                data = json.loads(resp.read().decode('utf-8'))
                for item in data.get('query', {}).get('search', [])[:3]:
                    title = item.get('title', '')
                    raw_snip = re.sub(r'<[^>]+>', '', item.get('snippet', '')).strip()
                    page_url = 'https://en.wikipedia.org/wiki/' + urllib.parse.quote(title.replace(' ', '_'))
                    if page_url not in seen_urls:
                        seen_urls.add(page_url)
                        combined.append({
                            'title': title,
                            'snippet': html.unescape(raw_snip),
                            'url': page_url,
                            'source': 'Wikipedia'
                        })
        except Exception as e:
            print('Wiki search notice:', e)

    return combined

class SmartTalkHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def translate_path(self, path):
        import urllib.parse
        path = path.split('?', 1)[0].split('#', 1)[0]
        trailing_slash = path.rstrip().endswith('/')
        try:
            path = urllib.parse.unquote(path, errors='surrogatepass')
        except Exception:
            path = urllib.parse.unquote(path)
        words = [w for w in path.split('/') if w and w != '.']
        safe_words = []
        for word in words:
            if word == '..':
                if safe_words: safe_words.pop()
            else:
                safe_words.append(word)
        res = os.path.join(DIR, *safe_words) if safe_words else DIR
        if trailing_slash:
            res += '/'
        return res

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=DIR, **kwargs)

    def end_headers(self):
        # Prevent browser caching so code and model updates are always immediately active
        self.send_header('Cache-Control', 'no-cache, no-store, must-revalidate')
        self.send_header('Pragma', 'no-cache')
        self.send_header('Expires', '0')
        super().end_headers()

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type, Authorization, Accept')
        self.end_headers()

    def do_GET(self):
        if self.path == '/api/settings':
            settings_path = get_settings_path()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            if os.path.exists(settings_path):
                with open(settings_path, 'rb') as f:
                    self.wfile.write(f.read())
            else:
                self.wfile.write(b'{}')
        elif self.path == '/api/heartbeat':
            global last_heartbeat
            last_heartbeat = time.time()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(b'{"status":"ok"}')
        elif self.path.startswith('/api/search'):
            import urllib.parse
            parsed = urllib.parse.urlparse(self.path)
            params = urllib.parse.parse_qs(parsed.query)
            q = params.get('q', [''])[0].strip()

            search_results = unified_web_search(q)
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(json.dumps({'query': q, 'results': search_results}).encode('utf-8'))
        else:
            super().do_GET()

    def do_POST(self):
        if self.path == '/api/settings':
            content_length = int(self.headers.get('Content-Length', 0))
            post_body = self.rfile.read(content_length)
            settings_path = get_settings_path()
            try:
                data = json.loads(post_body.decode('utf-8'))
                with open(settings_path, 'w', encoding='utf-8') as f:
                    json.dump(data, f, indent=2)
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Access-Control-Allow-Origin', '*')
                self.end_headers()
                self.wfile.write(b'{"status":"saved"}')
            except Exception as e:
                self.send_response(500)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Access-Control-Allow-Origin', '*')
                self.end_headers()
                self.wfile.write(json.dumps({'error': str(e)}).encode('utf-8'))

        elif self.path == '/api/shutdown':
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(b'{"status":"shutting_down"}')
            global active_server
            if active_server:
                threading.Thread(target=active_server.shutdown, daemon=True).start()
        elif self.path in ('/api/chat', '/api/proxy'):
            content_length = int(self.headers.get('Content-Length', 0))
            post_body = self.rfile.read(content_length)

            try:
                data = json.loads(post_body.decode('utf-8'))
                target_url = normalize_target_url(data.get('targetUrl', 'https://api.deepseek.com/chat/completions'))
                api_key = data.get('apiKey', '')
                payload = data.get('payload', {})

                req_headers = {
                    'Content-Type': 'application/json',
                    'User-Agent': 'SmartTalk/1.0 (Windows NT 10.0; Win64; x64)',
                    'Accept': 'application/json, text/event-stream, */*'
                }
                if api_key:
                    req_headers['Authorization'] = f'Bearer {api_key}'
                    req_headers['x-api-key'] = api_key

                req = urllib.request.Request(
                    target_url,
                    data=json.dumps(payload).encode('utf-8'),
                    headers=req_headers
                )

                req_stream = payload.get('stream', False)

                try:
                    with urllib.request.urlopen(req, timeout=180) as upstream:
                        self.send_response(200)
                        if req_stream:
                            self.send_header('Content-Type', 'text/event-stream')
                        else:
                            self.send_header('Content-Type', 'application/json')
                        self.send_header('Access-Control-Allow-Origin', '*')
                        self.end_headers()

                        while True:
                            try:
                                chunk = upstream.read(1024)
                                if not chunk:
                                    break
                                self.wfile.write(chunk)
                                self.wfile.flush()
                            except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError, socket.error):
                                break
                except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError, socket.error):
                    return
                except urllib.error.HTTPError as he:
                    try:
                        err_body = he.read()
                        self.send_response(he.code)
                        self.send_header('Content-Type', 'application/json')
                        self.send_header('Access-Control-Allow-Origin', '*')
                        self.end_headers()
                        self.wfile.write(err_body)
                    except Exception:
                        pass
                except Exception as ex:
                    try:
                        self.send_response(500)
                        self.send_header('Content-Type', 'application/json')
                        self.send_header('Access-Control-Allow-Origin', '*')
                        self.end_headers()
                        self.wfile.write(json.dumps({'error': {'message': str(ex)}}).encode('utf-8'))
                    except Exception:
                        pass

            except Exception as e:
                self.send_response(400)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Access-Control-Allow-Origin', '*')
                self.end_headers()
                self.wfile.write(json.dumps({'error': {'message': str(e)}}).encode('utf-8'))
        elif self.path.startswith('/api/transcribe'):
            import re
            content_length = int(self.headers.get('Content-Length', 0))
            post_body = self.rfile.read(content_length)
            content_type = self.headers.get('Content-Type', '')

            audio_data = b''
            ext = 'webm'

            if '?ext=' in self.path:
                try:
                    ext = self.path.split('?ext=')[-1].split('&')[0].strip().lower()
                except Exception:
                    pass

            if 'multipart/form-data' in content_type:
                boundary_match = re.search(r'boundary=([^;]+)', content_type)
                boundary = (boundary_match.group(1).strip() if boundary_match else '').encode('utf-8')
                if boundary:
                    parts = post_body.split(b'--' + boundary)
                    for part in parts:
                        if b'filename=' in part and b'\r\n\r\n' in part:
                            fn_m = re.search(rb'filename="?([^"\r\n]+)"?', part)
                            if fn_m:
                                fn = fn_m.group(1).decode('utf-8', errors='ignore')
                                if '.' in fn:
                                    ext = fn.split('.')[-1].lower()
                            header_end = part.find(b'\r\n\r\n') + 4
                            data = part[header_end:]
                            if data.endswith(b'\r\n'):
                                data = data[:-2]
                            audio_data = data
                            break
                if not audio_data:
                    audio_data = post_body
            else:
                audio_data = post_body

            # 1. Primary: Fast, high-accuracy native Windows Speech engine (zero cost, offline)
            transcription = local_transcribe_audio(audio_data, ext=ext)

            # 2. If native returned empty, check if upstream provider can transcribe
            if not transcription:
                settings_path = get_settings_path()
                api_key = ''
                base_url = 'https://api.apinex.bond/v1'
                if os.path.exists(settings_path):
                    try:
                        with open(settings_path, 'r', encoding='utf-8') as sf:
                            cfg = json.load(sf)
                            prov = cfg.get('provider', 'apinex')
                            prov_cfgs = cfg.get('providerConfigs', {})
                            p_cfg = prov_cfgs.get(prov, {})
                            api_key = p_cfg.get('apiKey') or cfg.get('apiKey', '')
                            base_url = p_cfg.get('baseUrl') or cfg.get('baseUrl', 'https://api.apinex.bond/v1')
                    except Exception:
                        pass

                if api_key:
                    try:
                        target_url = base_url.replace('/chat/completions', '').rstrip('/') + '/audio/transcriptions'
                        req_headers = {
                            'Content-Type': content_type or 'multipart/form-data',
                            'User-Agent': 'SmartTalk/1.0',
                            'Accept': 'application/json',
                            'Authorization': f'Bearer {api_key}',
                            'x-api-key': api_key
                        }
                        req = urllib.request.Request(target_url, data=post_body, headers=req_headers)
                        with urllib.request.urlopen(req, timeout=15) as upstream:
                            upstream_json = json.loads(upstream.read().decode('utf-8'))
                            transcription = upstream_json.get('text', '') or upstream_json.get('transcription', '')
                    except Exception:
                        pass

            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(json.dumps({
                'text': transcription or '',
                'transcription': transcription or '',
                'success': True
            }).encode('utf-8'))

        else:
            self.send_response(404)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(json.dumps({'error': 'Not found'}).encode('utf-8'))

def run_server(port=PORT):
    if is_port_in_use(port):
        print(f'Port {port} is already running. Reusing existing instance.')
        return
    socketserver.TCPServer.allow_reuse_address = True
    print(f'Starting SmartTalk Server & Proxy on http://localhost:{port} (serving from {DIR})...')
    global active_server
    with socketserver.TCPServer(('', port), SmartTalkHandler) as server:
        active_server = server
        server.serve_forever()

if __name__ == '__main__':
    run_server(PORT)
