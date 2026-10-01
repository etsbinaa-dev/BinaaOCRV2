package com.binaa.ocr.header

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * قرار الاسم بنفس حالات HeaderDeciderTest القديم (المأخوذة من تقرير S26)، بأسماء وأرقام بديلة وهمية
 * بنفس الخط والبنية (الأسماء الحقيقية لا تدخل Git). المواضع بالبكسل في الصورة المجهّزة كما في الأصل.
 */
class NameDeciderTest {

    /** صورة مجهّزة (أبيض) فيها حبر أسود تحت كل مدى أفقي. */
    private fun image(vararg ink: IntRange, width: Int = 320, height: Int = 80): GrayImage {
        val px = IntArray(width * height) { 255 }
        for (r in ink) for (x in r) for (y in 30..50) px[y * width + x] = 0
        return GrayImage(width, height, px)
    }

    private fun tw(text: String, conf: Int, r: IntRange) = TessWord(text, conf, r.first, r.last)
    private fun ml(text: String, r: IntRange) = MlWord(text, r.first, r.last)

    @Test
    fun arabicName_confidence90_isAccepted_inVisualOrder() {
        val a = 30..70; val b = 80..130; val c = 140..180
        val d = NameDecider.decide(listOf(tw("نور", 91, a), tw("كريم", 92, b), tw("سامي", 92, c)), image(a, b, c), emptyList())
        assertEquals("سامي كريم نور", d.name)
        assertEquals(DecisionCode.NAME_ACCEPTED, d.code)
        assertTrue(d.dropped.isEmpty())
    }

    @Test
    fun wordsInAnyInputOrder_areDecidedLeftToRight() {
        val a = 30..70; val b = 80..130; val c = 140..180
        val d = NameDecider.decide(listOf(tw("سامي", 92, c), tw("نور", 91, a), tw("كريم", 92, b)), image(a, b, c), emptyList())
        assertEquals("سامي كريم نور", d.name)
    }

    @Test
    fun arabicWord_confidence88_isDropped_restIsKept() {
        // الكلمة بثقة 88 تُحذف وحدها؛ لا يُخفَض حد 90.
        val a = 30..70; val b = 80..130
        val d = NameDecider.decide(listOf(tw("للتجارة", 88, a), tw("مؤسسة", 91, b)), image(a, b), emptyList())
        assertEquals("مؤسسة", d.name)
        assertEquals(listOf(DroppedWord(0, DropCode.ARABIC_LOW_CONFIDENCE)), d.dropped)
    }

    @Test
    fun phone_confirmedByMlKit() {
        val r = listOf(30..70, 80..100, 110..130, 140..160, 170..190)
        val digits = listOf("+222", "00", "11", "22", "33")
        val t = digits.zip(r).map { (s, x) -> tw(s, 93, x) }
        val m = digits.zip(r).map { (s, x) -> ml(s, x) }
        val d = NameDecider.decide(t, image(*r.toTypedArray()), m)
        assertEquals("+222 00 11 22 33", d.name)
        assertEquals(DecisionCode.PHONE_CONFIRMED, d.code)
    }

    @Test
    fun phone_mlKitSpacingDiffers_staysNull() {
        // ML Kit «+222 00 11 2233».
        val r = listOf(30..70, 80..100, 110..130, 140..160, 170..190)
        val t = listOf("+222", "00", "11", "22", "33").zip(r).map { (s, x) -> tw(s, 93, x) }
        val m = listOf(ml("+222", 30..70), ml("00", 80..100), ml("11", 110..130), ml("2233", 140..190))
        val d = NameDecider.decide(t, image(*r.toTypedArray()), m)
        assertNull(d.name)
        assertEquals(DecisionCode.PHONE_NOT_CONFIRMED, d.code)
    }

    @Test
    fun phone_withoutMlKit_isNotConfirmed() {
        val r = listOf(30..70, 80..100, 110..130, 140..160, 170..190)
        val t = listOf("+222", "00", "11", "22", "33").zip(r).map { (s, x) -> tw(s, 93, x) }
        assertEquals(DecisionCode.PHONE_NOT_CONFIRMED, NameDecider.decide(t, image(*r.toTypedArray()), emptyList()).code)
    }

    @Test
    fun digitsThatAreNotAPhone_areNull() {
        val a = 30..70; val b = 80..120
        assertEquals(DecisionCode.NOT_A_PHONE, NameDecider.decide(listOf(tw("123", 95, a), tw("45", 95, b)), image(a, b), listOf(ml("123", a), ml("45", b))).code)
    }

