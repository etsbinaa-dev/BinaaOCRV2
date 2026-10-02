package com.binaa.ocr.header

/** حالة اسم الطرف الآخر في V2.0: FOUND أو NOT_FOUND فقط (لا مرشّحين للاسم: الاسم اختياري ولا يُخمَّن). */
enum class NameStatus { FOUND, NOT_FOUND }

/** سبب NOT_FOUND، ولكل منهما تلميح مختلف في الواجهة (قرارات Phase 0). */
enum class NameReason {
    /** لا رأس Image Viewer: «للحصول على الاسم تلقائيًا، خذ لقطة للشاشة كاملة…». */
    HEADER_NOT_DETECTED,

    /** الرأس موجود والاسم غير مقروء: «لم نتمكن من قراءة الاسم». */
    NAME_UNREADABLE,

    /** عارض الصور يكتب «Vous»: إيصال أرسلته أنت، والمستلم غير ظاهر ⇒ «للدفعات الصادرة خذ لقطة للمحادثة». */
    SENT_BY_YOU,
}

/** اتجاه الدفعة من نوع اللقطة: عارض الصور = وصلك إيصال (وارد)، المحادثة أو «Vous» = أرسلته أنت (صادر). */
enum class PaymentDirection { INCOMING, OUTGOING }

/**
 * نتيجة Header Engine مع أدلتها: هندسة الرأس (لماذا اختير مربع الاسم أو رُفض)، ورمز القرار، والكلمات المحذوفة.
 * الاسم يأتي من رأس WhatsApp فقط، أبدًا من جسم الإيصال.
 */
data class HeaderResult(
    val status: NameStatus,
    val name: String?,
    val reason: NameReason?,
    val layout: HeaderLayout?,
    val detectorRejection: DetectorRejection?,
    val decision: NameDecision?,
) {
    init {
        when (status) {
            NameStatus.FOUND -> require(name != null && reason == null) { "FOUND carries a name and no reason" }
            NameStatus.NOT_FOUND -> require(name == null && reason != null) { "NOT_FOUND carries a reason and no name" }
        }
    }

    /** لا اسم في السجلات أبدًا. */
    override fun toString() =
        "HeaderResult($status, name=${if (name == null) "null" else "<${name.length} chars>"}, reason=$reason, rejection=$detectorRejection, decision=$decision)"

    /** الاسم قراءة تقريبية (القواعد الصارمة لم تؤكده): يظهر مع تنبيه «راجعه». */
    val approximate: Boolean get() = status == NameStatus.FOUND && decision?.code == DecisionCode.NAME_APPROXIMATE

    /** null إذا لم يُكتشف رأس. */
    val direction: PaymentDirection?
        get() = when {
            reason == NameReason.SENT_BY_YOU -> PaymentDirection.OUTGOING
            layout?.kind == HeaderKind.CHAT -> PaymentDirection.OUTGOING
            layout?.kind == HeaderKind.IMAGE_VIEWER -> PaymentDirection.INCOMING
            else -> null
        }

    companion object {
        /** ما يكتبه WhatsApp مكان اسمك (Vous/You) أو ما قد يُقرأ منه: ليس اسم الطرف الآخر أبدًا. */
        private val SELF = setOf("vous", "you", "moi", "me", "أنت", "انت", "أنا", "انا")

        fun isSelf(name: String) = name.trim().trimEnd('.', '…').lowercase() in SELF

        /**
         * يجمع الكاشف والقرار. [decision] = null إذا لم يُشغَّل OCR (لا مربع اسم).
         * لا يرفع أي حالة: الاسم FOUND فقط إذا قبله الكاشف والقرار معًا.
         */
        fun of(detection: Detection, decision: NameDecision?): HeaderResult = when {
            !detection.headerFound -> HeaderResult(NameStatus.NOT_FOUND, null, NameReason.HEADER_NOT_DETECTED, null, detection.rejection, null)
            detection.nameImage == null || decision?.name == null ->
                HeaderResult(NameStatus.NOT_FOUND, null, NameReason.NAME_UNREADABLE, detection.layout, detection.rejection, decision)
            isSelf(decision.name) -> HeaderResult(NameStatus.NOT_FOUND, null, NameReason.SENT_BY_YOU, detection.layout, null, decision)
            else -> HeaderResult(NameStatus.FOUND, decision.name, null, detection.layout, null, decision)
        }
    }
}
