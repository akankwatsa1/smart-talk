Set WshShell = CreateObject("WScript.Shell")
Set FSO = CreateObject("Scripting.FileSystemObject")
strScriptDir = FSO.GetParentFolderName(WScript.ScriptFullName)
WshShell.Run "cmd /c """ & strScriptDir & "\launch.bat""", 0, False
Set WshShell = Nothing
Set FSO = Nothing
