package com.binaa.ocr.header

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * المسار كاملًا (الكاشف ← التجهيز ← Tesseract ← ML Kit ← القرار) بمحرّكين مزيّفين يقرآن الحبر من الصورة التي تصلهما فعلًا:
 * كل «كلمة» = كتلة حبر متصلة، ويُعطى نصها من القائمة. فالمواضع حقيقية (من الصورة المجهّزة وصورة ML Kit)، والنصوص وهمية.
 */
class HeaderPipelineTest {

    private val dark = 0xFF141A1E.toInt()
    private val white = 0xFFFEFEFE.toInt()
    private val tessInfo = EngineInfo("tesseract", "test")
    private val mlInfo = EngineInfo("mlkit", "test")

    private class Canvas(val w: Int, val h: Int, bg: Int) {
        val px = IntArray(w * h) { bg }
        fun rect(l: Int, t: Int, r: Int, b: Int, c: Int) { for (y in t until b) for (x in l until r) px[y * w + x] = c }
        fun mirrored(): PixelImage = PixelImage(w, h, IntArray(w * h) { i -> px[(i / w) * w + (w - 1 - i % w)] })
        fun image() = PixelImage(w, h, px)
    }

    /** رأس Image Viewer (هندسة اختبارات الكاشف) واسم من كتل [words] في صف الأيقونات، و«نقاط» اختيارية. */
    private fun viewer(words: List<IntRange>, dots: List<Int> = emptyList(), extra: Canvas.() -> Unit = {}): Canvas =
        Canvas(1080, 2229, dark).apply {
            rect(49, 61, 96, 107, white)
            rect(718, 62, 770, 111, white)
            rect(858, 64, 902, 105, white)
            for (r in words) rect(r.first, 31, r.last, 80, white)
            for (x in dots) rect(x, 66, x + 10, 76, white)
            rect(146, 112, 400, 140, white)
            extra()
        }

    /** كتل حبر متصلة (أعمدة فيها حبر، تُفصل بفراغ > 3 أعمدة). */
    private fun runs(width: Int, height: Int, dark: (Int, Int) -> Boolean): List<IntRange> {
        val out = mutableListOf<IntRange>(); var start = -1; var last = -1
        for (x in 0 until width) {
            if ((0 until height).any { dark(x, it) }) { if (start < 0) start = x; last = x }
            else if (start >= 0 && x - last > 3) { out += start..last; start = -1 }
        }
        if (start >= 0) out += start..last
        return out
    }

    /** Tesseract مزيّف: الكتلة i ⇒ texts[i] (null = لم يقرأها). يسجّل الصورة التي وصلته. */
    private inner class FakeTess(val texts: List<String?>, val conf: List<Int> = texts.map { 95 }, val available: Boolean = true, val fail: Boolean = false) : TesseractWordReader {
        var seen: GrayImage? = null
        var calls = 0
        override fun read(image: GrayImage): List<RawWord>? {
            calls++; seen = image
            if (fail) throw IllegalStateException("native crash")
            if (!available) return null
            val r = runs(image.width, image.height) { x, y -> image.pixels[y * image.width + x] < 128 }
            assertEquals("ink runs in the prepared image", texts.size, r.size)
            return r.indices.mapNotNull { i -> texts[i]?.let { RawWord(it, conf[i], Box(r[i].first, 0, r[i].last + 1, image.height)) } }
        }
    }

    /** ML Kit مزيّف بنفس الفكرة على صورته (مربع الاسم بدقته الأصلية + إطار). */
    private inner class FakeMl(val texts: List<String?>, val fail: Boolean = false) : MlKitWordReader {
        var seen: PixelImage? = null
        var calls = 0
        override fun read(image: PixelImage): List<RawWord> {
            calls++; seen = image
            if (fail) throw RuntimeException("ml kit failed")
            val r = runs(image.width, image.height) { x, y -> (image.argb[y * image.width + x] and 0xFF) < 128 }
            assertEquals("ink runs in the ML Kit image", texts.size, r.size)
            return r.indices.mapNotNull { i -> texts[i]?.let { RawWord(it, null, Box(r[i].first, 0, r[i].last + 1, image.height)) } }
        }
    }

