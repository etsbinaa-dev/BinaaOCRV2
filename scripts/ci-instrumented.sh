#!/usr/bin/env bash
# يُشغَّل داخل المحاكي (android-emulator-runner) على نسخة debug (com.binaa.ocr.debug).
set -u
mkdir -p ci-output
adb logcat -c || true
./gradlew connectedDebugAndroidTest --no-daemon --stacktrace
status=$?
{ adb shell getprop ro.build.version.release; adb shell getprop ro.build.version.sdk; } > ci-output/android-version.txt || true
adb logcat -d -v brief AndroidRuntime:E TestRunner:I '*:S' > ci-output/test-logcat.txt || true
echo "===== Android version ====="; cat ci-output/android-version.txt
exit $status
