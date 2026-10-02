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
| `golden-corpus/` | أدوات Golden Corpus (Kotlin خالص): قراءة الحالات، التشغيل، التقرير والتقرير المقنّع. في نسخة debug والاختبارات فقط، ولا تدخل الإصدار. |
| `header-engine/` | اسم رأس WhatsApp Image Viewer (Kotlin خالص): الكاشف الهندسي، تجهيز صورة الاسم، قرار الاسم، والمسار كاملًا بمحرّكين مجرّدين. |
| `tessdata/tessdata.lock` | نماذج Tesseract المثبّتة (commit + SHA-256). الملفات نفسها لا تدخل Git. |
| `scripts/` | فحوص CI. |
| `signing/v2-cert-sha256.txt` | بصمة شهادة مفتاح V2 (عامة، ليست سرًّا). |

## المعرّفات والإصدارات

- `applicationId` = `com.binaa.ocr` — **نهائي**. كل تحديثات V2.x بهذا المعرّف ومفتاح V2 نفسه، فتُثبَّت فوق بعضها.
- نسخة debug: `com.binaa.ocr.debug` (تُثبَّت بجانب الإصدار، بمفتاح debug).
- `versionCode` يجب أن **يزيد** في كل نسخة تُوزَّع.
- minSdk 29، targetSdk/compileSdk 36.

## الخصوصية

- لا `INTERNET`، ولا `READ_MEDIA_IMAGES`، ولا `READ_EXTERNAL_STORAGE`. CI يفحص الـAPK النهائي.
- لقطة شاشة التطبيق مسموحة (قرار المالك، بدل `FLAG_SECURE` في Phase 0): ما يظهر على الشاشة قد يُحفظ في معرض الصور.
- النسخ السحابي ممنوع؛ النقل المباشر بين هاتفين مسموح (Android 12+) عدا صور الاستلام المؤقتة.
- الصور الحقيقية وGolden Corpus لا تدخل Git أبدًا (CI يرفض أي صورة نقطية متتبَّعة). أدوات Corpus لنسخة debug فقط.

## Header Engine (اسم الطرف الآخر من رأس WhatsApp)

لقطة الشاشة كاملة ← `HeaderDetector` ← مربع الاسم النظيف ← `NamePrep` (ارتفاع 40 + إطار 20) ← Tesseract ← ML Kit على مربع
الاسم النظيف نفسه (دقته الأصلية + إطار أبيض 20، والإحداثيات `x' = ((x − 20) · 40 / h + 20)`) ← `NameDecider` ← `HeaderResult`.

- لا رأس، أو أكثر من رأس حقيقي (`MULTIPLE_HEADER_CANDIDATES`) ⇒ `HEADER_NOT_DETECTED` بلا أي OCR. نص الإيصال لا يُقرأ هنا أبدًا.
- نوعان من الرأس، ومنهما الاتجاه: عارض الصور (← الاسم ☆ ↪ ⋮) = **وارد**؛ المحادثة (← صورة شخصية الاسم 📹 📞 ⋮) = **صادر**،
  والاسم اسم المستلم ويبدأ بعد الصورة الشخصية. «Vous/You/أنت» ليست اسمًا أبدًا (`SENT_BY_YOU`، صادر).
- فشل Tesseract (غير متاح أو استثناء) ⇒ `NAME_UNREADABLE` ولا يُشغَّل ML Kit. فشل ML Kit ⇒ `NAME_UNREADABLE`. لا استثناء يخرج.
- Tesseract (tesseract4android 4.9.0): `OEM_LSTM_ONLY`، `PSM_SINGLE_LINE`، `ara+eng`، كلمات `RIL_WORD`. كائن واحد خلف قفل.
- قراءة ثانية للأسماء اللاتينية فقط (`eng+ara`): إذا لم تعطِ القراءة الأولى اسمًا وفيها حروف لاتينية (`ara+eng` قد يقرأ كلمة
  لاتينية كأرقام). تُقبل فقط إذا كان الاسم كله لاتينيًا وأكّد ML Kit كل كلماته، وفيها يُحذف وسم Clt (وأشكاله المقروءة) من آخر السطر.
- النماذج: `ara` و`eng` كلاهما من `tessdata_best` (`eng` الدقيق بدل السريع: السريع قرأ h كـn في اسم لاتيني)، بالـcommit والـSHA-256 في
  `tessdata/tessdata.lock`. تُنزَّل وقت البناء (`fetchTessdata`) ويُتحقق منها قبل التغليف، ثم في APK الإصدار، ثم على الهاتف
  قبل كل تهيئة (تُنسخ إلى `noBackupFilesDir`). لا تنزيل على الهاتف ولا صلاحية شبكة.
- AAR الـTesseract من JitPack (غير منشور على Maven Central)، لمجموعته فقط (`exclusiveContent`)، ومثبّت بـSHA-256 في
  `gradle/tesseract4android.sha256` (`verifyTesseractAar` قبل كل بناء).
- لا سجلات للصورة ولا للنص؛ `toString()` لكل الأدلة والنتائج لا يُظهر النص.
- موصول بالاستلام (Step 5c)، ثم يقرأ `ReceiptReader` (`:core`) المبلغ والتاريخ لبنكيلي وBIMBANK والسداد من نص ML Kit تحت رأس WhatsApp: بعد حفظ اللقطة بايتًا ببايت يقرأ `IntakeViewModel` الاسم بـ`HeaderEngine` (فك ترميز في الذاكرة
  فقط) ويعرض النتيجة: الاسم (FOUND) أو السبب (NOT_FOUND). أي فشل ⇒ NOT_FOUND بلا استثناء. لا حفظ ولا أرشيف بعد.

