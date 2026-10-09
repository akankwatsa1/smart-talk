class NullWriter:
    def write(self, *args, **kwargs): pass
    def flush(self, *args, **kwargs): pass

if sys.stdout is None: sys.stdout = NullWriter()
if sys.stderr is None: sys.stderr = NullWriter()
"""
SmartTalk Desktop Launcher
Runs the embedded proxy/web server and displays the application in a standalone,
borderless desktop application window with full Web Speech API and accessible UI.
"""
import os
import sys
import time
import socket
import threading
import subprocess
import webbrowser
import traceback

def get_bundle_dir():
    if getattr(sys, 'frozen', False):
        return sys._MEIPASS
    return os.path.dirname(os.path.abspath(__file__))

def find_browser():
    candidates = [
        r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
        os.path.expandvars(r"%LOCALAPPDATA%\Microsoft\Edge\Application\msedge.exe"),
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
        os.path.expandvars(r"%LOCALAPPDATA%\Google\Chrome\Application\chrome.exe")
    ]
    for c in candidates:
        if os.path.exists(c):
            return c
    return None

def main():
    appdata = os.environ.get('APPDATA') or os.path.expanduser('~')
    log_dir = os.path.join(appdata, 'SmartTalk')
    os.makedirs(log_dir, exist_ok=True)
    log_file = os.path.join(log_dir, 'desktop.log')

    with open(log_file, 'a', encoding='utf-8') as log:
        log.write(f"\n[{time.strftime('%Y-%m-%d %H:%M:%S')}] SmartTalk desktop launcher started. Frozen={getattr(sys, 'frozen', False)}\n")

    try:
        import proxy
        port = proxy.PORT

        # Start background proxy server if not already running
        if not proxy.is_port_in_use(port):
            server_thread = threading.Thread(target=proxy.run_server, args=(port,), daemon=True)
            server_thread.start()
            
            # Wait up to 5 seconds for server socket to bind
            for _ in range(50):
                if proxy.is_port_in_use(port):
                    break
                time.sleep(0.1)

        url = f"http://127.0.0.1:{port}"
        browser_exe = find_browser()

        # User profile directory to prevent collisions and keep state isolated
        user_data = os.path.join(log_dir, 'browser_profile')
        os.makedirs(user_data, exist_ok=True)

        with open(log_file, 'a', encoding='utf-8') as log:
            log.write(f"Launching window: browser={browser_exe}, url={url}\n")

        if browser_exe:
            cmd = [
                browser_exe,
                f"--app={url}",
                "--window-size=1280,820",
                f"--user-data-dir={user_data}"
            ]
            try:
                proc = subprocess.Popen(cmd)
            except Exception as e:
                with open(log_file, 'a', encoding='utf-8') as log:
                    log.write(f"Process spawn note: {e}\n")
                webbrowser.open(url)
        else:
            webbrowser.open(url)

        # Keep server alive while window is open
        # Wait 10 seconds initially for window to load and emit heartbeats
        time.sleep(10)
        misses = 0
        while True:
            time.sleep(3)
            # If no heartbeat has arrived in 15 seconds
            if time.time() - proxy.last_heartbeat > 15:
                misses += 1
                if misses >= 3:
                    with open(log_file, 'a', encoding='utf-8') as log:
                        log.write("Heartbeat timeout: Application window closed. Shutting down.\n")
                    break
            else:
                misses = 0

    except Exception as e:
        with open(log_file, 'a', encoding='utf-8') as log:
            log.write(f"FATAL ERROR:\n{traceback.format_exc()}\n")

if __name__ == '__main__':
    main()
