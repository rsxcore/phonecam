@echo off
setlocal
cd /d "%~dp0"
rem Per-user registration: no administrator rights are needed.
if not exist out\PhoneCam.dll (
  echo Build the filter with build.bat first.
  exit /b 1
)
"%SystemRoot%\System32\regsvr32.exe" /s "%~dp0out\PhoneCam.dll"
exit /b %errorlevel%
