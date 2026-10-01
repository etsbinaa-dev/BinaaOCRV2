package com.binaa.ocr.header

import android.graphics.Bitmap
import android.graphics.Color

/** تحويلات بكسلات في الذاكرة فقط بين Bitmap وأنواع Header Engine. لا ملفات ولا نسخ خارج العملية. */
internal object BitmapPixels {

    fun toPixelImage(bitmap: Bitmap): PixelImage {
        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return PixelImage(bitmap.width, bitmap.height, argb)
    }

    fun toBitmap(image: PixelImage): Bitmap = Bitmap.createBitmap(image.argb, image.width, image.height, Bitmap.Config.ARGB_8888)

    /** رمادي ⇒ ARGB (R = G = B)، كما يتوقعه Tesseract. */
    fun toBitmap(gray: GrayImage): Bitmap {
        val argb = IntArray(gray.pixels.size) { i -> gray.pixels[i].let { Color.rgb(it, it, it) } }
        return Bitmap.createBitmap(argb, gray.width, gray.height, Bitmap.Config.ARGB_8888)
    }
}
