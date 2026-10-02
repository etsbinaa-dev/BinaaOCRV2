package com.binaa.ocr.header

/** Tesseract على الصورة المجهّزة. null = غير متاح. الاستثناء = فشل القراءة. */
fun interface TesseractWordReader {
    fun read(image: GrayImage): List<RawWord>?
}

/** ML Kit على صورة مربع الاسم النظيف بدقته الأصلية مع إطار أبيض. الاستثناء = فشل القراءة. */
fun interface MlKitWordReader {
    fun read(image: PixelImage): List<RawWord>
}

/** اسم المحرك وإصداره كما يُسجَّلان في الأدلة. */
class EngineInfo(val name: String, val version: String)

/** نتيجة المسار: [result] للواجهة لاحقًا، و[evidence] و[failure] لتفسيرها (لا نص في toString). */
class HeaderReading(val result: HeaderResult, val evidence: List<HeaderOcrEvidence>, val failure: ReadFailure?) {
    override fun toString() =
        "HeaderReading(${result.status}, reason=${result.reason}, rejection=${result.detectorRejection}, " +
            "decision=${result.decision?.code}, failure=$failure, evidence=$evidence)"
}

/**
 * مسار اسم رأس WhatsApp Image Viewer، بنفس ترتيب المسار المثبت في Binaa Payment OCR:
 * الكاشف ← مربع الاسم النظيف ← [NamePrep] ← Tesseract ← ML Kit على مربع الاسم النظيف نفسه ← [NameDecider] ← [HeaderResult].
 *
 * - لا رأس (أو رأس ملتبس) ⇒ لا OCR إطلاقًا.
 * - المحركان لا يريان إلا مربع الاسم؛ نص الإيصال لا يُقرأ هنا أبدًا.
 * - فشل Tesseract (غير متاح أو استثناء) ⇒ NAME_UNREADABLE ولا يُشغَّل ML Kit. فشل ML Kit ⇒ NAME_UNREADABLE.
 *   لا استثناء يخرج من [run].
 * - قراءة ثانية للأسماء اللاتينية فقط ([latinFirst]، Tesseract بـeng+ara): إذا لم تعطِ القراءة الأولى اسمًا وفيها حروف لاتينية
 *   (ara+eng قد يقرأ كلمة لاتينية كأرقام). تُقبل فقط إذا كان الاسم كله لاتينيًا وأكّد ML Kit كل كلماته؛ وإلا تبقى النتيجة الأولى.
 *   وفيها وحدها يُحذف وسم جهة الاتصال (Clt وأشكاله المقروءة) من نهاية السطر قبل القرار.
 */
object HeaderPipeline {

    private const val BORDER = NamePrep.BORDER
    private const val TARGET_HEIGHT = NamePrep.TARGET_HEIGHT
    private const val WHITE = 0xFFFFFFFF.toInt()

    fun run(
        image: PixelImage,
        tesseract: TesseractWordReader,
        tesseractInfo: EngineInfo,
        mlKit: MlKitWordReader,
        mlKitInfo: EngineInfo,
        latinFirst: TesseractWordReader? = null,
        latinFirstInfo: EngineInfo = tesseractInfo,
    ): HeaderReading {
        val detection = try {
            HeaderDetector.detect(image)
        } catch (e: Exception) {
            return HeaderReading(HeaderResult(NameStatus.NOT_FOUND, null, NameReason.HEADER_NOT_DETECTED, null, null, null), emptyList(), null)
        }
        val name = detection.nameImage ?: return HeaderReading(HeaderResult.of(detection, null), emptyList(), null)
        fun unreadable(failure: ReadFailure, evidence: List<HeaderOcrEvidence>) = HeaderReading(HeaderResult.of(detection, null), evidence, failure)

        val gray = NamePrep.prepare(name)
        val tessWords = try {
            tesseract.read(gray) ?: return unreadable(ReadFailure.TESSERACT_UNAVAILABLE, emptyList())
        } catch (e: Exception) {
            return unreadable(ReadFailure.TESSERACT_FAILED, emptyList())
        }
        val tess = tesseractEvidence(tessWords, tesseractInfo)

        val mlWords = try {
            mlKit.read(mlKitInput(name))
        } catch (e: Exception) {
            return unreadable(ReadFailure.MLKIT_FAILED, listOf(tess))
        }
        val ml = mlKitEvidence(mlWords, name.box.h, mlKitInfo)

        val decision = decide(tess.words, gray, ml.words)
        if (decision.name != null || latinFirst == null || tess.words.none { w -> w.text.any { isLatinLetter(it) } }) {
            return HeaderReading(HeaderResult.of(detection, decision), listOf(tess, ml), null)
        }

        // القراءة الثانية (لاتيني أولًا). أي فشل فيها ⇒ تبقى نتيجة القراءة الأولى.
        val second = try {
            latinFirst.read(gray)?.let { tesseractEvidence(it, latinFirstInfo) }
        } catch (e: Exception) {
            null
        } ?: return HeaderReading(HeaderResult.of(detection, decision), listOf(tess, ml), null)
        val (words, image2, mlLatin) = withoutTrailingContactTag(second.words, gray, ml.words)
        val latin = decide(words, image2, mlLatin)
        val accepted = latin.name != null && latin.code == DecisionCode.NAME_ACCEPTED &&
            DecisionNote.LATIN_CONFIRMED_BY_MLKIT in latin.notes && latin.name.none { NameDecider.isAr(it) }
        return HeaderReading(HeaderResult.of(detection, if (accepted) latin else decision), listOf(tess, ml, second), null)
    }

