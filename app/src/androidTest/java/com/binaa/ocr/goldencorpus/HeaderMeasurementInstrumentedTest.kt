package com.binaa.ocr.goldencorpus

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * قياس HeaderDetector في مشغّل الـCorpus: الهندسة الصحيحة تُكتب، والتقرير المقنّع لا يحتوي الاسم ولا الهاتف ولا المرجع.
 * كل القيم «الحساسة» هنا مخترعة (حراس اختبار)، والصور اصطناعية تُرسم أثناء الاختبار.
 */
@RunWith(AndroidJUnit4::class)
class HeaderMeasurementInstrumentedTest {

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
    fun measurement_writesTheDetectorGeometry_andTheMaskedReportHidesNamePhoneAndReference() {
        writeCase("viewer-case", viewerScreenshot())
        writeCase("no-header-case", plainScreenshot())
        CorpusRun.run(cases, reports)

        val fullText = File(reports, CorpusRun.FULL_REPORT).readText()
        val maskedText = File(reports, CorpusRun.MASKED_REPORT).readText()
        // التقرير الكامل يحمل القيم (فالاختبار ليس فارغًا)…
        assertTrue(fullText.contains(NAME) && fullText.contains(REFERENCE))
        // …والمقنّع لا يحمل أيًّا منها، ولا أرقامها.
        for (secret in listOf(NAME, "Zyxwq", "Qwerty", PHONE, "98 76 54 32", "987654", REFERENCE, "TX-")) {
            assertFalse("'$secret' leaked into the masked report", maskedText.contains(secret))
        }

        val geometry = JSONObject(maskedText).getJSONArray(HeaderMeasurement.KEY)
        assertEquals(listOf("no-header-case", "viewer-case"), (0 until geometry.length()).map { geometry.getJSONObject(it).getString("caseId") })

        val viewer = geometry.getJSONObject(1)
        assertEquals(ALLOWED_KEYS, viewer.keys().asSequence().toSet())
        assertTrue(viewer.getBoolean("headerFound"))
        assertTrue(viewer.isNull("rejection"))
        assertEquals("LTR", viewer.getString("direction"))
        assertEquals(49, viewer.getInt("iconHeight"))
        assertEquals(268, viewer.getInt("nameToIconsGap"))
        assertEquals(box(0, 0, 1080, 180), box(viewer, "headerBar"))
        assertEquals(box(49, 61, 96, 107), box(viewer, "arrow"))
        assertEquals(box(718, 62, 902, 111), box(viewer, "iconCluster"))
        assertEquals(box(148, 31, 450, 80), box(viewer, "name"))
        assertEquals(box(146, 112, 400, 140), box(viewer, "subtitle"))

        val plain = geometry.getJSONObject(0)
        assertFalse(plain.getBoolean("headerFound"))
        assertEquals("NO_ICON_GROUP", plain.getString("rejection"))
        assertEquals(setOf("caseId", "headerFound", "rejection"), plain.keys().asSequence().toSet())

        // نفس الهندسة في التقرير الكامل، وباقي التقرير المقنّع كما هو (مقنّع).
        assertEquals(geometry.toString(), JSONObject(fullText).getJSONArray(HeaderMeasurement.KEY).toString())
        assertTrue(JSONObject(maskedText).getBoolean("masked"))
    }

    // ------------------------------------------------------------------ أدوات

    private fun box(l: Int, t: Int, r: Int, b: Int) = listOf(l, t, r, b)

    private fun box(o: JSONObject, key: String) = o.getJSONObject(key).let { listOf(it.getInt("l"), it.getInt("t"), it.getInt("r"), it.getInt("b")) }

    private fun writeCase(id: String, bitmap: Bitmap) {
        val dir = File(cases, id).apply { mkdirs() }
        File(dir, "expected.json").writeText(
            """{"schemaVersion": 1, "caseId": "$id", "image": "image.png", "source": "SYNTHETIC",
               "fields": {"counterparty": {"status": "FOUND", "value": "$NAME"}, "reference": {"status": "FOUND", "value": "$REFERENCE"}},
               "ocrContains": ["Qwerty"]}""",
        )
        File(dir, "image.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** رأس Image Viewer بنفس هندسة اختبارات الكاشف (1080×2229)، وتحته نص الحراس كأنه في الإيصال. */
    private fun viewerScreenshot(): Bitmap {
        val bitmap = Bitmap.createBitmap(1080, 2229, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(DARK) }
        val fill = Paint().apply { color = WHITE }
        fun rect(l: Int, t: Int, r: Int, b: Int) = canvas.drawRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), fill)
        rect(49, 61, 96, 107)
        rect(718, 62, 770, 111)
        rect(858, 64, 902, 105)
        var x = 148; while (x < 450) { rect(x, 31, minOf(x + 30, 450), 80); x += 40 }
        rect(146, 112, 400, 140)
        drawSecrets(canvas, Color.WHITE)
        return bitmap
    }

    private fun plainScreenshot(): Bitmap {
        val bitmap = Bitmap.createBitmap(1080, 2229, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        drawSecrets(canvas, Color.BLACK)
        return bitmap
    }

    private fun drawSecrets(canvas: Canvas, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = 64f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }
        canvas.drawText(NAME, 60f, 800f, paint)
        canvas.drawText(PHONE, 60f, 950f, paint)
        canvas.drawText(REFERENCE, 60f, 1100f, paint)
    }

    private companion object {
        const val NAME = "Zyxwq Qwerty"
        const val PHONE = "+222 98 76 54 32"
        const val REFERENCE = "TX-987654"
        val DARK = 0xFF141A1E.toInt()
        val WHITE = 0xFFFEFEFE.toInt()
        val ALLOWED_KEYS = setOf(
            "caseId", "headerFound", "rejection", "direction", "iconHeight", "nameToIconsGap",
            "headerBar", "arrow", "iconCluster", "name", "subtitle",
        )
    }
}