    @Test
    fun latin_bothEnginesAgree_isAccepted() {
        val a = 30..100; val b = 110..170; val c = 180..220
        val t = listOf(tw("Karim", 92, a), tw("saleh", 93, b), tw("Btq", 92, c))
        val m = listOf(ml("Karim", a), ml("saleh", b), ml("Btq", c))
        val d = NameDecider.decide(t, image(a, b, c), m)
        assertEquals("Karim saleh Btq", d.name)
        assertTrue(DecisionNote.LATIN_CONFIRMED_BY_MLKIT in d.notes)
    }

    @Test
    fun latinName_enginesDisagree_isNull_noPartialName() {
        // ML Kit «Btg» / Tesseract «Btq». اسم لاتيني بالكامل ⇒ الاسم كله أو NULL (لا «Karim saleh»).
        val a = 30..100; val b = 110..170; val c = 180..220
        val t = listOf(tw("Karim", 92, a), tw("saleh", 93, b), tw("Btq", 92, c))
        val m = listOf(ml("Karim", a), ml("saleh", b), ml("Btg", c))
        val d = NameDecider.decide(t, image(a, b, c), m)
        assertNull(d.name)
        assertEquals(DecisionCode.LATIN_ALL_OR_NOTHING, d.code)
        assertEquals(listOf(DroppedWord(2, DropCode.MLKIT_DIFFERS)), d.dropped)
    }

    @Test
    fun latinWord_notSeenByMlKit_makesTheLatinNameNull() {
        val a = 30..100; val b = 110..170
        val d = NameDecider.decide(listOf(tw("Karim", 95, a), tw("saleh", 95, b)), image(a, b), listOf(ml("Karim", a)))
        assertNull(d.name)
        assertEquals(DroppedWord(1, DropCode.NOT_SEEN_BY_MLKIT), d.dropped.single())
    }

    @Test
    fun latinWord_mlKitWidthMismatch_isDropped() {
        val a = 30..100; val b = 110..170
        val d = NameDecider.decide(listOf(tw("Karim", 95, a), tw("saleh", 95, b)), image(a, b), listOf(ml("Karim", a), ml("saleh", 110..230)))
        assertEquals(DroppedWord(1, DropCode.MLKIT_WIDTH_MISMATCH), d.dropped.single())
    }

    @Test
    fun latinWord_lOrIUnresolved_belowConfidence_isDropped() {
        val a = 30..100; val b = 110..170
        val d = NameDecider.decide(listOf(tw("Karim", 95, a), tw("Ali", 80, b)), image(a, b), listOf(ml("Karim", a), ml("AIi", b)))
        assertEquals(DroppedWord(1, DropCode.L_I_UNRESOLVED), d.dropped.single())
        assertNull(d.name)
    }

    @Test
    fun mixedName_latinWordBelow90_isDropped_notGuessed() {
        // «أهل» قُرئت Jai (85) عند Tesseract وJal عند ML Kit ⇒ تُحذف، ولا تُكتب Jai ولا Jal.
        val a = 30..70; val b = 80..110; val c = 120..170
        val t = listOf(tw("منير", 93, a), tw("Jai", 85, b), tw("بوتيك", 92, c))
        val d = NameDecider.decide(t, image(a, b, c), listOf(ml("Jal", b)))
        assertEquals("بوتيك منير", d.name)
        assertEquals(listOf(DroppedWord(1, DropCode.LATIN_LOW_CONFIDENCE_IN_MIXED)), d.dropped)
    }

    // ---- وسم جهة الاتصال Clt (كما في الكود القديم حرفيًا) ----

    @Test
    fun contactTag_readCitAndCIt_isKeptAsClt() {
        val a = 30..110; val b = 120..200; val c = 210..240
        val t = listOf(tw("Omar", 92, a), tw("Lemine", 92, b), tw("Cit", 70, c))
        val m = listOf(ml("Omar", a), ml("Lemine", b), ml("CIt", c))
        assertEquals("Omar Lemine Clt", NameDecider.decide(t, image(a, b, c), m).name)
    }

    @Test
    fun contactTag_readCt_confirmedClt_isKeptAsClt() {
        val a = 30..110; val c = 120..150
        val t = listOf(tw("Omar", 92, a), tw("Ct", 60, c))
        assertEquals("Omar Clt", NameDecider.decide(t, image(a, c), listOf(ml("Omar", a), ml("Clt", c))).name)
    }

