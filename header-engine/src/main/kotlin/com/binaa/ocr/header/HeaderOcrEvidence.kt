package com.binaa.ocr.header

/** المحرك الذي قرأ الكلمة. */
enum class OcrSource { TESSERACT, MLKIT }

/**
 * كلمة كما أعادها المحرك، في إحداثيات الصورة التي قرأها هو ([box]).
 * [confidence] = ثقة Tesseract (0..100)، و null لـML Kit (لا يُستخدم منه إلا النص والموضع، كما في المسار المثبت).
 */
class RawWord(val text: String, val confidence: Int?, val box: Box) {
    /** لا نص في السجلات أبدًا. */
    override fun toString() = "RawWord(chars=${text.length}, confidence=$confidence, box=$box)"
}

/**
 * كلمة دليل لقرار الاسم: النص والثقة والمصدر، وموضعها في صورة المحرك ([engineBox])،
 * وحدّاها الأفقيان في إحداثيات الصورة المجهّزة لـTesseract ([left], [right]) التي يقارن فيها [NameDecider] المحركين.
 */
class EvidenceWord(
    val source: OcrSource,
    val text: String,
    val confidence: Int?,
    val engineBox: Box,
    val left: Int,
    val right: Int,
) {
    override fun toString() = "EvidenceWord($source, chars=${text.length}, confidence=$confidence, engineBox=$engineBox, x=$left..$right)"
}

/** ما قرأه محرك واحد من مربع الاسم: الكلمات بترتيبها من اليسار، واسم المحرك وإصداره. */
class HeaderOcrEvidence(val source: OcrSource, val engine: String, val engineVersion: String, val words: List<EvidenceWord>) {
    override fun toString() = "HeaderOcrEvidence($source, $engine $engineVersion, words=${words.size})"
}

/** لماذا لم يصل المسار إلى قرار رغم وجود مربع الاسم. كلها ⇒ NOT_FOUND + NAME_UNREADABLE. */
enum class ReadFailure {
    /** Tesseract غير متاح (النماذج أو المكتبة): لا يُشغَّل ML Kit. */
    TESSERACT_UNAVAILABLE,

    /** استثناء أثناء قراءة Tesseract: لا يُشغَّل ML Kit. */
    TESSERACT_FAILED,

    /** استثناء أثناء قراءة ML Kit. */
    MLKIT_FAILED,
}
