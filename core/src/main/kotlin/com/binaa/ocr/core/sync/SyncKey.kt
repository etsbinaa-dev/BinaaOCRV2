package com.binaa.ocr.core.sync

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * مفتاح المزامنة المشترك بين الهاتفين (AES-256). يُولَّد على الهاتف الأول وينتقل إلى الثاني برمز QR فقط:
 * لا يُرسَل إلى أي خادم، فالسحابة لا ترى إلا بيانات مشفّرة.
 */
class SyncKey(val bytes: ByteArray) {
    init {
        require(bytes.size == SIZE) { "sync key must be $SIZE bytes" }
    }

    /** محتوى رمز QR. */
    fun toQr(): String = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /**
     * رمز تحقق من 6 أرقام يظهر على الهاتفين: إن تطابق فالهاتفان يحملان المفتاح نفسه. لا يكشف شيئًا عن المفتاح.
     */
    val checkCode: String
        get() {
            val d = MessageDigest.getInstance("SHA-256").digest(CHECK_LABEL + bytes)
            val n = ((d[0].toInt() and 0xff) shl 16) or ((d[1].toInt() and 0xff) shl 8) or (d[2].toInt() and 0xff)
            return (n % 1_000_000).toString().padStart(6, '0')
        }

    override fun equals(other: Any?) = other is SyncKey && other.bytes.contentEquals(bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = "SyncKey(…)"

    companion object {
        const val SIZE = 32
        private const val PREFIX = "BINAA-SYNC-1:"
        private val CHECK_LABEL = "binaa-sync-check".toByteArray()

        fun generate(random: SecureRandom = SecureRandom()): SyncKey = SyncKey(ByteArray(SIZE).also { random.nextBytes(it) })

        /** رمز QR من الهاتف الأول ⇒ المفتاح، أو null إن لم يكن رمز مزامنة Binaa. */
        fun fromQr(text: String): SyncKey? {
            val t = text.trim()
            if (!t.startsWith(PREFIX)) return null
            val bytes = runCatching { Base64.getUrlDecoder().decode(t.removePrefix(PREFIX)) }.getOrNull() ?: return null
            return if (bytes.size == SIZE) SyncKey(bytes) else null
        }
    }
}
