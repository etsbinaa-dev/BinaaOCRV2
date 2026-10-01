package com.binaa.ocr.intake

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.binaa.ocr.core.intake.ImageFormat
import com.binaa.ocr.core.intake.IntakePhase
import com.binaa.ocr.header.NameReason
import com.binaa.ocr.header.NameStatus
import com.binaa.ocr.header.SyntheticHeader
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * المشاركة ← الاستلام الخاص ← Header Engine ← النتيجة على الشاشة، كما يطلقها WhatsApp.
 * كل الصور اصطناعية تُرسم أثناء الاختبار، والأسماء مخترعة.
 */
@RunWith(AndroidJUnit4::class)
class IntakeHeaderInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val inserted = mutableListOf<Uri>()
    private val intakeDir get() = File(context.filesDir, IntakeStore.DIR_NAME)

    @Before
    fun clean() {
        intakeDir.deleteRecursively()
        IntakeViewModel.readHeaderOverride = null
    }

    @After
    fun tearDown() {
        IntakeViewModel.readHeaderOverride = null
        currentIntake()?.let { activity -> instrumentation.runOnMainSync { activity.viewModel.close() } }
        waitUntil("intake activity closed") { currentIntake() == null }
        inserted.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        intakeDir.deleteRecursively()
    }

    @Test
    fun sharedViewerScreenshot_reachesHeaderEngine_andTheNameIsFound_offline() {
        // لا صلاحية شبكة: كل ما يلي يجري محليًا (العملية لا تستطيع فتح socket: NetworkDeniedInstrumentedTest).
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.INTERNET))
        share(insert("viewer", png(SyntheticHeader.viewer(NAME))))
        val state = awaitState { it.header is HeaderUi.Done }
        val result = (state.header as HeaderUi.Done).result
        assertEquals("$result", NameStatus.FOUND, result.status)
        assertEquals(NAME, result.name)
        assertEquals(IntakePhase.RECEIVED, state.phase)
    }

    @Test
    fun nameInTheReceiptBody_isNeverTaken_withoutAHeader() {
        // لقطة بلا رأس Image Viewer، والاسم مكتوب في جسمها: الاسم لا يُستنتج من الإيصال.
        val bitmap = SyntheticHeader.blank()
        Canvas(bitmap).drawText(NAME, 100f, 900f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f })
        share(insert("receipt", png(bitmap)))
        val result = (awaitState { it.header is HeaderUi.Done }.header as HeaderUi.Done).result
        assertEquals(NameStatus.NOT_FOUND, result.status)
        assertEquals(NameReason.HEADER_NOT_DETECTED, result.reason)
        assertNull(result.name)
    }

    @Test
    fun theRealImage_staysPrivate_andUnchanged_andIsDeletedOnClose() {
        val bytes = png(SyntheticHeader.viewer(NAME))
        share(insert("viewer", bytes))
        val state = awaitState { it.header is HeaderUi.Done }
        val image = state.image!!
        // الملف الوحيد في مجلد الاستلام الخاص، بايتًا ببايت كما شورك (القراءة في الذاكرة فقط).
        assertArrayEquals(bytes, image.file.readBytes())
        assertEquals(listOf(image.file.name), intakeDir.list()!!.toList())
        // لا نسخة أخرى من الصورة في أي مجلد للتطبيق (خاص أو خارجي): لا ملف صورة غير ملف الاستلام.
        val roots = listOfNotNull(context.filesDir, context.noBackupFilesDir, context.cacheDir, context.codeCacheDir) +
            context.getExternalFilesDirs(null).filterNotNull() + context.externalCacheDirs.filterNotNull()
        val images = roots.flatMap { root -> root.walkTopDown().filter { it.isFile }.toList() }
            .filter { f -> ImageFormat.sniff(head(f)) != null }
            .map { it.canonicalPath }.distinct()
        assertEquals(listOf(image.file.canonicalPath), images)

        val activity = currentIntake()!!
        instrumentation.runOnMainSync { activity.viewModel.close() }
        waitUntil("intake activity closed") { currentIntake() == null }
        assertFalse(image.file.exists())
    }

    @Test
    fun headerEngineFailure_isNotFound_andTheAppKeepsRunning() {
        IntakeViewModel.readHeaderOverride = { throw IllegalStateException("engine failure") }
        share(insert("viewer", png(SyntheticHeader.viewer(NAME))))
        val state = awaitState { it.header is HeaderUi.Done }
        val result = (state.header as HeaderUi.Done).result
        assertEquals(NameStatus.NOT_FOUND, result.status)
        assertNull(result.name)
        assertNotNull(result.reason)
        // الشاشة باقية والصورة محفوظة، والإغلاق يعمل.
        assertNotNull(currentIntake())
        assertTrue(state.image!!.file.exists())
    }

    // ------------------------------------------------------------------ أدوات

    private fun head(file: File): ByteArray {
        val buf = ByteArray(ImageFormat.SNIFF_BYTES)
        val n = file.inputStream().use { it.read(buf) }
        return buf.copyOf(maxOf(n, 0))
    }

    private fun png(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun share(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            setPackage(context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        assertEquals(IntakeActivity::class.java.name, context.packageManager.resolveActivity(intent, 0)?.activityInfo?.name)
        context.startActivity(intent)
    }

    private fun currentIntake(): IntakeActivity? {
        var found: IntakeActivity? = null
        instrumentation.runOnMainSync {
            val registry = ActivityLifecycleMonitorRegistry.getInstance()
            found = listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED, Stage.STOPPED, Stage.CREATED)
                .flatMap { registry.getActivitiesInStage(it) }
                .filterIsInstance<IntakeActivity>()
                .firstOrNull { !it.isFinishing }
        }
        return found
    }

    private fun awaitState(condition: (IntakeUiState) -> Boolean): IntakeUiState {
        var last: IntakeUiState? = null
        waitUntil("intake state") {
            last = currentIntake()?.viewModel?.state?.value
            last?.let(condition) == true
        }
        return last!!
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun insert(name: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "binaa_v2_test_${name}_${System.nanoTime()}")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BinaaOcrV2Test")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        inserted += uri
        return uri
    }

    private companion object {
        const val NAME = "Karim Saleh"
        const val TIMEOUT_MS = 60_000L
    }
}
