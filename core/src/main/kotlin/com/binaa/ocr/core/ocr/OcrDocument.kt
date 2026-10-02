package com.binaa.ocr.core.ocr

import kotlinx.serialization.Serializable

/**
 * ما قرأه محرّك OCR من الصورة كاملة، بكل مواضعه، قبل أي تفسير.
 * لا صفوف ولا مناطق ولا حقول هنا: هذه طبقات لاحقة تقرأ هذا المستند ولا تعدّله.
 *
 * ترتيب القراءة = ترتيب المحرّك (الكتل ثم الأسطر ثم الكلمات كما أعادها)، وليس ترتيبًا هندسيًا.
 * يُعرَّف كل عنصر بمعرّف ثابت مبني على هذا الترتيب: `b0`، `b0.l1`، `b0.l1.w2`.
 *
 * يحمل نص الإيصال كاملًا: لا يُسجَّل ولا يُكتب إلى ملف في نسخة الإصدار.
 */
@Serializable
data class OcrDocument(
    val schemaVersion: Int = SCHEMA_VERSION,
    val image: ImageSize,
    val engine: OcrEngineInfo,
    /** مدة القراءة بالمللي ثانية إن قيست. */
    val durationMs: Long? = null,
    val blocks: List<OcrBlock>,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "unsupported OcrDocument schema $schemaVersion" }
        blocks.forEachIndexed { b, block ->
            requireElement(block.id, OcrIds.block(b), block.box, block.norm)
            block.lines.forEachIndexed { l, line ->
                requireElement(line.id, OcrIds.line(b, l), line.box, line.norm)
                requireConfidence(line.id, line.confidence)
                line.words.forEachIndexed { w, word ->
                    requireElement(word.id, OcrIds.word(b, l, w), word.box, word.norm)
                    requireConfidence(word.id, word.confidence)
                }
            }
        }
    }

    /** كل الأسطر بترتيب القراءة. */
    val lines: List<OcrLine> get() = blocks.flatMap { it.lines }

    /** كل الكلمات بترتيب القراءة. */
    val words: List<OcrWord> get() = blocks.flatMap { b -> b.lines.flatMap { it.words } }

    /** عنصر (كتلة أو سطر أو كلمة) بمعرّفه، أو null. */
    fun element(id: String): OcrElement? = elementsById[id]

    private val elementsById: Map<String, OcrElement> by lazy {
        buildMap {
            for (block in blocks) {
                put(block.id, block)
                for (line in block.lines) {
                    put(line.id, line)
                    for (word in line.words) put(word.id, word)
                }
            }
        }
    }

    private fun requireElement(id: String, expectedId: String, box: PixelBox?, norm: NormBox?) {
        require(id == expectedId) { "element id $id does not match its reading position $expectedId" }
        require((box == null) == (norm == null)) { "$id: pixel and normalized boxes must both be present or absent" }
        if (box != null) require(norm == box.normalizedIn(image)) { "$id: normalized box does not match pixel box" }
    }

    private fun requireConfidence(id: String, confidence: Float?) {
        require(confidence == null || confidence in 0f..1f) { "$id: confidence $confidence out of 0..1" }
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** المحرّك الذي أنتج المستند وإصداره، ليُتتبَّع أي اختلاف بين الإصدارات. */
@Serializable
data class OcrEngineInfo(val name: String, val version: String)

/** ما تشترك فيه الكتلة والسطر والكلمة. */
sealed interface OcrElement {
    val id: String
    val text: String
    /** null إذا لم يُعِد المحرّك مربعًا. */
    val box: PixelBox?
    val norm: NormBox?
}

@Serializable
data class OcrBlock(
    override val id: String,
    override val text: String,
    override val box: PixelBox?,
    override val norm: NormBox?,
    val lines: List<OcrLine>,
) : OcrElement

@Serializable
data class OcrLine(
    override val id: String,
    override val text: String,
    override val box: PixelBox?,
    override val norm: NormBox?,
    /** 0..1 إن أعطاها المحرّك. */
    val confidence: Float? = null,
    /** زاوية الميل بالدرجات إن أعطاها المحرّك. */
    val angle: Float? = null,
    /** رمز اللغة كما أعاده المحرّك (مثل "fr")، أو null. */
    val language: String? = null,
    val words: List<OcrWord>,
) : OcrElement

@Serializable
data class OcrWord(
    override val id: String,
    override val text: String,
    override val box: PixelBox?,
    override val norm: NormBox?,
    val confidence: Float? = null,
    val angle: Float? = null,
    val language: String? = null,
) : OcrElement

object OcrIds {
    fun block(b: Int) = "b$b"
    fun line(b: Int, l: Int) = "b$b.l$l"
    fun word(b: Int, l: Int, w: Int) = "b$b.l$l.w$w"
}
