package com.binaa.ocr.core.intake

import org.junit.Assert.assertEquals
import org.junit.Test

class IntakePolicyTest {

    @Test
    fun singleImage_isAccepted() {
        assertEquals(ShareCheck.SingleImage, IntakePolicy.check(ShareAction.SEND, 1))
        // بعض التطبيقات ترسل SEND_MULTIPLE حتى لصورة واحدة.
        assertEquals(ShareCheck.SingleImage, IntakePolicy.check(ShareAction.SEND_MULTIPLE, 1))
    }

    @Test
    fun multipleImages_areRejected_noneIsProcessed() {
        for (count in listOf(2, 3, 10)) {
            assertEquals(ShareCheck.Rejected(IntakeRejection.MULTIPLE_IMAGES), IntakePolicy.check(ShareAction.SEND_MULTIPLE, count))
        }
    }

    @Test
    fun noImage_isRejected() {
        assertEquals(ShareCheck.Rejected(IntakeRejection.NO_IMAGE), IntakePolicy.check(ShareAction.SEND, 0))
        assertEquals(ShareCheck.Rejected(IntakeRejection.NO_IMAGE), IntakePolicy.check(ShareAction.SEND_MULTIPLE, 0))
    }

    @Test
    fun notAShare_isRejected() {
        assertEquals(ShareCheck.Rejected(IntakeRejection.NOT_A_SHARE), IntakePolicy.check(ShareAction.OTHER, 1))
    }
}
