package com.binaa.ocr.core.intake

/**
 * صور الاستلام مؤقتة إلى أن تُحفظ العملية (الأرشيف يأتي في مرحلة لاحقة).
 * أي صورة استلام تُركت دون إغلاق (مثلًا أُوقف التطبيق) تُحذف بعد مدة، حتى لا تتراكم صور حساسة.
 */
object IntakeRetention {

    const val MAX_AGE_MILLIS: Long = 24L * 60 * 60 * 1000

    data class StoredFile(val name: String, val lastModifiedMillis: Long)

    /** @return أسماء الملفات التي يجب حذفها. [keep] = ملف الاستلام الجاري إن وجد. */
    fun expired(files: List<StoredFile>, nowMillis: Long, keep: String? = null): List<String> =
        files.filter { it.name != keep && nowMillis - it.lastModifiedMillis > MAX_AGE_MILLIS }.map { it.name }
}
