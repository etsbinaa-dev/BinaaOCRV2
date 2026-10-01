package com.binaa.ocr.goldencorpus

import com.binaa.ocr.core.ocr.ImageSize
import com.binaa.ocr.core.ocr.OcrDocument
import com.binaa.ocr.core.ocr.OcrDocumentBuilder
import com.binaa.ocr.core.ocr.OcrEngineInfo
import com.binaa.ocr.core.ocr.PixelBox
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Corpus اصطناعي يُكتب في مجلد مؤقت أثناء الاختبار. لا صور ولا بيانات حقيقية، ولا ملفات تدخل Git.
 */
internal class SyntheticCorpus(val root: File) {

    fun case(dir: String, expectedJson: String?, image: String? = "image.png"): File {
        val caseDir = File(root, dir).apply { mkdirs() }
        expectedJson?.let { File(caseDir, ExpectedCase.FILE_NAME).writeText(it) }
        image?.let { writePng(File(caseDir, it)) }
        return caseDir
    }

    companion object {
        fun expected(
            caseId: String,
            fields: String = """"amount": {"status": "FOUND", "value": "18300.00"}""",
            ocrContains: String = """["MRU"]""",
            image: String = "image.png",
        ) = """
            {
              "schemaVersion": 1,
              "caseId": "$caseId",
              "image": "$image",
              "source": "SYNTHETIC",
              "fields": { $fields },
              "ocrContains": $ocrContains,
              "notes": "synthetic"
            }
        """.trimIndent()

        fun writePng(file: File) {
            val img = BufferedImage(108, 223, BufferedImage.TYPE_INT_RGB)
            img.createGraphics().apply { color = Color.WHITE; fillRect(0, 0, 108, 223); dispose() }
            ImageIO.write(img, "png", file)
        }

        /** قراءة OCR اصطناعية: «Vous avez envoye» و«18 300,00 MRU» في صف واحد. القيم وهمية. */
        fun document(): OcrDocument = OcrDocumentBuilder(ImageSize(1080, 2229), OcrEngineInfo("synthetic", "1"))
            .block("Vous avez envoye 18 300,00 MRU", PixelBox(60, 640, 1020, 710)) {
                line("Vous avez envoye", PixelBox(60, 640, 520, 710), confidence = 0.9f) {
                    word("Vous", PixelBox(60, 640, 190, 710), confidence = 0.9f)
                    word("avez", PixelBox(210, 640, 340, 710), confidence = 0.9f)
                    word("envoye", PixelBox(360, 640, 520, 710), confidence = 0.9f)
                }
                line("18 300,00 MRU", PixelBox(600, 640, 1020, 710), confidence = 0.8f) {
                    word("18", PixelBox(600, 640, 660, 710), confidence = 0.8f)
                    word("300,00", PixelBox(680, 640, 860, 710), confidence = 0.8f)
                    word("MRU", PixelBox(880, 640, 1020, 710), confidence = 0.95f)
                }
            }
            .build()
    }
}
