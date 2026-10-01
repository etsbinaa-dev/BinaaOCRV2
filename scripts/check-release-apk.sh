#!/usr/bin/env bash
# فحوص الـAPK النهائي للإصدار (ما سيُثبَّت على الهاتف فعلًا):
# المعرّف والاسم والأيقونة، الصلاحيات الممنوعة، غياب منتقي الصور وأدوات Corpus، والتوقيع بمفتاح V2 الدائم.
set -euo pipefail
APK=app/build/outputs/apk/release/app-release.apk
BT=$(find "$ANDROID_HOME/build-tools" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -1)
AAPT2="$BT/aapt2"; APKSIGNER="$BT/apksigner"
mkdir -p ci-output
[ -f "$APK" ] || { echo "::error::Release APK not found: $APK (unsigned? missing V2 key?)"; exit 1; }
fail=0

# ---- المعرّف والإصدار والاسم والأيقونة
"$AAPT2" dump badging "$APK" > ci-output/apk-badging.txt
grep -E "^package:|^(min)?[sS]dkVersion|^targetSdkVersion|^application:|^launchable-activity" ci-output/apk-badging.txt | tee ci-output/apk-identity.txt
grep -qE "^package: name='com.binaa.ocr' " ci-output/apk-badging.txt || { echo "::error::applicationId is not com.binaa.ocr"; fail=1; }
# aapt2 يطبع minSdkVersion (و aapt القديم sdkVersion).
grep -qE "^(minSdkVersion|sdkVersion):'29'$" ci-output/apk-badging.txt || { echo "::error::minSdk is not 29"; fail=1; }
grep -qE "^targetSdkVersion:'36'" ci-output/apk-badging.txt || { echo "::error::targetSdk is not 36"; fail=1; }
# MainActivity بلا label خاص (يرث اسم التطبيق)، فـaapt2 يطبع label='' للنشاط؛ الاسم الظاهر هو label التطبيق.
grep -qE "^launchable-activity: name='com.binaa.ocr.MainActivity' " ci-output/apk-badging.txt \
  || { echo "::error::Launcher activity is not com.binaa.ocr.MainActivity"; fail=1; }
grep -qE "^application: label='Binaa OCR V2' " ci-output/apk-badging.txt \
  || { echo "::error::Application label is not 'Binaa OCR V2'"; fail=1; }
ICON=$(grep -oE "^application: label='[^']*' icon='[^']*'" ci-output/apk-badging.txt | sed -E "s/.*icon='([^']*)'.*/\1/")
"$AAPT2" dump xmltree --file "$ICON" "$APK" > ci-output/ic_launcher.xmltree.txt || true
for layer in adaptive-icon background foreground monochrome; do
  grep -q "E: $layer" ci-output/ic_launcher.xmltree.txt || { echo "::error::Launcher icon ($ICON) lacks '$layer'"; fail=1; }
done

# ---- الصلاحيات
"$AAPT2" dump permissions "$APK" | tee ci-output/apk-permissions.txt
FORBIDDEN='android.permission.(INTERNET|ACCESS_NETWORK_STATE|READ_MEDIA_IMAGES|READ_MEDIA_VIDEO|READ_MEDIA_VISUAL_USER_SELECTED|READ_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE|CAMERA|QUERY_ALL_PACKAGES)'
if grep -E "$FORBIDDEN" ci-output/apk-permissions.txt; then echo "::error::Forbidden permission in release APK"; fail=1; fi

# ---- لا منتقي صور ولا Corpus داخل الكود النهائي (بعد R8)
unzip -p "$APK" 'classes*.dex' | strings -n 5 > ci-output/dex-strings.txt
if grep -nE 'PickVisualMedia|android\.intent\.action\.(PICK|GET_CONTENT|OPEN_DOCUMENT)$|android\.provider\.action\.PICK_IMAGES' ci-output/dex-strings.txt; then
  echo "::error::Gallery/document picker found in release APK"; fail=1
fi
if grep -niE 'corpus' ci-output/dex-strings.txt; then echo "::error::Golden Corpus code found in release APK"; fail=1; fi

# ---- Golden Corpus: لا كود ولا صور ولا fixtures في الإصدار
CORPUS_CLASSES='Lcom/binaa/ocr/goldencorpus/'
if grep -n "$CORPUS_CLASSES" ci-output/dex-strings.txt; then echo "::error::Golden Corpus classes found in release APK"; fail=1; fi
unzip -Z1 "$APK" > ci-output/apk-files.txt
if grep -niE 'corpus|fixture|(^|/)expected\.json$' ci-output/apk-files.txt; then
  echo "::error::Corpus or fixture files found in release APK"; fail=1
fi
# صور نقطية خارج res/ (أيقونات المكتبات داخل res/ مسموحة): أي صورة أخرى قد تكون لقطة اختبار.
if grep -vE '^res/' ci-output/apk-files.txt | grep -iE '\.(png|jpe?g|webp|gif|bmp|heic|heif)$'; then
  echo "::error::Raster image outside res/ found in release APK"; fail=1
fi
# تحكم إيجابي: نفس الفحص يجد الـCorpus في نسخة debug، فهو ليس فحصًا أعمى.
DEBUG_APK=app/build/outputs/apk/debug/app-debug.apk
if [ ! -f "$DEBUG_APK" ]; then
  echo "::error::Debug APK not found ($DEBUG_APK): cannot prove the corpus check works"; fail=1
elif unzip -p "$DEBUG_APK" 'classes*.dex' | strings -n 5 > ci-output/debug-dex-strings.txt && ! grep -q "$CORPUS_CLASSES" ci-output/debug-dex-strings.txt; then
  echo "::error::Golden Corpus classes not found in the debug APK: the release corpus check would be blind"; fail=1
else
  echo "Golden Corpus classes found in the debug APK: the release corpus check is not blind."
fi

# ---- التوقيع بمفتاح V2 الدائم
"$APKSIGNER" verify --verbose --print-certs "$APK" | tee ci-output/apk-signature.txt
ACTUAL=$(grep -m1 'certificate SHA-256 digest' ci-output/apk-signature.txt | awk -F': ' '{print $NF}' | tr -d ' ' | tr 'A-F' 'a-f')
EXPECTED=$(grep -v '^#' signing/v2-cert-sha256.txt | tr -d ' :\n' | tr 'A-F' 'a-f')
echo "expected: $EXPECTED"; echo "actual:   $ACTUAL"
if ! [[ "$EXPECTED" =~ ^[0-9a-f]{64}$ ]]; then
  echo "::error::signing/v2-cert-sha256.txt has no V2 certificate fingerprint yet (see README)"; fail=1
elif [ "$ACTUAL" != "$EXPECTED" ]; then
  echo "::error::Release APK is NOT signed with the permanent V2 key; it would not install as an update"; fail=1
fi

[ "$fail" -eq 0 ] && echo "Release APK checks OK." || exit 1
