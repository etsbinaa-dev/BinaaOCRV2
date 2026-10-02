package com.binaa.ocr.header

/** كلمة من Tesseract: الموضع الأفقي في الصورة المجهّزة ([NamePrep]). */
data class TessWord(val text: String, val conf: Int, val left: Int, val right: Int) {
    override fun toString() = "TessWord(chars=${text.length}, conf=$conf, x=$left..$right)"
}

/** كلمة من ML Kit على مربع الاسم النظيف، محوّلة إلى إحداثيات الصورة المجهّزة نفسها. */
data class MlWord(val text: String, val left: Int, val right: Int) {
    override fun toString() = "MlWord(chars=${text.length}, x=$left..$right)"
}

/** النتيجة النهائية للقرار (رمز ثابت، لا نص حر). */
enum class DecisionCode {
    NAME_ACCEPTED,
    NO_WORDS,
    ONLY_DOTS,
    PHONE_CONFIRMED,
    PHONE_UNREAD_INK,
    NOT_A_PHONE,
    PHONE_NOT_CONFIRMED,
    /** اسم لاتيني بالكامل: كله أو لا شيء، وفيه كلمة غير موثوقة أو حبر غير مقروء. */
    LATIN_ALL_OR_NOTHING,
    ALL_WORDS_UNRELIABLE,
    /** نقاط «…» في طرف البداية البصري: الاسم مقطوع من بدايته. */
    DOTS_AT_START,
    /** القواعد الصارمة لم تعطِ اسمًا ⇒ أفضل قراءة تقريبية (يراجعها المستخدم). انظر [NameDecider.approximate]. */
    NAME_APPROXIMATE,
}

/** لماذا حُذفت كلمة وحدها (لا تخمين ولا تصحيح). */
enum class DropCode {
    TOUCHES_UNREAD_INK,
    MIXED_SCRIPT,
    DIGITS_IN_NAME,
    ARABIC_LOW_CONFIDENCE,
    NOT_SEEN_BY_MLKIT,
    MLKIT_WIDTH_MISMATCH,
    LATIN_LOW_CONFIDENCE_IN_MIXED,
    L_I_UNRESOLVED,
    MLKIT_DIFFERS,
}

enum class DecisionNote {
    UNREAD_INK_PRESENT,
    LATIN_CONFIRMED_BY_MLKIT,
    /** «…» في طرف النهاية: الاسم المرئي مقطوع وأُبقيت النقاط. */
    NAME_TRUNCATED,
}

/** [index] = ترتيب الكلمة من اليسار إلى اليمين في الصورة المجهّزة. */
data class DroppedWord(val index: Int, val code: DropCode)

data class NameDecision(
    val name: String?,
    val code: DecisionCode,
    val dropped: List<DroppedWord> = emptyList(),
    val notes: Set<DecisionNote> = emptySet(),
) {
    /** لا اسم في السجلات أبدًا. */
    override fun toString() = "NameDecision(name=${if (name == null) "null" else "<${name.length} chars>"}, code=$code, dropped=$dropped, notes=$notes)"
}

/**
 * قرار اسم الـHeader، منقول من HeaderDecider في Binaa Payment OCR (مسار الهاتف: Tesseract + ML Kit) بنفس القواعد:
 * - الكلمة العربية موثوقة بثقة Tesseract ≥ 90؛ اللاتينية لا تُقبل إلا بتطابق ML Kit في الموضع والعرض والنص
 *   (تسامح l/I/1 فقط)، وفي اسم مختلط تحتاج أيضًا ثقة ≥ 90.
 * - في الأسماء العربية والمختلطة: كل كلمة غير موثوقة تُحذف وحدها؛ لا كلمة موثوقة ⇒ NULL.
 * - الاسم اللاتيني بالكامل ورقم الهاتف: كله أو لا شيء.
 * - وسم جهة الاتصال: Tesseract يقرأ clt/cit/ct وML Kit يؤكده (I→l) ⇒ يُكتب «Clt»؛ اسم من «Clt» وحده ⇒ NULL.
 * الفروق: أسباب برموز ثابتة بدل نص؛ ML Kit إلزامي (لا مسار بدونه)؛ الكلمات تُرتَّب من اليسار إلى اليمين قبل القرار.
 */
object NameDecider {

    const val MIN_CONFIDENCE = 90

    /** كلمة تبعد ≤ 5px عن حبر غير مقروء تُعدّ مقطوعة (فراغ ما بين الكلمات ≥ 7px). */
    private const val ADJACENT = 5

