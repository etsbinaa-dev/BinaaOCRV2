package com.binaa.ocr.core.intake

/**
 * صيغة الصورة من أول بايتات الملف (التوقيع السحري)، لا من النوع الذي يعلنه التطبيق المُرسِل.
 * لقطات Samsung تصل عادةً JPEG أو PNG.
 */
enum class ImageFormat(val extension: String) {
    JPEG("jpg"),
    PNG("png"),
    WEBP("webp"),
    HEIF("heic"),
    ;

    companion object {
        /** عدد البايتات الكافي للتعرف على كل الصيغ المدعومة. */
        const val SNIFF_BYTES = 16

        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1")

        /** @return الصيغة، أو null إن لم تكن صورة مدعومة. */
        fun sniff(header: ByteArray): ImageFormat? {
            fun at(i: Int) = header.getOrNull(i)?.toInt()?.and(0xFF)
            fun ascii(from: Int, length: Int): String? =
                if (header.size < from + length) null else String(header, from, length, Charsets.US_ASCII)

            return when {
                at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> JPEG
                header.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { header[it] == PNG_SIGNATURE[it] } -> PNG
                ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" -> WEBP
                ascii(4, 4) == "ftyp" && ascii(8, 4) in HEIF_BRANDS -> HEIF
                else -> null
            }
        }
    }
}