    private fun run(c: Canvas, tess: FakeTess, ml: FakeMl) = HeaderPipeline.run(c.image(), tess, tessInfo, ml, mlInfo)

    private val three = listOf(148..250, 290..370, 410..450)

    // ------------------------------------------------------------------ الأسماء

    @Test
    fun arabicName_isFound_inVisualOrder() {
        val r = run(viewer(three), FakeTess(listOf("نور", "كريم", "سالم")), FakeMl(listOf(null, null, null)))
        assertEquals(NameStatus.FOUND, r.result.status)
        assertEquals("سالم كريم نور", r.result.name)
        assertNull(r.failure)
    }

    @Test
    fun latinName_confirmedByMlKitAtTheSamePlace_isFound() {
        val words = listOf("Zyxwq", "Qwerty", "Abc")
        val r = run(viewer(three), FakeTess(words), FakeMl(words))
        assertEquals("Zyxwq Qwerty Abc", r.result.name)
        assertTrue(DecisionNote.LATIN_CONFIRMED_BY_MLKIT in r.result.decision!!.notes)
    }

    @Test
    fun latinName_mlKitDisagrees_isNotFound_nameUnreadable() {
        val r = run(viewer(three), FakeTess(listOf("Zyxwq", "Qwerty", "Abc")), FakeMl(listOf("Zyxwq", "Qwertv", "Abc")))
        assertEquals(NameStatus.NOT_FOUND, r.result.status)
        assertEquals(NameReason.NAME_UNREADABLE, r.result.reason)
        assertEquals(DecisionCode.LATIN_ALL_OR_NOTHING, r.result.decision!!.code)
    }

    @Test
    fun mixedName_confidentContactTag_isKeptAsClt() {
        val r = run(viewer(three), FakeTess(listOf("صرافه", "Cit", "علي"), listOf(91, 93, 91)), FakeMl(listOf(null, "CIt", null)))
        assertEquals("علي Clt صرافه", r.result.name)
    }

    @Test
    fun latinName_withContactTag_isFound_withClt() {
        val r = run(viewer(three), FakeTess(listOf("Omar", "Lemine", "Cit"), listOf(92, 92, 70)), FakeMl(listOf("Omar", "Lemine", "CIt")))
        assertEquals("Omar Lemine Clt", r.result.name)
    }

    @Test
    fun phoneNumber_confirmedByMlKit_isFound() {
        val blocks = listOf(148..230, 260..300, 330..370, 400..440, 470..510)
        val digits = listOf("+222", "98", "76", "54", "32")
        val r = run(viewer(blocks), FakeTess(digits), FakeMl(digits))
        assertEquals("+222 98 76 54 32", r.result.name)
        assertEquals(DecisionCode.PHONE_CONFIRMED, r.result.decision!!.code)
    }

    @Test
    fun ellipsis_atTheEndOfAnArabicName_isKept() {
        // «…» يسار اسم عربي (نهايته البصرية): ثلاث نقاط صغيرة لا يقرؤها Tesseract.
        val c = viewer(listOf(210..320, 360..450), dots = listOf(148, 163, 178))
        // ML Kit يرى النقاط منفصلة بدقتها الأصلية (ثلاث كتل)، ولا يقرأ العربية.
        val r = run(c, FakeTess(listOf(null, "للتجارة", "مؤسسة")), FakeMl(listOf(null, null, null, null, null)))
        assertEquals("مؤسسة للتجارة …", r.result.name)
        assertTrue(DecisionNote.NAME_TRUNCATED in r.result.decision!!.notes)
    }

