package com.binaa.ocr.ui.home

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import com.binaa.ocr.R
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.binaa.ocr.core.archive.PaymentRecord
import com.binaa.ocr.core.archive.PaymentStatus
import com.binaa.ocr.core.archive.PaymentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class HomeTab { PENDING, ARCHIVE }

data class HomeState(
    val pending: List<PaymentRecord> = emptyList(),
    val archived: List<PaymentRecord> = emptyList(),
    val tab: HomeTab = HomeTab.PENDING,
    /** صورة الإيصال المفتوحة (للعرض فقط، مصغّرة). */
    val viewing: Pair<PaymentRecord, ImageBitmap?>? = null,
    /** دفعة تنتظر تأكيد الحذف (ضغطة مطوّلة). */
    val deleting: PaymentRecord? = null,
    /** صورة الإيصال المفتوحة حُذفت (أرشيف أقدم من المدة). */
    val imageMissing: Boolean = false,
    /** نتيجة التصدير أو الاسترجاع: (نص، عدد). */
    val notice: Pair<Int, Int?>? = null,
)

/** «للإدخال» و«الأرشيف» من الأرشيف المحلي. كل قراءة وكتابة خارج الخيط الرئيسي. */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val store = PaymentStore(File(app.filesDir, PaymentStore.DIR_NAME))
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val all = withContext(Dispatchers.IO) { runCatching { store.pruneArchivedImages() }; store.list() }
            _state.update { s ->
                s.copy(pending = all.filter { it.status == PaymentStatus.PENDING }, archived = all.filter { it.status == PaymentStatus.ARCHIVED })
            }
            PendingBadge.update(getApplication(), _state.value.pending.size)
        }
    }

    fun selectTab(tab: HomeTab) = _state.update { it.copy(tab = tab) }

    /** «تم»: أُدخلت في المحاسبة ⇒ إلى الأرشيف. */
    fun markEntered(id: String) = change { store.markEntered(id) }

    /** إرجاع من الأرشيف إلى «للإدخال». */
    fun markPending(id: String) = change { store.markPending(id) }

    /** ضغطة مطوّلة ⇒ سؤال تأكيد قبل الحذف. */
    fun requestDelete(record: PaymentRecord) = _state.update { it.copy(deleting = record) }

    fun cancelDelete() = _state.update { it.copy(deleting = null) }

    /** حذف نهائي للسجل وصورة إيصاله. */
    fun confirmDelete() {
        val record = _state.value.deleting ?: return
        _state.update { it.copy(deleting = null) }
        change { store.delete(record.id) }
    }

    fun open(record: PaymentRecord) {
        val file = store.imageOf(record)
        _state.update { it.copy(viewing = record to null, imageMissing = false) }
        viewModelScope.launch {
            val missing = withContext(Dispatchers.IO) { !file.isFile }
            val image = if (missing) null else withContext(Dispatchers.IO) { decode(file) }
            _state.update { s -> if (s.viewing?.first?.id == record.id) s.copy(viewing = record to image, imageMissing = missing) else s }
        }
    }

    fun closeImage() = _state.update { it.copy(viewing = null) }

    /** يكتب نسخة احتياطية (ZIP) في [uri] الذي اختاره المستخدم. */
    fun exportTo(uri: Uri) = backup {
        val n = getApplication<Application>().contentResolver.openOutputStream(uri)!!.use { store.exportTo(it) }
        R.string.backup_exported to n
    }

    /** يضيف من نسخة احتياطية ما ليس موجودًا (لا يستبدل شيئًا). */
    fun importFrom(uri: Uri) = backup {
        val n = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { store.importFrom(it) }
        R.string.backup_imported to n
    }

    private fun backup(action: () -> Pair<Int, Int?>) {
        viewModelScope.launch {
            val notice = withContext(Dispatchers.IO) { runCatching(action).getOrElse { R.string.backup_failed to null } }
            _state.update { it.copy(notice = notice) }
            refresh()
        }
    }

    private fun change(action: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching(action) }
            refresh()
        }
    }

    private fun decode(file: File): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()
}
