param(
    [Parameter(Mandatory = $true)][string]$InputApk,
    [Parameter(Mandatory = $true)][string]$OutputApk
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
# IMPORTANT: This file contains no private key/password. The key never leaves the Windows profile.
$InputApk = (Resolve-Path -LiteralPath $InputApk).Path
if (-not (Test-Path -LiteralPath $InputApk -PathType Leaf)) { throw "Input APK not found" }
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools\36.1.0'
$aapt = Join-Path $sdk 'aapt.exe'
$signer = Join-Path $sdk 'apksigner.bat'
$keytool = 'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe'
$javaHome = Split-Path (Split-Path $keytool -Parent) -Parent
if (-not (Test-Path -LiteralPath (Join-Path $javaHome 'bin\java.exe'))) { throw 'Java runtime missing' }
$env:JAVA_HOME = $javaHome
foreach ($executable in @($aapt, $signer, $keytool)) {
    if (-not (Test-Path -LiteralPath $executable)) { throw "Missing required tool: $executable" }
}

$badging = (& $aapt dump badging $InputApk 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'Cannot read Android package metadata.' }
if ($badging -notmatch "package: name='kz\.kareta\.app\.staging' versionCode='12' versionName='1\.5\.2-staging'") {
    throw 'Refusing to sign APK: unexpected application ID/version.'
}
$keyDir = Join-Path $env:LOCALAPPDATA 'Kareta\Signing'
$keyStore = Join-Path $keyDir 'staging-keystore.p12'
$passFile = Join-Path $keyDir 'staging-password.dpapi'
if (-not (Test-Path -LiteralPath $keyDir)) { New-Item -ItemType Directory -Path $keyDir -Force | Out-Null }
if ((Test-Path -LiteralPath $keyStore) -xor (Test-Path -LiteralPath $passFile)) {
    throw 'Partial signing identity: restore the original keystore/password pair. Do not replace only one file.'
}
if (-not (Test-Path -LiteralPath $keyStore)) {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $pass = [Convert]::ToBase64String($bytes)
    $secure = ConvertTo-SecureString -String $pass -AsPlainText -Force
    $secure | ConvertFrom-SecureString | Set-Content -Path $passFile -Encoding UTF8 -NoNewline
    & $keytool -genkeypair -noprompt -storetype PKCS12 -keystore $keyStore -alias kareta_staging -keyalg RSA -keysize 3072 -validity 3650 -dname 'CN=Kareta Staging, O=Kareta, C=KZ' -storepass $pass -keypass $pass | Out-Null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $keyStore)) { throw 'Key generation failed.' }
    Write-Output "SIGNING_IDENTITY_CREATED: $keyStore"
    $pass = $null
}
$protected = (Get-Content -LiteralPath $passFile -Raw).Trim()
$securePass = ConvertTo-SecureString $protected
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePass)
try { $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
try {
    $outDir = Split-Path -Parent $OutputApk
    if ($outDir -and -not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }
    # apksigner generates a new v2/v3 signature. Keep the same PKCS12 key across future releases.
    & $signer sign --min-sdk-version 24 --ks $keyStore --ks-key-alias kareta_staging --ks-pass "pass:$password" --key-pass "pass:$password" --out $OutputApk $InputApk
    if ($LASTEXITCODE -ne 0) { throw 'apksigner sign failed.' }
}
finally { $password = $null }
& $signer verify --verbose --print-certs $OutputApk
if ($LASTEXITCODE -ne 0) { throw 'Signed APK verification failed.' }
$hash = (Get-FileHash -LiteralPath $OutputApk -Algorithm SHA256).Hash
Write-Output "SIGNED_APK: $OutputApk"
Write-Output "SHA256: $hash"
Write-Output "BACKUP_REQUIRED: $keyStore and $passFile (both files are required for future updates on this Windows account)"
