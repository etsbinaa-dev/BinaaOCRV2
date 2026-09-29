#!/usr/bin/env bash
# نظافة المستودع قبل أي بناء:
# - لا صور نقطية متتبَّعة في Git (الصور الحقيقية وGolden Corpus لا تدخل GitHub أبدًا؛ صور الاختبار تُرسم أثناء الاختبار).
# - لا مفاتيح توقيع متتبَّعة.
# - لا منتقي صور ولا أدوات Corpus في كود الإنتاج (أدوات debug مكانها app/src/debug فقط).
set -euo pipefail
fail=0

images=$(git ls-files | grep -Ei '\.(png|jpe?g|webp|heic|heif|gif|bmp|tiff?)$' || true)
if [ -n "$images" ]; then
  echo "::error::Raster images are tracked in Git (real screenshots must never be committed):"; echo "$images"; fail=1
fi

keys=$(git ls-files | grep -Ei '\.(jks|keystore|p12)$' || true)
if [ -n "$keys" ]; then
  echo "::error::Signing keys are tracked in Git:"; echo "$keys"; fail=1
fi

PICKER='PickVisualMedia|PickMultipleVisualMedia|ACTION_PICK|ACTION_GET_CONTENT|ACTION_OPEN_DOCUMENT|GetContent\(|OpenDocument\(|PICK_IMAGES'
picker=$(grep -rnE "$PICKER" app/src/main core/src/main --include='*.kt' --include='*.java' || true)
if [ -n "$picker" ]; then
  echo "::error::Gallery/document picker code found in production sources (the only input is a WhatsApp share):"; echo "$picker"; fail=1
fi

corpus=$(grep -rniE 'corpus' app/src/main core/src/main --include='*.kt' --include='*.java' --include='*.xml' || true)
if [ -n "$corpus" ]; then
  echo "::error::Golden Corpus code found in production sources (debug-only: app/src/debug):"; echo "$corpus"; fail=1
fi

[ "$fail" -eq 0 ] && echo "Repository hygiene OK." || exit 1
