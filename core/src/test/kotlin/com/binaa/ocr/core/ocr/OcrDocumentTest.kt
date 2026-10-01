package com.binaa.ocr.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** نص اصطناعي بشكل إيصال؛ لا أسماء ولا أرقام حقيقية. */
internal object SampleDocuments {
    val image = ImageSize(1080, 2229)
    val engine = OcrEngineInfo("test-engine", "1")

    fun twoBlocks(): OcrDocument = OcrDocumentBuilder(image, engine)
        .block("Vous avez envoyé 18 300,00 MRU", PixelBox(60, 600, 1020, 660)) {
            line("Vous avez envoyé", PixelBox(60, 600, 480, 660), confidence = 0.93f, angle = 0.5f, language = "fr") {
                word("Vous", PixelBox(60, 600, 170, 660), confidence = 0.95f, language = "fr")
                word("avez", PixelBox(185, 600, 300, 660), confidence = 0.91f)
                word("envoyé", PixelBox(315, 600, 480, 660), confidence = 0.9f)
            }
            line("18 300,00 MRU", PixelBox(640, 600, 1020, 660), confidence = 0.88f) {
                word("18", PixelBox(640, 600, 700, 660), confidence = 0.97f)
                word("300,00", PixelBox(712, 600, 880, 660), confidence = 0.86f)
                word("MRU", PixelBox(895, 600, 1020, 660), confidence = 0.99f)
            }
        }
        .block("Frais", null) {
            line("Frais", null) {
                word("Frais", null)
            }
        }
        .build(durationMs = 1234)
}

class OcrDocumentTest {

    @Test
    fun builder_assignsStableIdsInReadingOrder() {
        val doc = SampleDocuments.twoBlocks()
        assertEquals(listOf("b0", "b1"), doc.blocks.map { it.id })
        assertEquals(listOf("b0.l0", "b0.l1", "b1.l0"), doc.lines.map { it.id })
        assertEquals(
            listOf("b0.l0.w0", "b0.l0.w1", "b0.l0.w2", "b0.l1.w0", "b0.l1.w1", "b0.l1.w2", "b1.l0.w0"),
            doc.words.map { it.id },
        )
        assertEquals(listOf("Vous", "avez", "envoyé", "18", "300,00", "MRU", "Frais"), doc.words.map { it.text })
    }

    @Test
    fun builder_keepsEngineValues_andComputesNormalizedBoxes() {
        val doc = SampleDocuments.twoBlocks()
        val line = doc.lines.first()
        assertEquals(0.93f, line.confidence)
        assertEquals(0.5f, line.angle)
        assertEquals("fr", line.language)
        val word = doc.words[4]
        assertEquals(PixelBox(712, 600, 880, 660), word.box)
        assertEquals(word.box!!.normalizedIn(doc.image), word.norm)
        assertEquals(SampleDocuments.image, doc.image)
        assertEquals(1234L, doc.durationMs)
    }

    @Test
    fun missingBoxOrConfidence_staysMissing() {
        val frais = SampleDocuments.twoBlocks().words.last()
        assertNull(frais.box)
        assertNull(frais.norm)
        assertNull(frais.confidence)
    }

    @Test
    fun elementsAreFoundById() {
        val doc = SampleDocuments.twoBlocks()
        assertSame(doc.words[3], doc.element("b0.l1.w0"))
        assertSame(doc.lines[1], doc.element("b0.l1"))
        assertSame(doc.blocks[1], doc.element("b1"))
        assertNull(doc.element("b9.l0.w0"))
    }

    @Test
    fun idThatDoesNotMatchReadingPosition_isRejected() {
        val doc = SampleDocuments.twoBlocks()
        val swapped = doc.blocks[0].copy(id = "b1")
        assertThrows(IllegalArgumentException::class.java) { doc.copy(blocks = listOf(swapped, doc.blocks[1])) }
    }

    @Test
    fun normalizedBoxThatDoesNotMatchPixels_isRejected() {
        val doc = SampleDocuments.twoBlocks()
        val line = doc.lines[0]
        val badWord = line.words[0].copy(norm = NormBox(0.0, 0.0, 0.5, 0.5))
        val badLine = line.copy(words = listOf(badWord) + line.words.drop(1))
        val badBlock = doc.blocks[0].copy(lines = listOf(badLine, doc.blocks[0].lines[1]))
        assertThrows(IllegalArgumentException::class.java) { doc.copy(blocks = listOf(badBlock, doc.blocks[1])) }
    }

    @Test
    fun confidenceOutsideZeroToOne_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            OcrDocumentBuilder(SampleDocuments.image, SampleDocuments.engine)
                .block("x", null) { line("x", null, confidence = 1.5f) { word("x", null) } }
                .build()
        }
    }
}
