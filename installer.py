"""
SmartTalk Accessible Windows Setup Installer
Features full Screen Reader accessibility (Narrator, NVDA, JAWS),
keyboard navigation, speech announcements, and silent automated install mode.
"""
import os
import sys
import shutil
import subprocess
import threading
import time

APP_NAME = "SmartTalk"
APP_DESCRIPTION = "SmartTalk - Desktop Conversational AI"
DEFAULT_INSTALL_DIR = os.path.join(os.environ.get('LOCALAPPDATA', os.path.expanduser('~')), 'SmartTalk')

def safe_log(msg):
    if sys.stdout is not None:
        try:
            print(msg)
        except Exception:
            pass

def speak_accessible(text):
    """Speaks progress and guidance announcements for screen reader users."""
    try:
        import win32com.client
        speaker = win32com.client.Dispatch("SAPI.SpVoice")
        speaker.Speak(text, 1) # SVSFlagsAsync = 1
        return
    except Exception:
        pass
    try:
        clean_text = text.replace("'", "").replace('"', '').replace('\n', ' ')
        cmd = f"Add-Type -AssemblyName System.Speech; (New-Object System.Speech.Synthesis.SpeechSynthesizer).SpeakAsync('{clean_text}')"
        subprocess.Popen(
            ['powershell', '-NoProfile', '-NonInteractive', '-Command', cmd],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            creationflags=0x08000000 if sys.platform == 'win32' else 0
        )
    except Exception:
        pass

def get_bundle_dir():
    if getattr(sys, 'frozen', False):
        return sys._MEIPASS
    return os.path.dirname(os.path.abspath(__file__))

def create_shortcut(target_exe, shortcut_path, icon_path=None, description=APP_DESCRIPTION):
    """Creates a Windows shell shortcut (.lnk) using PowerShell WScript.Shell."""
    ps_cmd = f'''
    $ws = New-Object -ComObject WScript.Shell
    $s = $ws.CreateShortcut("{shortcut_path}")
    $s.TargetPath = "{target_exe}"
    $s.WorkingDirectory = "{os.path.dirname(target_exe)}"
    $s.Description = "{description}"
    '''
    if icon_path and os.path.exists(icon_path):
        ps_cmd += f'\n    $s.IconLocation = "{icon_path},0"\n'
    ps_cmd += '    $s.Save()\n'
    
    subprocess.run(
        ["powershell", "-NoProfile", "-NonInteractive", "-Command", ps_cmd],
        capture_output=True,
        text=True,
        creationflags=0x08000000 if sys.platform == 'win32' else 0
    )

def perform_install(target_dir, create_desktop=True, create_startmenu=True, launch_after=True, log_callback=None):
    def log(msg, speak=False):
        if log_callback:
            try:
                log_callback(msg)
            except Exception:
                pass
        safe_log(msg)
        if speak:
            speak_accessible(msg)

    log(f"Starting installation of {APP_NAME}...", speak=True)

    # 1. Terminate any running SmartTalk instances
    log("[1/5] Checking for running instances...")
    subprocess.run(["taskkill", "/F", "/IM", "SmartTalk.exe"], capture_output=True, creationflags=0x08000000 if sys.platform == 'win32' else 0)

    # 2. Prepare target directory
    log(f"[2/5] Preparing installation directory: {target_dir}")
    os.makedirs(target_dir, exist_ok=True)
    os.makedirs(os.path.join(target_dir, 'assets'), exist_ok=True)

    bundle_dir = get_bundle_dir()

    # Locate source SmartTalk.exe
    src_exe = None
    candidate_exes = [
        os.path.join(bundle_dir, "SmartTalk.exe"),
        os.path.join(bundle_dir, "dist", "SmartTalk.exe"),
        os.path.join(os.path.dirname(bundle_dir), "dist", "SmartTalk.exe"),
    ]
    for c in candidate_exes:
        if os.path.exists(c):
            src_exe = c
            break

    target_exe = os.path.join(target_dir, "SmartTalk.exe")

    if src_exe and os.path.exists(src_exe):
        log(f"[3/5] Copying {APP_NAME} application files...", speak=True)
        shutil.copy2(src_exe, target_exe)
    else:
        # Fallback: copy individual web and python files
        for item in ["desktop_app.py", "proxy.py", "index.html", "app.js", "styles.css", "api.js", "file-scanner.js", "settings.json", "apinex_public_models.json"]:
            s_file = os.path.join(bundle_dir, item)
            if os.path.exists(s_file):
                shutil.copy2(s_file, os.path.join(target_dir, item))

    # Copy icons if available
    target_ico = os.path.join(target_dir, "assets", "icon.ico")
    candidate_icos = [
        os.path.join(bundle_dir, "assets", "icon.ico"),
        os.path.join(bundle_dir, "icon.ico"),
    ]
    for c in candidate_icos:
        if os.path.exists(c):
            shutil.copy2(c, target_ico)
            break

    # 3. Create Shortcuts
    log("[4/5] Creating Windows application shortcuts...", speak=False)
    desktop_dir = os.path.join(os.environ.get('USERPROFILE', os.path.expanduser('~')), 'Desktop')
    start_menu_dir = os.path.join(os.environ.get('APPDATA', os.path.expanduser('~')), 'Microsoft', 'Windows', 'Start Menu', 'Programs')

    if create_desktop and os.path.exists(desktop_dir):
        desktop_link = os.path.join(desktop_dir, f"{APP_NAME}.lnk")
        create_shortcut(target_exe, desktop_link, target_ico)
        log("  - Created Desktop shortcut.")

    if create_startmenu and os.path.exists(start_menu_dir):
        start_link = os.path.join(start_menu_dir, f"{APP_NAME}.lnk")
        create_shortcut(target_exe, start_link, target_ico)
        log("  - Created Start Menu shortcut.")

    # 4. Create Uninstaller script
    uninstaller_path = os.path.join(target_dir, "Uninstall.bat")
    with open(uninstaller_path, "w", encoding="utf-8") as uf:
        uf.write(f"""@echo off
title Uninstall {APP_NAME}
echo Uninstalling {APP_NAME}...
taskkill /F /IM SmartTalk.exe >nul 2>&1
del /Q "{os.path.join(desktop_dir, f'{APP_NAME}.lnk')}" >nul 2>&1
del /Q "{os.path.join(start_menu_dir, f'{APP_NAME}.lnk')}" >nul 2>&1
echo Removing application directory...
rmdir /S /Q "{target_dir}" >nul 2>&1
echo {APP_NAME} has been uninstalled successfully.
pause
""")

    log("[5/5] Installation completed successfully!", speak=True)
    log(f"\n{APP_NAME} is ready to use.", speak=True)

    if launch_after and os.path.exists(target_exe):
        log("Launching SmartTalk now...", speak=False)
        try:
            subprocess.Popen([target_exe], creationflags=0x08000000 if sys.platform == 'win32' else 0)
        except Exception as e:
            log(f"Launch notice: {e}")

    return True

