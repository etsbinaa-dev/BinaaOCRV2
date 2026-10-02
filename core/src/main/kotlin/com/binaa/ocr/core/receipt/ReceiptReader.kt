package com.binaa.ocr.core.receipt

import com.binaa.ocr.core.ocr.OcrDocument
import com.binaa.ocr.core.ocr.PixelBox
import java.time.LocalDate
import java.time.LocalTime

/** تطبيقات الدفع المدعومة الآن (كل واحد بصيغه المعروفة فقط). */
enum class Provider { BANKILY, BIMBANK, SEDAD, BCIPAY, MASRVI }

enum class FieldStatus { FOUND, UNCERTAIN, NOT_FOUND }

/** لماذا لم يُعطَ الحقل قيمة مؤكدة. */
enum class FieldReason {
    /** لا تطبيق دفع معروف في اللقطة: لا تُقرأ صيغ لا نعرفها. */
    UNKNOWN_PROVIDER,

    /** علامات أكثر من تطبيق في اللقطة نفسها. */
    CONFLICTING_PROVIDERS,

    /** أكثر من قيمة مختلفة في الإيصال: لا اختيار تلقائي. */
    CONFLICTING_VALUES,

    /** لا قيمة بصيغة هذا التطبيق. */
    NO_VALUE,
}

/**
 * حقل واحد من الإيصال: FOUND بقيمة واحدة؛ UNCERTAIN بلا قيمة ومع المرشّحين ليختار المستخدم؛ NOT_FOUND بلا قيمة.
 * لا اختيار تلقائي بين مرشّحين أبدًا.
 */
data class ReceiptField<T>(val status: FieldStatus, val value: T?, val candidates: List<T>, val reason: FieldReason?) {
    init {
        when (status) {
            FieldStatus.FOUND -> require(value != null && candidates.isEmpty() && reason == null) { "FOUND carries one value" }
            FieldStatus.UNCERTAIN -> require(value == null && reason != null) { "UNCERTAIN carries no value" }
            FieldStatus.NOT_FOUND -> require(value == null && candidates.isEmpty() && reason != null) { "NOT_FOUND carries nothing" }
        }
    }

    companion object {
        fun <T> found(value: T) = ReceiptField(FieldStatus.FOUND, value, emptyList(), null)
        fun <T> notFound(reason: FieldReason) = ReceiptField<T>(FieldStatus.NOT_FOUND, null, emptyList(), reason)

        /** قيمة واحدة ⇒ FOUND؛ عدة قيم مختلفة ⇒ UNCERTAIN بها كلها؛ لا شيء ⇒ NOT_FOUND. */
        fun <T> of(values: List<T>): ReceiptField<T> {
            val distinct = values.distinct()
            return when (distinct.size) {
                0 -> notFound(FieldReason.NO_VALUE)
                1 -> found(distinct.single())
                else -> ReceiptField(FieldStatus.UNCERTAIN, null, distinct, FieldReason.CONFLICTING_VALUES)
            }
        }
    }
}

/** المبلغ بالأوقية الجديدة (MRU)، بالسنتيم لتفادي الكسور العشرية. [canonical] مثل "6200.00". */
data class Amount(val cents: Long) {
    val canonical: String get() = "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
    override fun toString() = canonical
}

/** تاريخ الدفع، والوقت إن وُجد في السطر نفسه. */
data class PaymentDate(val date: LocalDate, val time: LocalTime?)

data class ReceiptReading(
    val provider: ReceiptField<Provider>,
    val amount: ReceiptField<Amount>,
    val date: ReceiptField<PaymentDate>,
)