    @Test
    fun arabicWord_lowConfidence_isDropped_restIsFound() {
        val r = run(viewer(three), FakeTess(listOf("نور", "كريم", "سالم"), listOf(95, 88, 95)), FakeMl(listOf(null, null, null)))
        assertEquals("سالم نور", r.result.name)
        assertEquals(listOf(DroppedWord(1, DropCode.ARABIC_LOW_CONFIDENCE)), r.result.decision!!.dropped)
    }

    @Test
    fun allArabicWordsLowConfidence_isNotFound() {
        val r = run(viewer(three), FakeTess(listOf("نور", "كريم", "سالم"), listOf(80, 85, 89)), FakeMl(listOf(null, null, null)))
        assertEquals(NameReason.NAME_UNREADABLE, r.result.reason)
        assertNull(r.result.name)
    }

    // ------------------------------------------------------------------ لا رأس ⇒ لا OCR

    private fun assertNoOcr(r: HeaderReading, tess: FakeTess, ml: FakeMl, reason: NameReason) {
        assertEquals(NameStatus.NOT_FOUND, r.result.status)
        assertEquals(reason, r.result.reason)
        assertEquals("Tesseract must not run", 0, tess.calls)
        assertEquals("ML Kit must not run", 0, ml.calls)
        assertTrue(r.evidence.isEmpty())
    }

    @Test
    fun missingHeader_noOcrAtAll() {
        val c = Canvas(1080, 2229, 0xFFFFFFFF.toInt()).apply { rect(100, 800, 600, 860, dark) }
        val tess = FakeTess(emptyList()); val ml = FakeMl(emptyList())
        assertNoOcr(run(c, tess, ml), tess, ml, NameReason.HEADER_NOT_DETECTED)
    }

    @Test
    fun multipleHeaders_areAmbiguous_noOcr_headerNotDetected() {
        val c = Canvas(1080, 2229, dark).apply {
            for (top in listOf(8, 118)) {
                rect(49, top + 2, 96, top + 47, white); rect(718, top + 2, 770, top + 47, white); rect(858, top + 4, 902, top + 44, white)
                var x = 148; while (x < 450) { rect(x, top, minOf(x + 30, 450), top + 42, white); x += 40 }
            }
        }
        val tess = FakeTess(emptyList()); val ml = FakeMl(emptyList())
        val r = run(c, tess, ml)
        assertNoOcr(r, tess, ml, NameReason.HEADER_NOT_DETECTED)
        assertEquals(DetectorRejection.MULTIPLE_HEADER_CANDIDATES, r.result.detectorRejection)
    }

    @Test
    fun rtlHeader_isRejected_noOcr() {
        val tess = FakeTess(emptyList()); val ml = FakeMl(emptyList())
        val r = HeaderPipeline.run(viewer(three).mirrored(), tess, tessInfo, ml, mlInfo)
        assertNoOcr(r, tess, ml, NameReason.HEADER_NOT_DETECTED)
        assertEquals(DetectorRejection.NO_ICON_GROUP, r.result.detectorRejection)
    }

    @Test
    fun nameTouchingTheIcons_isUnreadable_noOcr() {
        val c = viewer(listOf(148..715))
        val tess = FakeTess(emptyList()); val ml = FakeMl(emptyList())
        val r = run(c, tess, ml)
        assertNoOcr(r, tess, ml, NameReason.NAME_UNREADABLE)
        assertEquals(DetectorRejection.NAME_TOUCHES_ICONS, r.result.detectorRejection)
    }

    @Test
    fun brokenImage_neverThrows() {
        val tess = FakeTess(emptyList()); val ml = FakeMl(emptyList())
        assertNoOcr(HeaderPipeline.run(PixelImage(1, 1, intArrayOf(0)), tess, tessInfo, ml, mlInfo), tess, ml, NameReason.HEADER_NOT_DETECTED)
    }

    // ------------------------------------------------------------------ فشل المحرّكين

