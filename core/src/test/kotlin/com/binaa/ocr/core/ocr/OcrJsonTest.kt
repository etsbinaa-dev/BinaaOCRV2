package com.binaa.ocr.core.ocr

import com.binaa.ocr.core.evidence.Evidence
import com.binaa.ocr.core.evidence.EvidenceKind
import com.binaa.ocr.core.evidence.EvidenceLog
import com.binaa.ocr.core.evidence.EvidenceSource
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrJsonTest {

    @Test
    fun document_roundTripsExactly() {
        val doc = SampleDocuments.twoBlocks()
        val decoded = OcrJson.decodeDocument(OcrJson.encode(doc))
        assertEquals(doc, decoded)
        // المربعات والثقة والترتيب كما هي، بعد الرحلة.
        assertEquals(doc.words.map { it.id to it.box }, decoded.words.map { it.id to it.box })
        assertEquals(doc.words.map { it.confidence }, decoded.words.map { it.confidence })
    }

    @Test
    fun evidenceLog_roundTripsExactly() {
        val log = EvidenceLog(SampleDocuments.twoBlocks())
            .with(Evidence(EvidenceKind.LABEL_NEARBY, EvidenceSource("test-rule", "1"), listOf("b0.l0", "b0.l1.w1"), "LABEL_SAME_ROW"))
        assertEquals(log, OcrJson.decodeLog(OcrJson.encode(log)))
    }

    @Test
    fun json_carriesSchemaVersion_andExplicitNulls() {
        val text = OcrJson.encode(SampleDocuments.twoBlocks())
        assertTrue(text.contains("\"schemaVersion\":1"))
        // كلمة بلا مربع تبقى صريحة في الملف (لا تختفي فتبدو كأنها نسيت).
        assertTrue(text.contains("\"box\":null"))
    }

    @Test
    fun unknownField_isRejected_notIgnored() {
        val text = OcrJson.encode(SampleDocuments.twoBlocks()).replaceFirst("{", "{\"surprise\":1,")
        assertThrows(SerializationException::class.java) { OcrJson.decodeDocument(text) }
    }

    @Test
    fun invalidContent_isRejectedOnRead() {
        // الفحوص نفسها تعمل عند القراءة من JSON، لا عند البناء فقط.
        val text = OcrJson.encode(SampleDocuments.twoBlocks())
        assertThrows(IllegalArgumentException::class.java) {
            OcrJson.decodeDocument(text.replace("\"id\":\"b0.l1.w0\"", "\"id\":\"b0.l1.w9\""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OcrJson.decodeDocument(text.replace("\"schemaVersion\":1", "\"schemaVersion\":99"))
        }
    }

    @Test
    fun evidencePointingAtMissingWords_isRejectedOnRead() {
        val log = EvidenceLog(SampleDocuments.twoBlocks())
            .with(Evidence(EvidenceKind.SIGN, EvidenceSource("test-rule", "1"), listOf("b0.l1.w0"), "NEGATIVE_SIGN"))
        val text = OcrJson.encode(log).replace("\"elementIds\":[\"b0.l1.w0\"]", "\"elementIds\":[\"b7.l0.w0\"]")
        assertThrows(IllegalArgumentException::class.java) { OcrJson.decodeLog(text) }
    }
}
