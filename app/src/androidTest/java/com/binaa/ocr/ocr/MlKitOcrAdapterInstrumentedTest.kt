package com.binaa.ocr.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.binaa.ocr.core.ocr.ImageSize
import com.binaa.ocr.core.ocr.OcrDocument
import com.binaa.ocr.core.ocr.OcrJson
import com.binaa.ocr.core.ocr.OcrWord
import com.binaa.ocr.core.ocr.normalizedIn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.max

/**
 * ML Kit على صور اصطناعية تُرسم هنا (لا صور حقيقية ولا ملفات صور في Git).
 * يتحقق من أن المحوّل ينقل ما قرأه ML Kit بمواضعه الصحيحة على الصورة كاملة، لا من دقة القراءة.
 */
@RunWith(AndroidJUnit4::class)
class MlKitOcrAdapterInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val adapter = MlKitOcrAdapter()

    @After
    fun close() = adapter.close()

    @Test
    fun wholeScreenshot_isRead_withEveryWordWhereItWasDrawn() {
        val shot = Screenshot(1080, 2229)
        val doc = adapter.read(shot.bitmap)

        // الصورة كاملة: لا قص ولا تصغير.
        assertEquals(ImageSize(1080, 2229), doc.image)
        assertEquals(MlKitOcrAdapter.ENGINE, doc.engine)
        assertNotNull(doc.durationMs)

        for (drawn in shot.words) {
            val read = doc.words.filter { it.text == drawn.text }
            assertTrue("'${drawn.text}' not read; ML Kit read: ${doc.words.map { it.text }}", read.isNotEmpty())
            assertTrue(
                "'${drawn.text}' drawn at ${drawn.box} but read at ${read.map { it.box }}",
                read.any { it.isAt(drawn.box) },
            )
        }
    }

    @Test
    fun textAtTheVeryTopAndBottom_isKept_soNothingWasCropped() {
        val shot = Screenshot(1080, 2229)
        val doc = adapter.read(shot.bitmap)
        val top = doc.words.first { it.text == "Header" }
        val bottom = doc.words.first { it.text == "Bottom" }
        assertTrue("header word must stay in the top bar: ${top.box}", top.box!!.bottom <= Screenshot.HEADER_BAR)
        assertTrue("bottom word must stay near the bottom: ${bottom.box}", bottom.box!!.top >= 2229 - 200)
    }

    @Test
    fun confidenceAngleAndBoxes_comeFromMlKit() {
        val doc = adapter.read(Screenshot(1080, 2229).bitmap)
        assertTrue(doc.lines.isNotEmpty())
        for (line in doc.lines) {
            assertNotNull("${line.id} box", line.box)
            assertNotNull("${line.id} confidence", line.confidence)
            assertNotNull("${line.id} angle", line.angle)
        }
        for (word in doc.words) {
            assertNotNull("${word.id} box", word.box)
            assertNotNull("${word.id} confidence", word.confidence)
            assertEquals(word.box!!.normalizedIn(doc.image), word.norm)
        }
    }

    @Test
    fun readingOrder_isMlKitOrder_withStableIds() {
        val doc = adapter.read(Screenshot(1080, 2229).bitmap)
        val ids = doc.lines.map { it.id }
        val header = doc.lines.indexOfFirst { it.text.contains("Header") }
        val bottom = doc.lines.indexOfFirst { it.text.contains("Bottom") }
        assertTrue("header line must come before bottom line in reading order: ${doc.lines.map { it.text }}", header in 0 until bottom)
        assertEquals(ids.distinct(), ids)
        assertTrue(doc.words.all { doc.element(it.id) === it })
    }

    @Test
    fun differentScreenSize_isReadAtItsOwnSize() {
        val doc = adapter.read(Screenshot(1440, 3120).bitmap)
        assertEquals(ImageSize(1440, 3120), doc.image)
        assertTrue(doc.words.any { it.text == "Bottom" && it.box!!.top >= 3120 - 200 })
    }

    @Test
    fun savedImage_isDecodedAtFullSize_thenRead() {
        val shot = Screenshot(1080, 2229)
        val file = File(context.cacheDir, "ocr-adapter-test.png")
        try {
            file.outputStream().use { shot.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val decoded = MlKitOcrAdapter.decodeFullImage(file)
            assertEquals(1080, decoded.width)
            assertEquals(2229, decoded.height)
            assertTrue(adapter.read(decoded).words.any { it.text == "MRU" })
        } finally {
            file.delete()
        }
    }

    @Test
    fun documentFromMlKit_roundTripsThroughJson() {
        val doc = adapter.read(Screenshot(1080, 2229).bitmap)
        assertEquals(doc, OcrJson.decodeDocument(OcrJson.encode(doc)))
    }

    @Test
    fun blankImage_givesAnEmptyDocument() {
        val blank = Bitmap.createBitmap(1080, 2229, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val doc: OcrDocument = adapter.read(blank)
        assertEquals(ImageSize(1080, 2229), doc.image)
        assertTrue(doc.blocks.isEmpty())
    }

    @Test
    fun readingOnTheMainThread_isRefused() {
        val bitmap = Screenshot(1080, 2229).bitmap
        // لا assert داخل الخيط الرئيسي (خطأ هناك يُسقط العملية بدل أن يُفشل الاختبار): نلتقط النتيجة ونفحصها هنا.
        var outcome: Result<OcrDocument>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { outcome = runCatching { adapter.read(bitmap) } }
        val error = outcome!!.exceptionOrNull()
        assertTrue("reading on the main thread must be refused, got $error", error is IllegalStateException)
    }

    // ------------------------------------------------------------------ أدوات

    private data class DrawnWord(val text: String, val box: Rect)

    /** مركز مربع ML Kit داخل موضع الرسم (مع هامش)، وارتفاعه من نفس الحجم. */
    private fun OcrWord.isAt(drawn: Rect): Boolean {
        val b = box ?: return false
        val margin = max(12, drawn.height() / 3)
        val cx = (b.left + b.right) / 2
        val cy = (b.top + b.bottom) / 2
        val centred = cx in (drawn.left - margin)..(drawn.right + margin) && cy in (drawn.top - margin)..(drawn.bottom + margin)
        val sameSize = b.height in (drawn.height() / 2)..(drawn.height() * 2 + margin)
        return centred && sameSize
    }

    /**
     * لقطة شاشة اصطناعية: شريط علوي (مكان WhatsApp Header) وأسطر بشكل إيصال، ونص قرب أسفل الصورة.
     * القيم وهمية. كل كلمة تُرسم وحدها ويُحفظ مربعها الفعلي بالبكسل.
     */
    private class Screenshot(width: Int, height: Int) {
        val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val words = mutableListOf<DrawnWord>()
        private val canvas = Canvas(bitmap)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textSize = 64f
        }

        init {
            canvas.drawColor(Color.WHITE)
            canvas.drawRect(0f, 0f, width.toFloat(), HEADER_BAR.toFloat(), Paint().apply { color = Color.rgb(31, 44, 52) })
            draw("Header Test", x = 160f, baseline = 120f, color = Color.WHITE)
            // صف واحد: تسمية يسارًا وقيمة يمينًا.
            draw("Vous avez envoye", x = 60f, baseline = 700f)
            draw("18 300,00 MRU", x = width - 60f - paint.measureText("18 300,00 MRU"), baseline = 700f)
            draw("Frais 9,17", x = 60f, baseline = 900f)
            draw("Bottom Line", x = 60f, baseline = height - 60f)
        }

        private fun draw(text: String, x: Float, baseline: Float, color: Int = Color.BLACK) {
            paint.color = color
            var cursor = x
            for (word in text.split(' ')) {
                canvas.drawText(word, cursor, baseline, paint)
                val bounds = Rect().also { paint.getTextBounds(word, 0, word.length, it) }
                bounds.offset(cursor.toInt(), baseline.toInt())
                words += DrawnWord(word, bounds)
                cursor += paint.measureText("$word ")
            }
        }

        companion object {
            const val HEADER_BAR = 180
        }
    }
}