/**
 * المبلغ والتاريخ من نص إيصال الدفع (ML Kit على اللقطة كاملة)، لثلاثة تطبيقات بصيغها المعروفة فقط:
 *
 * - **بنكيلي** (بالعربية أو الفرنسية): يُعرَف بمعرّف المعاملة (19 رقمًا) الذي يحمل تاريخ ووقت العملية YYMMDDhhmm بعد أول
 *   رقمين، ويطابق سطر التاريخ والوقت (YY-MM-DD أو DD-MM-YY، hh:mm:ss). المطابقة نفسها تحسم ترتيب التاريخ. المبلغ = الرقم الملاصق لـ«MRU»
 *   (في سطره، أو آخر رقم من جهة اليسار في السطر السابق إن نزلت MRU وحدها إلى سطر جديد).
 * - **BIMBANK** و**السداد** و**BCI Pay** (بالعربية أو الفرنسية): تُعرف بشعارها المكتوب لاتينيًا. المبلغ بصيغة 74.650,00
 *   أو 30 000,00 (مسافة بين الآلاف، وقد يقرؤها ML Kit كلمتين متجاورتين)؛ التاريخ DD-MM-YYYY.
 *
 * - **مصرفي**: يُعرف بشعاره أو بعنوان «Détails du transfert». المبلغ = مبلغ التحويل (45 500,00)، لا ما خُصم من المرسل
 *   مع الرسوم (−45 556,50، سالب) ولا الرسوم نفسها (Taxe/Frais). التاريخ DD/MM/YYYY.
 *
 * لا يُقرأ شيء من رأس WhatsApp (فيه تاريخ الرسالة) ولا من شريط الرد أسفل الشاشة: فقط ما بين [bodyTop] و[bodyBottom].
 * رقم من 8 أرقام أو أكثر بلا فواصل (هاتف، حساب، معرّف) لا يكون مبلغًا أبدًا.
 */
object ReceiptReader {

    fun read(document: OcrDocument, bodyTop: Int, bodyBottom: Int = (document.image.height * 0.92).toInt()): ReceiptReading {
        val all = tokens(document)
        val words = all.filter { it.box.top >= bodyTop && it.box.bottom <= bodyBottom }
        val rows = rows(words)
        // شعار التطبيق قد يختفي نصفه تحت شريط WhatsApp: يكفي أن ينزل تحته. ما كان كله داخل الشريط (اسم جهة الاتصال) لا يُقرأ.
        val logos = all.filter { it.box.bottom > bodyTop && it.box.bottom <= bodyBottom }

        val bankilyDates = bankilyDates(words, rows)
        val found = buildList {
            if (bankilyDates.isNotEmpty()) add(Provider.BANKILY)
            if (logos.any { BIMBANK.containsMatchIn(it.text) }) add(Provider.BIMBANK)
            if (logos.any { SEDAD.matches(it.text) }) add(Provider.SEDAD)
            if (logos.any { BCIPAY.matches(it.text) }) add(Provider.BCIPAY)
            // «Détails du transfert» متتالية في سطر واحد (بنكيلي بالفرنسية فيه «détails» و«Transfert» منفصلتين).
            val masrviTitle = rows.any { row -> row.windowed(3).any { (a, b, c) -> DETAILS.matches(a.text) && b.text.equals("du", true) && TRANSFERT.matches(c.text) } }
            if (logos.any { MASRVI.matches(it.text) } || masrviTitle) add(Provider.MASRVI)
        }
        return when (found.size) {
            // بلا شعار مقروء: معرّف «TR…» يخص السداد وBCI Pay (الصيغ نفسها: 30 000,00 و DD-MM-YYYY) ⇒ التطبيق غير مؤكد
            // بينهما، والمبلغ والتاريخ يُقرآن بصيغتهما المشتركة.
            0 -> if (words.any { TR_ID.matches(it.text) }) {
                european(Provider.SEDAD, words, rows).copy(
                    provider = ReceiptField(FieldStatus.UNCERTAIN, null, listOf(Provider.SEDAD, Provider.BCIPAY), FieldReason.CONFLICTING_PROVIDERS),
                )
            } else {
                unread(ReceiptField.notFound(FieldReason.UNKNOWN_PROVIDER), FieldReason.UNKNOWN_PROVIDER)
            }
            1 -> when (found.single()) {
                Provider.BANKILY -> ReceiptReading(ReceiptField.found(Provider.BANKILY), ReceiptField.of(bankilyAmounts(rows)), ReceiptField.of(bankilyDates))
                Provider.BIMBANK -> european(Provider.BIMBANK, words, rows)
                Provider.SEDAD -> european(Provider.SEDAD, words, rows)
                Provider.BCIPAY -> european(Provider.BCIPAY, words, rows)
                Provider.MASRVI -> european(Provider.MASRVI, words, rows).let { r ->
                    // نافذة «Vous avez envoyé 45500.00 MRU le …»: المبلغ بعد «envoyé» مباشرة (لا «total TTC»).
                    val sent = words.windowed(2).filter { (a, _) -> ENVOYE.matches(a.text) }.mapNotNull { (_, b) -> dottedAmount(b.text) }
                    val european = r.amount.value?.let { listOf(it) } ?: r.amount.candidates
                    r.copy(amount = ReceiptField.of(european + sent))
                }
            }
            else -> unread(ReceiptField(FieldStatus.UNCERTAIN, null, found, FieldReason.CONFLICTING_PROVIDERS), FieldReason.CONFLICTING_PROVIDERS)
        }
    }

