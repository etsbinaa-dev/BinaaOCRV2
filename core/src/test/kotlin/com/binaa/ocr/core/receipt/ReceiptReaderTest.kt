package com.binaa.ocr.core.receipt

import com.binaa.ocr.core.ocr.ImageSize
import com.binaa.ocr.core.ocr.OcrDocument
import com.binaa.ocr.core.ocr.OcrDocumentBuilder
import com.binaa.ocr.core.ocr.OcrEngineInfo
import com.binaa.ocr.core.ocr.PixelBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * قراءة المبلغ والتاريخ بنفس تخطيط لقطات S26 الحقيقية (مواضع ML Kit تقريبية من لقطات بنكيلي وBIMBANK والسداد)،
 * بأرقام وهمية: الهواتف والمبالغ والمعرّفات والتواريخ مخترعة. الكلمات العربية لا يقرؤها ML Kit اللاتيني فلا تظهر هنا.
 */
class ReceiptReaderTest {

    /** كلمة في الصورة: نص وموضع (يسار، أعلى، عرض). ارتفاع الكلمة 40 بكسل. */
    private class W(val text: String, val x: Int, val y: Int, val w: Int = 20 * text.length)

    private fun doc(vararg words: W): OcrDocument {
        val b = OcrDocumentBuilder(ImageSize(1080, 2225), OcrEngineInfo("test", "1"))
        // كتلة لكل كلمة: الترتيب والتجميع الهندسي من مواضعها فقط.
        for (w in words) {
            val box = PixelBox(w.x, w.y, w.x + w.w, w.y + 40)
            b.block(w.text, box) { line(w.text, box) { word(w.text, box) } }
        }
        return b.build()
    }

    private fun read(vararg words: W) = ReceiptReader.read(doc(*words), bodyTop = 192)

    private fun amount(v: String) = Amount(v.replace(".", "").toLong())

    // ------------------------------------------------------------------ بنكيلي

    /** نافذة «النقل ناجح!»: المستفيد ثم المبلغ وMRU في سطر واحد، ثم معرّف المعاملة، ثم التاريخ والوقت. */
    private fun bankily(amountRow: Array<W>, id: String = "1125091410151234567", date: String = "25-09-14", time: String = "10:15:42", vararg extra: W) =
        read(*amountRow, W(id, 148, 1082), W(time, 208, 1163), W(date, 404, 1163), *extra)

    @Test
    fun bankily_amountBesideMru_andTheDateConfirmedByTheTransactionId() {
        val r = bankily(arrayOf(W("MRU", 164, 1032), W("4500", 275, 1032), W("34111222", 599, 1032)))
        assertEquals(ReceiptField.found(Provider.BANKILY), r.provider)
        assertEquals(ReceiptField.found(amount("4500.00")), r.amount)
        assertEquals(ReceiptField.found(PaymentDate(LocalDate.of(2025, 9, 14), LocalTime.of(10, 15, 42))), r.date)
        assertEquals("4500.00", r.amount.value!!.canonical)
    }

    @Test
    fun bankily_mruWrappedToTheNextLine_takesTheAmountAtTheEndOfTheLineAbove() {
        // «المبلغ المرسل: 11900» ثم «MRU» وحدها في السطر التالي.
        val r = bankily(arrayOf(W("7300", 156, 887), W("34111222", 544, 887), W("MRU", 492, 962)))
        assertEquals(ReceiptField.found(amount("7300.00")), r.amount)
    }

    @Test
    fun bankily_thePhoneBesideMru_isNeverTheAmount() {
        val r = bankily(arrayOf(W("34111222", 300, 1032), W("MRU", 500, 1032)))
        assertEquals(FieldStatus.NOT_FOUND, r.amount.status)
        assertEquals(FieldReason.NO_VALUE, r.amount.reason)
    }

    @Test
    fun bankily_backgroundAmountBehindThePopup_isIgnored_whenThePhoneRowHasOne() {
        // بقايا الشاشة خلف النافذة: «MRU 6» نصف مخفية في سطر بلا هاتف.
        val r = bankily(arrayOf(W("MRU", 164, 1032), W("4500", 275, 1032), W("34111222", 599, 1032)), extra = arrayOf(W("MRU", 660, 550), W("6", 760, 550)))
        assertEquals(ReceiptField.found(amount("4500.00")), r.amount)
    }

    @Test
    fun bankily_twoDifferentAmountsBesideMru_areUncertain_withBoth() {
        val r = bankily(arrayOf(W("MRU", 164, 1032), W("4500", 275, 1032), W("34111222", 599, 1032)), extra = arrayOf(W("5400", 156, 887), W("34111222", 544, 887), W("MRU", 492, 962)))
        assertEquals(FieldStatus.UNCERTAIN, r.amount.status)
        assertNull(r.amount.value)
        assertEquals(setOf(amount("4500.00"), amount("5400.00")), r.amount.candidates.toSet())
    }

    @Test
    fun bankily_dateThatTheTransactionIdDoesNotConfirm_isNotBankily_andNothingIsRead() {
        // المعرّف يحمل 2509141015 والسطر 25-09-15: لا تأكيد لترتيب التاريخ ⇒ لا نقرأ صيغة لا نعرفها.
        val r = bankily(arrayOf(W("MRU", 164, 1032), W("4500", 275, 1032), W("34111222", 599, 1032)), date = "25-09-15")
        assertEquals(FieldStatus.NOT_FOUND, r.provider.status)
        assertEquals(FieldReason.UNKNOWN_PROVIDER, r.amount.reason)
        assertEquals(FieldReason.UNKNOWN_PROVIDER, r.date.reason)
    }

