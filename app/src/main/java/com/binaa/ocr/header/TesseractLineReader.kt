package com.binaa.ocr.header

import android.content.Context
import com.binaa.ocr.BuildConfig
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.Closeable

/**
 * Tesseract على صورة اسم الرأس المجهّزة ([NamePrep]) فقط، محليًا بالكامل (نفس إعداد المسار المثبت):
 * OEM_LSTM_ONLY، PSM_SINGLE_LINE، ara+eng، كلمات RIL_WORD بثقتها وصندوقها في الصورة نفسها.
 * كائن TessBaseAPI واحد خلف قفل (ليس thread-safe)، و clear() بعد كل قراءة، و recycle() عند الإغلاق.
 * لا سجلات إطلاقًا (لا نص ولا صورة). يُستدعى خارج الـMain Thread.
 */
class TesseractLineReader(context: Context, private val installer: TessdataInstaller = TessdataInstaller(context)) : TesseractWordReader, Closeable {

    private val lock = Any()
    private var tess: TessBaseAPI? = null
    private var closed = false

    /** @return null إن لم يكن Tesseract متاحًا (نموذج غير مطابق، أو فشل التهيئة، أو بعد الإغلاق). استثناء القراءة يصعد للمسار. */
    override fun read(image: GrayImage): List<RawWord>? = synchronized(lock) {
        if (closed) return null
        val api = tess ?: init() ?: return null
        val input = BitmapPixels.toBitmap(image)
        try {
            api.setImage(input)
            api.getUTF8Text() // يشغّل التعرّف؛ الكلمات ومواضعها من المكرِّر.
            words(api)
        } finally {
            api.clear()
            input.recycle()
        }
    }

    private fun words(api: TessBaseAPI): List<RawWord> {
        val iterator = api.resultIterator ?: return emptyList()
        val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
        val words = mutableListOf<RawWord>()
        try {
            iterator.begin()
            do {
                val text = iterator.getUTF8Text(level)?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    val rect = iterator.getBoundingRect(level)
                    words += RawWord(text, iterator.confidence(level).toInt(), Box(rect.left, rect.top, rect.right, rect.bottom))
                }
            } while (iterator.next(level))
        } finally {
            iterator.delete()
        }
        return words
    }

    private fun init(): TessBaseAPI? = try {
        val dataPath = installer.install()
        val api = TessBaseAPI()
        if (!api.init(dataPath.absolutePath, installer.languages, TessBaseAPI.OEM_LSTM_ONLY)) {
            api.recycle()
            null
        } else {
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_LINE)
            api.also { tess = it }
        }
    } catch (e: Exception) {
        null
    }

    override fun close() = synchronized(lock) {
        closed = true
        tess?.recycle()
        tess = null
    }

    companion object {
        /** المحرك وإصداره والنماذج المثبّتة (لغة@مستودع@commit قصير)، كما تُسجَّل في الأدلة. */
        val ENGINE = EngineInfo(
            "tesseract4android",
            BuildConfig.TESSERACT_ANDROID_VERSION + " " +
                TessdataInstaller.PINNED.joinToString(" ") { "${it.language}:${it.repository.substringAfter('/')}@${it.commit.take(7)}" },
        )
    }
}
