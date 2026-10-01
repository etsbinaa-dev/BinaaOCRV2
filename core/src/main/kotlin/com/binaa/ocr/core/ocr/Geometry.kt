package com.binaa.ocr.core.ocr

import kotlinx.serialization.Serializable

/** أبعاد الصورة كما استُلمت (لقطة الشاشة كاملة، بلا قص). */
@Serializable
data class ImageSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "image size must be positive: ${width}x$height" }
    }
}

/**
 * مربع بالبكسل في إحداثيات الصورة الأصلية، كما أعاده محرّك OCR (left/top شامل، right/bottom غير شامل).
 * قد يتجاوز حدود الصورة قليلًا: لا نصححه هنا، فهو قراءة المحرّك كما هي.
 */
@Serializable
data class PixelBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(right >= left && bottom >= top) { "inverted box: $this" }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * نفس المربع بإحداثيات نسبية 0..1 من أبعاد الصورة، لمقارنة لقطات بدقات مختلفة.
 * تُقص إلى 0..1 (المربع بالبكسل يبقى كما أعاده المحرّك).
 */
@Serializable
data class NormBox(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        require(listOf(left, top, right, bottom).all { it in 0.0..1.0 }) { "normalized box out of 0..1: $this" }
        require(right >= left && bottom >= top) { "inverted box: $this" }
    }
}

fun PixelBox.normalizedIn(image: ImageSize): NormBox = NormBox(
    left = (left.toDouble() / image.width).coerceIn(0.0, 1.0),
    top = (top.toDouble() / image.height).coerceIn(0.0, 1.0),
    right = (right.toDouble() / image.width).coerceIn(0.0, 1.0),
    bottom = (bottom.toDouble() / image.height).coerceIn(0.0, 1.0),
)
