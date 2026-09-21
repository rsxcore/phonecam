$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if ((Test-Path 'android\phonecam-release.jks') -or (Test-Path 'android\signing.properties')) { throw 'A signing key already exists. It must not be replaced: Android updates require the original key.' }
New-Item -ItemType Directory -Force work | Out-Null
$secret = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$secretFile = Join-Path $PSScriptRoot 'work\signing-password.txt'
[IO.File]::WriteAllText($secretFile, $secret)
$keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\keytool.exe' } else { (Get-Command keytool -ErrorAction Stop).Source }
& $keytool -genkeypair -keystore android\phonecam-release.jks -alias phonecam -keyalg RSA -keysize 3072 -validity 10000 -storepass:file $secretFile -keypass:file $secretFile -dname 'CN=PhoneCam Local Release, O=PhoneCam, C=XX'
if ($LASTEXITCODE -ne 0) { throw 'Key generation failed' }
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'android\signing.properties'), "storeFile=phonecam-release.jks`nstorePassword=$secret`nkeyAlias=phonecam`nkeyPassword=$secret`n")
Remove-Item -LiteralPath $secretFile
Write-Output 'Created private local release key. Back up android\phonecam-release.jks and android\signing.properties together; do not publish them.'