    @Test
    fun bankily_impossibleDate_isNotRead() {
        val r = bankily(arrayOf(W("MRU", 164, 1032), W("4500", 275, 1032), W("34111222", 599, 1032)), id = "1125139910151234567", date = "25-13-99")
        assertEquals(FieldStatus.NOT_FOUND, r.provider.status)
    }

    // ------------------------------------------------------------------ السداد وBIMBANK

    @Test
    fun sedad_europeanAmount_andDayMonthYearDate_withTime() {
        val r = read(
            W("SEDAD", 362, 288), W("BANK", 520, 288),
            W("3.150,00", 277, 819),
            W("34111222", 84, 1107),
            W("12:05:09", 86, 1246), W("03-08-2026", 248, 1245),
            W("TR01234567890", 94, 1386),
        )
        assertEquals(ReceiptField.found(Provider.SEDAD), r.provider)
        assertEquals(ReceiptField.found(amount("3150.00")), r.amount)
        assertEquals(ReceiptField.found(PaymentDate(LocalDate.of(2026, 8, 3), LocalTime.of(12, 5, 9))), r.date)
    }

    @Test
    fun bimbank_amountInTheBannerAndTheReceipt_areTheSameValue() {
        // الإشعار أعلاه «41250» بلا كسور، والإيصال «41.250,00»، ورقم الحساب الطويل ليس مبلغًا.
        val r = read(
            W("06:44", 166, 325), W("41250", 537, 387), W("BIM-", 246, 410), W("00011112222333344445", 327, 414),
            W("bimbank", 400, 440),
            W("41.250,00", 497, 1033), W("00011112222333344445555", 233, 1114),
            W("13:20:01", 136, 1378), W("05-07-2026", 286, 1379),
        )
        assertEquals(ReceiptField.found(Provider.BIMBANK), r.provider)
        assertEquals(ReceiptField.found(amount("41250.00")), r.amount)
        assertEquals(ReceiptField.found(PaymentDate(LocalDate.of(2026, 7, 5), LocalTime.of(13, 20, 1))), r.date)
    }

    @Test
    fun european_twoDifferentAmounts_areUncertain() {
        val r = read(W("SEDAD", 362, 288), W("3.150,00", 277, 819), W("3.510,00", 277, 900), W("03-08-2026", 248, 1245))
        assertEquals(FieldStatus.UNCERTAIN, r.amount.status)
        assertEquals(listOf(amount("3150.00"), amount("3510.00")), r.amount.candidates)
    }

    @Test
    fun theWhatsAppHeaderDate_isNeverTheReceiptDate() {
        // سطر الرأس «22-09-2026» فوق bodyTop: لا يُقرأ. الإيصال بلا تاريخ ⇒ NOT_FOUND.
        val r = read(W("22-09-2026", 146, 113), W("SEDAD", 362, 288), W("3.150,00", 277, 819))
        assertEquals(FieldStatus.NOT_FOUND, r.date.status)
        assertEquals(FieldReason.NO_VALUE, r.date.reason)
    }

    @Test
    fun dateWithoutTime_isStillFound_withNoTime() {
        val r = read(W("SEDAD", 362, 288), W("3.150,00", 277, 819), W("03-08-2026", 248, 1245))
        assertEquals(ReceiptField.found(PaymentDate(LocalDate.of(2026, 8, 3), null)), r.date)
    }

    // ------------------------------------------------------------------ تطبيق غير معروف أو ملتبس

    @Test
    fun unknownProvider_readsNothing() {
        val r = read(W("MRU", 164, 1032), W("4500", 275, 1032), W("03-08-2026", 248, 1245))
        assertEquals(ReceiptField.notFound<Provider>(FieldReason.UNKNOWN_PROVIDER), r.provider)
        assertEquals(FieldStatus.NOT_FOUND, r.amount.status)
        assertEquals(FieldStatus.NOT_FOUND, r.date.status)
    }

    @Test
    fun twoProviders_areUncertain_andNothingIsRead() {
        val r = read(W("SEDAD", 362, 288), W("bimbank", 400, 440), W("3.150,00", 277, 819), W("03-08-2026", 248, 1245))
        assertEquals(FieldStatus.UNCERTAIN, r.provider.status)
        assertEquals(setOf(Provider.SEDAD, Provider.BIMBANK), r.provider.candidates.toSet())
        assertEquals(FieldReason.CONFLICTING_PROVIDERS, r.amount.reason)
    }

    @Test
    fun replyBarAtTheBottom_isNotRead() {
        val r = read(W("SEDAD", 362, 288), W("3.150,00", 277, 819), W("9.999,00", 277, 2100))
        assertEquals(ReceiptField.found(amount("3150.00")), r.amount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun foundWithCandidates_isRejected() {
        ReceiptField(FieldStatus.FOUND, 1, listOf(2), null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun uncertainWithAValue_isRejected() {
        ReceiptField(FieldStatus.UNCERTAIN, 1, listOf(1, 2), FieldReason.CONFLICTING_VALUES)
    }
}
