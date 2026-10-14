package com.binaa.ocr.intake

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import android.graphics.Bitmap
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
import com.binaa.ocr.core.archive.PaymentRecord
import com.binaa.ocr.core.archive.PaymentStore
import com.binaa.ocr.core.receipt.FieldReason
import com.binaa.ocr.core.receipt.ReceiptField
import com.binaa.ocr.core.receipt.ReceiptReader
import com.binaa.ocr.core.receipt.ReceiptReading
import com.binaa.ocr.header.HeaderEngine
import com.binaa.ocr.header.HeaderReading
import com.binaa.ocr.header.HeaderResult
import com.binaa.ocr.header.NameReason
import com.binaa.ocr.header.NameStatus
import java.time.LocalDate
import java.time.LocalTime
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
    data class Done(val result: HeaderResult, val rows: List<String> = emptyList()) : HeaderUi
}

/** المبلغ والتاريخ من الإيصال نفسه (بعد الاسم). */
sealed interface ReceiptUi {
    data object Reading : ReceiptUi

    /** [rows] = صفوف الأرقام كما قرأها ML Kit، للتشخيص على الشاشة فقط (لا تُسجَّل ولا تُحفظ). */
    data class Done(val reading: ReceiptReading, val rows: List<String> = emptyList()) : ReceiptUi
}

/**
 * ما سيُحفظ بعد مراجعة المستخدم: نصوص قابلة للتعديل، مملوءة مسبقًا بما قُرئ بيقين (FOUND) فقط.
 * «غير مؤكد» لا يملأ الحقل: مرشّحوه يظهرون ليختار المستخدم بلمسة. لا حفظ قبل اكتمال الاسم والمبلغ والتاريخ والاتجاه.
 */
data class PaymentDraft(
    val name: String = "",
    val amount: String = "",
    val amountCandidates: List<String> = emptyList(),
    val date: String = "",
    val time: String = "",
    val dateCandidates: List<Pair<String, String>> = emptyList(),
    /** BANKILY / BIMBANK / SEDAD / BCIPAY / MASRVI / OTHER، أو null. */
    val provider: String? = null,
    val providerCandidates: List<String> = emptyList(),
    /** INCOMING / OUTGOING، أو null حتى يختار المستخدم. */
    val direction: String? = null,
) {
    val amountCents: Long? get() = PaymentStore.parseAmount(amount)
    val localDate: LocalDate? get() = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
    val localTime: LocalTime? get() = time.trim().takeIf { it.isNotEmpty() }?.let { t -> runCatching { LocalTime.parse(t) }.getOrNull() }
    private val timeValid: Boolean get() = time.isBlank() || localTime != null
    val canSave: Boolean get() = name.isNotBlank() && amountCents != null && localDate != null && timeValid && direction != null
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
    /** قراءة المبلغ والتاريخ من الإيصال (بعد الاسم). */
    val receipt: ReceiptUi? = null,
    /** المراجعة قبل الحفظ (بعد انتهاء القراءتين). */
    val draft: PaymentDraft? = null,
    /** حُفظت الدفعة في «للإدخال» (والصورة نُقلت إلى الأرشيف). */
    val saved: Boolean = false,
    /** دفعة محفوظة من قبل تشبه هذه (نفس اللقطة، أو نفس الاسم والمبلغ والتاريخ): تنبيه فقط، الحفظ يبقى ممكنًا. */
    val duplicate: PaymentStore.Duplicate? = null,
)

class IntakeViewModel(app: Application) : AndroidViewModel(app) {

    private val store = IntakeStore(app.filesDir, app.contentResolver)
    private val payments = PaymentStore(File(app.filesDir, PaymentStore.DIR_NAME))
    private val _state = MutableStateFlow(IntakeUiState())
    val state: StateFlow<IntakeUiState> = _state.asStateFlow()
    private var job: Job? = null
    /** بصمة اللقطة الحالية والدفعات المحفوظة عند استلامها: للتنبيه من التكرار دون قراءة الملفات مع كل حرف. */
    private var imageHash: String? = null
    private var savedRecords: List<PaymentRecord> = emptyList()
    /** محلي بالكامل (Tesseract + ML Kit). التهيئة الثقيلة (النماذج) عند أول قراءة فقط. */
    private val headerEngine = HeaderEngine(app)
    /** ML Kit على اللقطة كاملة (MlKitOcrAdapter كما هو)، محلي بالكامل. */
    private val documentOcr = MlKitOcrAdapter()

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