def run_gui():
    import tkinter as tk
    from tkinter import ttk, filedialog, messagebox

    root = tk.Tk()
    root.title(f"{APP_NAME} Setup - Accessible Installation")
    root.geometry("560x460")
    root.minsize(500, 400)

    # High contrast / clean palette
    bg_main = "#f4f6f9"
    fg_text = "#1f2937"
    primary_color = "#1877f2"
    root.configure(bg=bg_main)

    style = ttk.Style()
    style.theme_use('clam')
    style.configure(".", background=bg_main, foreground=fg_text, font=("Segoe UI", 10))
    style.configure("Header.TLabel", font=("Segoe UI", 14, "bold"), foreground="#0f172a")
    style.configure("SubHeader.TLabel", font=("Segoe UI", 10), foreground="#475569")
    style.configure("Accent.TButton", font=("Segoe UI", 11, "bold"), background=primary_color, foreground="white")
    style.map("Accent.TButton", background=[("active", "#1464c8")])

    # Variables
    install_dir_var = tk.StringVar(value=DEFAULT_INSTALL_DIR)
    desktop_shortcut_var = tk.BooleanVar(value=True)
    start_menu_var = tk.BooleanVar(value=True)
    launch_var = tk.BooleanVar(value=True)

    # Header Frame
    header_frame = tk.Frame(root, bg="#ffffff", padx=20, pady=15, relief="solid", bd=1)
    header_frame.pack(fill="x", side="top")

    lbl_title = ttk.Label(header_frame, text=f"Install {APP_NAME} for Windows", style="Header.TLabel", background="#ffffff")
    lbl_title.pack(anchor="w")

    lbl_desc = ttk.Label(header_frame, text="Desktop Conversational AI powered by DeepSeek & Multi-Providers\nAccessible with screen readers (NVDA, JAWS, Narrator).", style="SubHeader.TLabel", background="#ffffff")
    lbl_desc.pack(anchor="w", pady=(4, 0))

    # Main Content
    content_frame = tk.Frame(root, bg=bg_main, padx=20, pady=15)
    content_frame.pack(fill="both", expand=True)

    # Destination directory
    lbl_dir = ttk.Label(content_frame, text="Destination Folder (Alt+D):", font=("Segoe UI", 10, "bold"))
    lbl_dir.pack(anchor="w")

    dir_row = tk.Frame(content_frame, bg=bg_main)
    dir_row.pack(fill="x", pady=(4, 12))

    entry_dir = ttk.Entry(dir_row, textvariable=install_dir_var, font=("Segoe UI", 10))
    entry_dir.pack(side="left", fill="x", expand=True)

    def browse_dir():
        d = filedialog.askdirectory(initialdir=install_dir_var.get(), title="Select Installation Folder")
        if d:
            install_dir_var.set(d)
            speak_accessible(f"Selected installation folder: {d}")

    btn_browse = ttk.Button(dir_row, text="Browse... (Alt+B)", command=browse_dir)
    btn_browse.pack(side="right", padx=(8, 0))

    # Shortcut checkboxes with keyboard accelerators
    chk_desktop = ttk.Checkbutton(content_frame, text="Create Desktop shortcut (Alt+K)", variable=desktop_shortcut_var)
    chk_desktop.pack(anchor="w", pady=2)

    chk_start = ttk.Checkbutton(content_frame, text="Create Start Menu shortcut (Alt+M)", variable=start_menu_var)
    chk_start.pack(anchor="w", pady=2)

    chk_launch = ttk.Checkbutton(content_frame, text="Launch SmartTalk when setup completes (Alt+L)", variable=launch_var)
    chk_launch.pack(anchor="w", pady=2)

    # Progress / Status Log (Accessible text area)
    lbl_status_title = ttk.Label(content_frame, text="Setup Status (Alt+S):", font=("Segoe UI", 9, "bold"))
    lbl_status_title.pack(anchor="w", pady=(10, 2))

    log_box = tk.Text(content_frame, height=5, font=("Consolas", 9), wrap="word", bg="#ffffff", fg="#334155", relief="solid", bd=1)
    log_box.pack(fill="both", expand=True)
    log_box.insert("1.0", "Ready to install. Press Enter or click Install to begin.\n")
    log_box.config(state="disabled")

    # Bottom Actions Frame
    btn_frame = tk.Frame(root, bg=bg_main, padx=20, pady=12)
    btn_frame.pack(fill="x", side="bottom")

    btn_cancel = ttk.Button(btn_frame, text="Exit (Escape)", command=root.destroy)
    btn_cancel.pack(side="right", padx=(8, 0))

    def update_log(msg):
        log_box.config(state="normal")
        log_box.insert("end", msg + "\n")
        log_box.see("end")
        log_box.config(state="disabled")

    def start_installation():
        btn_install.config(state="disabled")
        btn_browse.config(state="disabled")
        entry_dir.config(state="disabled")
        update_log("Installing...")

        def worker():
            try:
                target = install_dir_var.get().strip() or DEFAULT_INSTALL_DIR
                success = perform_install(
                    target_dir=target,
                    create_desktop=desktop_shortcut_var.get(),
                    create_startmenu=start_menu_var.get(),
                    launch_after=launch_var.get(),
                    log_callback=lambda m: root.after(0, update_log, m)
                )
                if success:
                    root.after(1200, root.destroy)
            except Exception as e:
                root.after(0, update_log, f"Error: {e}")
                speak_accessible(f"Installation encountered an error: {e}")
                messagebox.showerror("Installation Error", str(e))
                btn_install.config(state="normal")

        threading.Thread(target=worker, daemon=True).start()

    btn_install = ttk.Button(btn_frame, text="Install Now (Enter / Alt+I)", style="Accent.TButton", command=start_installation)
    btn_install.pack(side="right")
    btn_install.focus_set()

    # Keyboard accelerators for complete accessibility
    root.bind("<Return>", lambda e: start_installation() if btn_install['state'] != 'disabled' else None)
    root.bind("<Escape>", lambda e: root.destroy())
    root.bind("<Alt-i>", lambda e: start_installation() if btn_install['state'] != 'disabled' else None)
    root.bind("<Alt-I>", lambda e: start_installation() if btn_install['state'] != 'disabled' else None)
    root.bind("<Alt-d>", lambda e: entry_dir.focus_set())
    root.bind("<Alt-D>", lambda e: entry_dir.focus_set())
    root.bind("<Alt-b>", lambda e: browse_dir())
    root.bind("<Alt-B>", lambda e: browse_dir())
    root.bind("<Alt-k>", lambda e: desktop_shortcut_var.set(not desktop_shortcut_var.get()))
    root.bind("<Alt-K>", lambda e: desktop_shortcut_var.set(not desktop_shortcut_var.get()))
    root.bind("<Alt-m>", lambda e: start_menu_var.set(not start_menu_var.get()))
    root.bind("<Alt-M>", lambda e: start_menu_var.set(not start_menu_var.get()))
    root.bind("<Alt-l>", lambda e: launch_var.set(not launch_var.get()))
    root.bind("<Alt-L>", lambda e: launch_var.set(not launch_var.get()))

    # Announce welcome to screen reader
    root.after(300, lambda: speak_accessible(f"Welcome to {APP_NAME} setup. Press Enter or Alt+I to install, or press Tab to review options."))

    root.mainloop()

def main():
    # Check command line flags for silent / unattended installation
    is_silent = False
    for arg in sys.argv[1:]:
        if arg.lower() in ["/silent", "--silent", "-s", "/s", "--install", "/install"]:
            is_silent = True
            break

    if is_silent:
        perform_install(
            target_dir=DEFAULT_INSTALL_DIR,
            create_desktop=True,
            create_startmenu=True,
            launch_after=False,
            log_callback=None
        )
    else:
        run_gui()

if __name__ == '__main__':
    main()
