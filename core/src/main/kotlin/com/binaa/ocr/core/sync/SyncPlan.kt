package com.binaa.ocr.core.sync

/** ملف في مجلد التطبيق الخاص على Google Drive. */
data class RemoteFile(val fileId: String, val name: String, val modifiedMillis: Long)

sealed interface SyncAction {
    /** رفع النسخة المحلية، ثم حذف نسخها القديمة [replaces] من السحابة. */
    data class Upload(val id: String, val replaces: List<String>) : SyncAction

    /** تنزيل نسخة أحدث من الهاتف الآخر. */
    data class Download(val fileId: String, val id: String, val version: Long) : SyncAction

    data class DeleteRemote(val fileId: String) : SyncAction

    /** حُذفت على الهاتف الآخر (أو انتهت مدتها في الأرشيف هناك). */
    data class DeleteLocal(val id: String) : SyncAction

    /** إبلاغ الهاتف الآخر بحذف هنا. */
    data class Tombstone(val id: String) : SyncAction

    /** النسختان متطابقتان: تُسجَّل كمتزامنة. */
    data class MarkSynced(val id: String, val version: Long) : SyncAction

    data class Forget(val id: String) : SyncAction
}

/**
 * قرار المزامنة، منطق خالص بلا شبكة. أسماء الملفات في السحابة:
 * `p-<معرّف>-<نسخة>.bin` (دفعة مشفّرة) و`d-<معرّف>.bin` (حُذفت). النسخة الأحدث تغلب؛ والحذف يغلب كل شيء.
 *
 * دفعة موجودة هنا وغائبة من السحابة: إن كانت متزامنة من قبل ولم تتغير هنا فقد حُذفت هناك ⇒ تُحذف هنا؛ وإلا تُرفع.
 * دفعة في السحابة وغائبة هنا: إن كانت متزامنة من قبل فقد حُذفت هنا (أرشيف انتهت مدته) ⇒ تُحذف من السحابة؛ وإلا تُنزَّل.
 */
object SyncPlan {

    private val PAYMENT = Regex("""p-([0-9a-f-]{36})-(\d+)\.bin""")
    private val DELETED = Regex("""d-([0-9a-f-]{36})\.bin""")

    /** علامة الحذف تبقى في السحابة هذه المدة، ليراها الهاتف الآخر، ثم تُزال. */
    const val TOMBSTONE_KEEP_MILLIS = 7 * 86_400_000L

    fun paymentName(id: String, version: Long) = "p-$id-$version.bin"
    fun tombstoneName(id: String) = "d-$id.bin"

    fun plan(local: Map<String, Long>, state: SyncState, remote: List<RemoteFile>, nowMillis: Long): List<SyncAction> {
        val versions = mutableMapOf<String, MutableList<Pair<Long, RemoteFile>>>()
        val tombs = mutableMapOf<String, RemoteFile>()
        val out = mutableListOf<SyncAction>()
        for (f in remote) {
            PAYMENT.matchEntire(f.name)?.let { m -> m.groupValues[2].toLongOrNull()?.let { v -> versions.getOrPut(m.groupValues[1]) { mutableListOf() } += v to f } }
                ?: DELETED.matchEntire(f.name)?.let { m -> tombs[m.groupValues[1]] = f }
        }
        val ids = (local.keys + versions.keys + tombs.keys + state.pendingDeletes).toSortedSet()
        for (id in ids) {
            val sorted = versions[id].orEmpty().sortedByDescending { it.first }
            val top = sorted.firstOrNull()
            val older = sorted.drop(1).map { it.second.fileId }
            val l = local[id]
            val synced = state.synced[id]
            when {
                id in state.pendingDeletes -> {
                    if (id !in tombs) out += SyncAction.Tombstone(id)
                    sorted.forEach { out += SyncAction.DeleteRemote(it.second.fileId) }
                    if (l != null) out += SyncAction.DeleteLocal(id)
                    out += SyncAction.Forget(id)
                }
                id in tombs -> {
                    if (l != null) out += SyncAction.DeleteLocal(id)
                    sorted.forEach { out += SyncAction.DeleteRemote(it.second.fileId) }
                    if (synced != null) out += SyncAction.Forget(id)
                }
                l != null && top != null -> {
                    older.forEach { out += SyncAction.DeleteRemote(it) }
                    when {
                        l > top.first -> out += SyncAction.Upload(id, listOf(top.second.fileId))
                        top.first > l -> out += SyncAction.Download(top.second.fileId, id, top.first)
                        synced != l -> out += SyncAction.MarkSynced(id, l)
                    }
                }
                l != null -> if (synced != null && l <= synced) {
                    out += SyncAction.DeleteLocal(id)
                    out += SyncAction.Forget(id)
                } else {
                    out += SyncAction.Upload(id, emptyList())
                }
                top != null -> if (synced != null) {
                    sorted.forEach { out += SyncAction.DeleteRemote(it.second.fileId) }
                    out += SyncAction.Forget(id)
                } else {
                    older.forEach { out += SyncAction.DeleteRemote(it) }
                    out += SyncAction.Download(top.second.fileId, id, top.first)
                }
            }
        }
        // علامات الحذف القديمة: رآها الهاتف الآخر منذ زمن.
        for ((_, f) in tombs) if (nowMillis - f.modifiedMillis > TOMBSTONE_KEEP_MILLIS) out += SyncAction.DeleteRemote(f.fileId)
        return out.distinct()
    }
}