    @Test
    fun contactTag_notConfirmedByMlKit_makesTheLatinNameNull() {
        // Tesseract «Cit» وML Kit «Cut»: ليس وسمًا مؤكدًا ⇒ اختلاف ⇒ الاسم اللاتيني كله NULL.
        val a = 30..110; val c = 120..150
        val d = NameDecider.decide(listOf(tw("Omar", 92, a), tw("Cit", 70, c)), image(a, c), listOf(ml("Omar", a), ml("Cut", c)))
        assertNull(d.name)
        assertEquals(DroppedWord(1, DropCode.MLKIT_DIFFERS), d.dropped.single())
    }

    @Test
    fun mixedName_unreliableContactTag_isDropped() {
        // «علي Clt صرافه»: Tesseract «Cit» (78) في اسم مختلط ⇒ تُحذف ⇒ «علي صرافه».
        val sarafa = 30..80; val tag = 90..115; val ali = 125..165
        val t = listOf(tw("صرافه", 91, sarafa), tw("Cit", 78, tag), tw("علي", 91, ali))
        val d = NameDecider.decide(t, image(sarafa, tag, ali), listOf(ml("CIt", tag)))
        assertEquals("علي صرافه", d.name)
        assertEquals(listOf(DroppedWord(1, DropCode.LATIN_LOW_CONFIDENCE_IN_MIXED)), d.dropped)
    }

    @Test
    fun mixedName_confidentContactTag_isKeptAsClt() {
        val sarafa = 30..80; val tag = 90..115; val ali = 125..165
        val t = listOf(tw("صرافه", 91, sarafa), tw("Cit", 93, tag), tw("علي", 91, ali))
        assertEquals("علي Clt صرافه", NameDecider.decide(t, image(sarafa, tag, ali), listOf(ml("CIt", tag))).name)
    }

    @Test
    fun onlyTheContactTagLeft_isNull() {
        val a = 30..80; val tag = 90..115
        val d = NameDecider.decide(listOf(tw("صرافه", 70, a), tw("Cit", 95, tag)), image(a, tag), listOf(ml("Clt", tag)))
        assertNull(d.name)
        assertEquals(DecisionCode.ALL_WORDS_UNRELIABLE, d.code)
    }

    // ---- الحبر غير المقروء ----

    @Test
    fun latinName_unreadInkInsideTheName_isNull() {
        // حبر بين كلمتين لا يغطيه أي مربع كلمة؛ اسم لاتيني بالكامل ⇒ NULL.
        val a = 30..100; val gap = 105..118; val b = 125..200
        val d = NameDecider.decide(listOf(tw("Yahya", 91, a), tw("Fadel", 92, b)), image(a, gap, b), listOf(ml("Yahya", a), ml("Fadel", b)))
        assertNull(d.name)
        assertTrue(DecisionNote.UNREAD_INK_PRESENT in d.notes)
    }

    @Test
    fun arabicName_unreadInk_dropsOnlyTheWordItTouches() {
        val a = 30..90; val gap = 95..110; val b = 120..170
        val d = NameDecider.decide(listOf(tw("بوتيك", 92, a), tw("منير", 93, b)), image(a, gap, b), emptyList())
        assertEquals("منير", d.name)
        assertEquals(listOf(DroppedWord(0, DropCode.TOUCHES_UNREAD_INK)), d.dropped)
        assertTrue(DecisionNote.UNREAD_INK_PRESENT in d.notes)
    }

    @Test
    fun unreadInk_touchingEveryWord_isNull() {
        val a = 30..100; val gap = 104..120; val b = 124..200
        assertNull(NameDecider.decide(listOf(tw("الشيخ", 95, a), tw("سالم", 94, b)), image(a, gap, b), emptyList()).name)
    }

    // ---- حذف الكلمة غير الموثوقة فقط ----

    @Test
    fun arabicName_unreliableFirstWord_keepsTheReliableWords() {
        // «د الشيخ حسن»: «د» (أقصى اليمين) غير موثوقة ⇒ «الشيخ حسن».
        val hasan = 30..90; val sheikh = 100..170; val dal = 180..195
        val d = NameDecider.decide(listOf(tw("حسن", 91, hasan), tw("الشيخ", 94, sheikh), tw("د", 62, dal)), image(hasan, sheikh, dal), emptyList())
        assertEquals("الشيخ حسن", d.name)
        assertEquals(listOf(DroppedWord(2, DropCode.ARABIC_LOW_CONFIDENCE)), d.dropped)
    }

