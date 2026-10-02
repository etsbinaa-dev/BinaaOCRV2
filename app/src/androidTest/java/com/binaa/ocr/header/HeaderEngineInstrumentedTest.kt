package com.binaa.ocr.header

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Header Engine على الجهاز بـTesseract وML Kit الحقيقيين، على لقطات اصطناعية. */
@RunWith(AndroidJUnit4::class)
class HeaderEngineInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val engine = HeaderEngine(context)

    @After
    fun close() = engine.close()

    private fun read(name: String): HeaderReading {
        val bitmap = SyntheticHeader.viewer(name)
        return try {
            // الكاشف يقبل اللقطة أولًا، فأي فشل لاحق سببه OCR أو القرار.
            assertNull("detector", HeaderDetector.detect(BitmapPixels.toPixelImage(bitmap)).rejection)
            engine.read(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun latinName_isReadByBothEngines_andFoundExactly() {
        val r = read("Karim Saleh")
        assertEquals("$r", NameStatus.FOUND, r.result.status)
        assertEquals("Karim Saleh", r.result.name)
        assertNull(r.failure)
        assertEquals(listOf(OcrSource.TESSERACT, OcrSource.MLKIT), r.evidence.map { it.source })
        // Tesseract: ثقة لكل كلمة، وصناديق داخل الصورة المجهّزة (ارتفاع 40 + إطار 20).
        val tess = r.evidence[0]
        assertEquals("tesseract4android", tess.engine)
        assertTrue(tess.words.isNotEmpty() && tess.words.all { it.confidence in 0..100 && it.left >= 0 && it.right <= it.engineBox.r && it.engineBox.b <= 80 })
        assertTrue(r.evidence[1].words.all { it.confidence == null })
    }

    @Test
    fun arabicName_isFoundExactly_orNotFound_neverAnotherName() {
        val r = read("سالم")
        if (r.result.status == NameStatus.FOUND) assertEquals("سالم", r.result.name)
        else assertEquals(NameReason.NAME_UNREADABLE, r.result.reason)
        assertNull(r.failure)
        assertTrue(r.evidence[0].words.isNotEmpty())
    }

    @Test
    fun noHeader_isNotDetected_andNoOcrRuns() {
        val bitmap = SyntheticHeader.blank()
        val r = try { engine.read(bitmap) } finally { bitmap.recycle() }
        assertEquals(NameStatus.NOT_FOUND, r.result.status)
        assertEquals(NameReason.HEADER_NOT_DETECTED, r.result.reason)
        assertTrue(r.evidence.isEmpty())
    }

    @Test
    fun mlKitFailure_onTheMainThread_isNameUnreadable_notAnException() {
        // ML Kit يرفض الانتظار على الخيط الرئيسي: الفشل يصير NAME_UNREADABLE، ولا استثناء يصل إلى الواجهة.
        val bitmap = SyntheticHeader.viewer("Karim Saleh")
        var r: HeaderReading? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { r = engine.read(bitmap) }
        bitmap.recycle()
        assertEquals(NameStatus.NOT_FOUND, r!!.result.status)
        assertEquals(NameReason.NAME_UNREADABLE, r!!.result.reason)
        assertEquals(ReadFailure.MLKIT_FAILED, r!!.failure)
    }

    @Test
    fun latinFirstReader_initialisesOffline_andReadsTheNameLine() {
        // القراءة الثانية للأسماء اللاتينية (eng+ara) بنفس النموذجين المثبّتين.
        val bitmap = SyntheticHeader.viewer("Karim Saleh")
        val name = HeaderDetector.detect(BitmapPixels.toPixelImage(bitmap)).nameImage!!
        bitmap.recycle()
        TesseractLineReader(context, languages = TesseractLineReader.LATIN_FIRST).use { reader ->
            val words = reader.read(NamePrep.prepare(name))
            assertTrue("$words", words != null && words.isNotEmpty() && words.all { it.confidence in 0..100 })
        }
    }

    @Test
    fun readingTwice_reusesTheSameTesseract_andGivesTheSameResult() {
        val first = read("Karim Saleh")
        val second = read("Karim Saleh")
        assertEquals(first.result.name, second.result.name)
        assertEquals(first.evidence[0].words.map { it.left to it.right }, second.evidence[0].words.map { it.left to it.right })
    }
}
