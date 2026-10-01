package com.binaa.ocr.goldencorpus

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.binaa.ocr.ocr.MlKitOcrAdapter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * الطريق كاملًا على الجهاز بحالات اصطناعية تُكتب أثناء الاختبار في مجلد مؤقت:
 * مجلد حالات → CorpusLoader → ML Kit → CorpusRunner → تقرير. لا صور حقيقية.
 */
@RunWith(AndroidJUnit4::class)
class GoldenCorpusInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root = File(context.cacheDir, "golden-corpus-test").apply { deleteRecursively(); mkdirs() }

    @After
    fun clean() {
        root.deleteRecursively()
    }

    @Test
    fun syntheticCorpus_runsThroughMlKit_andReports() {
        writeCase("synthetic-ok", """["Vous avez envoye", "MRU"]""")
        writeCase("synthetic-missing-text", """["Frais"]""")
        File(root, "synthetic-no-image").mkdirs()
        File(root, "synthetic-no-image/expected.json").writeText(expected("synthetic-no-image", """["MRU"]"""))

        val load = CorpusLoader.load(root)
        val report = MlKitOcrAdapter().use { adapter ->
            CorpusRunner.run(load) { case -> CaseObservation(adapter.read(MlKitOcrAdapter.decodeFullImage(case.imageFile))) }
        }

        assertEquals(listOf("synthetic-missing-text", "synthetic-ok"), report.cases.map { it.caseId })
        assertEquals(CaseStatus.FAIL, report.cases[0].status)
        assertEquals(CaseStatus.PASS, report.cases[1].status)
        assertEquals(CaseProblemKind.MISSING_IMAGE, report.problems.single().kind)
        assertEquals(0, report.totals.wrongFound)

        // التقرير المقنّع يحتفظ بالمربعات ويخفي الأرقام.
        val masked = Masking.mask(report)
        val ok = masked.cases[1]
        assertEquals(report.cases[1].document!!.words.map { it.box }, ok.document!!.words.map { it.box })
        assertTrue(ok.document!!.words.none { w -> w.text.any { it.isDigit() && it != '9' } })
    }

    @Test
    fun noCorpusFolder_isAnEmptyReport() {
        val report = CorpusRunner.run(CorpusLoader.load(File(root, "absent"))) { error("must not run") }
        assertEquals(0, report.totals.cases)
        assertEquals(0, report.totals.problems)
    }

    private fun writeCase(id: String, ocrContains: String) {
        val dir = File(root, id).apply { mkdirs() }
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
