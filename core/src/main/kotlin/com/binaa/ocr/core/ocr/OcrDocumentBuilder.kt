package com.binaa.ocr.core.ocr

/**
 * يبني [OcrDocument] بترتيب القراءة كما يعطيه المحرّك، ويحسب المعرّفات والمربعات النسبية،
 * حتى لا يكتبها أي محوّل (ML Kit أو fixture) بيده.
 */
class OcrDocumentBuilder(private val image: ImageSize, private val engine: OcrEngineInfo) {

    private val blocks = mutableListOf<OcrBlock>()

    fun block(text: String, box: PixelBox?, lines: BlockScope.() -> Unit): OcrDocumentBuilder {
        val b = blocks.size
        val scope = BlockScope(b).apply(lines)
        blocks += OcrBlock(OcrIds.block(b), text, box, box?.normalizedIn(image), scope.lines)
        return this
    }

    fun build(durationMs: Long? = null): OcrDocument =
        OcrDocument(image = image, engine = engine, durationMs = durationMs, blocks = blocks.toList())

    inner class BlockScope internal constructor(private val b: Int) {
        internal val lines = mutableListOf<OcrLine>()

        fun line(
            text: String,
            box: PixelBox?,
            confidence: Float? = null,
            angle: Float? = null,
            language: String? = null,
            words: LineScope.() -> Unit,
        ) {
            val l = lines.size
            val scope = LineScope(b, l).apply(words)
            lines += OcrLine(OcrIds.line(b, l), text, box, box?.normalizedIn(image), confidence, angle, language, scope.words)
        }
    }

    inner class LineScope internal constructor(private val b: Int, private val l: Int) {
        internal val words = mutableListOf<OcrWord>()

        fun word(text: String, box: PixelBox?, confidence: Float? = null, angle: Float? = null, language: String? = null) {
            words += OcrWord(OcrIds.word(b, l, words.size), text, box, box?.normalizedIn(image), confidence, angle, language)
        }
    }
}
