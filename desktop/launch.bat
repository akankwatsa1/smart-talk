@echo off
setlocal
title SmartTalk Desktop Launcher

cd /d "%~dp0.."

REM Start Python proxy in background if not already running
start "" /min python proxy.py

REM Reliable pause without input redirection errors
ping 127.0.0.1 -n 2 >nul

REM Launch in native standalone desktop app window mode
if exist "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" (
    start "" "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" --app=http://localhost:5500 --window-size=1280,820
    goto end
)

if exist "C:\Program Files\Google\Chrome\Application\chrome.exe" (
    start "" "C:\Program Files\Google\Chrome\Application\chrome.exe" --app=http://localhost:5500 --window-size=1280,820
    goto end
)

REM Fallback to default web browser
start "" http://localhost:5500

:end
exit /b 0
