# Key used by the phone to sign eX game stub APKs. Made once, lives in res/raw.
#
# The key guards nothing: a stub asks for no permissions and shares no uid with DustoreX,
# so leaking it grants nothing beyond the right to produce an identical icon. One key for
# all games means a stub can be reinstalled without "different signature" errors.
#
# ASCII only on purpose: Windows PowerShell 5.1 reads .ps1 without a BOM as ANSI, and any
# typographic dash inside a string turns into a stray quote that breaks the whole script.
#
#   powershell -ExecutionPolicy Bypass -File make-stub-key.ps1

$ErrorActionPreference = "Stop"

$project = $PSScriptRoot
$jdk     = if ($env:DUSTORE_JDK) { $env:DUSTORE_JDK } else { (Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory | Select-Object -First 1).FullName }
$raw     = Join-Path $project "res\raw"
$store   = Join-Path $project "tools\stub.p12"

New-Item -ItemType Directory -Force -Path $raw | Out-Null

if (Test-Path (Join-Path $raw "exstub_key.bin")) {
    Write-Host "key already exists: $raw\exstub_key.bin (kept)"
    exit 0
}

if (-not (Test-Path $store)) {
    & "$jdk\bin\keytool.exe" -genkeypair -v -keystore $store -storetype PKCS12 -storepass exstub `
        -alias exstub -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=eX game, OU=eX, O=eXepc, C=RU"
    if ($LASTEXITCODE -ne 0) { throw "keytool exited with $LASTEXITCODE" }
}

& "$jdk\bin\java.exe" (Join-Path $project "tools\ExportKey.java") $store exstub exstub `
    (Join-Path $raw "exstub_key.bin") (Join-Path $raw "exstub_cer.bin")
if ($LASTEXITCODE -ne 0) { throw "ExportKey exited with $LASTEXITCODE" }

Write-Host "DONE: $raw\exstub_key.bin, exstub_cer.bin"
