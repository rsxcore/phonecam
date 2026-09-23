param([switch]$Test)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
function Run-Step([string]$Executable, [string[]]$Arguments, [string]$Dir = $PSScriptRoot) {
    Push-Location $Dir
    try {
        & $Executable @Arguments
        if ($LASTEXITCODE -ne 0) { throw "Failed ($LASTEXITCODE): $Executable $Arguments" }
    } finally { Pop-Location }
}
if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem 'C:\Program Files\Microsoft\jdk-*' -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if (-not (Test-Path 'android\signing.properties')) { throw 'Run New-ReleaseKey.ps1 once to create a local APK signing key.' }
New-Item -ItemType Directory -Force dist | Out-Null

# 1. Virtual camera DLL (embedded into the desktop app).
Run-Step "$PSScriptRoot\vcam\build.bat" @()

# 2. Desktop app: Rust engine + Tauri UI, one executable.
Run-Step 'npm' @('ci', '--no-audit', '--no-fund') "$PSScriptRoot\desktop"
Run-Step 'npx' @('tauri', 'build', '--no-bundle') "$PSScriptRoot\desktop"
Copy-Item 'desktop\src-tauri\target\release\phonecam.exe' 'dist\PhoneCam.exe' -Force

# 3. Android app.
Run-Step "$PSScriptRoot\android\gradlew.bat" @('-p', 'android', 'assembleRelease', '--console=plain')
Copy-Item 'android\app\build\outputs\apk\release\app-release.apk' 'dist\PhoneCam.apk' -Force

if ($Test) {
    Run-Step 'cargo' @('test', '--release', '--manifest-path', 'server\Cargo.toml')
    Run-Step "$PSScriptRoot\tests\native.bat" @()
    Run-Step 'npx' @('svelte-check', '--tsconfig', './tsconfig.json') "$PSScriptRoot\desktop"
}
Write-Output 'Ready: dist\PhoneCam.exe and dist\PhoneCam.apk'
