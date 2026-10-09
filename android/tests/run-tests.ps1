# Компилирует исходники приложения и проверки против android.jar и запускает ExTests на
# обычной JDK. Годится только для классов, которые не трогают Android на ходу: рецепт,
# бинарный манифест, подпись APK, иконка из PE.
#
#   powershell -ExecutionPolicy Bypass -File tests\run-tests.ps1 recipe "D:\Games\Fruit Blaster"

$ErrorActionPreference = "Stop"

$project  = Split-Path $PSScriptRoot -Parent
$sdk      = if ($env:DUSTORE_ANDROID_SDK) { $env:DUSTORE_ANDROID_SDK } else { "$env:LOCALAPPDATA\Android\Sdk" }
$platform = "$sdk\platforms\android-36\android.jar"
$jdk      = if ($env:DUSTORE_JDK) { $env:DUSTORE_JDK } else { (Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory | Select-Object -First 1).FullName }
$out      = Join-Path $PSScriptRoot "build"

$resolvedOutput = [IO.Path]::GetFullPath($out)
$expectedOutput = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "build"))
if ($resolvedOutput -ne $expectedOutput) { throw "Test output is outside the project" }
if (Test-Path -LiteralPath $resolvedOutput) { Remove-Item -LiteralPath $resolvedOutput -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null

$sources = @()
$sources += (Get-ChildItem "$project\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$sources += (Get-ChildItem $PSScriptRoot -Filter *.java | ForEach-Object { $_.FullName })

# R.java нужен только классам с ресурсами — их здесь не запускают, но компилировать надо
# всё вместе. Берём сгенерированный основной сборкой, если он есть.
$gen = Join-Path $project "build\gen"
if (Test-Path $gen) {
    $sources += (Get-ChildItem $gen -Recurse -Filter *.java | ForEach-Object { $_.FullName })
}

& "$jdk\bin\javac.exe" -source 17 -target 17 -encoding UTF-8 -nowarn -classpath $platform -d $out $sources
if ($LASTEXITCODE -ne 0) { throw "javac exited with $LASTEXITCODE" }

$sep = ";"

# Настоящий org.json (та же библиотека, что встроена в Android) стоит ПЕРЕД android.jar:
# в android.jar от него одни заглушки, и всякий new JSONObject() падал бы «Stub!».
$json = Join-Path $PSScriptRoot "lib\json.jar"
& "$jdk\bin\java.exe" "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" -cp "$out$sep$json$sep$platform" ru.dustore.launcher.ExTests @args
exit $LASTEXITCODE
