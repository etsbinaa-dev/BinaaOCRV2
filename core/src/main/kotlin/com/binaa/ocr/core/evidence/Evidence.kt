package com.binaa.ocr.core.evidence

import com.binaa.ocr.core.ocr.OcrDocument
import com.binaa.ocr.core.ocr.OcrElement
import kotlinx.serialization.Serializable

/**
 * نوع الدليل. كل دليل يشير إلى كلمات أو أسطر في [OcrDocument]، فيمكن دائمًا معرفة ما قُرئ وأين وبأي ثقة.
 * في Phase 2a لا يُنشئ أي Parser أدلة بعد؛ الأنواع هنا هي ما اتُّفق عليه في التصميم.
 */
enum class EvidenceKind {
    /** تسمية مجاورة (مثل «Montant» أو «المبلغ» أو «Frais»). */
    LABEL_NEARBY,

    /** موضع بنيوي يعرفه تخطيط المزوّد. */
    STRUCTURAL_POSITION,

    /** إشارة سالبة (-) أمام قيمة. */
    SIGN,

    /** عملة في نفس الصف. */
    CURRENCY,

    /** قاعدة خاصة بمزوّد. */
    PROVIDER_RULE,

    /** تحقق متقاطع بين حقلين. */
    CROSS_CHECK,
}

/** من أنتج الدليل: معرّف ثابت وإصدار (مثل "masrvi-fr" و"1")، ليُتتبَّع أي خطأ مستقبلي إلى قاعدته. */
@Serializable
data class EvidenceSource(val component: String, val version: String)

@Serializable
data class Evidence(
    val kind: EvidenceKind,
    val source: EvidenceSource,
    /** معرّفات عناصر [OcrDocument] التي بُني عليها الدليل. لا يُكرَّر نصها هنا. */
    val elementIds: List<String>,
    /** رمز ثابت يصف القاعدة (لا نص حر، حتى لا تتسرب قيم الإيصال إلى الأدلة). */
    val code: String,
) {
    init {
        require(elementIds.isNotEmpty()) { "evidence must point at OCR elements" }
        require(CODE.matches(code)) { "evidence code must be a fixed identifier like AMOUNT_LABEL_SAME_ROW: $code" }
    }

    private companion object {
        val CODE = Regex("[A-Z][A-Z0-9_]*")
    }
}

/**
 * سجل الأدلة لقراءة واحدة: المستند كما قرأه المحرّك (ماذا، أين، بأي ثقة)،
 * والأدلة التي ستستخدمها الطبقات اللاحقة، وكل دليل يشير إلى عناصر موجودة فعلًا في المستند.
 *
 * يحمل نص الإيصال: للتشخيص في نسخة debug فقط، ولا يُكتب إلى ملف أو سجل في نسخة الإصدار.
 */
@Serializable
data class EvidenceLog(
    val document: OcrDocument,
    val entries: List<Evidence> = emptyList(),
) {
    init {
        for (entry in entries) {
            val missing = entry.elementIds.filter { document.element(it) == null }
            require(missing.isEmpty()) { "evidence ${entry.code} points at unknown OCR elements $missing" }
        }
    }

    fun with(evidence: Evidence): EvidenceLog = copy(entries = entries + evidence)

    /** العناصر التي يشير إليها دليل، بنصها ومربعها وثقتها كما قرأها المحرّك. */
    fun elementsOf(evidence: Evidence): List<OcrElement> = evidence.elementIds.map { document.element(it)!! }
}