    @Test
    fun tesseractUnavailable_isUnreadable_andMlKitDoesNotRun() {
        val tess = FakeTess(listOf("x", "y", "z"), available = false); val ml = FakeMl(listOf("x", "y", "z"))
        val r = run(viewer(three), tess, ml)
        assertEquals(NameReason.NAME_UNREADABLE, r.result.reason)
        assertEquals(ReadFailure.TESSERACT_UNAVAILABLE, r.failure)
        assertEquals(1, tess.calls); assertEquals(0, ml.calls)
    }

    @Test
    fun tesseractException_isUnreadable_andMlKitDoesNotRun() {
        val tess = FakeTess(listOf("x", "y", "z"), fail = true); val ml = FakeMl(listOf("x", "y", "z"))
        val r = run(viewer(three), tess, ml)
        assertEquals(NameReason.NAME_UNREADABLE, r.result.reason)
        assertEquals(ReadFailure.TESSERACT_FAILED, r.failure)
        assertEquals(0, ml.calls)
    }

    @Test
    fun mlKitException_isUnreadable_notThrown() {
        val r = run(viewer(three), FakeTess(listOf("Zyxwq", "Qwerty", "Abc")), FakeMl(listOf("x", "y", "z"), fail = true))
        assertEquals(NameStatus.NOT_FOUND, r.result.status)
        assertEquals(NameReason.NAME_UNREADABLE, r.result.reason)
        assertEquals(ReadFailure.MLKIT_FAILED, r.failure)
        assertNull(r.result.decision)
    }

    // ------------------------------------------------------------------ ما يصل إلى المحرّكين

    @Test
    fun engines_seeOnlyTheCleanNameBox() {
        val c = viewer(three)
        val tess = FakeTess(listOf("نور", "كريم", "سالم")); val ml = FakeMl(listOf(null, null, null))
        val r = run(c, tess, ml)
        val name = HeaderDetector.detect(c.image()).nameImage!!
        val subtitle = r.result.layout!!.subtitle!!
        assertTrue("the name box ${name.box} must not reach the subtitle $subtitle", name.box.b <= subtitle.t)
        // Tesseract: الصورة المجهّزة نفسها (ارتفاع 40 + إطار 20).
        assertEquals(NamePrep.prepare(name).pixels.toList(), tess.seen!!.pixels.toList())
        assertEquals(NamePrep.TARGET_HEIGHT + 2 * NamePrep.BORDER, tess.seen!!.height)
        // ML Kit: مربع الاسم النظيف بدقته الأصلية داخل إطار أبيض 20، لا قصّ خام من اللقطة.
        val m = ml.seen!!
        assertEquals(name.width + 40, m.width); assertEquals(name.height + 40, m.height)
        for (y in 0 until m.height) for (x in 0 until m.width) {
            val inside = x in 20 until 20 + name.width && y in 20 until 20 + name.height
            val expected = if (inside) name.argb[(y - 20) * name.width + (x - 20)] else 0xFFFFFFFF.toInt()
            if (m.argb[y * m.width + x] != expected) throw AssertionError("ML Kit pixel ($x,$y)")
        }
        // الصورة النظيفة أسود على أبيض: لا لون الواجهة الداكن.
        assertFalse(m.argb.any { it == dark })
    }

    @Test
    fun mlKitCoordinates_mapToThePreparedImage_likeTheProvenPath() {
        // x' = ((x − 20) · 40 / h + 20).toInt()
        val e = HeaderPipeline.mlKitEvidence(listOf(RawWord("b", null, Box(101, 0, 182, 10)), RawWord("a", null, Box(20, 0, 60, 10))), 81, mlInfo)
        assertEquals(listOf("a", "b"), e.words.map { it.text })
        assertEquals(listOf(20 to 39, 60 to 100), e.words.map { it.left to it.right })
        assertEquals(Box(101, 0, 182, 10), e.words[1].engineBox)
        assertEquals(OcrSource.MLKIT, e.source)
    }

