package com.binaa.ocr.goldencorpus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MaskingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun values_areMasked_labelsAndStructureKept() {
        assertEquals("99 999,99 MRU", Masking.mask("18 300,00 MRU"))
        assertEquals("Montant : 9 999 MRU", Masking.mask("Montant : 7 200 MRU"))
        assertEquals("aaaaa aaaaa", Masking.mask("Ahmed Salem"))
        assertEquals("بببب ببب", Masking.mask("محمد علي"))
        assertEquals("المبلغ: 99999", Masking.mask("المبلغ: ١١٩٠٠"))
        assertEquals("Vous avez envoye", Masking.mask("Vous avez envoye"))
    }

    @Test
    fun maskedDocument_keepsIdsBoxesAndConfidence() {
        val doc = SyntheticCorpus.document()
        val masked = Masking.mask(doc)
        assertEquals(doc.words.map { it.id to it.box }, masked.words.map { it.id to it.box })
        assertEquals(doc.words.map { it.norm }, masked.words.map { it.norm })
        assertEquals(doc.words.map { it.confidence }, masked.words.map { it.confidence })
        assertEquals(listOf("Vous", "avez", "envoye", "99", "999,99", "MRU"), masked.words.map { it.text })
    }

    @Test
    fun maskedReport_hidesValuesTextsAndProblemDetails() {
        val corpus = SyntheticCorpus(tmp.newFolder())
        corpus.case("c1", SyntheticCorpus.expected("c1", ocrContains = """["18 300,00"]"""))
        corpus.case("broken", "{ \"caseId\": \"Ahmed 18300\"")
        val evaluator = CaseEvaluator {
            CaseObservation(SyntheticCorpus.document(), mapOf(CorpusField.AMOUNT to ObservedField(ExpectedStatus.FOUND, "18329.30")))
        }
        val full = CorpusRunner.run(CorpusLoader.load(corpus.root), evaluator)
        val masked = Masking.mask(full)

        val json = masked.toJson()
        assertTrue(masked.masked)
        for (secret in listOf("18300", "18329", "18 300", "300,00", "Ahmed")) assertFalse("'$secret' leaked", json.contains(secret))
        assertNull(masked.problems.single().detail)
        // البنية والأعداد كما هي.
        assertEquals(full.totals, masked.totals)
        assertEquals(full.cases.map { it.checks.map { c -> c.outcome to c.wrongFound } }, masked.cases.map { it.checks.map { c -> c.outcome to c.wrongFound } })
        assertEquals(listOf("ocr:99 999,99", "field:amount"), masked.cases.single().checks.map { it.check })
        assertEquals("FOUND 99999.99", masked.cases.single().checks.last().expected)
    }
}
