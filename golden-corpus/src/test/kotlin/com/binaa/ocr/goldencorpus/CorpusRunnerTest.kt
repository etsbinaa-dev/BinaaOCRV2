package com.binaa.ocr.goldencorpus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CorpusRunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun load(vararg cases: Pair<String, String>): CorpusLoadResult {
        val corpus = SyntheticCorpus(tmp.newFolder())
        for ((dir, json) in cases) corpus.case(dir, json)
        return CorpusLoader.load(corpus.root)
    }

    private val ocrOnly = CaseEvaluator { CaseObservation(SyntheticCorpus.document()) }

    private fun withAmount(observed: ObservedField) = CaseEvaluator { CaseObservation(SyntheticCorpus.document(), mapOf(CorpusField.AMOUNT to observed)) }

    @Test
    fun syntheticCase_executes_andOcrChecksPass() {
        val load = load("c1" to SyntheticCorpus.expected("c1", fields = "", ocrContains = """["Vous avez", "MRU"]"""))
        val report = CorpusRunner.run(load, ocrOnly)
        assertEquals(CorpusTotals(cases = 1, passed = 1, failed = 0, notRun = 0, errors = 0, problems = 0, wrongFound = 0), report.totals)
        assertEquals(listOf(CheckOutcome.PASS, CheckOutcome.PASS), report.cases.single().checks.map { it.outcome })
    }

    @Test
    fun missingOcrText_fails() {
        val report = CorpusRunner.run(load("c1" to SyntheticCorpus.expected("c1", fields = "", ocrContains = """["Frais"]""")), ocrOnly)
        assertEquals(CaseStatus.FAIL, report.cases.single().status)
    }

    @Test
    fun fieldsNotProducedYet_areNotRun_notFailed() {
        // Phase 2a: لا Header ولا Parsers، فلا ينتج النظام حقولًا بعد.
        val report = CorpusRunner.run(load("c1" to SyntheticCorpus.expected("c1", ocrContains = "[]")), ocrOnly)
        assertEquals(CaseStatus.NOT_RUN, report.cases.single().status)
        assertEquals(CheckOutcome.NOT_RUN, report.cases.single().checks.single().outcome)
        assertEquals(0, report.totals.wrongFound)
    }

    @Test
    fun correctFound_passes() {
        val report = CorpusRunner.run(load("c1" to SyntheticCorpus.expected("c1")), withAmount(ObservedField(ExpectedStatus.FOUND, "18300.00")))
        assertEquals(CaseStatus.PASS, report.cases.single().status)
        assertEquals(0, report.totals.wrongFound)
    }

    @Test
    fun foundWithTheWrongValue_isCountedAsWrongFound() {
        val report = CorpusRunner.run(load("c1" to SyntheticCorpus.expected("c1")), withAmount(ObservedField(ExpectedStatus.FOUND, "18329.30")))
        val check = report.cases.single().checks.first { it.check == "field:amount" }
        assertEquals(CheckOutcome.FAIL, check.outcome)
        assertTrue(check.wrongFound)
        assertEquals(1, report.totals.wrongFound)
    }

    @Test
    fun foundWhereTheTruthIsUncertainOrMissing_isWrongFound() {
        val uncertain = SyntheticCorpus.expected("u", fields = """"amount": {"status": "UNCERTAIN", "candidates": ["18300.00", "18329.30"]}""")
        val missing = SyntheticCorpus.expected("n", fields = """"amount": {"status": "NOT_FOUND"}""")
        val report = CorpusRunner.run(load("u" to uncertain, "n" to missing), withAmount(ObservedField(ExpectedStatus.FOUND, "18300.00")))
        assertEquals(2, report.totals.wrongFound)
    }

    @Test
    fun saferAnswerThanExpected_failsButIsNotWrongFound() {
        val report = CorpusRunner.run(load("c1" to SyntheticCorpus.expected("c1")), withAmount(ObservedField(ExpectedStatus.UNCERTAIN, candidates = listOf("18300.00"))))
        val check = report.cases.single().checks.first { it.check == "field:amount" }
        assertEquals(CheckOutcome.FAIL, check.outcome)
        assertFalse(check.wrongFound)
    }

    @Test
    fun uncertain_passesOnlyWhenTheExpectedCandidatesAreOffered() {
        val expected = SyntheticCorpus.expected("u", fields = """"amount": {"status": "UNCERTAIN", "candidates": ["18300.00", "18329.30"]}""")
        val both = CorpusRunner.run(load("u" to expected), withAmount(ObservedField(ExpectedStatus.UNCERTAIN, candidates = listOf("18329.30", "18300.00"))))
        assertEquals(CaseStatus.PASS, both.cases.single().status)
        val one = CorpusRunner.run(load("u" to expected), withAmount(ObservedField(ExpectedStatus.UNCERTAIN, candidates = listOf("18329.30"))))
        assertEquals(CaseStatus.FAIL, one.cases.single().status)
    }

    @Test
    fun evaluatorFailure_isAnError_withoutItsMessage() {
        val report = CorpusRunner.run(load("c1" to SyntheticCorpus.expected("c1"))) { throw IllegalStateException("secret 18300") }
        val case = report.cases.single()
        assertEquals(CaseStatus.ERROR, case.status)
        assertEquals("IllegalStateException", case.error)
        assertFalse(report.toJson().contains("secret"))
    }

    @Test
    fun loadProblems_appearInTheReport() {
        val corpus = SyntheticCorpus(tmp.newFolder())
        corpus.case("ok", SyntheticCorpus.expected("ok", fields = "", ocrContains = """["MRU"]"""))
        corpus.case("no-image", SyntheticCorpus.expected("no-image"), image = null)
        val report = CorpusRunner.run(CorpusLoader.load(corpus.root), ocrOnly)
        assertEquals(1, report.totals.problems)
        assertEquals(CaseProblemKind.MISSING_IMAGE, report.problems.single().kind)
    }

    @Test
    fun report_isDeterministic() {
        val load = load(
            "z" to SyntheticCorpus.expected("z"),
            "a" to SyntheticCorpus.expected("a", fields = "", ocrContains = """["MRU", "Vous"]"""),
        )
        val first = CorpusRunner.run(load, ocrOnly).toJson()
        assertEquals(first, CorpusRunner.run(load, ocrOnly).toJson())
        assertTrue(first.indexOf("\"caseId\": \"a\"") < first.indexOf("\"caseId\": \"z\""))
    }
}
