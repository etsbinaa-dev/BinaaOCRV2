package com.binaa.ocr.core.sync

import com.binaa.ocr.core.archive.PaymentRecord
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * دفعة واحدة كما تُخزَّن في السحابة: السجل وصورة الإيصال معًا، مشفّران بـAES-256-GCM بمفتاح المزامنة.
 * معرّف الدفعة مربوط بالتشفير (AAD): ملف لا يمكن نقله تحت اسم دفعة أخرى، وأي تعديل عليه يُرفض.
 *
 * الصيغة: nonce (12 بايت) ثم النص المشفّر مع وسم GCM. النص الأصلي: "BSB1"، طول JSON (4 بايت)، JSON السجل، ثم الصورة.
 */
object SyncBundle {

    private val json = Json { ignoreUnknownKeys = false }
    private val MAGIC = "BSB1".toByteArray()
    private const val NONCE = 12
    private const val TAG_BITS = 128

    fun seal(key: SyncKey, record: PaymentRecord, image: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        val recordJson = json.encodeToString(PaymentRecord.serializer(), record).toByteArray(Charsets.UTF_8)
        val plain = ByteBuffer.allocate(MAGIC.size + 4 + recordJson.size + image.size)
            .put(MAGIC).putInt(recordJson.size).put(recordJson).put(image).array()
        val nonce = ByteArray(NONCE).also { random.nextBytes(it) }
        val cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce, record.id)
        return nonce + cipher.doFinal(plain)
    }

    /**
     * يفك دفعة [id]. مفتاح خاطئ، ملف معدَّل، أو ملف دفعة أخرى ⇒ استثناء (لا تُقبل أبدًا).
     */
    fun open(key: SyncKey, id: String, sealed: ByteArray): Pair<PaymentRecord, ByteArray> {
        require(sealed.size > NONCE) { "sealed bundle too short" }
        val plain = cipher(Cipher.DECRYPT_MODE, key, sealed.copyOfRange(0, NONCE), id).doFinal(sealed, NONCE, sealed.size - NONCE)
        val buf = ByteBuffer.wrap(plain)
        val magic = ByteArray(MAGIC.size).also { buf.get(it) }
        require(magic.contentEquals(MAGIC)) { "not a payment bundle" }
        val len = buf.int
        require(len in 1..buf.remaining()) { "bad bundle" }
        val recordJson = ByteArray(len).also { buf.get(it) }
        val record = json.decodeFromString(PaymentRecord.serializer(), recordJson.toString(Charsets.UTF_8))
        require(record.id == id) { "bundle of another payment" }
        val image = ByteArray(buf.remaining()).also { buf.get(it) }
        return record to image
    }

    private fun cipher(mode: Int, key: SyncKey, nonce: ByteArray, id: String): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key.bytes, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD("binaa-sync-v1:$id".toByteArray(Charsets.UTF_8))
        }
}
