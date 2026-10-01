package com.binaa.ocr.header

import com.binaa.ocr.BuildConfig
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable

/**
 * ML Kit (النموذج اللاتيني المضمّن، بلا شبكة) على صورة مربع الاسم النظيف فقط ([HeaderPipeline.mlKitInput]).
 * عميل مستقل عن MlKitOcrAdapter (الذي يقرأ المستند كاملًا ولا يتغير). كلماته = عناصر الأسطر، والعنصر بلا صندوق يُهمَل.
 * لا سجلات. يُستدعى خارج الـMain Thread (ML Kit يرفض الانتظار عليه).
 */
class MlKitNameReader : MlKitWordReader, Closeable {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun read(image: PixelImage): List<RawWord> {
        val bitmap = BitmapPixels.toBitmap(image)
        val text = try {
            Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
        } finally {
            bitmap.recycle()
        }
        return text.textBlocks.flatMap { it.lines }.flatMap { it.elements }.mapNotNull { e ->
            e.boundingBox?.let { r -> RawWord(e.text, null, Box(r.left, r.top, r.right, r.bottom)) }
        }
    }

    override fun close() = recognizer.close()

    companion object {
        val ENGINE = EngineInfo("mlkit-text-recognition-latin", BuildConfig.MLKIT_TEXT_VERSION)
    }
}
