@echo off
setlocal
cd /d "%~dp0"
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath > "%TEMP%\phonecam_ui_vs.txt"
set /p VSPATH=<"%TEMP%\phonecam_ui_vs.txt"
if not defined VSPATH exit /b 1
call "%VSPATH%\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
if errorlevel 1 exit /b 1
rc /nologo PhoneCam.rc
if errorlevel 1 exit /b 1
if not exist ..\dist mkdir ..\dist
cl /nologo /utf-8 /std:c++17 /MT /O2 /EHsc /W4 /DUNICODE /D_UNICODE PhoneCam.cpp PhoneCam.res /Fe:..\dist\PhoneCam-1.1.exe /link /SUBSYSTEM:WINDOWS user32.lib gdi32.lib shell32.lib ole32.lib advapi32.lib
exit /b %errorlevel%

