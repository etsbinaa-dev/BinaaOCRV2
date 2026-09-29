package com.binaa.ocr.core.intake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageFormatTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test
    fun jpeg() = assertEquals(ImageFormat.JPEG, ImageFormat.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE1, 0, 0)))

    @Test
    fun png() = assertEquals(ImageFormat.PNG, ImageFormat.sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)))

    @Test
    fun webp() = assertEquals(ImageFormat.WEBP, ImageFormat.sniff(ascii("RIFF") + bytes(1, 2, 3, 4) + ascii("WEBPVP8 ")))

    @Test
    fun heif() = assertEquals(ImageFormat.HEIF, ImageFormat.sniff(bytes(0, 0, 0, 0x18) + ascii("ftypheic") + bytes(0, 0, 0, 0)))

    @Test
    fun notAnImage_isNull() {
        assertNull(ImageFormat.sniff(ascii("this is not an image")))
        assertNull(ImageFormat.sniff(ascii("%PDF-1.7\n")))
        assertNull(ImageFormat.sniff(bytes(0, 0, 0, 0x18) + ascii("ftypmp42") + bytes(0, 0, 0, 0))) // فيديو MP4
        assertNull(ImageFormat.sniff(ByteArray(0)))
        assertNull(ImageFormat.sniff(bytes(0xFF, 0xD8))) // مقطوع
    }
}
