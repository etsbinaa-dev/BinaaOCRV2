package com.binaa.ocr.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GeometryTest {

    private val screen = ImageSize(1080, 2229)

    @Test
    fun pixelBox_isNormalizedByImageSize() {
        val norm = PixelBox(108, 222, 540, 445).normalizedIn(screen)
        assertEquals(0.1, norm.left, 1e-12)
        assertEquals(222.0 / 2229, norm.top, 1e-12)
        assertEquals(0.5, norm.right, 1e-12)
        assertEquals(445.0 / 2229, norm.bottom, 1e-12)
    }

    @Test
    fun boxSlightlyOutsideTheImage_keepsPixels_butNormalizedIsClamped() {
        // ML Kit قد يعيد مربعًا يتجاوز الحافة ببضع بكسلات: المربع بالبكسل يبقى كما هو.
        val box = PixelBox(-4, 2220, 1090, 2240)
        assertEquals(-4, box.left)
        assertEquals(NormBox(0.0, 2220.0 / 2229, 1.0, 1.0), box.normalizedIn(screen))
    }

    @Test
    fun widthAndHeight() {
        val box = PixelBox(10, 20, 110, 70)
        assertEquals(100, box.width)
        assertEquals(50, box.height)
    }

    @Test
    fun invalidGeometry_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { ImageSize(0, 2229) }
        assertThrows(IllegalArgumentException::class.java) { PixelBox(10, 10, 5, 20) }
        assertThrows(IllegalArgumentException::class.java) { PixelBox(10, 10, 20, 5) }
        assertThrows(IllegalArgumentException::class.java) { NormBox(0.0, 0.0, 1.2, 1.0) }
    }
}
