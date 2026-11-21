package com.binaa.ocr.sync

import android.app.PendingIntent
import android.content.Context
import com.binaa.ocr.core.archive.PaymentStore
import com.binaa.ocr.core.sync.SyncAction
import com.binaa.ocr.core.sync.SyncBundle
import com.binaa.ocr.core.sync.SyncKey
import com.binaa.ocr.core.sync.SyncPlan
import com.binaa.ocr.core.sync.SyncState
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** ما تعرضه الشاشة عن المزامنة. */
sealed interface SyncStatus {
    data object Off : SyncStatus

    /** [checkCode] = رمز التحقق نفسه على الهاتفين. [problem] = سبب آخر فشل، أو null. */
    data class On(val checkCode: String, val lastOkMillis: Long? = null, val running: Boolean = false, val problem: SyncProblem? = null) : SyncStatus
}

enum class SyncProblem { NEEDS_ACCESS, OFFLINE, FAILED }

/**
 * المزامنة بين هاتفي المستخدم عبر مجلد التطبيق الخاص في Google Drive، مشفّرة من طرف إلى طرف (SyncBundle).
 * عملية واحدة في كل مرة. الشبكة هنا فقط: محرّكا القراءة (Tesseract وML Kit) لا يتصلان بشيء.
 */
object SyncEngine {

    const val DIR = "sync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Off)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** إذن Google Drive ينتظر موافقة المستخدم (أول مرة فقط). */
    @Volatile
    var pendingConsent: PendingIntent? = null
        private set

    private fun stateFile(context: Context) = File(File(context.filesDir, DIR), "state.json")
    private fun store(context: Context) = PaymentStore(File(context.filesDir, PaymentStore.DIR_NAME))

    /** يقرأ الحالة عند فتح التطبيق (بلا شبكة). */
    fun init(context: Context) {
        val key = SyncKeyStore.load(context)
        _status.update { cur -> if (key == null) SyncStatus.Off else (cur as? SyncStatus.On)?.copy(checkCode = key.checkCode) ?: SyncStatus.On(key.checkCode) }
    }

    /** الهاتف الأول: مفتاح جديد يُعرض رمز QR. */
    fun pairAsFirst(context: Context): SyncKey = SyncKey.generate().also { pair(context, it) }

    /** الهاتف الثاني: المفتاح من رمز QR الممسوح. */
    fun pairFromQr(context: Context, qr: String): SyncKey? = SyncKey.fromQr(qr)?.also { pair(context, it) }

    private fun pair(context: Context, key: SyncKey) {
        SyncKeyStore.save(context, key)
        stateFile(context).delete()
        _status.value = SyncStatus.On(key.checkCode)
    }

    /** إيقاف المزامنة على هذا الهاتف: الدفعات المحلية تبقى كما هي. */
    fun stop(context: Context) {
        SyncKeyStore.clear(context)
        stateFile(context).delete()
        pendingConsent = null
        _status.value = SyncStatus.Off
    }

    /** حذف دفعة هنا: يُبلَّغ الهاتف الآخر في المزامنة التالية. */
    suspend fun noteDeleted(context: Context, id: String) = mutex.withLock {
        if (SyncKeyStore.load(context) == null) return@withLock
        val f = stateFile(context)
        val s = SyncState.load(f)
        SyncState.save(f, s.copy(pendingDeletes = s.pendingDeletes + id))
    }

    fun syncInBackground(context: Context, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        scope.launch { sync(app); onDone() }
    }

    /** مزامنة كاملة الآن. @return true إن تغيّرت دفعات هذا الهاتف. */
    suspend fun sync(context: Context): Boolean = mutex.withLock {
        val key = SyncKeyStore.load(context) ?: return@withLock false.also { _status.value = SyncStatus.Off }
        _status.update { (it as? SyncStatus.On)?.copy(running = true) ?: SyncStatus.On(key.checkCode, running = true) }
        var changed = false
        val problem = try {
            changed = run(context, key, token(context))
            null
        } catch (e: NeedsConsent) {
            SyncProblem.NEEDS_ACCESS
        } catch (e: IOException) {
            SyncProblem.OFFLINE
        } catch (e: Exception) {
            SyncProblem.FAILED
        }
        _status.update { cur ->
            val on = cur as? SyncStatus.On ?: SyncStatus.On(key.checkCode)
            on.copy(running = false, problem = problem, lastOkMillis = if (problem == null) System.currentTimeMillis() else on.lastOkMillis)
        }
        changed
    }

    private class NeedsConsent : Exception()

    private fun token(context: Context): String {
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DriveClient.SCOPE))).build()
        val result = Tasks.await(Identity.getAuthorizationClient(context).authorize(request), 30, TimeUnit.SECONDS)
        if (result.hasResolution()) {
            pendingConsent = result.pendingIntent
            throw NeedsConsent()
        }
        pendingConsent = null
        return result.accessToken ?: throw NeedsConsent()
    }

    private fun run(context: Context, key: SyncKey, token: String): Boolean {
        val drive = DriveClient(token)
        val store = store(context)
        store.pruneArchived()
        val records = store.list().associateBy { it.id }
        val file = stateFile(context)
        var state = SyncState.load(file)
        var changed = false
        val actions = SyncPlan.plan(records.mapValues { it.value.version }, state, drive.list(), System.currentTimeMillis())
        for (a in actions) {
            when (a) {
                is SyncAction.Upload -> {
                    val r = records.getValue(a.id)
                    val image = runCatching { store.imageOf(r).readBytes() }.getOrNull() ?: continue
                    drive.upload(SyncPlan.paymentName(r.id, r.version), SyncBundle.seal(key, r, image))
                    a.replaces.forEach { drive.delete(it) }
                    state = state.copy(synced = state.synced + (r.id to r.version))
                }
                is SyncAction.Download -> {
                    val opened = runCatching { SyncBundle.open(key, a.id, drive.download(a.fileId)) }
                    val bundle = opened.getOrNull()
                    if (bundle == null) {
                        // لا يُفك بمفتاح الربط الحالي (من ربط سابق): لا فائدة منه لأي هاتف مربوط الآن.
                        if (opened.exceptionOrNull() !is IOException) drive.delete(a.fileId) else throw opened.exceptionOrNull()!!
                        continue
                    }
                    store.putSynced(bundle.first, bundle.second)
                    state = state.copy(synced = state.synced + (a.id to bundle.first.version))
                    changed = true
                }
                is SyncAction.DeleteRemote -> drive.delete(a.fileId)
                is SyncAction.DeleteLocal -> if (store.delete(a.id)) changed = true
                is SyncAction.Tombstone -> drive.upload(SyncPlan.tombstoneName(a.id), ByteArray(0))
                is SyncAction.MarkSynced -> state = state.copy(synced = state.synced + (a.id to a.version))
                is SyncAction.Forget -> state = state.copy(synced = state.synced - a.id, pendingDeletes = state.pendingDeletes - a.id)
            }
            SyncState.save(file, state)
        }
        return changed
    }
}
