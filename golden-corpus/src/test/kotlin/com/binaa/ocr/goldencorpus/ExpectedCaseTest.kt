package com.binaa.ocr.goldencorpus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpectedCaseTest {

    private fun rejected(json: String): String =
        assertThrows(CorpusFormatException::class.java) { ExpectedCase.parse(json) }.message!!

    @Test
    fun validExpectedJson_loads() {
        val case = ExpectedCase.parse(
            SyntheticCorpus.expected(
                caseId = "synthetic-001",
                fields = """
                    "amount": {"status": "UNCERTAIN", "candidates": ["18300.00", "18329.30"]},
                    "counterparty": {"status": "NOT_FOUND"},
                    "date": {"status": "FOUND", "value": "2026-09-19"},
                    "time": {"status": "FOUND", "value": "10:12"},
                    "provider": {"status": "FOUND", "value": "MASRVI"},
                    "reference": {"status": "FOUND", "value": "TX-0001"}
                """.trimIndent(),
            ),
        )
        assertEquals("synthetic-001", case.caseId)
        assertEquals(CaseSource.SYNTHETIC, case.source)
        assertEquals(ExpectedField(ExpectedStatus.UNCERTAIN, candidates = listOf("18300.00", "18329.30")), case.fields[CorpusField.AMOUNT])
        assertEquals(ExpectedStatus.NOT_FOUND, case.fields[CorpusField.COUNTERPARTY]!!.status)
        assertEquals("MASRVI", case.fields[CorpusField.PROVIDER]!!.value)
        assertEquals(listOf("MRU"), case.ocrContains)
    }

    @Test
    fun invalidJson_isRejected() {
        rejected("""{"schemaVersion": 1, "caseId": """)
        rejected("not json at all")
    }

    @Test
    fun missingRequiredField_isRejected() {
        for (field in listOf("schemaVersion", "caseId", "image", "source")) {
            val json = SyntheticCorpus.expected("c1").lines().filterNot { it.trimStart().startsWith("\"$field\"") }.joinToString("\n")
            rejected(json)
        }
    }

    @Test
    fun unknownStatus_isRejected() {
        rejected(SyntheticCorpus.expected("c1", fields = """"amount": {"status": "MAYBE", "value": "1.00"}"""))
        rejected(SyntheticCorpus.expected("c1", fields = """"amount": {"status": "found", "value": "1.00"}"""))
    }

    @Test
    fun unknownFieldOrKey_isRejected_notIgnored() {
        rejected(SyntheticCorpus.expected("c1", fields = """"balance": {"status": "FOUND", "value": "1.00"}"""))
        rejected(SyntheticCorpus.expected("c1").replaceFirst("{", """{"extra": 1,"""))
    }

    @Test
    fun statusAndValueMustAgree() {
        // FOUND بلا قيمة، UNCERTAIN بقيمة مختارة، NOT_FOUND بقيمة.
        assertTrue(rejected(SyntheticCorpus.expected("c1", fields = """"amount": {"status": "FOUND"}""")).contains("FOUND"))
        rejected(SyntheticCorpus.expected("c1", fields = """"amount": {"status": "UNCERTAIN", "value": "18300.00"}"""))
        rejected(SyntheticCorpus.expected("c1", fields = """"amount": {"status": "NOT_FOUND", "value": "18300.00"}"""))
    }

    @Test
    fun valuesMustUseTheirCanonicalFormat() {
        for (bad in listOf(
            """"amount": {"status": "FOUND", "value": "18 300,00"}""",
            """"amount": {"status": "FOUND", "value": "18300"}""",
            """"date": {"status": "FOUND", "value": "19/09/2026"}""",
            """"time": {"status": "FOUND", "value": "25:00"}""",
            """"provider": {"status": "FOUND", "value": "Masrvi"}""",
            """"amount": {"status": "UNCERTAIN", "candidates": ["18300.00", "abc"]}""",
        )) {
            rejected(SyntheticCorpus.expected("c1", fields = bad))
        }
    }

    @Test
    fun imageMustBeAFileInTheCaseFolder() {
        rejected(SyntheticCorpus.expected("c1", image = "../other/image.png"))
        rejected(SyntheticCorpus.expected("c1", image = "image.txt"))
        rejected(SyntheticCorpus.expected("bad id with spaces"))
    }

    @Test
    fun caseWithNothingToCheck_isRejected() {
        rejected(SyntheticCorpus.expected("c1", fields = "", ocrContains = "[]"))
    }

    @Test
    fun unsupportedSchema_isRejected() {
        rejected(SyntheticCorpus.expected("c1").replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"))
    }
}
