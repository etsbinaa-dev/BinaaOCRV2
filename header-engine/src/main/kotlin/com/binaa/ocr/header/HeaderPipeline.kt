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
 */
object HeaderPipeline {

    private const val BORDER = NamePrep.BORDER
    private const val TARGET_HEIGHT = NamePrep.TARGET_HEIGHT
    private const val WHITE = 0xFFFFFFFF.toInt()

    fun run(image: PixelImage, tesseract: TesseractWordReader, tesseractInfo: EngineInfo, mlKit: MlKitWordReader, mlKitInfo: EngineInfo): HeaderReading {
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

        val decision = NameDecider.decide(
            tess.words.map { TessWord(it.text, it.confidence ?: 0, it.left, it.right) },
            gray,
            ml.words.map { MlWord(it.text, it.left, it.right) },
        )
        return HeaderReading(HeaderResult.of(detection, decision), listOf(tess, ml), null)
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
