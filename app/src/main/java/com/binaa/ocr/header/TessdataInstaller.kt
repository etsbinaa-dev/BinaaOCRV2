package com.binaa.ocr.header

import android.content.Context
import com.binaa.ocr.BuildConfig
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * ينسخ نماذج Tesseract من assets الـAPK إلى مجلد التطبيق الخاص (Tesseract يحتاج مسار ملف)، بلا أي شبكة.
 * المجلد noBackupFilesDir: لا يدخل النسخ الاحتياطي ولا النقل بين الهواتف.
 * كل نموذج يُتحقق من حجمه وSHA-256 (من tessdata.lock عبر BuildConfig) قبل كل تهيئة؛ نسخة تالفة تُستبدل، ونسخة الـAPK
 * التي لا تطابق ترفض (Tesseract غير متاح ⇒ NAME_UNREADABLE).
 */
class TessdataInstaller(private val context: Context, private val models: List<Model> = PINNED) {

    class Model(val language: String, val repository: String, val commit: String, val sha256: String, val bytes: Long)

    /** المجلد الذي يُمرَّر لـTessBaseAPI.init (فيه tessdata/). */
    val dataPath: File get() = File(context.noBackupFilesDir, "tesseract")

    /** @return [dataPath] بعد التحقق من كل النماذج. يرمي استثناءً إن تعذّر تثبيت نموذج مطابق. */
    fun install(): File = synchronized(INSTALL_LOCK) {
        val dir = File(dataPath, "tessdata").apply { mkdirs() }
        for (model in models) {
            val target = File(dir, "${model.language}.traineddata")
            if (matches(target, model)) continue
            val part = File(dir, "${target.name}.part")
            context.assets.open("tessdata/${model.language}.traineddata").use { input -> part.outputStream().use { input.copyTo(it) } }
            if (!matches(part, model)) {
                part.delete()
                error("packaged ${model.language} model does not match its pinned SHA-256")
            }
            target.delete()
            check(part.renameTo(target)) { "cannot install ${model.language} model" }
        }
        dataPath
    }

    val languages: String get() = models.joinToString("+") { it.language }

    companion object {
        /** قارئان (ara+eng و eng+ara) يتشاركان المجلد نفسه: تثبيت واحد في كل مرة. */
        private val INSTALL_LOCK = Any()

        /** tessdata.lock كما ثُبّت وقت البناء. */
        val PINNED: List<Model> = BuildConfig.TESSDATA_MODELS.split(';').map { line ->
            val (language, repository, commit, sha, bytes) = line.split(' ')
            Model(language, repository, commit, sha, bytes.toLong())
        }

        fun matches(file: File, model: Model) = file.isFile && file.length() == model.bytes && file.inputStream().use { sha256(it) } == model.sha256

        fun sha256(input: InputStream): String {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
