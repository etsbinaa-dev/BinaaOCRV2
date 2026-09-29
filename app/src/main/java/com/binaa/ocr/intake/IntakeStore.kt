package com.binaa.ocr.intake

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import com.binaa.ocr.core.intake.ImageFormat
import com.binaa.ocr.core.intake.IntakePolicy
import com.binaa.ocr.core.intake.IntakeRejection
import com.binaa.ocr.core.intake.IntakeRetention
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

/** الصورة كما وصلت، محفوظة في التخزين الخاص. لا تُقص ولا تُصغَّر ولا يُعاد ترميزها. */
data class StoredImage(
    val file: File,
    val format: ImageFormat,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
)

sealed interface IntakeResult {
    data class Stored(val image: StoredImage) : IntakeResult
    data class Rejected(val reason: IntakeRejection) : IntakeResult
}

/**
 * ينسخ الصورة المشاركة بايتًا ببايت إلى `filesDir/intake/` (خاص بالتطبيق، خارج النسخ الاحتياطي).
 * التحقق من أنها صورة يتم من بايتاتها ثم من قراءة أبعادها، لا من النوع الذي يعلنه المُرسِل.
 */
class IntakeStore(filesDir: File, private val resolver: ContentResolver) {

    val dir: File = File(filesDir, DIR_NAME)

    suspend fun receive(uri: Uri): IntakeResult {
        dir.mkdirs()
        val id = UUID.randomUUID().toString()
        val part = File(dir, "$id.part")
        try {
            val opened = try {
                resolver.openInputStream(uri)
            } catch (e: FileNotFoundException) {
                null
            } catch (e: SecurityException) {
                null
            }
            val input = opened ?: return IntakeResult.Rejected(IntakeRejection.UNREADABLE)

            var format: ImageFormat? = null
            var total = 0L
            input.use { stream ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    val header = ByteArray(ImageFormat.SNIFF_BYTES)
                    var headerFilled = 0
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = stream.read(buffer)
                        if (read < 0) break
                        if (headerFilled < header.size) {
                            val n = minOf(read, header.size - headerFilled)
                            System.arraycopy(buffer, 0, header, headerFilled, n)
                            headerFilled += n
                        }
                        total += read
                        if (total > IntakePolicy.MAX_BYTES) return IntakeResult.Rejected(IntakeRejection.TOO_LARGE)
                        out.write(buffer, 0, read)
                    }
                    format = ImageFormat.sniff(header.copyOf(headerFilled))
                }
            }
            val detected = format ?: return IntakeResult.Rejected(IntakeRejection.NOT_AN_IMAGE)

            val target = File(dir, "$id.${detected.extension}")
            if (!part.renameTo(target)) return IntakeResult.Rejected(IntakeRejection.UNREADABLE)

            // أبعاد فقط، بلا تحميل البكسلات: صورة تالفة تُرفض هنا.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                target.delete()
                return IntakeResult.Rejected(IntakeRejection.NOT_AN_IMAGE)
            }
            return IntakeResult.Stored(StoredImage(target, detected, bounds.outWidth, bounds.outHeight, total))
        } catch (e: IOException) {
            return IntakeResult.Rejected(IntakeRejection.UNREADABLE)
        } catch (e: SecurityException) {
            return IntakeResult.Rejected(IntakeRejection.UNREADABLE)
        } finally {
            // يبقى فقط الملف النهائي عند النجاح؛ عند الإلغاء أو الرفض لا يبقى شيء.
            part.delete()
        }
    }

    fun delete(image: StoredImage?) {
        image?.file?.delete()
    }

    /** يحذف صور الاستلام المتروكة (أقدم من يوم). [keep] = الصورة الجارية. */
    fun deleteExpired(nowMillis: Long, keep: File? = null) {
        val files = dir.listFiles().orEmpty().map { IntakeRetention.StoredFile(it.name, it.lastModified()) }
        for (name in IntakeRetention.expired(files, nowMillis, keep?.name)) File(dir, name).delete()
    }

    companion object {
        /** مستثنى من النقل بين الهواتف في data_extraction_rules.xml. */
        const val DIR_NAME = "intake"
        private const val BUFFER_BYTES = 64 * 1024
    }
}
