#!/usr/bin/env bash
# يُشغَّل داخل المحاكي (android-emulator-runner) على نسخة debug (com.binaa.ocr.debug).
set -u
mkdir -p ci-output
adb logcat -c || true

# ---- إثبات حماية CorpusActivity (android.permission.DUMP) قبل الاختبارات، على هذا الـAPI نفسه:
# 1) تطبيق عادي (access-probe: حزمة وuid مستقلان) لا يستطيع تشغيله، والضابط: يستطيع تشغيل MainActivity المُصدَّر،
# 2) adb shell يستطيع تشغيله ويكتب التقرير، 3) نسخة debug لا تطلب أي صلاحية Android.
APP=com.binaa.ocr.debug
CORPUS=$APP/com.binaa.ocr.goldencorpus.CorpusActivity
REPORTS=/sdcard/Android/data/$APP/files/corpus-reports
probe_fail=0
probe() { echo "$*" | tee -a ci-output/corpus-access-probe.txt; }

./gradlew installDebug --no-daemon --stacktrace || probe_fail=1

# (1) تطبيق عادي: اختبارات :access-probe تعمل داخل تطبيقه هو، تحت uid مستقل.
adb logcat -c || true
./gradlew :access-probe:connectedDebugAndroidTest --no-daemon --stacktrace
access_status=$?
probe "== access-probe (ordinary app, own uid) results:"
probe "$(adb logcat -d -s AccessProbe:I | grep ACCESS_PROBE)"
if [ "$access_status" -ne 0 ]; then
  echo "::error::access-probe failed: an ordinary app was not refused, or the control could not start MainActivity"; probe_fail=1
fi
adb shell am force-stop "$APP" || true

# (2) adb shell يستطيع تشغيله، والتشغيل يكتب التقرير.
adb shell rm -rf "$REPORTS" || true
shell_start=$(adb shell am start -W -n "$CORPUS" 2>&1)
probe "== adb shell -> CorpusActivity:"; probe "$shell_start"
if ! grep -q 'Status: ok' <<<"$shell_start" || grep -qE 'Permission Denial|SecurityException|Error' <<<"$shell_start"; then
  echo "::error::adb shell could not start CorpusActivity"; probe_fail=1
fi
report_ok=0
for _ in $(seq 1 60); do
  if adb shell ls "$REPORTS/report-masked.json" >/dev/null 2>&1; then report_ok=1; break; fi
  sleep 1
done
probe "== report written after adb launch: $report_ok"
[ "$report_ok" -eq 1 ] || { echo "::error::CorpusActivity started from adb did not write its report"; probe_fail=1; }
adb shell am force-stop "$APP" || true
adb shell rm -rf "$REPORTS" || true

# (3) لا صلاحية Android مطلوبة (لا تخزين ولا وسائط ولا شبكة ولا DUMP).
requested=$(adb shell dumpsys package "$APP" | sed -n '/requested permissions:/,/install permissions:/p')
probe "== debug app requested permissions:"; probe "$requested"
if grep -q 'android\.permission\.' <<<"$requested"; then
  echo "::error::The debug app requests an Android permission"; probe_fail=1
fi
if [ "$probe_fail" -eq 0 ]; then probe "CorpusActivity access probe OK."; else probe "CorpusActivity access probe FAILED."; fi

# ---- اختبارات الجهاز
./gradlew connectedDebugAndroidTest --no-daemon --stacktrace
status=$?
{ adb shell getprop ro.build.version.release; adb shell getprop ro.build.version.sdk; } > ci-output/android-version.txt || true
adb logcat -d -v brief AndroidRuntime:E TestRunner:I GoldenCorpus:I '*:S' > ci-output/test-logcat.txt || true
echo "===== Android version ====="; cat ci-output/android-version.txt
[ "$probe_fail" -eq 0 ] || exit 1
exit $status
