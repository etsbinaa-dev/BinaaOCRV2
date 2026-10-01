#!/usr/bin/env bash
# يُشغَّل داخل المحاكي (android-emulator-runner) على نسخة debug (com.binaa.ocr.debug).
set -u
mkdir -p ci-output
adb logcat -c || true

# ---- إثبات حماية CorpusActivity (android.permission.DUMP) قبل الاختبارات، على هذا الـAPI نفسه:
# 1) تطبيق عادي لا يستطيع تشغيله، 2) adb shell يستطيع، 3) نسخة debug لا تطلب أي صلاحية Android.
APP=com.binaa.ocr.debug
TEST_APP=$APP.test
CORPUS=$APP/com.binaa.ocr.goldencorpus.CorpusActivity
REPORTS=/sdcard/Android/data/$APP/files/corpus-reports
probe_fail=0
probe() { echo "$*" | tee -a ci-output/corpus-access-probe.txt; }

./gradlew installDebug installDebugAndroidTest --no-daemon --stacktrace || probe_fail=1

# (1) هوية تطبيق عادي: run-as يعطي uid حزمة الاختبار (تطبيق debuggable عادي، بلا أي صلاحية خاصة).
ordinary=$(adb shell run-as "$TEST_APP" am start -W -n "$CORPUS" 2>&1)
probe "== ordinary app -> CorpusActivity:"; probe "$ordinary"
# ضابط: نفس الطريقة على نشاط مُصدَّر عادي لا تُرفض بسبب صلاحية، فالرفض أعلاه سببه DUMP لا الطريقة.
control=$(adb shell run-as "$TEST_APP" am start -W -n "$APP/com.binaa.ocr.MainActivity" 2>&1)
probe "== ordinary app -> MainActivity (control):"; probe "$control"
adb shell am force-stop "$APP" || true
if ! grep -qE 'Permission Denial|SecurityException' <<<"$ordinary" || grep -q 'Status: ok' <<<"$ordinary"; then
  echo "::error::An ordinary app was NOT refused when starting CorpusActivity"; probe_fail=1
fi
if grep -qE 'Permission Denial|SecurityException|run-as: ' <<<"$control"; then
  echo "::error::Probe method invalid: the ordinary-app identity could not start even an unprotected activity"; probe_fail=1
fi

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
