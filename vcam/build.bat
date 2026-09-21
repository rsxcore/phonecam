@echo off
rem Builds PhoneCam.dll. Run from any prompt; the script locates the toolchain
rem itself so it works regardless of which VS edition is installed.
setlocal

cd /d "%~dp0"

set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%VSWHERE%" (
    echo [-] vswhere.exe not found. Is Visual Studio installed?
    exit /b 1
)

rem Via a temp file, not `for /f`: the "(x86)" in the vswhere path contains
rem parentheses that cmd would otherwise parse as part of the loop syntax.
set "VSPATH="
"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath > "%TEMP%\phonecam_vspath.txt" 2>nul
set /p VSPATH=<"%TEMP%\phonecam_vspath.txt"
del "%TEMP%\phonecam_vspath.txt" >nul 2>&1

if not defined VSPATH (
    echo [-] No Visual Studio installation with the C++ toolset was found.
    echo     Add the "Desktop development with C++" workload.
    exit /b 1
)

echo [*] Visual Studio at: %VSPATH%
rem vcvars64.bat probes for vswhere itself and prints a spurious "not recognized"
rem on this machine. It finds the toolchain anyway; the redirect just hides it.
call "%VSPATH%\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
if errorlevel 1 (
    echo [-] Failed to initialise the MSVC environment.
    exit /b 1
)

echo [*] Compiling PhoneCam.dll ...
if not exist out mkdir out
cl /nologo /LD /MT /O2 /EHsc /W3 /DUNICODE /D_UNICODE ^
    PhoneCamFilter.cpp ^
    /Fe:out\PhoneCam.dll ^
    /link /DEF:PhoneCamFilter.def ^
    ole32.lib oleaut32.lib uuid.lib strmiids.lib advapi32.lib user32.lib gdi32.lib

if errorlevel 1 (
    echo [-] Build failed.
    exit /b 1
)

del /q *.obj >nul 2>&1
echo [+] Built PhoneCam.dll