    /** تعديل المراجعة (حقل نصي أو اختيار مرشّح). */
    fun editDraft(change: (PaymentDraft) -> PaymentDraft) {
        _state.update { s -> s.draft?.let { d -> change(d).let { nd -> s.copy(draft = nd, duplicate = duplicateOf(nd)) } } ?: s }
    }

    /** «حفظ»: الدفعة إلى «للإدخال»، وصورة الإيصال تُنقل إلى الأرشيف (لا تُحذف). ثم تُغلق الشاشة. */
    fun save() {
        val current = _state.value
        val draft = current.draft?.takeIf { it.canSave } ?: return
        val image = current.image ?: return
        if (current.saved) return
        _state.update { it.copy(saved = true) }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    payments.save(draft.name, checkNotNull(draft.amountCents), checkNotNull(draft.localDate), draft.localTime, draft.provider, draft.direction, image.file)
                }.isSuccess
            }
            if (ok) {
                runCatching { com.binaa.ocr.ui.home.PendingBadge.update(getApplication(), withContext(Dispatchers.IO) { payments.pending().size }) }
                job?.cancel()
                _state.value = IntakeUiState(closed = true, saved = true)
            } else {
                _state.update { it.copy(saved = false) }
            }
        }
    }

    private fun duplicateOf(d: PaymentDraft): PaymentStore.Duplicate? =
        payments.duplicatesOf(imageHash, d.name, d.amountCents, d.localDate, savedRecords).firstOrNull()

    private fun discardCurrent() {
        job?.cancel()
        job = null
        imageHash = null
        savedRecords = emptyList()
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
                    _state.update {
                        it.copy(phase = IntakePhase.RECEIVED, image = result.image, preview = preview, header = HeaderUi.Reading, receipt = ReceiptUi.Reading)
                    }
                    // التحليل يبدأ تلقائيًا: الاسم من رأس WhatsApp أولًا، ثم المبلغ والتاريخ من الإيصال. فك ترميز واحد في الذاكرة.
                    val bitmap = withContext(Dispatchers.IO) { decodeOrNull(result.image.file) }
                    withContext(Dispatchers.IO) {
                        imageHash = runCatching { PaymentStore.sha256(result.image.file) }.getOrNull()
                        savedRecords = runCatching { payments.list() }.getOrDefault(emptyList())
                    }
                    try {
                        val (header, headerRows) = withContext(Dispatchers.Default) { readHeader(result.image.file, bitmap) }
                        _state.update { if (it.image == result.image) it.copy(header = HeaderUi.Done(header, headerRows)) else it }
                        val (receipt, rows) = withContext(Dispatchers.Default) { readReceipt(bitmap, header) }
                        val draft = draftOf(header, receipt)
                        _state.update { if (it.image == result.image) it.copy(receipt = ReceiptUi.Done(receipt, rows), draft = draft, duplicate = duplicateOf(draft)) else it }
                    } finally {
                        bitmap?.recycle()
                    }
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) { store.deleteExpired(System.currentTimeMillis()) }
    }

    override fun onCleared() {
        // الـViewModel ينتهي دون إغلاق صريح (مثلًا أُزيلت المهمة): لا نترك الصورة.
        if (!_state.value.closed) store.delete(_state.value.image)
        // الإغلاق ينتظر أي قراءة Tesseract جارية: خارج الخيط الرئيسي.
        Thread { headerEngine.close(); documentOcr.close() }.start()
    }

    /** المراجعة الأولى: ما قُرئ بيقين فقط يملأ الحقول؛ «غير مؤكد» يعرض مرشّحيه. */
    private fun draftOf(header: HeaderResult, receipt: ReceiptReading): PaymentDraft {
        fun hhmm(t: LocalTime?) = t?.let { it.hour.toString().padStart(2, '0') + ":" + it.minute.toString().padStart(2, '0') } ?: ""
        val amount = receipt.amount.value
        val date = receipt.date.value
        return PaymentDraft(
            name = header.name.orEmpty(),
            amount = amount?.let { amountText(it.cents) }.orEmpty(),
            amountCandidates = receipt.amount.candidates.map { amountText(it.cents) },
            date = date?.date?.toString().orEmpty(),
            time = date?.let { hhmm(it.time) }.orEmpty(),
            dateCandidates = receipt.date.candidates.map { it.date.toString() to hhmm(it.time) },
            provider = receipt.provider.value?.name,
            providerCandidates = receipt.provider.candidates.map { it.name },
            direction = header.direction?.name,
        )
    }

    private fun amountText(cents: Long) = (cents / 100).toString() + if (cents % 100 == 0L) "" else "." + (cents % 100).toString().padStart(2, '0')

    private fun decodeOrNull(file: File): Bitmap? = try {
        MlKitOcrAdapter.decodeFullImage(file)
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    /**
     * المبلغ والتاريخ: ML Kit على اللقطة كاملة ثم [ReceiptReader]، تحت رأس WhatsApp فقط (تاريخ الرسالة فيه لا يُقرأ).
     * أي فشل ⇒ NOT_FOUND، بلا استثناء ولا سجلات.
     */
    private fun readReceipt(bitmap: Bitmap?, header: HeaderResult): Pair<ReceiptReading, List<String>> = try {
        readReceiptOverride?.invoke()?.let { it to emptyList() } ?: run {
            checkNotNull(bitmap)
            val bodyTop = header.layout?.headerBar?.b ?: (bitmap.height * HEADER_FALLBACK).toInt()
            val document = documentOcr.read(bitmap)
            ReceiptReader.read(document, bodyTop) to ReceiptReader.bodyRows(document, bodyTop)
        }
    } catch (e: Exception) {
        RECEIPT_NOT_READ to emptyList()
    } catch (e: OutOfMemoryError) {
        RECEIPT_NOT_READ to emptyList()
    }

    /**
     * Header Engine على اللقطة المحفوظة كاملة كما هي (فك ترميز في الذاكرة فقط، بلا قص ولا تصغير).
     * أي فشل (فك الترميز، الذاكرة، المحرك) ⇒ NOT_FOUND، ولا استثناء يصل إلى الواجهة. لا سجلات.
     */
    private fun readHeader(file: File, bitmap: Bitmap?): Pair<HeaderResult, List<String>> = try {
        readHeaderOverride?.invoke(file)?.let { it to emptyList() }
            ?: headerEngine.read(checkNotNull(bitmap)).let { it.result to headerRows(it) }
    } catch (e: Exception) {
        NOT_READ to emptyList()
    } catch (e: OutOfMemoryError) {
        NOT_READ to emptyList()
    }

    /**
     * للتشخيص فقط (يُعرض على شاشة المستخدم، لا يُسجَّل ولا يُحفظ): قرار الاسم، ثم كلمات كل محرك بثقتها وحدّيها
     * في الصورة المجهّزة، من اليسار إلى اليمين.
     */
    private fun headerRows(reading: HeaderReading): List<String> {
        val r = reading.result
        val d = r.decision
        val head = buildString {
            append("name: ").append(r.reason ?: r.status)
            r.detectorRejection?.let { append(" rejection=").append(it) }
            reading.failure?.let { append(" failure=").append(it) }
            if (d != null) {
                append(" code=").append(d.code)
                if (d.notes.isNotEmpty()) append(" notes=").append(d.notes.joinToString(","))
                if (d.dropped.isNotEmpty()) append(" dropped=").append(d.dropped.joinToString(",") { "${it.index}:${it.code}" })
            }
        }
        // القراءة الثانية (لاتيني أولًا) تظهر «TESSERACT 2».
        return listOf(head) + reading.evidence.mapIndexed { i, e ->
            val pass = reading.evidence.take(i).count { it.source == e.source }
            "${e.source}${if (pass > 0) " ${pass + 1}" else ""}: " + e.words.joinToString("  ") { w -> "«${w.text}»" + (w.confidence?.let { " $it" } ?: "") + " x${w.left}-${w.right}" }
        }
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

        /** للاختبارات فقط: بديل لقراءة الإيصال (مثلًا قراءة ترمي استثناءً). */
        @VisibleForTesting
        @Volatile
        var readReceiptOverride: (() -> ReceiptReading)? = null

        /** بلا رأس مكتشف: يُتجاهَل أعلى اللقطة بارتفاع شريط WhatsApp تقريبًا. */
        private const val HEADER_FALLBACK = 0.09

        private val RECEIPT_NOT_READ = ReceiptReading(
            ReceiptField.notFound(FieldReason.NO_VALUE),
            ReceiptField.notFound(FieldReason.NO_VALUE),
            ReceiptField.notFound(FieldReason.NO_VALUE),
        )
    }
}
