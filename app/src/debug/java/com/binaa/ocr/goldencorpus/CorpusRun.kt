package com.binaa.ocr.goldencorpus

import android.content.Context
import android.util.Log
import com.binaa.ocr.ocr.MlKitOcrAdapter
import java.io.File

/**
 * تشغيل Golden Corpus على الجهاز (نسخة debug فقط).
 *
 * الحالات: getExternalFilesDir("corpus")/<case>/{expected.json, image}، يضعها المطوّر بـ adb.
 * التقارير: getExternalFilesDir("corpus-reports")/{report.json, report-masked.json}.
 * المجلدان خاصان بنسخة debug: لا يحتاجان أي صلاحية، ولا يقرؤهما تطبيق آخر، ولا يدخلان النسخ السحابي ولا النقل
 * بين الهواتف (قواعد data_extraction_rules تستبعد external)، ويُحذفان مع حذف نسخة debug.
 */
object CorpusRun {

    const val TAG = "GoldenCorpus"
    const val CASES_DIR = "corpus"
    const val REPORTS_DIR = "corpus-reports"
    const val FULL_REPORT = "report.json"
    const val MASKED_REPORT = "report-masked.json"

    fun casesDir(context: Context): File = requireNotNull(context.getExternalFilesDir(CASES_DIR)) { "external files dir unavailable" }

    fun reportsDir(context: Context): File = requireNotNull(context.getExternalFilesDir(REPORTS_DIR)) { "external files dir unavailable" }

    /**
     * يقرأ كل الحالات، ويشغّل ML Kit على صورة كل حالة كاملة، ويكتب التقريرين.
     * التقرير المقنّع يُكتب أخيرًا: وجوده يعني أن التشغيل انتهى.
     */
    fun run(casesDir: File, reportsDir: File): CorpusReport {
        reportsDir.mkdirs()
        File(reportsDir, MASKED_REPORT).delete()
        File(reportsDir, FULL_REPORT).delete()

        val load = CorpusLoader.load(casesDir)
        // قياس الكاشف قبل 5b: هندسة ورموز فقط (لا نص)، لكل صورة حالة.
        val geometry = mutableListOf<org.json.JSONObject>()
        val report = MlKitOcrAdapter().use { adapter ->
            CorpusRunner.run(load) { case ->
                val bitmap = MlKitOcrAdapter.decodeFullImage(case.imageFile)
                try {
                    geometry += HeaderMeasurement.measure(case.caseId, bitmap)
                    CaseObservation(adapter.read(bitmap))
                } finally {
                    bitmap.recycle()
                }
            }
        }
        writeAtomically(File(reportsDir, FULL_REPORT), HeaderMeasurement.appendTo(report.toJson(), geometry))
        writeAtomically(File(reportsDir, MASKED_REPORT), HeaderMeasurement.appendTo(Masking.mask(report).toJson(), geometry))

        // سطر واحد للأعداد فقط (لا نصوص ولا قيم).
        with(report.totals) {
            Log.i(TAG, "GOLDEN_CORPUS_DONE cases=$cases passed=$passed failed=$failed notRun=$notRun errors=$errors problems=$problems wrongFound=$wrongFound")
        }
        return report
    }

    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        check(tmp.renameTo(target)) { "could not write ${target.name}" }
    }
}
