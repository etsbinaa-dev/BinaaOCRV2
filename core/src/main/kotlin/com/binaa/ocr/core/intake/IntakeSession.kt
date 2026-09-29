package com.binaa.ocr.core.intake

/** مرحلة الاستلام الحالية على الشاشة. */
enum class IntakePhase {
    /** لا شيء جارٍ (أو أُغلق). */
    IDLE,

    /** نسخ الصورة المشاركة إلى التخزين الخاص. */
    RECEIVING,

    /** الصورة محفوظة وشاشة التحليل ظاهرة. */
    RECEIVED,

    /** رُفضت المشاركة؛ لم تُنشأ أي عملية. */
    REJECTED,
}

enum class NewShareDecision {
    /** ابدأ الاستلام الجديد مباشرة. */
    START,

    /** اسأل المستخدم: استبدال القراءة الجارية أم متابعتها؟ */
    ASK_REPLACE,
}

object IntakeSession {

    /** مشاركة جديدة وصلت بينما [current] على الشاشة. */
    fun onNewShare(current: IntakePhase): NewShareDecision = when (current) {
        IntakePhase.RECEIVING, IntakePhase.RECEIVED -> NewShareDecision.ASK_REPLACE
        IntakePhase.IDLE, IntakePhase.REJECTED -> NewShareDecision.START
    }
}
