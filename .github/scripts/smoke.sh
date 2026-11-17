#!/bin/bash
# Installs the APK on the emulator, opens a few screens, and prints the
# crash log plus base64 screenshots so they can be read from the CI log.
APK=$(find apk -name '*.apk' | head -1)
PKG=net.sourceforge.servestream

shot() {
  sleep "${2:-6}"
  adb exec-out screencap -p > "/tmp/$1.png"
  echo "=====SHOT $1 BEGIN====="
  base64 -w0 "/tmp/$1.png"; echo
  echo "=====SHOT $1 END====="
}

adb install -r -g "$APK" || exit 1
adb logcat -c

adb shell am start -W -n $PKG/.activity.MainActivity
shot main 8

adb shell input keyevent KEYCODE_BACK
adb shell am start -W -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "https://stream1.rcast.net/73730" -n $PKG/.activity.AddUrlActivity
shot add_url 5

adb shell cmd uimode night yes
adb shell am start -W -n $PKG/.activity.MainActivity
shot main_dark 6
adb shell cmd uimode night no

echo "=====LOGCAT BEGIN====="
adb logcat -d -v brief AndroidRuntime:E DEBUG:F CrashReporter:* ServeStream:* '*:E' | grep -v "^E/\(Adreno\|perf\|cutils\|libc\|Zygote\|bpf\)" | tail -n 300
echo "=====LOGCAT END====="

adb shell pidof $PKG >/dev/null && echo "APP_ALIVE=yes" || echo "APP_ALIVE=no"
exit 0
