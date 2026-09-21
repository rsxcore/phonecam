param([switch]$Test)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
function Run-Step([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Failed ($LASTEXITCODE): $Executable $Arguments" }
}
$cargo = Join-Path $env:USERPROFILE '.cargo\bin\cargo.exe'
if (-not (Test-Path $cargo)) { $cargo = (Get-Command cargo -ErrorAction Stop).Source }
if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem 'C:\Program Files\Microsoft\jdk-*' -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if (-not (Test-Path 'android\signing.properties')) { throw 'Run New-ReleaseKey.ps1 once to create a local APK signing key.' }
Run-Step $cargo @('build','--locked','--release','--manifest-path','server\Cargo.toml')
Run-Step '.\vcam\build.bat' @()
Run-Step '.\desktop\build.bat' @()
Run-Step '.\android\gradlew.bat' @('-p','android','assembleRelease','--console=plain')
Copy-Item 'android\app\build\outputs\apk\release\app-release.apk' 'dist\PhoneCam.apk' -Force
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
Run-Step "$sdk\build-tools\36.0.0\apksigner.bat" @('verify','--verbose','dist\PhoneCam.apk')
if ($Test) {
    Run-Step $cargo @('test','--locked','--manifest-path','server\Cargo.toml')
    Run-Step $cargo @('clippy','--locked','--manifest-path','server\Cargo.toml','--all-targets','--','-D','warnings')
    Run-Step '.\tests\native.bat' @()
    Run-Step '.\android\gradlew.bat' @('-p','android','testDebugUnitTest','lintRelease','--console=plain')
}
Write-Output 'Ready: dist\PhoneCam-1.1.exe and dist\PhoneCam.apk'
