@echo off
rem Registers (or unregisters) PhoneCam.dll as a system video capture device.
rem Writing to HKCR\CLSID needs administrator, so this re-launches itself
rem elevated when it is not already.
setlocal

cd /d "%~dp0"

if /i "%~1"=="unregister" (set "ARG=unregister") else (set "ARG=register")
set "ACTION="
if /i "%ARG%"=="unregister" set "ACTION=/u"

net session >nul 2>&1
if errorlevel 1 (
    echo [*] Requesting administrator rights ...
    rem Always pass a real argument: Start-Process rejects an empty -ArgumentList.
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -ArgumentList '%ARG%' -Verb RunAs"
    exit /b
)

if not exist "%~dp0PhoneCam.dll" (
    echo [-] PhoneCam.dll not found. Run build.bat first.
    pause
    exit /b 1
)

regsvr32 /s %ACTION% "%~dp0PhoneCam.dll"
if errorlevel 1 (
    echo [-] Registration failed.
    pause
    exit /b 1
)

if defined ACTION (echo [+] PhoneCam unregistered.) else (echo [+] PhoneCam registered as a camera.)
echo     Restart any app that was already open so it re-enumerates devices.
pause