    private fun unread(provider: ReceiptField<Provider>, reason: FieldReason) =
        ReceiptReading(provider, ReceiptField.notFound(reason), ReceiptField.notFound(reason))

    /**
     * كلمات المستند بنصها بلا فراغات ولا نقطتين في طرفيها («6200:» ⇒ «6200»، «:» وحدها تُهمل).
     * ML Kit يُلصق نقطتي «المبلغ المرسل:» بالرقم المجاور في السطر العربي.
     */
    private fun tokens(document: OcrDocument): List<Token> =
        document.words.mapNotNull { w -> w.box?.let { Token(w.text.trim().trim(':', '：', '؛', ';').trim(), it) } }.filter { it.text.isNotEmpty() }

    /**
     * للتشخيص فقط (يُعرض على شاشة المستخدم، لا يُسجَّل ولا يُحفظ): صفوف جسم الإيصال كما قرأها ML Kit، من اليسار إلى اليمين،
     * فقط الصفوف التي فيها رقم أو MRU أو شعار.
     */
    fun bodyRows(document: OcrDocument, bodyTop: Int, bodyBottom: Int = (document.image.height * 0.92).toInt()): List<String> {
        val words = tokens(document).filter { it.box.bottom > bodyTop && it.box.bottom <= bodyBottom }
        return rows(words).filter { row -> row.any { t -> t.text.any { it.isDigit() } || t.text.contains("MRU", true) } }
            .take(40)
            .map { row -> "y${row.first().box.top}: " + row.joinToString("  ") { "«${it.text}»" } }
    }

    private class Token(val text: String, val box: PixelBox) {
        val cy get() = (box.top + box.bottom) / 2
    }

    /** صفوف بصرية: كلمات يتقاطع مداها العمودي مع منتصف الصف، مرتّبة من اليسار. */
    private fun rows(words: List<Token>): List<List<Token>> {
        val out = mutableListOf<MutableList<Token>>()
        for (w in words.sortedBy { it.cy }) {
            val row = out.lastOrNull()
            if (row != null && w.cy in row.minOf { it.box.top }..row.maxOf { it.box.bottom }) row += w else out += mutableListOf(w)
        }
        return out.map { r -> r.sortedBy { it.box.left } }
    }

