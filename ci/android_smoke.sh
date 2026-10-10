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
# The three bottom tabs sit at 1/6, 3/6 and 5/6 of the width, just above the gesture bar.
tab() {
  local size w h
  size=$(adb shell wm size | tail -1 | grep -oE '[0-9]+x[0-9]+')
  w=${size%x*}; h=${size#*x}
  adb shell input tap $(( w * (2 * $1 - 1) / 6 )) $(( h - h * 56 / 1000 ))
  sleep 4
}
tabs() {
  tab 2; shot "$1-library"
  tab 3; shot "$1-computer"
  tab 1; shot "$1-store"
}
alive() { adb shell pidof "$PKG" > /dev/null 2>&1; }

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
tabs 01
adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" | head -3 > "$OUT/top.txt"
note "top: $(tr -s ' ' < "$OUT/top.txt" | head -1)"

# Layout at the font scales people actually use.
for scale in 1.3 2.0; do
  adb shell settings put system font_scale "$scale"
  start_app
  sleep 15
  shot "02-font-$scale"
  tabs "02-font-$scale"
done
adb shell settings put system font_scale 1.0

# A small phone: density change only, which does not restart Google services the way
# a display size override does (that restart kills dependent apps on the emulator).
adb shell wm density 560
start_app
sleep 15
shot 03-dense
tabs 03-dense
adb shell wm density reset
sleep 3

# Rotation: the activity handles configuration changes itself.
start_app
sleep 10
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 5
shot 04-landscape
# On its side the bars go compact: every tab must still be reachable, and the upright
# layout must come back afterwards.
ltab() {
  local size w h
  size=$(adb shell wm size | tail -1 | grep -oE '[0-9]+x[0-9]+')
  h=${size%x*}; w=${size#*x}
  adb shell input tap $(( w * (2 * $1 - 1) / 6 )) $(( h - h * 120 / 1000 ))
  sleep 4
}
ltab 2; shot 04-landscape-library
ltab 3; shot 04-landscape-computer
ltab 1; shot 04-landscape-store
adb shell settings put system user_rotation 0
sleep 4
shot 04-upright-again
alive || { note "process gone after rotation"; failures=$((failures + 1)); }

# Random interaction inside the app only: the task is pinned so monkey cannot wander off
# into the shade or the home screen. A fixed seed keeps runs comparable.
start_app
sleep 10
task=$(adb shell am stack list 2>/dev/null | grep -oE "taskId=[0-9]+: $PKG" | head -1 | grep -oE '[0-9]+' | head -1)
[ -n "${task:-}" ] && adb shell am task lock "$task" && note "pinned task $task"
adb shell monkey -p "$PKG" --pct-syskeys 0 --pct-appswitch 0 --pct-anyevent 0 --throttle 120 -s 4242 -v 4000 > "$OUT/monkey.txt" 2>&1
note "monkey: $(tail -1 "$OUT/monkey.txt")"
shot 05-after-monkey
adb shell am task lock stop > /dev/null 2>&1 || true

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
# Deaths the two checks above cannot see: a native crash or the app ending itself.
grep -E "Process $PKG .* has died|Fatal signal|tombstone" "$OUT/logcat.txt" | grep -v "depends on provider" | head -5 >> "$OUT/summary.txt" || true
grep -E "StrictMode|W/System.err|E/AndroidRuntime" "$OUT/logcat.txt" | grep -c . > "$OUT/warnings-count.txt" || true
note "failures: $failures"
exit $failures
