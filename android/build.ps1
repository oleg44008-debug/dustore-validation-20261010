# Builds the Dustore Android APK straight from the SDK tools.
#
# Gradle is not installed and is not needed for an app this small: aapt2 compiles the
# resources and links the manifest, javac builds the one class, d8 turns it into dex, and
# apksigner signs it. Every step is visible here rather than hidden behind a build system.
#
#   powershell -ExecutionPolicy Bypass -File build.ps1

param([ValidateSet("Free","Prime")][string]$Edition = "Free", [switch]$UpdateDesktopCompanion)

$ErrorActionPreference = "Stop"

$project   = $PSScriptRoot
# DUSTORE_ANDROID_SDK / DUSTORE_JDK let CI point at its own toolchain; the defaults are this machine.
$sdk       = if ($env:DUSTORE_ANDROID_SDK) { $env:DUSTORE_ANDROID_SDK } else { "$env:LOCALAPPDATA\Android\Sdk" }
$buildTool = "$sdk\build-tools\36.0.0"
$platform  = "$sdk\platforms\android-36\android.jar"
$jdk       = if ($env:DUSTORE_JDK) { $env:DUSTORE_JDK } else { (Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory | Select-Object -First 1).FullName }

$out       = Join-Path $project "build"
$outApk    = Join-Path $project $(if ($Edition -eq "Prime") { "Dustore-Prime.apk" } else { "Dustore.apk" })
$keystore  = Join-Path $project "dustore.keystore"

function Step($text) { Write-Host "==> $text" -ForegroundColor Cyan }
function Run($exe, $argList) {
    & $exe @argList
    if ($LASTEXITCODE -ne 0) { throw "$exe exited with $LASTEXITCODE" }
}

foreach ($p in @($buildTool, $platform, $jdk)) {
    if (-not (Test-Path $p)) { throw "missing: $p" }
}
if (-not (Test-Path -LiteralPath $keystore)) {
    throw "Original dustore.keystore is required to preserve signature continuity."
}