    private fun decide(tess: List<EvidenceWord>, gray: GrayImage, ml: List<EvidenceWord>) = NameDecider.decide(
        tess.map { TessWord(it.text, it.confidence ?: 0, it.left, it.right) },
        gray,
        ml.map { MlWord(it.text, it.left, it.right) },
    )

    private fun isLatinLetter(c: Char) = c.isLetter() && c.code < 0x250

    /** Clt وما يُقرأ منه: Ct، Cit، Clit، Clte، C1t… (كلمة واحدة). */
    private val CONTACT_TAG = Regex("^[Cc][lLiI1|]{0,2}[tT][eE]?$")

    /**
     * يحذف وسم جهة الاتصال إن كان آخر كلمة في السطر (وبقيت كلمة قبله): كلمته، وحبره من الصورة (أبيض)، وكلمات ML Kit فوقه.
     * حذف فقط: لا يضيف حرفًا ولا كلمة.
     */
    private fun withoutTrailingContactTag(tess: List<EvidenceWord>, gray: GrayImage, ml: List<EvidenceWord>): Triple<List<EvidenceWord>, GrayImage, List<EvidenceWord>> {
        val last = tess.lastOrNull()
        if (tess.size < 2 || last == null || !CONTACT_TAG.matches(last.text)) return Triple(tess, gray, ml)
        val cut = (last.left - 3).coerceAtLeast(0)
        val prev = tess[tess.size - 2]
        if (cut <= prev.right) return Triple(tess, gray, ml)
        val pixels = gray.pixels.copyOf()
        for (y in 0 until gray.height) for (x in cut until gray.width) pixels[y * gray.width + x] = 255
        return Triple(tess.dropLast(1), GrayImage(gray.width, gray.height, pixels), ml.filter { (it.left + it.right) / 2 < cut })
    }

    /** كلمات Tesseract: بلا علامات الاتجاه، بلا الفارغة، مرتّبة من اليسار. إحداثياتها هي إحداثيات الصورة المجهّزة. */
    fun tesseractEvidence(words: List<RawWord>, info: EngineInfo) = HeaderOcrEvidence(
        OcrSource.TESSERACT, info.name, info.version,
        words.map { EvidenceWord(OcrSource.TESSERACT, NameDecider.stripMarks(it.text), it.confidence, it.box, it.box.l, it.box.r) }
            .filter { it.text.isNotEmpty() }
            .sortedBy { it.left },
    )

    /**
     * كلمات ML Kit من صورة [mlKitInput] (ارتفاع مربع الاسم [nameHeight] + إطار) إلى إحداثيات الصورة المجهّزة:
     * x' = ((x − BORDER) · TARGET_HEIGHT / nameHeight + BORDER).toInt()، مرتّبة من اليسار.
     */
    fun mlKitEvidence(words: List<RawWord>, nameHeight: Int, info: EngineInfo): HeaderOcrEvidence {
        val scale = TARGET_HEIGHT.toDouble() / nameHeight
        fun map(x: Int) = ((x - BORDER) * scale + BORDER).toInt()
        return HeaderOcrEvidence(
            OcrSource.MLKIT, info.name, info.version,
            words.map { EvidenceWord(OcrSource.MLKIT, it.text, it.confidence, it.box, map(it.box.l), map(it.box.r)) }.sortedBy { it.left },
        )
    }

    /** مربع الاسم النظيف (أسود على أبيض) بدقته الأصلية، داخل إطار أبيض بعرض BORDER: ما يقرؤه ML Kit. */
    fun mlKitInput(name: NameImage): PixelImage {
        val w = name.width + 2 * BORDER
        val h = name.height + 2 * BORDER
        val out = IntArray(w * h) { WHITE }
        for (y in 0 until name.height) System.arraycopy(name.argb, y * name.width, out, (y + BORDER) * w + BORDER, name.width)
        return PixelImage(w, h, out)
    }
}
