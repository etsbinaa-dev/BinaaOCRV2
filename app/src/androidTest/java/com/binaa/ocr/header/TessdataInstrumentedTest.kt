package com.binaa.ocr.header

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** النماذج داخل الـAPK هي المثبّتة في tessdata.lock، وتُثبَّت محليًا بلا شبكة مع تحقق SHA-256. */
@RunWith(AndroidJUnit4::class)
class TessdataInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun pinnedModels_areAraBestAndEngBest() {
        val pinned = TessdataInstaller.PINNED
        assertEquals(listOf("ara", "eng"), pinned.map { it.language })
        assertEquals(listOf("tesseract-ocr/tessdata_best", "tesseract-ocr/tessdata_best"), pinned.map { it.repository })
        assertEquals("ab9d157d8e38ca00e7e39c7d5363a5239e053f5b0dbdb3167dde9d8124335896", pinned[0].sha256)
        assertEquals("8280aed0782fe27257a68ea10fe7ef324ca0f8d85bd2fd145d1c2b560bcb66ba", pinned[1].sha256)
    }

    @Test
    fun packagedAssets_matchThePinnedSha256() {
        for (m in TessdataInstaller.PINNED) {
            val sha = context.assets.open("tessdata/${m.language}.traineddata").use { TessdataInstaller.sha256(it) }
            assertEquals(m.language, m.sha256, sha)
        }
    }

    @Test
    fun install_isVerified_andRepairsACorruptedModel() {
        val installer = TessdataInstaller(context)
        val dir = File(installer.install(), "tessdata")
        assertTrue(dir.path.startsWith(context.noBackupFilesDir.path))
        for (m in TessdataInstaller.PINNED) assertTrue(m.language, TessdataInstaller.matches(File(dir, "${m.language}.traineddata"), m))
        // نموذج تالف بنفس الحجم ⇒ يُكشف بـSHA-256 ويُستبدل.
        val eng = File(dir, "eng.traineddata")
        val bytes = eng.readBytes().also { it[1000] = (it[1000] + 1).toByte() }
        eng.writeBytes(bytes)
        assertEquals(false, TessdataInstaller.matches(eng, TessdataInstaller.PINNED[1]))
        installer.install()
        assertTrue(TessdataInstaller.matches(eng, TessdataInstaller.PINNED[1]))
    }

    @Test
    fun packagedModelNotMatchingThePin_makesTesseractUnavailable() {
        val wrong = TessdataInstaller.PINNED.map { TessdataInstaller.Model(it.language, it.repository, it.commit, "0".repeat(64), it.bytes) }
        val installer = TessdataInstaller(context, wrong)
        try { installer.install(); fail("a model that does not match its pin must be refused") } catch (e: IllegalStateException) { }
        TesseractLineReader(context, installer).use { reader ->
            assertNull(reader.read(GrayImage(10, 10, IntArray(100) { 255 })))
        }
        // النماذج الصحيحة لم تتأثر.
        TessdataInstaller(context).install()
    }
}
