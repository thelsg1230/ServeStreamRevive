#!/bin/bash
# Installs the APK on the emulator, opens screens, and prints the log,
# main-thread stacks (if the app hangs) and base64 screenshots to the CI log.
APK=$(find apk -name '*.apk' | head -1)
PKG=net.sourceforge.servestream
T="timeout 60"

shot() {
  sleep "${2:-6}"
  $T adb exec-out screencap -p > "/tmp/$1.png"
  echo "=====SHOT $1 BEGIN====="
  base64 -w0 "/tmp/$1.png"; echo
  echo "=====SHOT $1 END====="
}

stacks() {
  pid=$($T adb shell pidof $PKG | tr -d '\r')
  echo "=====STACKS $1 (pid $pid) BEGIN====="
  if [ -n "$pid" ]; then
    $T adb shell kill -3 "$pid"
    sleep 3
    f=$($T adb shell 'ls -t /data/anr/ 2>/dev/null | head -1' | tr -d '\r')
    [ -n "$f" ] && $T adb shell cat "/data/anr/$f" | awk '/^"main"/,/^$/' | head -80
  fi
  echo "=====STACKS $1 END====="
}

echo "boot done: $($T adb shell getprop sys.boot_completed)"
$T adb root; sleep 3; $T adb wait-for-device
$T adb install -r -g "$APK" || exit 1
$T adb logcat -c

$T adb shell am start -n $PKG/.activity.MainActivity
shot main 12
stacks main

$T adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "https://stream1.rcast.net/73730" -n $PKG/.activity.AddUrlActivity
shot add_url 6

$T adb shell cmd uimode night yes
$T adb shell am start -n $PKG/.activity.MainActivity
shot main_dark 6
$T adb shell cmd uimode night no

echo "=====LOGCAT BEGIN====="
$T adb logcat -d -v brief | grep -iE "servestream|AndroidRuntime|FATAL|ANR|crash|Exception" | tail -n 300
echo "=====LOGCAT END====="
$T adb shell pidof $PKG >/dev/null && echo "APP_ALIVE=yes" || echo "APP_ALIVE=no"
exit 0