    @Test
    fun tesseractWords_withoutDirectionMarks_emptyDropped_sortedByLeft() {
        val e = HeaderPipeline.tesseractEvidence(
            listOf(RawWord("‏سالم‎", 93, Box(90, 0, 120, 9)), RawWord("‫‬", 99, Box(60, 0, 70, 9)), RawWord("نور", 91, Box(30, 0, 60, 9))),
            tessInfo,
        )
        assertEquals(listOf("نور", "سالم"), e.words.map { it.text })
        assertEquals(listOf(91, 93), e.words.map { it.confidence })
        assertEquals(listOf(30 to 60, 90 to 120), e.words.map { it.left to it.right })
    }

    @Test
    fun evidence_toString_neverShowsTheText() {
        val words = listOf("Zyxwq", "Qwerty", "Abc")
        val r = run(viewer(three), FakeTess(words), FakeMl(words))
        assertEquals(2, r.evidence.size)
        val printed = listOf(
            r.toString(), r.result.toString(), r.result.decision.toString(), r.evidence.toString(), r.evidence.flatMap { it.words }.toString(),
            RawWord("Zyxwq", 1, Box(0, 0, 1, 1)).toString(), TessWord("Zyxwq", 1, 0, 1).toString(), MlWord("Zyxwq", 0, 1).toString(),
        )
        for (p in printed) for (w in words) assertFalse("'$w' printed in $p", p.contains(w))
        assertNotNull(r.result.name)
    }

    // ------------------------------------------------------------------ القراءة الثانية (لاتيني أولًا)

    private fun run2(c: Canvas, tess: FakeTess, ml: FakeMl, latin: FakeTess) =
        HeaderPipeline.run(c.image(), tess, tessInfo, ml, mlInfo, latin, EngineInfo("tesseract", "eng+ara"))

    @Test
    fun latinName_misreadAsDigitsFirst_isReadByTheLatinFirstPass_andConfirmedByMlKit() {
        // ara+eng: «Zyxwq 862610 Abc» ⇒ لا اسم. eng+ara: «Zyxwq Qwerty Abc» وML Kit يؤكد كل كلمة ⇒ FOUND.
        val latin = FakeTess(listOf("Zyxwq", "Qwerty", "Abc"))
        val r = run2(viewer(three), FakeTess(listOf("Zyxwq", "862610", "Abc")), FakeMl(listOf("Zyxwq", "Qwerty", "Abc")), latin)
        assertEquals(NameStatus.FOUND, r.result.status)
        assertEquals("Zyxwq Qwerty Abc", r.result.name)
        assertEquals(1, latin.calls)
        assertEquals(3, r.evidence.size)
    }

    @Test
    fun latinFirstPass_dropsATrailingContactTag_readAsClit() {
        // الوسم في آخر السطر يُقرأ «Clit» وML Kit يقرأ «Clt»: يُحذف الوسم وحده، والاسم مؤكد.
        val r = run2(
            viewer(three),
            FakeTess(listOf("Zyxwq", "862610", "Clit")),
            FakeMl(listOf("Zyxwq", "Qwerty", "Clt")),
            FakeTess(listOf("Zyxwq", "Qwerty", "Clit")),
        )
        assertEquals("Zyxwq Qwerty", r.result.name)
    }

    @Test
    fun latinFirstPass_mlKitDisagrees_staysNotFound() {
        val r = run2(
            viewer(three),
            FakeTess(listOf("Zyxwq", "862610", "Abc")),
            FakeMl(listOf("Zyxwq", "Qwertv", "Abc")),
            FakeTess(listOf("Zyxwq", "Qwerty", "Abc")),
        )
        assertEquals(NameStatus.NOT_FOUND, r.result.status)
        assertEquals(NameReason.NAME_UNREADABLE, r.result.reason)
    }

