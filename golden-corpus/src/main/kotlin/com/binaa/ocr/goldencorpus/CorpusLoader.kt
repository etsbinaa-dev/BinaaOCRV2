package com.binaa.ocr.goldencorpus

import kotlinx.serialization.Serializable
import java.io.File

/** حالة جاهزة للتشغيل: ملف التوقعات وصورته موجودان. */
data class CorpusCase(val expected: ExpectedCase, val directory: File, val imageFile: File) {
    val caseId: String get() = expected.caseId
}

@Serializable
enum class CaseProblemKind {
    /** مجلد بلا expected.json. */
    NO_EXPECTED_JSON,

    /** expected.json غير صالح (JSON معطوب، حقل ناقص أو غير معروف، حالة غير معروفة، صيغة قيمة خاطئة...). */
    INVALID_EXPECTED_JSON,

    /** الصورة التي يذكرها expected.json غير موجودة في مجلد الحالة. */
    MISSING_IMAGE,

    /** نفس caseId في أكثر من مجلد: تُرفض كل هذه المجلدات، لا يُختار أحدها. */
    DUPLICATE_CASE_ID,
}

/** مجلد لم يصبح حالة، ولماذا. [detail] قد يحمل جزءًا من الملف: لا يظهر في التقرير المقنّع. */
@Serializable
data class CaseProblem(val directory: String, val kind: CaseProblemKind, val detail: String? = null)

data class CorpusLoadResult(val cases: List<CorpusCase>, val problems: List<CaseProblem>)

/**
 * يكتشف الحالات في مجلد جذر: كل مجلد فرعي = حالة فيها `expected.json` وصورة.
 * بترتيب أسماء المجلدات دائمًا (نتيجة ثابتة). لا يرمي بسبب حالة واحدة: كل خلل يصبح [CaseProblem] وتُكمَل البقية.
 * جذر غير موجود = Corpus فارغ (لا خطأ).
 */
object CorpusLoader {

    fun load(root: File): CorpusLoadResult {
        val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
        val problems = mutableListOf<CaseProblem>()
        val parsed = mutableListOf<Pair<File, ExpectedCase>>()

        for (dir in dirs) {
            val file = File(dir, ExpectedCase.FILE_NAME)
            if (!file.isFile) {
                problems += CaseProblem(dir.name, CaseProblemKind.NO_EXPECTED_JSON)
                continue
            }
            try {
                parsed += dir to ExpectedCase.parse(file.readText(Charsets.UTF_8))
            } catch (e: CorpusFormatException) {
                problems += CaseProblem(dir.name, CaseProblemKind.INVALID_EXPECTED_JSON, e.message)
            }
        }

        val duplicated = parsed.groupBy { it.second.caseId }.filterValues { it.size > 1 }.keys
        val cases = mutableListOf<CorpusCase>()
        for ((dir, expected) in parsed) {
            if (expected.caseId in duplicated) {
                problems += CaseProblem(dir.name, CaseProblemKind.DUPLICATE_CASE_ID, expected.caseId)
                continue
            }
            val image = File(dir, expected.image)
            if (!image.isFile) {
                problems += CaseProblem(dir.name, CaseProblemKind.MISSING_IMAGE, expected.image)
                continue
            }
            cases += CorpusCase(expected, dir, image)
        }
        return CorpusLoadResult(cases.sortedBy { it.caseId }, problems.sortedWith(compareBy({ it.directory }, { it.kind })))
    }
}
