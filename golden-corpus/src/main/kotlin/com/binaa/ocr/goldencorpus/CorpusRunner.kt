package com.binaa.ocr.goldencorpus

import com.binaa.ocr.core.ocr.OcrDocument
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** ما أعطاه النظام لحقل. */
data class ObservedField(val status: ExpectedStatus, val value: String? = null, val candidates: List<String> = emptyList())

/**
 * ما أنتجه النظام لحالة واحدة. [document] قراءة OCR إن وُجدت.
 * الحقول التي لا ينتجها النظام بعد (لا Header ولا Parsers في Phase 2a) تبقى غائبة: تُسجَّل NOT_RUN لا FAIL.
 */
data class CaseObservation(val document: OcrDocument?, val fields: Map<CorpusField, ObservedField> = emptyMap())

/** يشغّل النظام على حالة. على الهاتف: ML Kit (ثم الطبقات اللاحقة). في اختبارات JVM: مقيِّم اصطناعي. */
fun interface CaseEvaluator {
    fun evaluate(case: CorpusCase): CaseObservation
}

@Serializable
enum class CheckOutcome { PASS, FAIL, NOT_RUN }

@Serializable
enum class CaseStatus { PASS, FAIL, NOT_RUN, ERROR }

@Serializable
data class CheckResult(
    /** "field:amount" أو "ocr:<النص>". */
    val check: String,
    val outcome: CheckOutcome,
    val expected: String,
    val observed: String? = null,
    /** FOUND خاطئ: قيمة مؤكدة لا تطابق الحقيقة. يجب أن يبقى عددها 0. */
    val wrongFound: Boolean = false,
)

@Serializable
data class CaseResult(
    val caseId: String,
    val source: CaseSource,
    val status: CaseStatus,
    val checks: List<CheckResult>,
    /** نوع الاستثناء فقط إذا فشل التشغيل (لا رسالته: قد تحمل بيانات). */
    val error: String? = null,
    /** قراءة OCR كاملة (التقرير الكامل) أو مقنّعة (التقرير المقنّع). */
    val document: OcrDocument? = null,
)

@Serializable
data class CorpusTotals(
    val cases: Int,
    val passed: Int,
    val failed: Int,
    val notRun: Int,
    val errors: Int,
    val problems: Int,
    val wrongFound: Int,
)

/** تقرير ثابت: نفس المدخلات تعطي نفس الملف حرفيًا (لا أوقات ولا مسارات). */
@Serializable
data class CorpusReport(
    val schemaVersion: Int = SCHEMA_VERSION,
    val masked: Boolean = false,
    val totals: CorpusTotals,
    val cases: List<CaseResult>,
    val problems: List<CaseProblem>,
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val SCHEMA_VERSION = 1
        private val json = Json { prettyPrint = true; encodeDefaults = true }
    }
}

object CorpusRunner {

    fun run(load: CorpusLoadResult, evaluator: CaseEvaluator): CorpusReport {
        val results = load.cases.sortedBy { it.caseId }.map { runCase(it, evaluator) }
        return CorpusReport(
            totals = CorpusTotals(
                cases = results.size,
                passed = results.count { it.status == CaseStatus.PASS },
                failed = results.count { it.status == CaseStatus.FAIL },
                notRun = results.count { it.status == CaseStatus.NOT_RUN },
                errors = results.count { it.status == CaseStatus.ERROR },
                problems = load.problems.size,
                wrongFound = results.sumOf { r -> r.checks.count { it.wrongFound } },
            ),
            cases = results,
            problems = load.problems,
        )
    }

    private fun runCase(case: CorpusCase, evaluator: CaseEvaluator): CaseResult {
        val observation = try {
            evaluator.evaluate(case)
        } catch (e: Exception) {
            return CaseResult(case.caseId, case.expected.source, CaseStatus.ERROR, emptyList(), error = e::class.java.simpleName)
        }
        val checks = case.expected.ocrContains.map { checkOcr(it, observation.document) } +
            case.expected.fields.entries.sortedBy { it.key }.map { (field, expected) -> checkField(field, expected, observation.fields[field]) }
        val status = when {
            checks.any { it.outcome == CheckOutcome.FAIL } -> CaseStatus.FAIL
            checks.any { it.outcome == CheckOutcome.PASS } -> CaseStatus.PASS
            else -> CaseStatus.NOT_RUN
        }
        return CaseResult(case.caseId, case.expected.source, status, checks, document = observation.document)
    }

    private fun checkOcr(text: String, document: OcrDocument?): CheckResult {
        val check = "ocr:$text"
        if (document == null) return CheckResult(check, CheckOutcome.NOT_RUN, expected = text)
        val found = document.lines.any { it.text.contains(text) }
        return CheckResult(check, if (found) CheckOutcome.PASS else CheckOutcome.FAIL, expected = text, observed = if (found) text else null)
    }

    private fun checkField(field: CorpusField, expected: ExpectedField, observed: ObservedField?): CheckResult {
        val check = "field:${field.name.lowercase()}"
        val expectedText = describe(expected.status, expected.value, expected.candidates)
        if (observed == null) return CheckResult(check, CheckOutcome.NOT_RUN, expectedText)
        val observedText = describe(observed.status, observed.value, observed.candidates)

        // أي FOUND لا يطابق الحقيقة تمامًا خطأ جسيم: قيمة خاطئة، أو تأكيد حيث يجب «لا أعرف».
        val wrongFound = observed.status == ExpectedStatus.FOUND &&
            (expected.status != ExpectedStatus.FOUND || observed.value != expected.value)
        val pass = when (expected.status) {
            ExpectedStatus.FOUND -> observed.status == ExpectedStatus.FOUND && observed.value == expected.value
            ExpectedStatus.UNCERTAIN -> observed.status == ExpectedStatus.UNCERTAIN && observed.candidates.containsAll(expected.candidates)
            ExpectedStatus.NOT_FOUND -> observed.status == ExpectedStatus.NOT_FOUND
        }
        return CheckResult(check, if (pass) CheckOutcome.PASS else CheckOutcome.FAIL, expectedText, observedText, wrongFound)
    }

    private fun describe(status: ExpectedStatus, value: String?, candidates: List<String>): String = when {
        value != null -> "$status $value"
        candidates.isNotEmpty() -> "$status ${candidates.joinToString(" | ")}"
        else -> status.name
    }
}
