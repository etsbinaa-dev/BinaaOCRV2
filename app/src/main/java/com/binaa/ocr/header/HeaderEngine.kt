package com.binaa.ocr.header

import android.content.Context
import android.graphics.Bitmap
import java.io.Closeable

/**
 * Header Engine على الهاتف: لقطة الشاشة كاملة ⇒ [HeaderPipeline] بـTesseract وML Kit المحليين ⇒ [HeaderReading].
 * غير موصول بأي واجهة إنتاج بعد. لا يرمي استثناءً أبدًا (أي فشل ⇒ NOT_FOUND)، ولا يسجّل شيئًا.
 * يُستدعى خارج الـMain Thread. الصورة في الذاكرة فقط: لا تُحفظ ولا تُرسل.
 */
class HeaderEngine(context: Context) : Closeable {

    private val tesseract = TesseractLineReader(context.applicationContext)
    /** يُهيَّأ فقط إن احتاجه اسم لاتيني (القراءة الثانية). */
    private val latinFirst = TesseractLineReader(context.applicationContext, languages = TesseractLineReader.LATIN_FIRST)
    private val mlKit = MlKitNameReader()

    fun read(screenshot: Bitmap): HeaderReading = try {
        HeaderPipeline.run(
            BitmapPixels.toPixelImage(screenshot), tesseract, TesseractLineReader.ENGINE, mlKit, MlKitNameReader.ENGINE,
            latinFirst, TesseractLineReader.LATIN_FIRST_ENGINE,
        )
    } catch (e: Exception) {
        HeaderReading(HeaderResult(NameStatus.NOT_FOUND, null, NameReason.HEADER_NOT_DETECTED, null, null, null), emptyList(), null)
    }

    override fun close() {
        tesseract.close()
        latinFirst.close()
        mlKit.close()
    }
}
