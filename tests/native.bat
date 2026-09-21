@echo off
setlocal
cd /d "%~dp0.."
"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath > work\vs-test.txt
set /p VSPATH=<work\vs-test.txt
call "%VSPATH%\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
cl /nologo /MT /O2 /EHsc /W4 /DUNICODE /D_UNICODE tests\native_lifetime.cpp /Fe:work\native-tests.exe /link ole32.lib oleaut32.lib uuid.lib strmiids.lib advapi32.lib user32.lib gdi32.lib
if errorlevel 1 exit /b 1
work\native-tests.exe