## المراجعة والحفظ والأرشيف

بعد القراءة: مراجعة (الاسم، المبلغ، التاريخ والوقت، التطبيق، الاتجاه)، كلها قابلة للتعديل؛ «غير مؤكد» يعرض مرشّحيه للاختيار
ولا يملأ الحقل. «حفظ» لا يعمل قبل اكتمال الاسم والمبلغ والتاريخ والاتجاه. الدفعة تذهب إلى **«للإدخال»** في الشاشة
الرئيسية، وصورة إيصالها تُنقل معها (`filesDir/payments/`، ملف JSON لكل دفعة، كتابة ذرّية). بعد إدخالها يدويًا في المحاسبة:
**«تم»** ⇒ **«الأرشيف»** (و«إرجاع» للخطأ). لا نسخ سحابي؛ النقل المباشر بين الهواتف يشمل الأرشيف.

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

## Golden Corpus

مجلد لكل حالة، على جهاز المطوّر فقط (لا يدخل Git: الصور ومجلدات `corpus/` مستبعدة، وCI يرفض أي صورة متتبَّعة):

```
case-001/
  expected.json
  image.png
```

`expected.json` (صيغة صارمة؛ حقل غير معروف أو قيمة بغير صيغتها ترفض الحالة):

```json
{
  "schemaVersion": 1,
  "caseId": "case-001",
  "image": "image.png",
  "source": "LOCAL_REAL",
  "fields": {
    "amount": {"status": "UNCERTAIN", "candidates": ["18300.00", "18329.30"]},
    "counterparty": {"status": "NOT_FOUND"},
    "date": {"status": "FOUND", "value": "2026-09-19"}
  },
  "ocrContains": ["MRU"],
  "notes": "اختياري"
}
```

- الحالات: `FOUND` بقيمة واحدة، `UNCERTAIN` بلا قيمة مع مرشّحين اختياريين، `NOT_FOUND` بلا قيمة.
- الحقول: `counterparty`، `amount` (`18300.00`)، `date` (`YYYY-MM-DD`)، `time` (`HH:MM`)، `provider` (`MASRVI`/`BANKILY`/`SEDAD`/`BCIPAY`/`BIMBANK`/`OTHER`)، `reference`. الحقل غير المذكور لا يُقيَّم.
- `caseId` بلا أسماء أو أرقام حقيقية (يبقى ظاهرًا في التقرير المقنّع).
- التقرير يعدّ `wrongFound` (قيمة FOUND خاطئة، أو FOUND حيث الحقيقة UNCERTAIN/NOT_FOUND): يجب أن يبقى 0.
- التقرير المقنّع يحتفظ بالمربعات والثقة والبنية وكلمات التسميات، ويستبدل الأرقام بـ9 والحروف اللاتينية بـa والعربية بـب. يُراجَع يدويًا قبل مشاركته.

### تشغيل Golden Corpus على الهاتف (نسخة debug فقط)

1. ثبّت نسخة debug من artifact `binaa-ocr-v2-debug-apk` في CI (`com.binaa.ocr.debug`، تُثبَّت بجانب نسخة الإصدار).
2. ضع الحالات في مجلد على حاسوبك (مجلد لكل حالة كما أعلاه)، ثم:
   ```
   scripts/corpus-device.sh push ~/binaa-corpus
   scripts/corpus-device.sh run
   scripts/corpus-device.sh pull-masked     # corpus-reports-local/report-masked.json: راجعه قبل مشاركته
   ```
   `pull-full` ينسخ التقرير الكامل (فيه نصوص الإيصالات): يبقى على حاسوبك. `clear` يحذف كل شيء من الهاتف.
- مكان الحالات على الهاتف: `/sdcard/Android/data/com.binaa.ocr.debug/files/corpus/` (مجلد نسخة debug الخاص:
  بلا أي صلاحية، لا يقرؤه تطبيق آخر، خارج النسخ السحابي والنقل بين الهواتف، ويُحذف مع حذف نسخة debug).
- `CorpusActivity` محمي بـ `android.permission.DUMP`: يشغّله adb shell فقط، لا أي تطبيق على الهاتف (يُثبَت في CI على API 35 و36).
- قبل 5b يضيف المشغّل قسم `headerGeometry` إلى التقريرين: لكل حالة نتيجة HeaderDetector كأرقام ورموز فقط
  (حدود شريط الرأس والسهم ومجموعة الأيقونات ومربع الاسم والـsubtitle، الاتجاه، ارتفاع الأيقونة، المسافة إلى الأيقونات،
  ورمز الرفض). لا نص ولا صورة اسم.
- **مهم:** CI يوقّع نسخة debug بمفتاح debug يتغير مع كل تشغيل. تثبيت نسخة debug أحدث قد يتطلب أولًا
  `adb uninstall com.binaa.ocr.debug`، وهذا **يحذف الحالات والتقارير الموجودة على الهاتف**. الحالات الأصلية تبقى
  على حاسوبك: أعد `push` بعد التثبيت. نسخة الإصدار `com.binaa.ocr` لا تتأثر.

## CI

- **Build**: نظافة المستودع، اختبارات `core` وunit، lint، APK debug وrelease (R8)، ثم فحوص APK الإصدار (منها SHA-256 نموذجي Tesseract)، ثم رفعه كـartifact `binaa-ocr-v2-release-apk`.
- **Instrumented**: اختبارات الجهاز على محاكي API 35 و36 (نسخة debug).

## المراحل

Phase 1 (الحالية): الأساس + استلام المشاركة + CI + توقيع V2. لا OCR ولا Parsers ولا أرشيف بعد.
