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

# Open the settings through the menu button and check they draw (once showed a black screen).
tap() { # tap the centre of the first node whose content-desc or text is $1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null && adb pull /sdcard/ui.xml ui.xml >/dev/null
  b=$(tr '>' '\n' < ui.xml | grep -E "(content-desc|text)=\"$1\"" | head -1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
  [ -n "$b" ] || return 1
  set -- $b
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
adb exec-out screencap -p > home.png
tap "Instellingen" || tap "Settings" || { echo "Menu button not found"; cat ui.xml; exit 1; }
sleep 5
adb exec-out screencap -p > settings.png
adb logcat -d > logcat2.txt
grep -A40 "FATAL EXCEPTION" logcat2.txt && { echo "App crashed opening settings"; exit 1; }
adb shell uiautomator dump /sdcard/ui.xml >/dev/null && adb pull /sdcard/ui.xml ui.xml >/dev/null
grep -q "Versie\|Version" ui.xml || { echo "Settings did not draw"; grep -iE "sparkink|AndroidRuntime|ANR" logcat2.txt | tail -80; exit 1; }
echo "Settings open fine"
