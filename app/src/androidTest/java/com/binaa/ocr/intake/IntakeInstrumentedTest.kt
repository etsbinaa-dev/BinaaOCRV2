package com.binaa.ocr.intake

import android.content.ContentValues
import android.content.Context
import android.content.Intent
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
import com.binaa.ocr.core.intake.IntakeRejection
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
 * المدخل الوحيد: مشاركة لقطة شاشة. تُطلق المشاركة كما يطلقها WhatsApp (startActivity بـSEND/SEND_MULTIPLE)،
 * فتُختبر أيضًا إعدادات الـManifest (intent-filters و singleTask). الصور هنا اصطناعية تُرسم أثناء الاختبار.
 */
@RunWith(AndroidJUnit4::class)
class IntakeInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val inserted = mutableListOf<Uri>()
    private val intakeDir get() = File(context.filesDir, IntakeStore.DIR_NAME)

    @Before
    fun clean() {
        intakeDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        currentIntake()?.let { activity -> instrumentation.runOnMainSync { activity.viewModel.close() } }
        waitUntil("intake activity closed") { currentIntake() == null }
        inserted.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        intakeDir.deleteRecursively()
    }

    // ------------------------------------------------------------------ الحالات

    @Test
    fun fullScreenshot_isStoredByteForByte_uncropped_andReceivedAutomatically() {
        val bytes = screenshotPng(1080, 2340, Color.rgb(20, 26, 30))
        share(single(insert("screenshot", "image/png", bytes)))

        val state = awaitState { it.phase == IntakePhase.RECEIVED }
        assertNotNull(state.image)
        val image = state.image!!
        assertArrayEquals("stored bytes must equal the shared bytes", bytes, image.file.readBytes())
        assertEquals(1080, image.width)
        assertEquals(2340, image.height)
        assertEquals(ImageFormat.PNG, image.format)
        assertEquals(intakeDir.canonicalPath, image.file.parentFile!!.canonicalPath)
        assertNotNull("preview for display", state.preview)
        assertEquals(listOf(image.file.name), intakeDir.list()!!.toList())
    }

    @Test
    fun multipleImages_areRejected_andNothingIsStored() {
        val a = insert("a", "image/png", screenshotPng(1080, 2340, Color.WHITE))
        val b = insert("b", "image/png", screenshotPng(1080, 2340, Color.BLACK))
        share(
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/png"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, b))
            },
        )
        val state = awaitState { it.phase == IntakePhase.REJECTED }
        assertEquals(IntakeRejection.MULTIPLE_IMAGES, state.rejection)
        assertNull(state.image)
        assertTrue("no file may be stored", intakeDir.list().isNullOrEmpty())
    }

    @Test
    fun fileThatIsNotAnImage_isRejected_andNothingIsStored() {
        share(single(insert("fake", "image/jpeg", "this is not an image".toByteArray())))
        val state = awaitState { it.phase == IntakePhase.REJECTED }
        assertEquals(IntakeRejection.NOT_AN_IMAGE, state.rejection)
        assertTrue("no file may be stored", intakeDir.list().isNullOrEmpty())
    }

    @Test
    fun newShareWhileReceived_asksFirst_thenReplaces() {
        share(single(insert("first", "image/png", screenshotPng(1080, 2340, Color.WHITE))))
        val first = awaitState { it.phase == IntakePhase.RECEIVED }.image!!

        val secondBytes = screenshotPng(1080, 2229, Color.BLACK)
        share(single(insert("second", "image/png", secondBytes)))
        val asking = awaitState { it.pendingReplace != null }
        assertEquals("the current screenshot stays until the user decides", first, asking.image)

        instrumentation.runOnMainSync { currentIntake()!!.viewModel.confirmReplace() }
        val replaced = awaitState { it.phase == IntakePhase.RECEIVED && it.image != null && it.image != first }
        assertArrayEquals(secondBytes, replaced.image!!.file.readBytes())
        assertFalse("the replaced screenshot is deleted", first.file.exists())
        assertNull(replaced.pendingReplace)
    }

    @Test
    fun newShareWhileReceived_keepCurrent_keepsTheFirstScreenshot() {
        share(single(insert("first", "image/png", screenshotPng(1080, 2340, Color.WHITE))))
        val first = awaitState { it.phase == IntakePhase.RECEIVED }.image!!
        share(single(insert("second", "image/png", screenshotPng(1080, 2340, Color.BLACK))))
        awaitState { it.pendingReplace != null }

        instrumentation.runOnMainSync { currentIntake()!!.viewModel.keepCurrent() }
        val kept = awaitState { it.pendingReplace == null }
        assertEquals(first, kept.image)
        assertEquals(listOf(first.file.name), intakeDir.list()!!.toList())
    }

    @Test
    fun close_deletesTheReceivedScreenshot() {
        share(single(insert("screenshot", "image/png", screenshotPng(1080, 2340, Color.WHITE))))
        val image = awaitState { it.phase == IntakePhase.RECEIVED }.image!!
        instrumentation.runOnMainSync { currentIntake()!!.viewModel.close() }
        waitUntil("intake activity closed") { currentIntake() == null }
        assertFalse(image.file.exists())
    }

    // ------------------------------------------------------------------ أدوات

    private fun single(uri: Uri) = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
    }

    /** كما يفعل WhatsApp: مهمة جديدة + إذن قراءة مؤقت، والتوجيه بالـintent-filter لا بالاسم الصريح. */
    private fun share(intent: Intent) {
        intent.setPackage(context.packageName)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val resolved = context.packageManager.resolveActivity(intent, 0)
        assertEquals("share must resolve to IntakeActivity", IntakeActivity::class.java.name, resolved?.activityInfo?.name)
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

    private fun insert(name: String, mime: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "binaa_v2_test_${name}_${System.nanoTime()}")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
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

    /** لقطة اصطناعية بحجم شاشة هاتف: شريط علوي (مكان Header) ومستطيل (مكان الإيصال). */
    private fun screenshotPng(width: Int, height: Int, background: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        val paint = Paint().apply { color = Color.rgb(120, 130, 140) }
        canvas.drawRect(0f, 0f, width.toFloat(), height * 0.08f, paint)
        paint.color = Color.WHITE
        canvas.drawRect(width * 0.1f, height * 0.25f, width * 0.9f, height * 0.7f, paint)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
