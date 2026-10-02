package com.binaa.ocr.core.evidence

import com.binaa.ocr.core.ocr.ImageSize
import com.binaa.ocr.core.ocr.OcrDocumentBuilder
import com.binaa.ocr.core.ocr.OcrEngineInfo
import com.binaa.ocr.core.ocr.OcrWord
import com.binaa.ocr.core.ocr.PixelBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceLogTest {

    private val doc = OcrDocumentBuilder(ImageSize(1080, 2229), OcrEngineInfo("test-engine", "1"))
        .block("Montant 7 200 MRU", PixelBox(60, 900, 1020, 960)) {
            line("Montant 7 200 MRU", PixelBox(60, 900, 1020, 960), confidence = 0.9f) {
                word("Montant", PixelBox(60, 900, 300, 960), confidence = 0.92f)
                word("7", PixelBox(600, 900, 630, 960), confidence = 0.8f)
                word("200", PixelBox(640, 900, 740, 960), confidence = 0.85f)
                word("MRU", PixelBox(760, 900, 880, 960), confidence = 0.99f)
            }
        }
        .build()

    private val source = EvidenceSource("test-rule", "1")

    @Test
    fun newLog_holdsWhatOcrRead_withNoParserEvidenceYet() {
        val log = EvidenceLog(doc)
        assertTrue(log.entries.isEmpty())
        assertEquals(4, log.document.words.size)
    }

    @Test
    fun evidence_resolvesToTheWordsItUsed_withTextBoxAndConfidence() {
        val label = Evidence(EvidenceKind.LABEL_NEARBY, source, listOf("b0.l0.w0", "b0.l0.w2"), "AMOUNT_LABEL_SAME_ROW")
        val log = EvidenceLog(doc).with(label)
        val words = log.elementsOf(label).map { it as OcrWord }
        assertEquals(listOf("Montant", "200"), words.map { it.text })
        assertEquals(listOf(PixelBox(60, 900, 300, 960), PixelBox(640, 900, 740, 960)), words.map { it.box })
        assertEquals(listOf(0.92f, 0.85f), words.map { it.confidence })
    }

    @Test
    fun evidenceToAnUnknownElement_isRejected() {
        val bad = Evidence(EvidenceKind.CURRENCY, source, listOf("b0.l0.w9"), "CURRENCY_SAME_ROW")
        assertThrows(IllegalArgumentException::class.java) { EvidenceLog(doc).with(bad) }
    }

    @Test
    fun evidenceWithoutElements_orWithFreeTextCode_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Evidence(EvidenceKind.SIGN, source, emptyList(), "NEGATIVE_SIGN")
        }
        // الرمز ثابت، لا نص حر قد يحمل قيمة من الإيصال.
        assertThrows(IllegalArgumentException::class.java) {
            Evidence(EvidenceKind.SIGN, source, listOf("b0.l0.w1"), "amount is 7 200")
        }
    }
}
