package com.binaa.ocr.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.SystemClock
import com.binaa.ocr.BuildConfig
import com.binaa.ocr.core.ocr.ImageSize
import com.binaa.ocr.core.ocr.OcrDocument
import com.binaa.ocr.core.ocr.OcrDocumentBuilder
import com.binaa.ocr.core.ocr.OcrEngineInfo
import com.binaa.ocr.core.ocr.PixelBox
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import java.io.File

/**
 * ML Kit Text Recognition (النموذج المضمّن في التطبيق، لاتيني) → [OcrDocument].
 *
 * - يعمل على الهاتف فقط: لا شبكة ولا تحميل نماذج.
 * - يقرأ الصورة كاملة كما استُلمت: لا قص ولا تصغير ولا تدوير.
 * - ينقل ما أعاده ML Kit كما هو: النص، والمربعات، والثقة، والزاوية، واللغة، وترتيب الكتل والأسطر والكلمات.
 *   لا تفسير هنا: لا مناطق ولا صفوف ولا حقول.
 *
 * [read] تنتظر ML Kit، فلا تُستدعى من الخيط الرئيسي (يرفض ML Kit ذلك).
 */
class MlKitOcrAdapter : Closeable {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun read(bitmap: Bitmap): OcrDocument {
        val image = ImageSize(bitmap.width, bitmap.height)
        val started = SystemClock.elapsedRealtime()
        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
        return text.toOcrDocument(image, durationMs = SystemClock.elapsedRealtime() - started)
    }

    override fun close() = recognizer.close()

    companion object {
        val ENGINE = OcrEngineInfo(name = "mlkit-text-recognition-latin", version = BuildConfig.MLKIT_TEXT_VERSION)

        /** يفك الصورة المحفوظة بدقتها الكاملة (بلا inSampleSize ولا قص)، كما يجب أن يراها OCR. */
        fun decodeFullImage(file: File): Bitmap {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
            return requireNotNull(BitmapFactory.decodeFile(file.path, options)) { "not a decodable image: ${file.name}" }
        }
    }
}

internal fun Text.toOcrDocument(image: ImageSize, durationMs: Long?): OcrDocument {
    val builder = OcrDocumentBuilder(image, MlKitOcrAdapter.ENGINE)
    for (mlBlock in textBlocks) {
        builder.block(mlBlock.text, mlBlock.boundingBox.toPixelBox()) {
            for (mlLine in mlBlock.lines) {
                line(mlLine.text, mlLine.boundingBox.toPixelBox(), mlLine.confidence, mlLine.angle, mlLine.recognizedLanguage) {
                    for (mlWord in mlLine.elements) {
                        word(mlWord.text, mlWord.boundingBox.toPixelBox(), mlWord.confidence, mlWord.angle, mlWord.recognizedLanguage)
                    }
                }
            }
        }
    }
    return builder.build(durationMs)
}

private fun Rect?.toPixelBox(): PixelBox? = this?.let { PixelBox(it.left, it.top, it.right, it.bottom) }