    private val PHONE = Regex("""^\+?\d{2,3}( \d{2}){4}$|^\d{8}$""")
    private val TAGS = setOf("clt", "cit", "ct")

    fun isAr(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.ARABIC && !c.isDigit()

    enum class Kind { DOTS, AR, LAT, NUM, MIX }

    fun kind(w: String): Kind {
        val t = w.trim('.', '…', '،', ',', ':', ';')
        return when {
            t.isEmpty() -> Kind.DOTS
            t.all { isAr(it) } -> Kind.AR
            t.all { it.isLetter() && it.code < 0x250 } -> Kind.LAT
            t.all { it.isDigit() || it == '+' } -> Kind.NUM
            else -> Kind.MIX
        }
    }

    fun stripMarks(s: String) = s.filter { c -> c.code !in listOf(0x200E, 0x200F, 0x061C) && c.code !in 0x202A..0x202E && c.code !in 0x2066..0x2069 }.trim()

    fun decide(tesseract: List<TessWord>, img: GrayImage, ml: List<MlWord>): NameDecision {
        val words = tesseract.sortedBy { it.left }
        if (words.isEmpty()) return NameDecision(null, DecisionCode.NO_WORDS)
        // تغطية: كل كتلة حبر غير مقروءة أعرض من 5 بكسل ⇒ رفض، إلا إذا كانت نقاط «…» (2–3 نقاط صغيرة) في أحد الطرفين.
        val dark = { x: Int, yy: Int -> img.pixels[yy * img.width + x] < 128 }
        val inkCols = (0 until img.width).filter { x -> (0 until img.height).any { dark(x, it) } }
        val runs = mutableListOf<IntRange>(); var start = -1; var blank = 0; var last = -1
        // مربع كلمة Tesseract قد يقصر عن حرفها الأخير (حرف أخير خارج المربع على S26): إذا قرأ ML Kit الكلمة نفسها بالنص نفسه،
        // فحبرها داخل مربع ML Kit مقروء أيضًا. لا يغيّر نصًا ولا يضيف كلمة.
        val covered = words.map { it.left..it.right } + words.mapNotNull { w -> agreeingMlWord(w, ml)?.let { it.left..it.right } }
        for (x in 0 until img.width) {
            val read = covered.any { x in (it.first - 2)..(it.last + 2) }
            val ink = !read && (0 until img.height).any { dark(x, it) }
            if (ink) { if (start < 0) start = x; last = x; blank = 0 }
            else if (start >= 0 && ++blank > 3) { runs += start..last; start = -1 }
        }
        if (start >= 0) runs += start..last
        var ellipsisLeft = false; var ellipsisRight = false
        fun dotsOnly(cols: IntRange): Boolean {
            val m = Array(img.height) { yy -> BooleanArray(img.width) { x -> x in cols && dark(x, yy) } }
            val comps = HeaderDetector.components(m, cols.first, 0, cols.last + 1, img.height).map { it.first }
            return comps.size in 2..3 && comps.all { it.w <= 9 && it.h <= 9 } && comps.all { it.cy > img.height / 2 - 4 }
        }
        // حبر غير مقروء (> 5px وليس «…» في طرف): لا يُرفض الاسم كله؛ الكلمة الملاصقة له مقطوعة ⇒ تُحذف هي فقط.
        val unread = mutableListOf<IntRange>()
        for (run in runs) {
            if (run.last - run.first + 1 <= 5) continue
            val atLeft = run.first <= (inkCols.firstOrNull() ?: 0) + 2
            val atRight = run.last >= (inkCols.lastOrNull() ?: 0) - 2
            when {
                atLeft && dotsOnly(run) -> ellipsisLeft = true
                atRight && dotsOnly(run) -> ellipsisRight = true
                else -> unread += run
            }
        }
        val inkNote = if (unread.isNotEmpty()) setOf(DecisionNote.UNREAD_INK_PRESENT) else emptySet()
        val kinds = words.map { kind(it.text) }
        val core = words.withIndex().zip(kinds).filter { it.second != Kind.DOTS }
        if (core.isEmpty()) return NameDecision(null, DecisionCode.ONLY_DOTS)

        // رقم الهاتف: الرقم كله أو لا شيء، وML Kit يؤكد كل الأرقام.
        if (core.all { it.second == Kind.NUM }) {
            if (unread.isNotEmpty()) return NameDecision(null, DecisionCode.PHONE_UNREAD_INK, notes = inkNote)
            val s = core.joinToString(" ") { it.first.value.text }
            if (!PHONE.matches(s)) return NameDecision(null, DecisionCode.NOT_A_PHONE)
            val mlText = ml.sortedBy { it.left }.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ").trim()
            return if (PHONE.matches(mlText) && mlText.filter { it.isDigit() } == s.filter { it.isDigit() }) NameDecision(s, DecisionCode.PHONE_CONFIRMED)
            else NameDecision(null, DecisionCode.PHONE_NOT_CONFIRMED)
        }

        // كل كلمة على حدة: الموثوقة تبقى بنصها كما قُرئ، وغير الموثوقة تُحذف وحدها.
        val hasAr = core.any { it.second == Kind.AR }
        val kept = mutableListOf<Pair<String, Kind>>() // (النص، النوع) من اليسار إلى اليمين
        val dropped = mutableListOf<DroppedWord>()
        for ((iw, k) in core) {
            val w = iw.value
            val touchesUnread = unread.any { r -> w.right + ADJACENT >= r.first && w.left - ADJACENT <= r.last }
            val (text, why) = when {
                touchesUnread -> null to DropCode.TOUCHES_UNREAD_INK
                k == Kind.MIX -> null to DropCode.MIXED_SCRIPT
                k == Kind.NUM -> null to DropCode.DIGITS_IN_NAME
                k == Kind.AR && w.conf < MIN_CONFIDENCE -> null to DropCode.ARABIC_LOW_CONFIDENCE
                k == Kind.AR -> w.text to null
                else -> latin(w, ml, hasAr)
            }
            if (text != null) kept += text to k else dropped += DroppedWord(iw.index, why!!)
        }
        // اسم لاتيني بالكامل (بلا كلمة عربية): الاسم كله أو NULL، بلا حذف جزئي.
        if (!hasAr && (dropped.isNotEmpty() || unread.isNotEmpty())) return NameDecision(null, DecisionCode.LATIN_ALL_OR_NOTHING, dropped, inkNote)
        if (kept.none { it.second == Kind.AR || it.first != "Clt" }) return NameDecision(null, DecisionCode.ALL_WORDS_UNRELIABLE, dropped, inkNote)

        val ltr = startsLtr(core.first().first.value.text)
        var name = visualOrder(kept, ltr)
        // «…» في طرف النهاية البصري فقط.
        val dotWords = words.zip(kinds).filter { it.second == Kind.DOTS }.map { it.first }
        val dotsLeft = ellipsisLeft || dotWords.any { it.right <= core.first().first.value.left }
        val dotsRight = ellipsisRight || dotWords.any { it.left >= core.last().first.value.right }
        if ((ltr && dotsLeft) || (!ltr && dotsRight)) return NameDecision(null, DecisionCode.DOTS_AT_START, dropped, inkNote)
        val notes = inkNote.toMutableSet()
        if ((!ltr && dotsLeft) || (ltr && dotsRight)) { name += " …"; notes += DecisionNote.NAME_TRUNCATED }
        if (kept.any { it.second == Kind.LAT }) notes += DecisionNote.LATIN_CONFIRMED_BY_MLKIT
        return NameDecision(name, DecisionCode.NAME_ACCEPTED, dropped, notes)
    }

    /** كلمة لاتينية: نصها إن أكّدها ML Kit، وإلا null مع السبب. */
    private fun latin(w: TessWord, ml: List<MlWord>, hasAr: Boolean): Pair<String?, DropCode?> {
        val m = ml.maxByOrNull { overlap(w, it) }?.takeIf { overlap(w, it) * 2 >= minOf(w.right - w.left, it.right - it.left) }
            ?: return null to DropCode.NOT_SEEN_BY_MLKIT
        val ratio = (m.right - m.left).toDouble() / (w.right - w.left).coerceAtLeast(1)
        if (ratio !in 0.7..1.43) return null to DropCode.MLKIT_WIDTH_MISMATCH
        if (hasAr && w.conf < MIN_CONFIDENCE) return null to DropCode.LATIN_LOW_CONFIDENCE_IN_MIXED
        val tag = w.text.lowercase() in TAGS && m.text.replace('I', 'l').lowercase() in TAGS
        return when {
            tag -> "Clt" to null
            w.text == m.text -> w.text to null
            norm(w.text) == norm(m.text) && w.conf >= MIN_CONFIDENCE -> w.text to null
            norm(w.text) == norm(m.text) -> null to DropCode.L_I_UNRESOLVED
            else -> null to DropCode.MLKIT_DIFFERS
        }
    }

    /**
     * اسم تقريبي حين لا تعطي القواعد الصارمة اسمًا: الهدف معرفة المرسل، لا الدقة الحرفية (المستخدم يراجع ويعدّل).
     * - كلمة لاتينية أو مختلطة أو أرقام وسط اسم: نص ML Kit فوقها إن كان لاتينيًا (أدق في اللاتيني)، وإلا نص Tesseract اللاتيني.
     * - كلمة عربية: نص Tesseract إن كانت ثقته ≥ [APPROX_MIN_CONFIDENCE] (أدنى منها غالبًا ضجيج).
     * - أرقام فقط (هاتف): الأرقام كما قُرئت. النقاط والرموز تُهمل.
     * @return NAME_APPROXIMATE بالاسم، أو null إن لم يبق شيء يُقرأ.
     */
    fun approximate(tesseract: List<TessWord>, ml: List<MlWord>): NameDecision? {
        val words = tesseract.sortedBy { it.left }.filter { kind(it.text) != Kind.DOTS }
        if (words.isEmpty()) {
            val latin = ml.sortedBy { it.left }.map { it.text }.filter { kind(it) == Kind.LAT }
            return latin.takeIf { it.isNotEmpty() }?.let { NameDecision(it.joinToString(" "), DecisionCode.NAME_APPROXIMATE) }
        }
        val allDigits = words.all { kind(it.text) == Kind.NUM }
        val kept = words.mapNotNull { w ->
            val k = kind(w.text)
            val over = ml.maxByOrNull { overlap(w, it) }?.takeIf { overlap(w, it) > 0 && kind(it.text) == Kind.LAT }?.text
            when {
                w.text.lowercase() in TAGS || over?.replace('I', 'l')?.lowercase() in TAGS -> "Clt" to Kind.LAT
                k == Kind.AR -> if (w.conf >= APPROX_MIN_CONFIDENCE) w.text to Kind.AR else null
                k == Kind.NUM && allDigits -> w.text to Kind.NUM
                over != null -> over to Kind.LAT
                k == Kind.LAT && w.conf >= APPROX_MIN_CONFIDENCE -> w.text to Kind.LAT
                else -> null
            }
        }
        if (kept.none { it.first != "Clt" }) return null
        // الكلمة اليسرى المقروءة غير عربية ⇒ LTR، كما في [decide].
        return NameDecision(visualOrder(kept, ltr = kept.first().second != Kind.AR), DecisionCode.NAME_APPROXIMATE)
    }

    const val APPROX_MIN_CONFIDENCE = 50

    /** الترتيب البصري كما في WhatsApp: الكلمة اليسرى في السطر لاتينية الخط ⇒ LTR، وإلا RTL. */
    private fun startsLtr(t: String) = t.any { it.isLetter() && it.code < 0x250 } && t.none { isAr(it) }

    /** [kept] من اليسار إلى اليمين ⇒ نص الاسم بترتيب القراءة. */
    private fun visualOrder(kept: List<Pair<String, Kind>>, ltr: Boolean): String {
        val groups = mutableListOf<MutableList<Pair<String, Kind>>>(); var gk: Kind? = null
        val seq = if (ltr) kept else kept.asReversed()
        for (p in seq) { if (p.second != gk) { groups += mutableListOf<Pair<String, Kind>>(); gk = p.second }; groups.last() += p }
        val parts = groups.map { g -> val k = g.first().second; if ((ltr && k == Kind.AR) || (!ltr && k == Kind.LAT)) g.asReversed() else g }
        return parts.flatten().joinToString(" ") { it.first }
    }

    /** كلمة ML Kit فوق [w] بالنص نفسه (أو بفرق l/I/1 فقط) وبعرض متقارب، أو null. */
    private fun agreeingMlWord(w: TessWord, ml: List<MlWord>): MlWord? {
        val m = ml.maxByOrNull { overlap(w, it) }?.takeIf { overlap(w, it) * 2 >= minOf(w.right - w.left, it.right - it.left) } ?: return null
        val ratio = (m.right - m.left).toDouble() / (w.right - w.left).coerceAtLeast(1)
        return m.takeIf { ratio in 0.7..1.43 && (w.text == m.text || norm(w.text) == norm(m.text)) }
    }

    private fun overlap(w: TessWord, m: MlWord) = (minOf(w.right, m.right) - maxOf(w.left, m.left)).coerceAtLeast(0)

    private fun norm(s: String) = s.map { if (it == 'I' || it == '1' || it == '|') 'l' else it }.joinToString("")
}
