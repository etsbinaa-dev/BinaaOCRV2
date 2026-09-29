package com.binaa.ocr.core.intake

import org.junit.Assert.assertEquals
import org.junit.Test

class IntakeSessionTest {

    @Test
    fun newShare_whileReceivingOrReceived_asksBeforeReplacing() {
        assertEquals(NewShareDecision.ASK_REPLACE, IntakeSession.onNewShare(IntakePhase.RECEIVING))
        assertEquals(NewShareDecision.ASK_REPLACE, IntakeSession.onNewShare(IntakePhase.RECEIVED))
    }

    @Test
    fun newShare_whenNothingIsInProgress_startsDirectly() {
        assertEquals(NewShareDecision.START, IntakeSession.onNewShare(IntakePhase.IDLE))
        assertEquals(NewShareDecision.START, IntakeSession.onNewShare(IntakePhase.REJECTED))
    }

    @Test
    fun retention_deletesOnlyOldFiles_andNeverTheCurrentOne() {
        val now = 10 * IntakeRetention.MAX_AGE_MILLIS
        val files = listOf(
            IntakeRetention.StoredFile("old.jpg", now - IntakeRetention.MAX_AGE_MILLIS - 1),
            IntakeRetention.StoredFile("fresh.jpg", now - 1000),
            IntakeRetention.StoredFile("current.png", now - 2 * IntakeRetention.MAX_AGE_MILLIS),
        )
        assertEquals(listOf("old.jpg"), IntakeRetention.expired(files, now, keep = "current.png"))
    }
}
