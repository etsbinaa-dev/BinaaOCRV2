package com.binaa.ocr.intake

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.binaa.ocr.core.intake.IntakePhase
import com.binaa.ocr.core.intake.IntakePolicy
import com.binaa.ocr.core.intake.IntakeRejection
import com.binaa.ocr.core.intake.IntakeSession
import com.binaa.ocr.core.intake.NewShareDecision
import com.binaa.ocr.core.intake.ShareAction
import com.binaa.ocr.core.intake.ShareCheck
import com.binaa.ocr.header.HeaderEngine
import com.binaa.ocr.header.HeaderResult
import com.binaa.ocr.header.NameReason
import com.binaa.ocr.header.NameStatus
import com.binaa.ocr.ocr.MlKitOcrAdapter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** اسم الطرف الآخر من رأس WhatsApp في اللقطة المستلمة. */
sealed interface HeaderUi {
    /** Header Engine يعمل على اللقطة. */
    data object Reading : HeaderUi

    /** النتيجة: FOUND بالاسم، أو NOT_FOUND بالسبب. لا مرشّحين ولا اختيار تلقائي. */
    data class Done(val result: HeaderResult) : HeaderUi
}

/** مشاركة كما وصلت من Android، قبل أي قرار. */
data class ShareRequest(val action: ShareAction, val uris: List<Uri>)

data class IntakeUiState(
    val phase: IntakePhase = IntakePhase.IDLE,
    val image: StoredImage? = null,
    /** معاينة للعرض فقط (مُصغَّرة). الصورة الأصلية في [image] لا تُمس. */
    val preview: ImageBitmap? = null,
    val rejection: IntakeRejection? = null,
    /** مشاركة جديدة تنتظر قرار المستخدم (استبدال أم متابعة). */
    val pendingReplace: ShareRequest? = null,
    /** أُغلقت الشاشة: لا صورة باقية. */
    val closed: Boolean = false,
    /** قراءة الاسم من رأس WhatsApp (بعد الاستلام فقط). */
    val header: HeaderUi? = null,
)

class IntakeViewModel(app: Application) : AndroidViewModel(app) {

    private val store = IntakeStore(app.filesDir, app.contentResolver)
    private val _state = MutableStateFlow(IntakeUiState())
    val state: StateFlow<IntakeUiState> = _state.asStateFlow()
    private var job: Job? = null
    /** محلي بالكامل (Tesseract + ML Kit). التهيئة الثقيلة (النماذج) عند أول قراءة فقط. */
    private val headerEngine = HeaderEngine(app)

    /** كل مشاركة تصل هنا (أول فتح أو onNewIntent). */
    fun onShare(request: ShareRequest) {
        when (IntakeSession.onNewShare(_state.value.phase)) {
            NewShareDecision.ASK_REPLACE -> _state.update { it.copy(pendingReplace = request) }
            NewShareDecision.START -> start(request)
        }
    }

    fun confirmReplace() {
        val pending = _state.value.pendingReplace ?: return
        discardCurrent()
        start(pending)
    }

    fun keepCurrent() {
        _state.update { it.copy(pendingReplace = null) }
    }

    /** إلغاء أو إغلاق: لا يبقى أي أثر للصورة (الحفظ يأتي في مرحلة الأرشيف). */
    fun close() {
        discardCurrent()
        _state.value = IntakeUiState(closed = true)
    }

    private fun discardCurrent() {
        job?.cancel()
        job = null
        store.delete(_state.value.image)
    }

    private fun start(request: ShareRequest) {
        when (val check = IntakePolicy.check(request.action, request.uris.size)) {
            is ShareCheck.Rejected -> {
                _state.value = IntakeUiState(phase = IntakePhase.REJECTED, rejection = check.reason)
                return
            }
            ShareCheck.SingleImage -> Unit
        }
        _state.value = IntakeUiState(phase = IntakePhase.RECEIVING)
        job = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { store.receive(request.uris.single()) }
            when (result) {
                is IntakeResult.Rejected -> _state.update {
                    it.copy(phase = IntakePhase.REJECTED, rejection = result.reason, image = null, preview = null)
                }
                is IntakeResult.Stored -> {
                    val preview = withContext(Dispatchers.IO) { decodePreview(result.image) }
                    _state.update { it.copy(phase = IntakePhase.RECEIVED, image = result.image, preview = preview, header = HeaderUi.Reading) }
                    // التحليل يبدأ تلقائيًا: اسم الطرف الآخر من رأس WhatsApp فقط (قراءة الإيصال في مراحل تالية).
                    val header = withContext(Dispatchers.Default) { readHeader(result.image.file) }
                    _state.update { if (it.image == result.image) it.copy(header = HeaderUi.Done(header)) else it }
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) { store.deleteExpired(System.currentTimeMillis()) }
    }

    override fun onCleared() {
        // الـViewModel ينتهي دون إغلاق صريح (مثلًا أُزيلت المهمة): لا نترك الصورة.
        if (!_state.value.closed) store.delete(_state.value.image)
        // الإغلاق ينتظر أي قراءة Tesseract جارية: خارج الخيط الرئيسي.
        Thread { headerEngine.close() }.start()
    }

    /**
     * Header Engine على اللقطة المحفوظة كاملة كما هي (فك ترميز في الذاكرة فقط، بلا قص ولا تصغير).
     * أي فشل (فك الترميز، الذاكرة، المحرك) ⇒ NOT_FOUND، ولا استثناء يصل إلى الواجهة. لا سجلات.
     */
    private fun readHeader(file: File): HeaderResult = try {
        readHeaderOverride?.invoke(file) ?: run {
            val bitmap = MlKitOcrAdapter.decodeFullImage(file)
            try { headerEngine.read(bitmap).result } finally { bitmap.recycle() }
        }
    } catch (e: Exception) {
        NOT_READ
    } catch (e: OutOfMemoryError) {
        NOT_READ
    }

    private fun decodePreview(image: StoredImage): ImageBitmap? {
        var sample = 1
        while (maxOf(image.width, image.height) / (sample * 2) >= PREVIEW_MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(image.file.path, options)?.asImageBitmap()
    }

    companion object {
        private const val PREVIEW_MAX_SIDE = 1600

        /** فشل قبل أن يصل المحرك إلى قرار: لا نعرف إن كان الرأس موجودًا ⇒ تلميح «خذ لقطة للشاشة كاملة». */
        private val NOT_READ = HeaderResult(NameStatus.NOT_FOUND, null, NameReason.HEADER_NOT_DETECTED, null, null, null)

        /** للاختبارات فقط: بديل للمحرك (مثلًا محرك يرمي استثناءً). */
        @VisibleForTesting
        @Volatile
        var readHeaderOverride: ((File) -> HeaderResult)? = null
    }
}
