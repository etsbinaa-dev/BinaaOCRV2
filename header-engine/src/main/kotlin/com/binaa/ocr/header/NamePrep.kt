package com.binaa.ocr.header

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** صورة رمادية: قيمة لكل بكسل 0..255 (0 = حبر أسود، 255 = خلفية بيضاء). */
class GrayImage(val width: Int, val height: Int, val pixels: IntArray)

/**
 * تجهيز مربع الاسم لـTesseract، منقول من HeaderImage في Binaa Payment OCR دون تغيير:
 * رمادي، عكس الألوان إن كانت الخلفية داكنة، رفع التباين، تحجيم إلى ارتفاع ثابت، ثم إطار أبيض.
 * يعمل على نسخة في الذاكرة فقط؛ الصورة المحفوظة لا تُمس.
 */
object NamePrep {

    /** القيم المعتمدة على الهاتف: ارتفاع النص 40 بكسل وإطار 20 بكسل. */
    const val TARGET_HEIGHT = 40
    const val BORDER = 20

    /** نسبة البكسلات المهملة في كل طرف من المدرّج (ضجيج JPEG) قبل مدّ المجال إلى 0..255. */
    private const val CLIP = 0.02

    /** أقل مجال سطوع يستحق المدّ؛ أقل منه = مربع شبه فارغ، يُترك كما هو. */
    private const val MIN_RANGE = 16

    fun prepare(name: NameImage): GrayImage = prepare(name.argb, name.width, name.height, TARGET_HEIGHT, BORDER)

    fun prepare(argb: IntArray, width: Int, height: Int, targetHeight: Int, border: Int): GrayImage {
        val gray = GrayImage(width, height, normalize(argb))
        val scaledWidth = (width * targetHeight.toFloat() / height).toInt().coerceAtLeast(1)
        return withBorder(resize(gray, scaledWidth, targetHeight), border)
    }

    /** @param argb بكسلات ARGB. @return سطوع لكل بكسل بعد العكس (إن لزم) ورفع التباين. */
    fun normalize(argb: IntArray): IntArray {
        if (argb.isEmpty()) return IntArray(0)
        val gray = IntArray(argb.size) { i ->
            val c = argb[i]
            (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
        if (gray.average() < 128) {
            for (i in gray.indices) gray[i] = 255 - gray[i]
        }

        val histogram = IntArray(256)
        for (v in gray) histogram[v]++
        val clip = gray.size * CLIP
        val low = level(histogram, clip, 0..255)
        val high = level(histogram, clip, 255 downTo 0)
        if (high - low < MIN_RANGE) return gray
        for (i in gray.indices) {
            gray[i] = ((gray[i] - low) * 255 / (high - low)).coerceIn(0, 255)
        }
        return gray
    }

    /**
     * تحجيم ثنائي الخط بمرشّح يتّسع عند التصغير (مثل Pillow BILINEAR)، أفقيًا ثم عموديًا.
     * التصغير بعيّنة 4 بكسلات فقط (مثل createScaledBitmap) يُسقط تفاصيل الحروف.
     */
    fun resize(src: GrayImage, width: Int, height: Int): GrayImage {
        val horizontal = DoubleArray(width * src.height)
        val xWeights = weights(src.width, width)
        for (y in 0 until src.height) {
            for (x in 0 until width) {
                val (start, w) = xWeights[x]
                var sum = 0.0
                for (k in w.indices) sum += w[k] * src.pixels[y * src.width + start + k]
                horizontal[y * width + x] = sum
            }
        }
        val out = IntArray(width * height)
        val yWeights = weights(src.height, height)
        for (y in 0 until height) {
            val (start, w) = yWeights[y]
            for (x in 0 until width) {
                var sum = 0.0
                for (k in w.indices) sum += w[k] * horizontal[(start + k) * width + x]
                out[y * width + x] = sum.roundToInt().coerceIn(0, 255)
            }
        }
        return GrayImage(width, height, out)
    }

    private fun withBorder(src: GrayImage, border: Int): GrayImage {
        val width = src.width + 2 * border
        val height = src.height + 2 * border
        val out = IntArray(width * height) { 255 }
        for (y in 0 until src.height) {
            System.arraycopy(src.pixels, y * src.width, out, (y + border) * width + border, src.width)
        }
        return GrayImage(width, height, out)
    }

    /** لكل بكسل ناتج: أول بكسل مصدر + أوزان المرشّح المثلثي (مجموعها 1). */
    private fun weights(inSize: Int, outSize: Int): Array<Pair<Int, DoubleArray>> {
        val scale = inSize.toDouble() / outSize
        val filterScale = maxOf(scale, 1.0)
        return Array(outSize) { i ->
            val center = (i + 0.5) * scale
            val start = floor(center - filterScale).toInt().coerceIn(0, inSize - 1)
            val end = ceil(center + filterScale).toInt().coerceIn(start + 1, inSize)
            val w = DoubleArray(end - start) { k ->
                val d = abs((start + k + 0.5 - center) / filterScale)
                if (d < 1.0) 1.0 - d else 0.0
            }
            val total = w.sum()
            if (total > 0) for (k in w.indices) w[k] /= total else w[0] = 1.0
            start to w
        }
    }

    /** أول مستوى سطوع (في اتجاه [order]) يتجاوز عنده عدد البكسلات المتراكم [clip]. */
    private fun level(histogram: IntArray, clip: Double, order: IntProgression): Int {
        var sum = 0L
        for (v in order) {
            sum += histogram[v]
            if (sum > clip) return v
        }
        return order.last
    }
}
