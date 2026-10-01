#!/usr/bin/env bash
# Golden Corpus على الهاتف (نسخة debug com.binaa.ocr.debug فقط)، من حاسوبك عبر adb.
#   scripts/corpus-device.sh push <cases-dir>    ينسخ كل مجلد حالة (expected.json + الصورة) إلى الهاتف
#   scripts/corpus-device.sh run                 يشغّل الحالات وينتظر التقرير ويطبع الأعداد
#   scripts/corpus-device.sh pull-masked [dir]   ينسخ التقرير المقنّع (للمراجعة ثم المشاركة)
#   scripts/corpus-device.sh pull-full [dir]     ينسخ التقرير الكامل (فيه نصوص الإيصالات: يبقى عندك)
#   scripts/corpus-device.sh clear               يحذف الحالات والتقارير من الهاتف
# الحالات الأصلية تبقى على حاسوبك. حذف نسخة debug من الهاتف يحذف ما نُسخ إليه.
set -euo pipefail
APP=com.binaa.ocr.debug
BASE=/sdcard/Android/data/$APP/files
CASES=$BASE/corpus
REPORTS=$BASE/corpus-reports
OUT_DEFAULT=corpus-reports-local

case "${1:-}" in
  push)
    src=${2:?usage: push <cases-dir>}
    adb shell mkdir -p "$CASES"
    for dir in "$src"/*/; do
      [ -f "$dir/expected.json" ] || { echo "skip (no expected.json): $dir"; continue; }
      adb push "${dir%/}" "$CASES/" >/dev/null && echo "pushed $(basename "$dir")"
    done
    ;;
  run)
    adb shell rm -f "$REPORTS/report.json" "$REPORTS/report-masked.json"
    adb logcat -c
    adb shell am start -W -n "$APP/com.binaa.ocr.goldencorpus.CorpusActivity" >/dev/null
    for _ in $(seq 1 600); do
      if adb shell ls "$REPORTS/report-masked.json" >/dev/null 2>&1; then
        adb logcat -d -s GoldenCorpus:I | grep GOLDEN_CORPUS_DONE | tail -1
        exit 0
      fi
      if adb logcat -d -s GoldenCorpus:E | grep -q GOLDEN_CORPUS_FAILED; then
        adb logcat -d -s GoldenCorpus:E | grep GOLDEN_CORPUS_FAILED | tail -1; exit 1
      fi
      sleep 1
    done
    echo "timed out waiting for the report"; exit 1
    ;;
  pull-masked)
    out=${2:-$OUT_DEFAULT}; mkdir -p "$out"
    adb pull "$REPORTS/report-masked.json" "$out/" >/dev/null && echo "$out/report-masked.json (review it before sharing)"
    ;;
  pull-full)
    out=${2:-$OUT_DEFAULT}; mkdir -p "$out"
    adb pull "$REPORTS/report.json" "$out/" >/dev/null && echo "$out/report.json (contains receipt text: keep it on this computer)"
    ;;
  clear)
    adb shell rm -rf "$CASES" "$REPORTS" && echo "cleared"
    ;;
  *)
    sed -n '2,8p' "$0"; exit 2
    ;;
esac
