package com.binaa.ocr.header

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** منقولة من HeaderImageTest القديم، مع اختبار مربع اسم الكاشف. */
class NamePrepTest {

    private fun rgb(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test
    fun darkGrayHeader_becomesBlackTextOnWhite() {
        // رأس رمادي داكن (95) وحروف فاتحة (235): بعد العكس خلفية 160 وحروف 20 → بعد المدّ 255 و0.
        val out = NamePrep.normalize(IntArray(100) { if (it < 20) rgb(235) else rgb(95) })
        assertEquals(0, out[0])
        assertEquals(255, out[99])
    }

    @Test
    fun lightHeader_isNotInverted() {
        val out = NamePrep.normalize(IntArray(100) { if (it < 20) rgb(40) else rgb(200) })
        assertEquals(0, out[0])
        assertEquals(255, out[99])
    }

    @Test
    fun flatBox_isLeftAsIs() {
        assertArrayEquals(IntArray(50) { 200 + it % 3 }, NamePrep.normalize(IntArray(50) { rgb(200 + it % 3) }))
    }

    @Test
    fun empty_isEmpty() {
        assertEquals(0, NamePrep.normalize(IntArray(0)).size)
    }

    @Test
    fun resize_keepsUniformAndAveragesThinStrokes() {
        val flat = NamePrep.resize(GrayImage(8, 8, IntArray(64) { 200 }), 4, 4)
        assertTrue(flat.pixels.all { it == 200 })
        // خط أسود بعرض بكسل واحد لا يختفي عند التصغير إلى النصف (مرشّح يتّسع، لا عيّنة نقطية).
        val stroke = NamePrep.resize(GrayImage(8, 1, IntArray(8) { if (it == 3) 0 else 255 }), 4, 1)
        assertTrue(stroke.pixels.joinToString(), stroke.pixels.minOrNull()!! < 200)
    }

    @Test
    fun prepare_scalesToTargetHeightWithWhiteBorder() {
        val out = NamePrep.prepare(IntArray(80 * 20) { rgb(if (it % 80 in 30..40) 20 else 230) }, 80, 20, 40, 20)
        assertEquals(40 + 40, out.height)
        assertEquals(160 + 40, out.width)
        assertEquals(255, out.pixels[0])
        assertEquals(255, out.pixels.last())
    }

    @Test
    fun detectorNameImage_isPreparedAt40pxWith20pxBorder_andTheSourceIsUntouched() {
        val box = Box(132, 15, 466, 96)
        val argb = IntArray(box.w * box.h) { i -> if ((i % box.w) in 16..300 && (i / box.w) in 16..64) rgb(0) else rgb(255) }
        val copy = argb.copyOf()
        val out = NamePrep.prepare(NameImage(box, argb))
        assertEquals(NamePrep.TARGET_HEIGHT + 2 * NamePrep.BORDER, out.height)
        assertEquals((box.w * 40f / box.h).toInt() + 2 * NamePrep.BORDER, out.width)
        assertArrayEquals("prepare works on a copy", copy, argb)
    }
}
