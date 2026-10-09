@echo off
TITLE SmartTalk Windows Setup
color 0B
echo ===================================================================
echo               SMARTTALK ACCESSIBLE WINDOWS INSTALLER
echo ===================================================================
echo.

:: Announce to screen reader users via Windows SAPI SpeechSynthesizer
powershell -NoProfile -Command "Add-Type -AssemblyName System.Speech; (New-Object System.Speech.Synthesis.SpeechSynthesizer).SpeakAsync('Starting SmartTalk accessible installation. Please wait.');" >nul 2>&1

set "INSTALL_DIR=%LOCALAPPDATA%\SmartTalk"
echo [1/4] Preparing installation folder at:
echo       %INSTALL_DIR%
if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"
if not exist "%INSTALL_DIR%\assets" mkdir "%INSTALL_DIR%\assets"

echo.
echo [2/4] Stopping any existing SmartTalk instances...
taskkill /F /IM SmartTalk.exe >nul 2>&1

echo.
echo [3/4] Copying SmartTalk application files...
if exist "%~dp0dist\SmartTalk.exe" (
    copy /Y "%~dp0dist\SmartTalk.exe" "%INSTALL_DIR%\SmartTalk.exe" >nul
) else if exist "%~dp0SmartTalk.exe" (
    copy /Y "%~dp0SmartTalk.exe" "%INSTALL_DIR%\SmartTalk.exe" >nul
)

if exist "%~dp0assets\icon.ico" (
    copy /Y "%~dp0assets\icon.ico" "%INSTALL_DIR%\assets\icon.ico" >nul
)

echo.
echo [4/4] Creating Desktop and Start Menu Shortcuts...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ws = New-Object -ComObject WScript.Shell; " ^
  "$desktop = [Environment]::GetFolderPath('Desktop'); " ^
  "$startMenu = [Environment]::GetFolderPath('Programs'); " ^
  "$exe = '%INSTALL_DIR%\SmartTalk.exe'; " ^
  "$ico = '%INSTALL_DIR%\assets\icon.ico'; " ^
  "$s1 = $ws.CreateShortcut(\"$desktop\SmartTalk.lnk\"); " ^
  "$s1.TargetPath = $exe; " ^
  "$s1.WorkingDirectory = '%INSTALL_DIR%'; " ^
  "$s1.IconLocation = \"$ico,0\"; " ^
  "$s1.Description = 'SmartTalk - Desktop Conversational AI'; " ^
  "$s1.Save(); " ^
  "$s2 = $ws.CreateShortcut(\"$startMenu\SmartTalk.lnk\"); " ^
  "$s2.TargetPath = $exe; " ^
  "$s2.WorkingDirectory = '%INSTALL_DIR%'; " ^
  "$s2.IconLocation = \"$ico,0\"; " ^
  "$s2.Description = 'SmartTalk - Desktop Conversational AI'; " ^
  "$s2.Save(); " ^
  "Write-Host ' [OK] Desktop and Start Menu shortcuts created successfully.'"

echo.
echo ===================================================================
echo                    INSTALLATION COMPLETE!
echo ===================================================================
echo  SmartTalk is now installed and ready to use.
echo  Shortcuts have been placed on your Desktop and in your Start Menu.
echo ===================================================================
echo.

powershell -NoProfile -Command "Add-Type -AssemblyName System.Speech; (New-Object System.Speech.Synthesis.SpeechSynthesizer).SpeakAsync('SmartTalk installation completed successfully. You can now launch SmartTalk from your Desktop or Start Menu.');" >nul 2>&1

set /p launch="Would you like to launch SmartTalk now? (Y/N): "
if /i "%launch%"=="Y" (
    start "" "%INSTALL_DIR%\SmartTalk.exe"
)
