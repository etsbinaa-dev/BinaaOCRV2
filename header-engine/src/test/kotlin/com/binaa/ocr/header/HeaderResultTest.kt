package com.binaa.ocr.header

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** تجميع الكاشف والقرار: الاسم FOUND فقط إذا قبله الاثنان؛ وسبب NOT_FOUND يفرّق بين «لا رأس» و«اسم غير مقروء». */
class HeaderResultTest {

    private val layout = HeaderLayout(
        UiDirection.LTR, Box(0, 0, 1080, 180), Box(49, 61, 96, 107),
        listOf(Box(718, 62, 770, 111), Box(858, 64, 902, 105)), Box(718, 62, 902, 111), 49,
        Box(148, 31, 450, 80), Box(146, 112, 400, 140), 268,
    )
    private val nameImage = NameImage(Box(132, 15, 466, 96), IntArray(334 * 81) { -1 })

    @Test
    fun noHeader_isHeaderNotDetected() {
        val r = HeaderResult.of(Detection(null, DetectorRejection.NO_ICON_GROUP, null), null)
        assertEquals(NameStatus.NOT_FOUND, r.status)
        assertEquals(NameReason.HEADER_NOT_DETECTED, r.reason)
        assertEquals(DetectorRejection.NO_ICON_GROUP, r.detectorRejection)
        assertNull(r.layout)
    }

    @Test
    fun headerWithRejectedNameBox_isNameUnreadable_withTheLayoutAsEvidence() {
        val r = HeaderResult.of(Detection(layout, DetectorRejection.NAME_TOUCHES_ICONS, null), null)
        assertEquals(NameReason.NAME_UNREADABLE, r.reason)
        assertEquals(layout, r.layout)
        assertEquals(DetectorRejection.NAME_TOUCHES_ICONS, r.detectorRejection)
    }

    @Test
    fun acceptedBox_butRejectedName_isNameUnreadable() {
        val decision = NameDecision(null, DecisionCode.LATIN_ALL_OR_NOTHING, listOf(DroppedWord(2, DropCode.MLKIT_DIFFERS)))
        val r = HeaderResult.of(Detection(layout, null, nameImage), decision)
        assertEquals(NameStatus.NOT_FOUND, r.status)
        assertEquals(NameReason.NAME_UNREADABLE, r.reason)
        assertEquals(decision, r.decision)
    }

    @Test
    fun acceptedBox_andAcceptedName_isFound() {
        val decision = NameDecision("سامي كريم", DecisionCode.NAME_ACCEPTED)
        val r = HeaderResult.of(Detection(layout, null, nameImage), decision)
        assertEquals(NameStatus.FOUND, r.status)
        assertEquals("سامي كريم", r.name)
        assertNull(r.reason)
        assertEquals(layout.name, r.layout!!.name)
    }

    @Test
    fun foundWithoutName_orNotFoundWithName_isImpossible() {
        assertThrows(IllegalArgumentException::class.java) { HeaderResult(NameStatus.FOUND, null, null, null, null, null) }
        assertThrows(IllegalArgumentException::class.java) { HeaderResult(NameStatus.NOT_FOUND, "x", NameReason.NAME_UNREADABLE, null, null, null) }
    }
}
