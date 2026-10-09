Set WshShell = CreateObject("WScript.Shell")
strDesktop = WshShell.SpecialFolders("Desktop")
strScriptDir = CreateObject("Scripting.FileSystemObject").GetParentFolderName(WScript.ScriptFullName)

Set oShellLink = WshShell.CreateShortcut(strDesktop & "\SmartTalk.lnk")
oShellLink.TargetPath = "wscript.exe"
oShellLink.Arguments = """" & strScriptDir & "\launch.vbs"""
oShellLink.WorkingDirectory = strScriptDir
oShellLink.WindowStyle = 1
oShellLink.Description = "SmartTalk - Desktop Conversational AI"
oShellLink.Save

WScript.Echo "SmartTalk desktop shortcut created successfully on your Desktop!"
