package com.binaa.ocr.goldencorpus

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * المشغّل على الجهاز بحالات اصطناعية تُكتب أثناء الاختبار في مجلد Corpus الخاص بنسخة debug.
 * (إثبات أن adb وحده يستطيع تشغيل CorpusActivity وأن التطبيقات العادية لا تستطيع: scripts/ci-instrumented.sh.)
 */
@RunWith(AndroidJUnit4::class)
class CorpusActivityInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var cases: File
    private lateinit var reports: File

    @Before
    fun clean() {
        cases = CorpusRun.casesDir(context).apply { deleteRecursively(); mkdirs() }
        reports = CorpusRun.reportsDir(context).apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun cleanUp() {
        cases.deleteRecursively()
        reports.deleteRecursively()
    }

    @Test
    fun activity_runsTheSyntheticCorpus_andWritesBothReports() {
        writeCase("synthetic-ok", """["Vous avez envoye", "MRU"]""")
        writeCase("synthetic-missing-text", """["Frais"]""")
        File(cases, "synthetic-no-image").mkdirs()
        File(cases, "synthetic-no-image/expected.json").writeText(expected("synthetic-no-image", """["MRU"]"""))

        ActivityScenario.launch(CorpusActivity::class.java).use {
            val masked = awaitFile(File(reports, CorpusRun.MASKED_REPORT))
            val full = JSONObject(File(reports, CorpusRun.FULL_REPORT).readText())
            val maskedJson = JSONObject(masked.readText())

            val totals = full.getJSONObject("totals")
            assertEquals(2, totals.getInt("cases"))
            assertEquals(1, totals.getInt("passed"))
            assertEquals(1, totals.getInt("failed"))
            assertEquals(1, totals.getInt("problems"))
            assertEquals(0, totals.getInt("wrongFound"))
            assertEquals("MISSING_IMAGE", full.getJSONArray("problems").getJSONObject(0).getString("kind"))

            assertTrue(maskedJson.getBoolean("masked"))
            assertEquals(totals.toString(), maskedJson.getJSONObject("totals").toString())
            // التقرير المقنّع: نفس المربعات، ولا رقم أصلي في أي نص.
            assertEquals(boxes(full), boxes(maskedJson))
            assertTrue(words(maskedJson).none { w -> w.any { it.isDigit() && it != '9' } })
            assertTrue(words(full).any { it.contains("MRU") })
        }
    }

    @Test
    fun noCases_givesAnEmptyReport() {
        val report = CorpusRun.run(cases, reports)
        assertEquals(0, report.totals.cases)
        assertEquals(0, report.totals.problems)
        assertTrue(File(reports, CorpusRun.MASKED_REPORT).isFile)
    }

    @Test
    fun brokenExpectedJson_isReported_andTheOtherCasesStillRun() {
        writeCase("synthetic-ok", """["MRU"]""")
        File(cases, "synthetic-broken").mkdirs()
        File(cases, "synthetic-broken/expected.json").writeText("{ not json")
        val report = CorpusRun.run(cases, reports)
        assertEquals(1, report.totals.passed)
        assertEquals(CaseProblemKind.INVALID_EXPECTED_JSON, report.problems.single().kind)
    }

    @Test
    fun corpusActivity_isProtectedByDump_andTheAppRequestsNoPermissionForIt() {
        val pm = context.packageManager
        val info = pm.getActivityInfo(ComponentName(context, CorpusActivity::class.java), 0)
        assertEquals(Manifest.permission.DUMP, info.permission)
        assertTrue(info.exported)
        // التطبيق نفسه لا يملك DUMP، ولا يطلب أي صلاحية Android (لا تخزين ولا وسائط ولا شبكة)،
        // سوى الإشعارات: إشعار صامت واحد يحمل عدد الدفعات «للإدخال» على أيقونة التطبيق.
        assertEquals(PackageManager.PERMISSION_DENIED, pm.checkPermission(Manifest.permission.DUMP, context.packageName))
        val requested = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue("unexpected permissions: ${requested.toList()}", requested.none { it.startsWith("android.permission.") && it != Manifest.permission.POST_NOTIFICATIONS })
    }

    // ------------------------------------------------------------------ أدوات

    private fun awaitFile(file: File): File {
        val deadline = SystemClock.elapsedRealtime() + 120_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (file.isFile) return file
            Thread.sleep(200)
        }
        throw AssertionError("timed out waiting for ${file.name}")
    }

    private fun boxes(report: JSONObject): List<String> = docWords(report).map { it.optJSONObject("box")?.toString().orEmpty() }

    private fun words(report: JSONObject): List<String> = docWords(report).map { it.getString("text") }

    private fun docWords(report: JSONObject): List<JSONObject> = buildList {
        val casesArray = report.getJSONArray("cases")
        for (c in 0 until casesArray.length()) {
            val doc = casesArray.getJSONObject(c).optJSONObject("document") ?: continue
            val blocks = doc.getJSONArray("blocks")
            for (b in 0 until blocks.length()) {
                val lines = blocks.getJSONObject(b).getJSONArray("lines")
                for (l in 0 until lines.length()) {
                    val ws = lines.getJSONObject(l).getJSONArray("words")
                    for (w in 0 until ws.length()) add(ws.getJSONObject(w))
                }
            }
        }
    }

    private fun writeCase(id: String, ocrContains: String) {
        val dir = File(cases, id).apply { mkdirs() }
        File(dir, "expected.json").writeText(expected(id, ocrContains))
        File(dir, "image.png").outputStream().use { screenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun expected(id: String, ocrContains: String) =
        """{"schemaVersion": 1, "caseId": "$id", "image": "image.png", "source": "SYNTHETIC", "ocrContains": $ocrContains}"""

    /** لقطة اصطناعية بقيم وهمية. */
    private fun screenshot(): Bitmap {
        val bitmap = Bitmap.createBitmap(1080, 2229, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 64f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        canvas.drawText("Vous avez envoye", 60f, 700f, paint)
        canvas.drawText("18 300,00 MRU", 1020f - paint.measureText("18 300,00 MRU"), 700f, paint)
        return bitmap
    }
}
