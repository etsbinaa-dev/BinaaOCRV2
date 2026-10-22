package com.binaa.ocr.goldencorpus

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** الحالة المتوقعة لحقل، بنفس الحالات الثلاث المتفق عليها. */
@Serializable
enum class ExpectedStatus { FOUND, UNCERTAIN, NOT_FOUND }

/** الحقول التي يمكن أن تتوقعها حالة Corpus. كل قيمة بصيغة ثابتة واحدة (انظر [CorpusField.accepts]). */
@Serializable
enum class CorpusField {
    /** الطرف الآخر (من WhatsApp Header فقط). نص حر غير فارغ. */
    @SerialName("counterparty") COUNTERPARTY,

    /** المبلغ بصيغة قانونية واحدة: أرقام ثم نقطة ثم رقمان، مثل "18300.00". */
    @SerialName("amount") AMOUNT,

    /** التاريخ YYYY-MM-DD. */
    @SerialName("date") DATE,

    /** الوقت HH:MM. */
    @SerialName("time") TIME,

    /** طريقة الدفع / المزوّد: MASRVI أو BANKILY أو SEDAD أو BCIPAY أو BIMBANK أو OTHER. */
    @SerialName("provider") PROVIDER,

    /** رقم العملية أو المرجع، نص غير فارغ. */
    @SerialName("reference") REFERENCE;

    fun accepts(value: String): Boolean = when (this) {
        COUNTERPARTY, REFERENCE -> value.isNotBlank() && value == value.trim()
        AMOUNT -> AMOUNT_FORMAT.matches(value)
        DATE -> DATE_FORMAT.matches(value)
        TIME -> TIME_FORMAT.matches(value)
        PROVIDER -> value in PROVIDERS
    }

    private companion object {
        val AMOUNT_FORMAT = Regex("""(0|[1-9]\d*)\.\d{2}""")
        val DATE_FORMAT = Regex("""\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])""")
        val TIME_FORMAT = Regex("""([01]\d|2[0-3]):[0-5]\d""")
        val PROVIDERS = setOf("MASRVI", "BANKILY", "SEDAD", "BCIPAY", "BIMBANK", "CLICK", "OTHER")
    }
}

/**
 * ما يجب أن يعطيه النظام لحقل واحد:
 * - FOUND: قيمة واحدة إلزامية، ولا مرشّحين.
 * - UNCERTAIN: لا قيمة (لا يجوز اختيار تلقائي)، ومرشّحون اختياريون يجب أن يظهروا للمستخدم.
 * - NOT_FOUND: لا قيمة ولا مرشّحين.
 */
@Serializable
data class ExpectedField(
    val status: ExpectedStatus,
    val value: String? = null,
    val candidates: List<String> = emptyList(),
) {
    init {
        when (status) {
            ExpectedStatus.FOUND -> require(value != null && candidates.isEmpty()) { "FOUND needs exactly one value and no candidates" }
            ExpectedStatus.UNCERTAIN -> require(value == null) { "UNCERTAIN must not carry a value (nothing is picked automatically)" }
            ExpectedStatus.NOT_FOUND -> require(value == null && candidates.isEmpty()) { "NOT_FOUND carries no value and no candidates" }
        }
        require(candidates.distinct() == candidates) { "duplicate candidates" }
    }
}

/** مصدر صورة الحالة. الصور الحقيقية لا تدخل Git أبدًا: تبقى على جهاز المطوّر وحده. */
@Serializable
enum class CaseSource { SYNTHETIC, LOCAL_REAL }

/**
 * محتوى `expected.json` لحالة واحدة. القراءة صارمة: حقل ناقص أو غير معروف أو قيمة بغير صيغتها ترفض الحالة.
 * الحقول غير المذكورة في [fields] لا تُقيَّم.
 */
@Serializable
data class ExpectedCase(
    val schemaVersion: Int,
    val caseId: String,
    /** اسم ملف الصورة داخل مجلد الحالة نفسه (لا مسارات). */
    val image: String,
    val source: CaseSource,
    val fields: Map<CorpusField, ExpectedField> = emptyMap(),
    /** نصوص يجب أن تظهر في قراءة OCR (اختبار طبقة OCR نفسها). */
    val ocrContains: List<String> = emptyList(),
    val notes: String? = null,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "unsupported expected.json schema $schemaVersion" }
        require(CASE_ID.matches(caseId)) { "caseId must be 1-64 chars of letters, digits, '.', '_' or '-'" }
        require(IMAGE.matches(image)) { "image must be a file name in the case folder (.png, .jpg, .jpeg or .webp)" }
        require(fields.isNotEmpty() || ocrContains.isNotEmpty()) { "a case must check at least one field or OCR text" }
        require(ocrContains.all { it.isNotBlank() }) { "ocrContains entries must not be blank" }
        for ((field, expected) in fields) {
            (listOfNotNull(expected.value) + expected.candidates).forEach {
                require(field.accepts(it)) { "${field.name}: value not in its canonical format" }
            }
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val FILE_NAME = "expected.json"

        private val CASE_ID = Regex("""[A-Za-z0-9][A-Za-z0-9._-]{0,63}""")
        private val IMAGE = Regex("""[A-Za-z0-9][A-Za-z0-9._-]{0,63}\.(png|jpg|jpeg|webp)""")

        private val json = Json { ignoreUnknownKeys = false }

        /** يقرأ `expected.json`، أو يرمي [CorpusFormatException] بسبب واضح. */
        fun parse(text: String): ExpectedCase = try {
            json.decodeFromString(serializer(), text)
        } catch (e: SerializationException) {
            throw CorpusFormatException("invalid expected.json: ${e.message?.lineSequence()?.firstOrNull()}")
        } catch (e: IllegalArgumentException) {
            throw CorpusFormatException("invalid expected.json: ${e.message}")
        }
    }
}

class CorpusFormatException(message: String) : Exception(message)
