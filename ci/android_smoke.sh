#!/usr/bin/env bash
# Installs a CI-signed APK on the emulator and looks for what a user would hit first:
# crashes, ANRs, layouts at large font scales, and random interaction (monkey).
# Usage: android_smoke.sh <apk> <report dir>
set -u
APK="$1"
OUT="$2"
PKG=ru.dustore.launcher
mkdir -p "$OUT"
failures=0
note() { echo "$*" | tee -a "$OUT/summary.txt"; }

shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }
start_app() {
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/.MainActivity" >> "$OUT/start.txt" 2>&1
}

adb shell getprop ro.product.cpu.abilist > "$OUT/abilist.txt"
note "ABIs: $(cat "$OUT/abilist.txt")"
if ! adb install -r -g "$APK" > "$OUT/install.txt" 2>&1; then
  note "INSTALL FAILED: $(cat "$OUT/install.txt")"
  exit 1
fi
note "installed $(basename "$APK")"

adb logcat -c
start_app
sleep 25
shot 01-start
adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" | head -3 > "$OUT/top.txt"
note "top: $(tr -s ' ' < "$OUT/top.txt" | head -1)"

# Layout at the font scales people actually use.
for scale in 1.3 2.0; do
  adb shell settings put system font_scale "$scale"
  start_app
  sleep 15
  shot "02-font-$scale"
done
adb shell settings put system font_scale 1.0

# Small and tablet-like screens.
for size in 720x1280 1600x2560; do
  adb shell wm size "$size"
  start_app
  sleep 15
  shot "03-size-$size"
done
adb shell wm size reset
adb shell wm density reset

# Random interaction inside the app only; a fixed seed keeps runs comparable.
start_app
sleep 10
adb shell monkey -p "$PKG" --pct-syskeys 0 --pct-appswitch 0 --throttle 150 -s 4242 -v 2500 > "$OUT/monkey.txt" 2>&1
note "monkey: $(tail -1 "$OUT/monkey.txt")"
shot 04-after-monkey

adb logcat -d > "$OUT/logcat.txt"
adb logcat -d -b crash > "$OUT/logcat-crash.txt" 2>/dev/null || true

if grep -qE "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat.txt" "$OUT/logcat-crash.txt"; then
  note "CRASH/ANR found:"
  grep -nE -A25 "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat.txt" "$OUT/logcat-crash.txt" | head -120 | tee -a "$OUT/summary.txt"
  failures=$((failures + 1))
fi
if grep -qE "CRASH: $PKG|NOT RESPONDING: $PKG" "$OUT/monkey.txt"; then
  note "monkey reported a crash or ANR"
  grep -nE -A30 "CRASH: $PKG|NOT RESPONDING: $PKG" "$OUT/monkey.txt" | head -80 | tee -a "$OUT/summary.txt"
  failures=$((failures + 1))
fi
note "failures: $failures"
exit $failures