    private fun timeIn(row: List<Token>): LocalTime? =
        row.mapNotNull { TIME.matchEntire(it.text) }.firstNotNullOfOrNull { m ->
            runCatching { LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].ifEmpty { "0" }.toInt()) }.getOrNull()
        }

    // ------------------------------------------------------------------ بنكيلي

    /**
     * تواريخ بنكيلي التي يؤكدها معرّف المعاملة (الأرقام 3–12 = YYMMDDhhmm). الواجهة العربية تُظهر YY-MM-DD والفرنسية DD-MM-YY:
     * يُجرَّب الترتيبان، ويُقبل فقط ما يطابق المعرّف، فالمعرّف نفسه يحسم الترتيب.
     */
    private fun bankilyDates(words: List<Token>, rows: List<List<Token>>): List<PaymentDate> {
        // «ID:1126100109413164250»: ML Kit قد يُلصق التسمية بالمعرّف، فتُؤخذ الأرقام الـ19 من داخل الكلمة.
        val ids = words.mapNotNull { ID_IN_WORD.find(it.text)?.groupValues?.get(1) }.map { it.substring(2, 12) }.toSet()
        if (ids.isEmpty()) return emptyList()
        return rows.flatMap { row ->
            val time = timeIn(row) ?: return@flatMap emptyList()
            // «26-10-02:cöglg»: ML Kit قد يُلصق بالتاريخ بقايا الكلمة العربية المجاورة، فيُؤخذ التاريخ من داخل الكلمة.
            row.flatMap { SHORT_DATE.findAll(it.text) }.flatMap { m ->
                val (a, mm, c) = m.destructured
                val hhmm = time.hour.toString().padStart(2, '0') + time.minute.toString().padStart(2, '0')
                listOf(Triple(a, mm, c), Triple(c, mm, a)).distinct().mapNotNull { (yy, month, dd) ->
                    if (yy + month + dd + hhmm !in ids) return@mapNotNull null
                    runCatching { PaymentDate(LocalDate.of(2000 + yy.toInt(), month.toInt(), dd.toInt()), time) }.getOrNull()
                }
            }
        }
    }

    /**
     * مبالغ بنكيلي: الرقم الملاصق لـ«MRU». نافذة النجاح تكتب «المستفيد: <الهاتف> المبلغ المرسل: <المبلغ> MRU»، فالمبالغ
     * في سطر فيه رقم هاتف المستفيد (8 أرقام) تُقدَّم على ما سواها (مثل بقايا الشاشة خلف النافذة).
     */
    private fun bankilyAmounts(rows: List<List<Token>>): List<Amount> {
        val withPhone = mutableListOf<Amount>()
        val others = mutableListOf<Amount>()
        rows.forEachIndexed { r, row ->
            fun collect(source: List<Token>, amounts: List<Amount>) =
                (if (source.any { PHONE.matches(it.text) }) withPhone else others).addAll(amounts)
            row.forEachIndexed { i, token ->
                if (token.text.equals("MRU", ignoreCase = true)) {
                    val beside = listOfNotNull(row.getOrNull(i - 1), row.getOrNull(i + 1)).mapNotNull { amountOf(it.text) }
                    if (beside.isNotEmpty()) collect(row, beside)
                    // MRU وحدها في سطرها: نزلت من السطر السابق، فالمبلغ أول رقم من جهة اليسار (نهاية السطر العربي).
                    else if (row.size == 1) rows.getOrNull(r - 1)?.let { prev -> collect(prev, listOfNotNull(prev.firstNotNullOfOrNull { amountOf(it.text) })) }
                }
                MRU_JOINED.matchEntire(token.text)?.let { m -> collect(row, listOfNotNull(amountOf(m.groupValues[1].ifEmpty { m.groupValues[2] }))) }
            }
        }
        return withPhone.ifEmpty { others }
    }

    // ------------------------------------------------------------------ BIMBANK والسداد

    private fun european(provider: Provider, words: List<Token>, rows: List<List<Token>>): ReceiptReading {
        val amounts = rows.flatMap { joinedNumbers(it) }.mapNotNull { EUROPEAN_AMOUNT.matchEntire(it) }.map { m ->
            Amount(m.groupValues[1].replace(Regex("[. ]"), "").toLong() * 100 + m.groupValues[2].toLong())
        }
        val dates = rows.flatMap { row ->
            row.mapNotNull { LONG_DATE.matchEntire(it.text) }.mapNotNull { m ->
                val (dd, mm, yyyy) = m.destructured.let { d -> Triple(d.component1(), d.component3(), d.component4()) }
                runCatching { PaymentDate(LocalDate.of(yyyy.toInt(), mm.toInt(), dd.toInt()), timeIn(row)) }.getOrNull()
            }
        }
        return ReceiptReading(ReceiptField.found(provider), ReceiptField.of(amounts), ReceiptField.of(dates))
    }

    /**
     * نصوص الأرقام في الصف: الكلمات الرقمية المتجاورة جدًّا (فراغ أقل من ارتفاع الكلمة) تُضم بمسافة كرقم واحد
     * («30» + «000,00» ⇒ «30 000,00»)، ولا تُحسب أجزاؤه وحدها. إن لم يكن المضموم مبلغًا صالحًا تُعاد الكلمات منفردة.
     */
    private fun joinedNumbers(row: List<Token>): List<String> {
        val runs = mutableListOf<MutableList<Token>>()
        for (t in row) {
            if (!NUMERIC.matches(t.text)) continue
            val run = runs.lastOrNull()
            val prev = run?.last()
            if (prev != null && row.indexOf(prev) == row.indexOf(t) - 1 && t.box.left - prev.box.right in 0 until t.box.height) run += t
            else runs += mutableListOf(t)
        }
        // مبلغ سالب (−45 556,50: ما خُصم من المرسل مع الرسوم) أو رسوم (Taxe / Frais قبله مباشرة) ليس مبلغ التحويل.
        runs.removeAll { run ->
            run.first().text.first() in "-−–" || row.getOrNull(row.indexOf(run.first()) - 1)?.text?.let { FEE.matches(it) } == true
        }
        return runs.flatMap { run ->
            val joined = run.joinToString(" ") { it.text }
            if (run.size == 1 || EUROPEAN_AMOUNT.matches(joined)) listOf(joined) else run.map { it.text }
        }
    }

    /** مبلغ بنكيلي: 6200 أو 6.200 أو 6,200 أو 6200.00، وليس رقمًا طويلًا بلا فواصل (هاتف أو معرّف). */
    private fun amountOf(text: String): Amount? {
        PLAIN_AMOUNT.matchEntire(text)?.let { m -> return Amount(m.groupValues[1].toLong() * 100 + m.groupValues[2].ifEmpty { "0" }.toLong()) }
        GROUPED_AMOUNT.matchEntire(text)?.let { m -> return Amount(m.groupValues[1].replace(Regex("[.,]"), "").toLong() * 100) }
        return null
    }

    private val ID_IN_WORD = Regex("""(?<!\d)(\d{19})(?!\d)""")
    private val ENVOYE = Regex("""(?i)envoy[ée]e?""")

    /** «45500.00» أو «45500» أو «45 500,00»: مبلغ بعد «envoyé». */
    private fun dottedAmount(text: String): Amount? =
        Regex("""([1-9]\d{0,6})(?:[.,](\d{2}))?""").matchEntire(text)?.let { m -> Amount(m.groupValues[1].toLong() * 100 + m.groupValues[2].ifEmpty { "0" }.toLong()) }

    private val BANKILY_ID = Regex("""\d{19}""")
    private val PHONE = Regex("""[234]\d{7}""")
    private val SHORT_DATE = Regex("""(?<!\d)(\d{2})-(\d{2})-(\d{2})(?!\d)""")
    /** DD-MM-YYYY أو DD/MM/YYYY (الفاصل نفسه مرتين). */
    private val LONG_DATE = Regex("""(\d{2})([-/])(\d{2})\2(\d{4})""")
    private val TIME = Regex("""(\d{2}):(\d{2})(?::(\d{2}))?""")
    private val BIMBANK = Regex("""(?i)bimbank|^BIM-""")
    private val SEDAD = Regex("""(?i)sedad""")
    /** «BCIpay»، وقد يقرأ ML Kit حرف I كـl أو 1. */
    private val BCIPAY = Regex("""(?i)bc[il1|]\s?pay""")
    /** معرّف معاملة السداد وBCI Pay: TR ثم 9–11 رقمًا (وقد يُقرأ الصفر الأول O). */
    private val TR_ID = Regex("""TR[0O]\d{8,10}""")
    private val NUMERIC = Regex("""[-−–]?[\d.,]+""")
    private val MASRVI = Regex("""(?i)masrvi|مصرفي""")
    private val DETAILS = Regex("""(?i)d[ée]tails""")
    private val TRANSFERT = Regex("""(?i)transfert""")
    private val FEE = Regex("""(?i)taxes?|frais|tax|fees?|commission|رسوم|ضريبة""")
    /** 74.650,00 أو 30 000,00 أو 7200,00. لا يبدأ بصفر (فـ«000,00» وحدها، نصف «30 000,00»، ليست مبلغًا). */
    private val EUROPEAN_AMOUNT = Regex("""([1-9]\d{0,2}(?:[. ]\d{3})+|[1-9]\d*|0),(\d{2})""")

    /** 1 إلى 7 أرقام، وكسر بنقطة ورقمين اختياري. */
    private val PLAIN_AMOUNT = Regex("""([1-9]\d{0,6})(?:\.(\d{2}))?""")

    /** فاصل آلاف (نقطة أو فاصلة) قبل كل 3 أرقام. */
    private val GROUPED_AMOUNT = Regex("""([1-9]\d{0,2}(?:[.,]\d{3})+)""")

    /** «MRU6200» أو «6200MRU» ملتصقين في كلمة واحدة. */
    private val MRU_JOINED = Regex("""(?i)MRU(\d[\d.,]*)|(\d[\d.,]*)MRU""")
}
