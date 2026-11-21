package com.binaa.ocr.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * ما يتذكره هذا الهاتف عن المزامنة: آخر نسخة متزامنة من كل دفعة ([synced]: معرّف ⇒ نسخة)، والدفعات التي حذفها
 * المستخدم هنا ولم يُبلَّغ بها الهاتف الآخر بعد ([pendingDeletes]).
 */
@Serializable
data class SyncState(
    val synced: Map<String, Long> = emptyMap(),
    val pendingDeletes: Set<String> = emptySet(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun load(file: File): SyncState =
            runCatching { json.decodeFromString(serializer(), file.readText(Charsets.UTF_8)) }.getOrDefault(SyncState())

        fun save(file: File, state: SyncState) {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(serializer(), state), Charsets.UTF_8)
            check(tmp.renameTo(file)) { "cannot write the sync state" }
        }
    }
}