    @Test
    fun latinFirstPass_withArabicWords_isNeverUsed() {
        // اسم مختلط: القراءة الثانية لا تُقبل أبدًا (عربي فيها) ⇒ تبقى نتيجة القراءة الأولى.
        val r = run2(
            viewer(three),
            FakeTess(listOf("نور", "Cit", "سالم"), listOf(80, 93, 80)),
            FakeMl(listOf(null, "CIt", null)),
            FakeTess(listOf("نور", "Cit", "سالم")),
        )
        assertEquals(NameStatus.NOT_FOUND, r.result.status)
        assertEquals(DecisionCode.ALL_WORDS_UNRELIABLE, r.result.decision!!.code)
    }

    @Test
    fun latinFirstPass_runsOnlyWhenTheFirstReadingFailedWithLatinLetters() {
        val found = FakeTess(listOf("x", "y", "z"))
        run2(viewer(three), FakeTess(listOf("Zyxwq", "Qwerty", "Abc")), FakeMl(listOf("Zyxwq", "Qwerty", "Abc")), found)
        assertEquals("first reading found the name", 0, found.calls)
        val arabic = FakeTess(listOf("x", "y", "z"))
        run2(viewer(three), FakeTess(listOf("نور", "كريم", "سالم"), listOf(80, 80, 80)), FakeMl(listOf(null, null, null)), arabic)
        assertEquals("no Latin letters in the first reading", 0, arabic.calls)
    }

    @Test
    fun latinFirstPassFailure_keepsTheFirstResult_noException() {
        val r = run2(
            viewer(three),
            FakeTess(listOf("Zyxwq", "862610", "Abc")),
            FakeMl(listOf("Zyxwq", "Qwerty", "Abc")),
            FakeTess(listOf("x", "y", "z"), fail = true),
        )
        assertEquals(NameStatus.NOT_FOUND, r.result.status)
        assertEquals(NameReason.NAME_UNREADABLE, r.result.reason)
        assertNull(r.failure)
    }

    // ------------------------------------------------------------------ الاتجاه و«Vous»

    @Test
    fun viewerHeaderReadingVous_isNotAName_itIsYourOwnPayment() {
        val r = run(viewer(listOf(148..260)), FakeTess(listOf("Vous")), FakeMl(listOf("Vous")))
        assertEquals(NameStatus.NOT_FOUND, r.result.status)
        assertEquals(NameReason.SENT_BY_YOU, r.result.reason)
        assertNull(r.result.name)
        assertEquals(PaymentDirection.OUTGOING, r.result.direction)
    }

    @Test
    fun viewerHeaderName_isIncoming() {
        val r = run(viewer(three), FakeTess(listOf("نور", "كريم", "سالم")), FakeMl(listOf(null, null, null)))
        assertEquals(PaymentDirection.INCOMING, r.result.direction)
    }

    @Test
    fun noHeader_hasNoDirection() {
        val c = Canvas(1080, 2229, 0xFFFFFFFF.toInt())
        assertNull(run(c, FakeTess(emptyList()), FakeMl(emptyList())).result.direction)
    }

    @Test
    fun chatHeaderName_isOutgoing_andReadAfterTheProfilePicture() {
        val c = Canvas(1080, 2229, 0xFFFFFFFF.toInt()).apply {
            val ink = 0xFF111111.toInt()
            rect(48, 60, 96, 108, ink); rect(157, 30, 264, 137, 0xFF6F86A0.toInt())
            rect(303, 52, 400, 94, ink); rect(440, 52, 530, 94, ink)
            rect(714, 60, 774, 108, ink); rect(861, 57, 915, 111, ink)
        }
        val tess = FakeTess(listOf("Zyxwq", "Qwerty"))
        val r = run(c, tess, FakeMl(listOf("Zyxwq", "Qwerty")))
        assertEquals("Zyxwq Qwerty", r.result.name)
        assertEquals(HeaderKind.CHAT, r.result.layout!!.kind)
        assertEquals(PaymentDirection.OUTGOING, r.result.direction)
    }
}
