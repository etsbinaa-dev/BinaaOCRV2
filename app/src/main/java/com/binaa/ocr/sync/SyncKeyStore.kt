package com.binaa.ocr.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.binaa.ocr.core.sync.SyncKey
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * مفتاح المزامنة على هذا الهاتف، مشفّرًا بمفتاح من Android Keystore لا يغادر الهاتف أبدًا (ولا يُنقل مع البيانات إلى
 * هاتف جديد: هناك يجب الربط من جديد برمز QR).
 */
object SyncKeyStore {

    private const val ALIAS = "binaa-sync-wrap"
    private fun file(context: Context) = File(File(context.filesDir, SyncEngine.DIR), "key.bin")

    fun save(context: Context, key: SyncKey) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        val f = file(context).apply { parentFile?.mkdirs() }
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(cipher.iv + cipher.doFinal(key.bytes))
        check(tmp.renameTo(f)) { "cannot save the sync key" }
    }

    /** null ⇒ المزامنة غير مفعّلة على هذا الهاتف (أو نُقلت البيانات من هاتف آخر). */
    fun load(context: Context): SyncKey? = runCatching {
        val bytes = file(context).takeIf { it.isFile }?.readBytes() ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, existingKey() ?: return null, GCMParameterSpec(128, bytes, 0, 12))
        }
        SyncKey(cipher.doFinal(bytes, 12, bytes.size - 12))
    }.getOrNull()

    fun clear(context: Context) {
        file(context).delete()
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(ALIAS, null) as? SecretKey

    private fun wrappingKey(): SecretKey = existingKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generateKey()
    }
}
