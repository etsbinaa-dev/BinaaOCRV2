# Binaa OCR V2

تطبيق Android مستقل تمامًا عن **Binaa Payment OCR** القديم (لا أرشيف مشترك، لا migration، لا مفتاح مشترك).

## القاعدة الأساسية

المدخل الإنتاجي الوحيد:

WhatsApp ← فتح صورة الإيصال في Image Viewer ← لقطة شاشة كاملة ← مشاركة ← **Binaa OCR V2** ← التحليل يبدأ تلقائيًا.

- لا منتقي صور، ولا زر «قراءة إيصال»، ولا «إضافة عملية»، ولا عملية بلا صورة.
- الصورة تُحفظ كما وصلت (بايتًا ببايت)، بلا قص.
- مشاركة أكثر من صورة: «شارك صورة واحدة فقط في كل مرة» ولا تُعالج أي صورة.
- OCR يقترح، والمستخدم يقرر. «لا أعرف» أفضل من قيمة خاطئة.

## الهيكل

| الوحدة | المحتوى |
|---|---|
| `core/` | منطق Kotlin خالص، يُختبر على JVM بلا Android (سياسة الاستلام، صيغ الصور، الاحتفاظ). |
| `app/` | تطبيق Android (Compose، عربي RTL أولًا). |
| `scripts/` | فحوص CI. |
| `signing/v2-cert-sha256.txt` | بصمة شهادة مفتاح V2 (عامة، ليست سرًّا). |

## المعرّفات والإصدارات

- `applicationId` = `com.binaa.ocr` — **نهائي**. كل تحديثات V2.x بهذا المعرّف ومفتاح V2 نفسه، فتُثبَّت فوق بعضها.
- نسخة debug: `com.binaa.ocr.debug` (تُثبَّت بجانب الإصدار، بمفتاح debug).
- `versionCode` يجب أن **يزيد** في كل نسخة تُوزَّع.
- minSdk 29، targetSdk/compileSdk 36.

## الخصوصية

- لا `INTERNET`، ولا `READ_MEDIA_IMAGES`، ولا `READ_EXTERNAL_STORAGE`. CI يفحص الـAPK النهائي.
- `FLAG_SECURE` على كل الشاشات.
- النسخ السحابي ممنوع؛ النقل المباشر بين هاتفين مسموح (Android 12+) عدا صور الاستلام المؤقتة.
- الصور الحقيقية وGolden Corpus لا تدخل Git أبدًا (CI يرفض أي صورة نقطية متتبَّعة). أدوات Corpus لنسخة debug فقط.

## مفتاح التوقيع V2 (مرة واحدة فقط)

ضياع المفتاح = استحالة أي تحديث للتطبيق إلى الأبد. يُولَّد على جهازك، ولا يدخل Git ولا المحادثات.

1. التوليد (Java keytool):
   ```
   keytool -genkeypair -v -storetype PKCS12 -keystore binaa-ocr-v2.jks -alias binaa-ocr-v2 \
     -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Binaa OCR V2, O=Binaa, C=MR"
   ```
   في PKCS12 كلمة سر المفتاح = كلمة سر المخزن.
2. النسخ الاحتياطي: الملف + كلمة السر في مدير كلمات مرور، ونسخة غير متصلة (USB).
3. GitHub → Settings → Secrets and variables → Actions:
   - `BINAA_V2_KEYSTORE_BASE64` = ناتج `base64 -w0 binaa-ocr-v2.jks`
   - `BINAA_V2_KEYSTORE_PASSWORD` = كلمة السر (تُستخدم أيضًا كلمةً لسر المفتاح، كما في PKCS12)

   الاسم المستعار `binaa-ocr-v2` مكتوب مباشرة في الـworkflow (ليس سرًّا).
4. البصمة العامة: ناتج
   `keytool -list -v -keystore binaa-ocr-v2.jks -alias binaa-ocr-v2 | grep SHA256`
   يوضع في `signing/v2-cert-sha256.txt`. CI يرفض أي APK إصدار لا يطابقها.

## CI

- **Build**: نظافة المستودع، اختبارات `core` وunit، lint، APK debug وrelease (R8)، ثم فحوص APK الإصدار، ثم رفعه كـartifact `binaa-ocr-v2-release-apk`.
- **Instrumented**: اختبارات الجهاز على محاكي API 35 و36 (نسخة debug).

## المراحل

Phase 1 (الحالية): الأساس + استلام المشاركة + CI + توقيع V2. لا OCR ولا Parsers ولا أرشيف بعد.
