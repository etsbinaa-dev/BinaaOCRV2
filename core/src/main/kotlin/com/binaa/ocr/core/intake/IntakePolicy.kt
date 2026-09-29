package com.binaa.ocr.core.intake

/**
 * المدخل الوحيد لـBinaa OCR V2: لقطة شاشة واحدة كاملة من WhatsApp تُشارَك إلى التطبيق.
 * هذا الملف يقرر فقط هل طلب المشاركة مقبول، قبل قراءة أي بايت من الصورة.
 */
enum class ShareAction { SEND, SEND_MULTIPLE, OTHER }

/** لماذا لا تُنشأ عملية من هذه المشاركة. لا يوجد أي مسار آخر لإنشاء عملية. */
enum class IntakeRejection {
    /** أكثر من صورة في مشاركة واحدة: لا نعالج أيًّا منها، ولا حتى الأولى. */
    MULTIPLE_IMAGES,

    /** المشاركة لا تحمل أي صورة. */
    NO_IMAGE,

    /** ليست مشاركة (فتح عادي أو إجراء آخر). */
    NOT_A_SHARE,

    /** المحتوى ليس صورة (بحسب بايتاته، لا بحسب النوع المعلَن). */
    NOT_AN_IMAGE,

    /** الملف أكبر من الحد. */
    TOO_LARGE,

    /** تعذّرت قراءة الملف المشارك. */
    UNREADABLE,
}

sealed interface ShareCheck {
    /** صورة واحدة: تُنسخ كما هي ويبدأ التحليل تلقائيًا. */
    data object SingleImage : ShareCheck
    data class Rejected(val reason: IntakeRejection) : ShareCheck
}

object IntakePolicy {

    /** حد أعلى لحجم الملف المشارك. لقطة شاشة هاتف لا تتجاوز بضعة ميغابايت. */
    const val MAX_BYTES: Long = 50L * 1024 * 1024

    /** @param streamCount عدد الملفات في المشاركة (EXTRA_STREAM). */
    fun check(action: ShareAction, streamCount: Int): ShareCheck = when {
        action == ShareAction.OTHER -> ShareCheck.Rejected(IntakeRejection.NOT_A_SHARE)
        streamCount <= 0 -> ShareCheck.Rejected(IntakeRejection.NO_IMAGE)
        streamCount > 1 -> ShareCheck.Rejected(IntakeRejection.MULTIPLE_IMAGES)
        else -> ShareCheck.SingleImage
    }
}
