package com.binaa.ocr.goldencorpus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CorpusLoaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun corpus() = SyntheticCorpus(tmp.newFolder("corpus-root"))

    @Test
    fun casesAreDiscovered_inStableOrder_withTheirImages() {
        val corpus = corpus()
        corpus.case("b-case", SyntheticCorpus.expected("case-b"))
        corpus.case("a-case", SyntheticCorpus.expected("case-a"))
        corpus.case("c-case", SyntheticCorpus.expected("case-c", image = "shot.jpg"), image = "shot.jpg")

        val result = CorpusLoader.load(corpus.root)
        assertEquals(listOf("case-a", "case-b", "case-c"), result.cases.map { it.caseId })
        assertEquals(File(File(corpus.root, "c-case"), "shot.jpg"), result.cases[2].imageFile)
        assertTrue(result.problems.isEmpty())
    }

    @Test
    fun caseWithoutImage_isReported_andTheOthersStillLoad() {
        val corpus = corpus()
        corpus.case("ok", SyntheticCorpus.expected("ok"))
        corpus.case("no-image", SyntheticCorpus.expected("no-image"), image = null)

        val result = CorpusLoader.load(corpus.root)
        assertEquals(listOf("ok"), result.cases.map { it.caseId })
        assertEquals(listOf(CaseProblem("no-image", CaseProblemKind.MISSING_IMAGE, "image.png")), result.problems)
    }

    @Test
    fun invalidOrMissingExpectedJson_isReported_perCase() {
        val corpus = corpus()
        corpus.case("ok", SyntheticCorpus.expected("ok"))
        corpus.case("broken", "{ not json")
        corpus.case("bad-status", SyntheticCorpus.expected("bad-status", fields = """"amount": {"status": "MAYBE"}"""))
        corpus.case("empty-folder", expectedJson = null, image = null)

        val result = CorpusLoader.load(corpus.root)
        assertEquals(listOf("ok"), result.cases.map { it.caseId })
        assertEquals(
            listOf(
                "bad-status" to CaseProblemKind.INVALID_EXPECTED_JSON,
                "broken" to CaseProblemKind.INVALID_EXPECTED_JSON,
                "empty-folder" to CaseProblemKind.NO_EXPECTED_JSON,
            ),
            result.problems.map { it.directory to it.kind },
        )
    }

    @Test
    fun duplicateCaseId_rejectsEveryCopy_noneIsPicked() {
        val corpus = corpus()
        corpus.case("first", SyntheticCorpus.expected("same-id"))
        corpus.case("second", SyntheticCorpus.expected("same-id"))
        corpus.case("other", SyntheticCorpus.expected("other"))

        val result = CorpusLoader.load(corpus.root)
        assertEquals(listOf("other"), result.cases.map { it.caseId })
        assertEquals(
            listOf("first", "second"),
            result.problems.filter { it.kind == CaseProblemKind.DUPLICATE_CASE_ID }.map { it.directory },
        )
    }

    @Test
    fun missingOrEmptyRoot_isAnEmptyCorpus_notAnError() {
        val missing = CorpusLoader.load(File(tmp.root, "does-not-exist"))
        assertTrue(missing.cases.isEmpty() && missing.problems.isEmpty())
        val empty = CorpusLoader.load(tmp.newFolder("empty"))
        assertTrue(empty.cases.isEmpty() && empty.problems.isEmpty())
    }
}
