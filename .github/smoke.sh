#!/bin/sh
# Start the freshly built app on the emulator and fail when it crashes, so a broken build is never published.
set -u
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am start -W -n app.sparkink/.MainActivity
sleep 15
adb logcat -d > logcat.txt
grep -A40 "FATAL EXCEPTION" logcat.txt && { echo "App crashed on start"; exit 1; }
adb shell pidof app.sparkink || { echo "App is not running"; tail -200 logcat.txt; exit 1; }
echo "App started fine"
