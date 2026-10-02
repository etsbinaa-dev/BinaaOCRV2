package com.binaa.ocr.core.ocr

import com.binaa.ocr.core.evidence.EvidenceLog
import kotlinx.serialization.json.Json

/**
 * صيغة JSON الوحيدة لـ[OcrDocument] و[EvidenceLog]: للـfixtures وتقارير التشخيص فقط.
 * منطق OCR والـParsers يعمل على الكائنات، لا على JSON.
 * القراءة صارمة: حقل غير معروف أو قيمة غير صالحة ترفض الملف كله بدل أن تُتجاهل بصمت.
 */
object OcrJson {

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun encode(document: OcrDocument): String = json.encodeToString(OcrDocument.serializer(), document)

    fun decodeDocument(text: String): OcrDocument = json.decodeFromString(OcrDocument.serializer(), text)

    fun encode(log: EvidenceLog): String = json.encodeToString(EvidenceLog.serializer(), log)

    fun decodeLog(text: String): EvidenceLog = json.decodeFromString(EvidenceLog.serializer(), text)
}