$resolvedBuild = [IO.Path]::GetFullPath($out)
$expectedBuild = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "build"))
if ($resolvedBuild -ne $expectedBuild) { throw "Build output is outside the project" }
if (Test-Path -LiteralPath $resolvedBuild) { Remove-Item -LiteralPath $resolvedBuild -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$out\compiled","$out\gen","$out\classes","$out\dex" | Out-Null

# ---- стаб игры eX: шаблон APK без подписи, едет в res/raw/exstub.bin --------------
#
# Телефон подменяет в нём имя пакета, подпись и иконку и подписывает сам (ExApk). Здесь
# только те же aapt2 → javac → d8, что и у приложения, без zipalign и без подписи: их
# делает телефон.
$stub    = Join-Path $project "exstub"
$stubOut = Join-Path $out "stub"
New-Item -ItemType Directory -Force -Path "$stubOut\compiled","$stubOut\gen","$stubOut\classes","$stubOut\dex" | Out-Null

Step "stub: compiling resources"
Run "$buildTool\aapt2.exe" @("compile", "--dir", "$stub\res", "-o", "$stubOut\compiled\res.zip")

Step "stub: linking manifest"
Run "$buildTool\aapt2.exe" @(
    "link",
    "-o", "$stubOut\base.apk",
    "-I", $platform,
    "--manifest", "$stub\AndroidManifest.xml",
    "--java", "$stubOut\gen",
    "--min-sdk-version", "24",
    "--target-sdk-version", "34",
    "--version-code", "1",
    "--version-name", "1.0",
    "$stubOut\compiled\res.zip"
)

Step "stub: compiling Java"
$stubSources = @()
$stubSources += (Get-ChildItem "$stub\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$stubSources += (Get-ChildItem "$stubOut\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
Run "$jdk\bin\javac.exe" (@(
    "-source", "17", "-target", "17",
    "-encoding", "UTF-8",
    "-nowarn",
    "-classpath", $platform,
    "-d", "$stubOut\classes"
) + $stubSources)

Step "stub: dexing"
# d8.bat is a cmd.exe wrapper and cmd.exe caps the command line at 8191 characters:
# a list of class files stopped fitting. One jar instead of hundreds of paths.
Run "$jdk\bin\jar.exe" @("cf", "$stubOut\classes.jar", "-C", "$stubOut\classes", ".")
Run "$buildTool\d8.bat" @(
    "--lib", $platform,
    "--min-api", "24",
    "--output", "$stubOut\dex",
    "$stubOut\classes.jar"
)

Step "stub: packaging into res/raw/exstub.bin"
# A raw resource, not an asset: AssetManager on the test phone listed assets/ex as empty
# although the files were inside the APK, while openRawResource never fails. Neutral
# extension on purpose: a template must not look like an installable package.
New-Item -ItemType Directory -Force -Path "$project\res\raw" | Out-Null
Copy-Item "$stubOut\base.apk" "$project\res\raw\exstub.bin" -Force
Push-Location "$stubOut\dex"
try {
    Run "$buildTool\aapt.exe" @("add", "$project\res\raw\exstub.bin", "classes.dex")
} finally {
    Pop-Location
}

if (-not (Test-Path "$project\res\raw\exstub_key.bin")) {
    throw "нет ключа стаба: сначала powershell -File make-stub-key.ps1"
}

Step "compiling resources ($Edition)"
# Resources are staged so the Prime build can carry its own launcher label.
$res = Join-Path $out "res"
Copy-Item "$project\res" $res -Recurse -Force
if ($Edition -eq "Prime") {
    $strings = Join-Path $res "values\strings.xml"
    $text = [IO.File]::ReadAllText($strings)
    [IO.File]::WriteAllText($strings, $text.Replace(">DustoreX</string>", ">DustoreX Prime</string>"), (New-Object Text.UTF8Encoding $false))
}
Run "$buildTool\aapt2.exe" @("compile", "--dir", $res, "-o", "$out\compiled\res.zip")

Step "linking manifest + R.java"
Run "$buildTool\aapt2.exe" @(
    "link",
    "-o", "$out\base.apk",
    "-I", $platform,
    "--manifest", "$project\AndroidManifest.xml",
    "--java", "$out\gen",
    "--min-sdk-version", "24",
    "--target-sdk-version", "34",
    "--version-code", "33",
    "--version-name", "5.21",
    "$out\compiled\res.zip"
)

Step "compiling Java"
# The edition is a compile-time constant: Prime.ON folds away in the Free build.
$editionDir = Join-Path $out "gen\ru\dustore\launcher"
New-Item -ItemType Directory -Force -Path $editionDir | Out-Null
$primeFlag = if ($Edition -eq "Prime") { "true" } else { "false" }
[IO.File]::WriteAllText((Join-Path $editionDir "BuildEdition.java"), "package ru.dustore.launcher;`nfinal class BuildEdition { static final boolean PRIME = $primeFlag; }`n")
$sources = @()
$sources += (Get-ChildItem "$project\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$sources += (Get-ChildItem "$out\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
Run "$jdk\bin\javac.exe" (@(
    "-source", "17", "-target", "17",
    "-encoding", "UTF-8",
    "-nowarn",
    "-classpath", $platform,
    "-d", "$out\classes"
) + $sources)

Step "dexing"
# Same 8191-character cmd.exe limit as above: the app has passed a hundred classes.
Run "$jdk\bin\jar.exe" @("cf", "$out\classes.jar", "-C", "$out\classes", ".")
Run "$buildTool\d8.bat" @(
    "--lib", $platform,
    "--min-api", "24",
    "--output", "$out\dex",
    "$out\classes.jar"
)

Step "packaging"
Copy-Item "$out\base.apk" "$out\unsigned.apk" -Force
Push-Location "$out\dex"
try {
    # aapt add stores the dex at the archive root, which is where Android looks for it.
    Run "$buildTool\aapt.exe" @("add", "$out\unsigned.apk", "classes.dex")
} finally {
    Pop-Location
}

Step "adding eXen runtime (native libs)"
$jni = Join-Path $project "jniLibs"
if (Test-Path $jni) {
    # Бинарники eXen едут в lib/arm64-v8a: Android разрешает исполнять файлы только
    # оттуда, и при установке распаковывает их туда, откуда запуск разрешён
    # (extractNativeLibs="true" в манифесте).
    $stage = Join-Path $out "lib\arm64-v8a"
    New-Item -ItemType Directory -Force -Path $stage | Out-Null
    Copy-Item "$jni\arm64-v8a\*" $stage -Force

    Push-Location $out
    try {
        Get-ChildItem "lib\arm64-v8a" -File | ForEach-Object {
            Run "$buildTool\aapt.exe" @("add", "$out\unsigned.apk", "lib/arm64-v8a/$($_.Name)")
        }
    } finally {
        Pop-Location
    }
}

Step "zipalign"
Run "$buildTool\zipalign.exe" @("-f", "-p", "4", "$out\unsigned.apk", "$out\aligned.apk")

Step "signing"
Run "$buildTool\apksigner.bat" @(
    "sign",
    "--ks", $keystore,
    "--ks-pass", ("pass:" + $env:DUSTORE_KS_PASS),
    "--key-pass", ("pass:" + $env:DUSTORE_KS_PASS),
    "--out", $outApk,
    "$out\aligned.apk"
)

Step "verifying signature"
Run "$buildTool\apksigner.bat" @("verify", "--verbose", $outApk)

# The desktop launcher embeds companion.apk into its own .exe and installs THAT file on
# the phone. Without this copy the phone keeps getting the old build: everything fixed
# here would simply never reach the user.
$companion = Join-Path $project $(if ($Edition -eq "Prime") { "..\DustoreX\companion-prime.apk" } else { "..\DustoreX\companion.apk" })

if ($UpdateDesktopCompanion -and (Test-Path (Split-Path $companion -Parent))) {
    Step "updating companion.apk for the desktop launcher"
    Copy-Item $outApk $companion -Force
}

$size = [math]::Round((Get-Item $outApk).Length / 1KB, 1)
Write-Host ""
Write-Host "DONE: $outApk ($size KB)" -ForegroundColor Green
