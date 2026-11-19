#!/bin/bash
# Emulator test: launches the app, plays a real radio stream with the FFmpeg
# player, checks that playback survives going to the background, rotation and
# foldable-like screen sizes, and prints screenshots (base64) and logs.
APK=$(find apk -name '*.apk' | head -1)
PKG=net.sourceforge.servestream
STREAM="https://stream1.rcast.net/73730"
T="timeout 60"
RESULTS=""

shot() {
  sleep "${2:-5}"
  $T adb exec-out screencap -p > "/tmp/$1.png"
  echo "=====SHOT $1 BEGIN====="
  base64 -w0 "/tmp/$1.png"; echo
  echo "=====SHOT $1 END====="
}

uid_of_app() { $T adb shell cmd package list packages -U $PKG | sed -n 's/.*uid:\([0-9]*\).*/\1/p' | head -1; }

# 1 = our app has an AudioTrack in "started" state, 0 otherwise
audio_playing() {
  local uid; uid=$(uid_of_app)
  $T adb shell dumpsys audio | grep -E "u/pid:$uid/" | grep -q "state:started" && echo 1 || echo 0
}

check() {
  local name="$1"
  local alive=0; $T adb shell pidof $PKG >/dev/null && alive=1
  local playing; playing=$(audio_playing)
  local res="FAIL"; [ "$alive" = 1 ] && [ "$playing" = 1 ] && res="PASS"
  echo "CHECK $name: alive=$alive audio_started=$playing -> $res"
  RESULTS="$RESULTS\n$res  $name"
}

echo "boot done: $($T adb shell getprop sys.boot_completed), sdk $($T adb shell getprop ro.build.version.sdk)"
$T adb shell wm size; $T adb shell wm density
$T adb install -r -g "$APK" || exit 1
$T adb shell appops set $PKG POST_NOTIFICATION allow
$T adb logcat -c

# --- phone portrait, station list
$T adb shell am start -n $PKG/.activity.MainActivity
shot phone_main 8

# --- play a real stream (FFmpeg + TLS)
$T adb shell am start -a android.intent.action.VIEW -d "$STREAM" -t audio/mpeg -n $PKG/.activity.MainActivity
sleep 25
shot phone_player 2
check "stream plays"

# --- background: go home, wait
$T adb shell input keyevent KEYCODE_HOME
sleep 30
check "keeps playing in background (30 s)"

# --- rotation
$T adb shell am start -n $PKG/.activity.MainActivity
$T adb shell settings put system accelerometer_rotation 0
$T adb shell settings put system user_rotation 1
shot phone_landscape 6
check "keeps playing after rotation"
$T adb shell settings put system user_rotation 0

# --- foldable-like sizes (density 420)
$T adb shell wm density 420
for size in "1840x1840 fold_open_1x1" "1800x2400 fold_open_4x3" "1080x1728 fold_closed_10x16"; do
  set -- $size
  $T adb shell wm size "$1"
  shot "$2" 7
  check "keeps playing at $2 ($1)"
done
$T adb shell wm size reset
$T adb shell wm density reset

echo "=====LOGCAT BEGIN====="
$T adb logcat -d -v brief | grep -iE "servestream|FFmpeg|AndroidRuntime|FATAL|TAG  |MediaPlayer" | grep -viE "ApkAssets|PackageConfigPersister" | tail -n 200
echo "=====LOGCAT END====="

echo "=====RESULTS BEGIN====="
echo -e "$RESULTS"
echo "=====RESULTS END====="
exit 0