    @Test
    fun mixedName_unreadableLatinAbbreviation_keepsTheArabicName() {
        // «محمد سعيد بوتيك M.E.O»: Tesseract «MLE.» (32) لا يغطي «O» (حبر 70–82)، وML Kit «M.E.0».
        val mle = 28..67; val o = 70..82; val boutique = 91..134; val said = 141..176; val mohamed = 183..225
        val t = listOf(tw("MLE.", 32, mle), tw("بوتيك", 92, boutique), tw("سعيد", 92, said), tw("محمد", 96, mohamed))
        val m = listOf(ml("M.E.0", 28..82), ml("Lj", boutique), ml("l", said), ml("a", mohamed))
        assertEquals("محمد سعيد بوتيك", NameDecider.decide(t, image(mle, o, boutique, said, mohamed), m).name)
    }

    @Test
    fun mixedName_correctlyReadButMixedWord_isDroppedAlone() {
        val meo = 28..82; val boutique = 91..134; val said = 141..176; val mohamed = 183..225
        val t = listOf(tw("M.E.O", 72, meo), tw("بوتيك", 92, boutique), tw("سعيد", 92, said), tw("محمد", 96, mohamed))
        val d = NameDecider.decide(t, image(meo, boutique, said, mohamed), listOf(ml("M.E.O", meo)))
        assertEquals("محمد سعيد بوتيك", d.name)
        assertEquals(listOf(DroppedWord(0, DropCode.MIXED_SCRIPT)), d.dropped)
    }

    @Test
    fun digitsInsideAName_areDropped() {
        val a = 30..80; val b = 90..120
        val d = NameDecider.decide(listOf(tw("محمود", 93, a), tw("12", 95, b)), image(a, b), listOf(ml("12", b)))
        assertEquals("محمود", d.name)
        assertEquals(listOf(DroppedWord(1, DropCode.DIGITS_IN_NAME)), d.dropped)
    }

    @Test
    fun arabicName_allReliable_isUnchanged() {
        val a = 30..80; val b = 90..110; val c = 120..170
        val d = NameDecider.decide(listOf(tw("الأمين", 93, a), tw("ول", 93, b), tw("محمود", 91, c)), image(a, b, c), emptyList())
        assertEquals("محمود ول الأمين", d.name)
        assertTrue(d.dropped.isEmpty() && d.notes.isEmpty())
    }

    @Test
    fun allWordsUnreliable_isNull() {
        val a = 30..80; val b = 90..140
        val d = NameDecider.decide(listOf(tw("للتجارة", 85, a), tw("مؤسسة", 88, b)), image(a, b), emptyList())
        assertNull(d.name)
        assertEquals(DecisionCode.ALL_WORDS_UNRELIABLE, d.code)
    }

    // ---- «…» ----

    @Test
    fun ellipsisDots_atTheEndOfAnArabicName_areKept_andMarkedTruncated() {
        val w = 320; val h = 80
        val px = IntArray(w * h) { 255 }
        for (x0 in listOf(5, 12, 19)) for (x in x0..x0 + 3) for (y in 44..47) px[y * w + x] = 0
        for (x in 30..90) for (y in 30..50) px[y * w + x] = 0
        for (x in 100..160) for (y in 30..50) px[y * w + x] = 0
        val d = NameDecider.decide(listOf(tw("للتجارة", 91, 30..90), tw("مؤسسة", 92, 100..160)), GrayImage(w, h, px), emptyList())
        assertEquals("مؤسسة للتجارة …", d.name)
        assertTrue(DecisionNote.NAME_TRUNCATED in d.notes)
    }

    @Test
    fun ellipsisDots_atTheStartOfALatinName_areNull() {
        // «…» في بداية اسم LTR (يسارًا): الاسم مقطوع من بدايته ⇒ NULL.
        val w = 320; val h = 80
        val px = IntArray(w * h) { 255 }
        for (x0 in listOf(5, 12, 19)) for (x in x0..x0 + 3) for (y in 44..47) px[y * w + x] = 0
        for (x in 30..90) for (y in 30..50) px[y * w + x] = 0
        val d = NameDecider.decide(listOf(tw("Karim", 95, 30..90)), GrayImage(w, h, px), listOf(ml("Karim", 30..90)))
        assertNull(d.name)
        assertEquals(DecisionCode.DOTS_AT_START, d.code)
    }

    @Test
    fun noWords_isNull() {
        assertEquals(DecisionCode.NO_WORDS, NameDecider.decide(emptyList(), image(30..60), emptyList()).code)
    }
}
