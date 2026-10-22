package com.binaa.ocr.core.archive

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** «للإدخال» = محفوظة ولم تُدخل في المحاسبة بعد؛ «الأرشيف» = أُدخلت (ضغطت «تم»). */
enum class PaymentStatus { PENDING, ARCHIVED }

/**
 * دفعة واحدة كما راجعها المستخدم وحفظها. القيم نصوص بصيغ قانونية ثابتة (JSON بسيط يُقرأ دائمًا):
 * [amountCents] بالسنتيم، [date] YYYY-MM-DD، [time] HH:MM أو null، [provider] و[direction] أسماء ثابتة أو null.
 * [image] = اسم ملف صورة الإيصال في مجلد الأرشيف نفسه.
 */
@Serializable
data class PaymentRecord(
    val id: String,
    val savedAtMillis: Long,
    val name: String,
    val amountCents: Long,
    val date: String,
    val time: String? = null,
    val provider: String? = null,
    val direction: String? = null,
    val image: String,
    val status: PaymentStatus = PaymentStatus.PENDING,
    val archivedAtMillis: Long? = null,
    /** SHA-256 لصورة الإيصال كما وصلت: نفس اللقطة مرتين ⇒ نفس البصمة. null في السجلات الأقدم. */
    val imageSha256: String? = null,
) {
    init {
        require(ID.matches(id)) { "bad id" }
        require(name.isNotBlank() && name == name.trim()) { "name must be non-blank and trimmed" }
        require(amountCents > 0) { "amount must be positive" }
        require(runCatching { LocalDate.parse(date) }.isSuccess) { "date must be YYYY-MM-DD" }
        require(time == null || (TIME.matches(time) && runCatching { LocalTime.parse(time) }.isSuccess)) { "time must be HH:MM" }
        require(provider == null || provider in PROVIDERS) { "unknown provider" }
        require(direction == null || direction in DIRECTIONS) { "unknown direction" }
        require(IMAGE.matches(image)) { "bad image file name" }
        require((status == PaymentStatus.ARCHIVED) == (archivedAtMillis != null)) { "archivedAt only for archived" }
        require(imageSha256 == null || SHA256.matches(imageSha256)) { "bad image hash" }
    }

    /** للعرض: «6200» أو «6200.50». */
    val amountText: String get() = (amountCents / 100).toString() + if (amountCents % 100 == 0L) "" else "." + (amountCents % 100).toString().padStart(2, '0')

    companion object {
        val PROVIDERS = setOf("BANKILY", "BIMBANK", "SEDAD", "BCIPAY", "MASRVI", "OTHER")
        val DIRECTIONS = setOf("INCOMING", "OUTGOING")
        private val ID = Regex("""[0-9a-f-]{36}""")
        private val TIME = Regex("""\d{2}:\d{2}""")
        private val IMAGE = Regex("""[0-9a-f-]{36}\.(png|jpg|webp)""")
        private val SHA256 = Regex("""[0-9a-f]{64}""")
    }
}

/**
 * أرشيف الدفعات المحلي: ملف JSON لكل دفعة وصورة إيصالها بجانبه، في مجلد خاص بالتطبيق.
 * كل كتابة ذرّية (ملف مؤقت ثم إعادة تسمية)، فلا يبقى ملف نصف مكتوب. لا شبكة ولا مشاركة.
 */
class PaymentStore(val dir: File, private val clock: () -> Long = System::currentTimeMillis) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = false }

    /** كل الدفعات: الأحدث تاريخًا أولًا. ملف تالف يُتجاهل (لا يُسقط القائمة كلها). */
    fun list(): List<PaymentRecord> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { f -> runCatching { json.decodeFromString(PaymentRecord.serializer(), f.readText(Charsets.UTF_8)) }.getOrNull() }
        .sortedWith(compareByDescending<PaymentRecord> { it.date }.thenByDescending { it.time ?: "" }.thenByDescending { it.savedAtMillis })

    fun pending() = list().filter { it.status == PaymentStatus.PENDING }

    fun archived() = list().filter { it.status == PaymentStatus.ARCHIVED }

    /**
     * يحفظ دفعة جديدة «للإدخال»، وينقل صورة الإيصال [image] إلى الأرشيف (إعادة تسمية داخل التخزين الخاص، بلا نسخ).
     * @return السجل المحفوظ.
     */
    fun save(
        name: String,
        amountCents: Long,
        date: LocalDate,
        time: LocalTime?,
        provider: String?,
        direction: String?,
        image: File,
    ): PaymentRecord {
        dir.mkdirs()
        val id = UUID.randomUUID().toString()
        val hash = sha256(image)
        val ext = image.extension.lowercase().let { if (it == "jpeg") "jpg" else it }
        val record = PaymentRecord(
            id = id,
            savedAtMillis = clock(),
            name = name.trim(),
            amountCents = amountCents,
            date = date.toString(),
            time = time?.let { it.hour.toString().padStart(2, '0') + ":" + it.minute.toString().padStart(2, '0') },
            provider = provider,
            direction = direction,
            image = "$id.$ext",
            imageSha256 = hash,
        )
        val target = File(dir, record.image)
        check(image.renameTo(target) || (image.copyTo(target).exists() && image.delete())) { "cannot move the receipt image" }
        try {
            write(record)
        } catch (e: Exception) {
            target.renameTo(image)
            throw e
        }
        return record
    }

    /** «تم»: أُدخلت في المحاسبة ⇒ إلى الأرشيف. */
    fun markEntered(id: String): PaymentRecord? {
        val current = list().firstOrNull { it.id == id } ?: return null
        if (current.status == PaymentStatus.ARCHIVED) return current
        return current.copy(status = PaymentStatus.ARCHIVED, archivedAtMillis = clock()).also { write(it) }
    }

    /** إعادة دفعة من الأرشيف إلى «للإدخال» (ضغطة «تم» بالخطأ). */
    fun markPending(id: String): PaymentRecord? {
        val current = list().firstOrNull { it.id == id } ?: return null
        return current.copy(status = PaymentStatus.PENDING, archivedAtMillis = null).also { write(it) }
    }

    fun imageOf(record: PaymentRecord): File = File(dir, record.image)

    /**
     * حذف نهائي (دفعة مكررة أو خاطئة): السجل أولًا، ثم صورة إيصاله. لا سلة محذوفات.
     * @return true إن حُذف السجل، false إن لم يوجد.
     */
    fun delete(id: String): Boolean {
        val current = list().firstOrNull { it.id == id } ?: return false
        if (!File(dir, "${current.id}.json").delete()) return false
        imageOf(current).delete()
        return true
    }

    /**
     * دفعات محفوظة قد تكون هي نفسها، للتنبيه فقط (لا منع): نفس اللقطة ([imageSha256])، أو نفس الاسم (بلا فرق حالة الأحرف
     * والفراغات) والمبلغ والتاريخ. نفس اللقطة أولًا.
     */
    fun duplicatesOf(imageSha256: String?, name: String, amountCents: Long?, date: LocalDate?, records: List<PaymentRecord> = list()): List<Duplicate> {
        val key = normalizedName(name)
        return records.mapNotNull { r ->
            when {
                imageSha256 != null && r.imageSha256 == imageSha256 -> Duplicate(r, sameImage = true)
                key.isNotEmpty() && amountCents != null && date != null &&
                    normalizedName(r.name) == key && r.amountCents == amountCents && r.date == date.toString() -> Duplicate(r, sameImage = false)
                else -> null
            }
        }.sortedByDescending { it.sameImage }
    }

    private fun write(record: PaymentRecord) {
        dir.mkdirs()
        val tmp = File(dir, "${record.id}.json.tmp")
        tmp.writeText(json.encodeToString(PaymentRecord.serializer(), record), Charsets.UTF_8)
        val target = File(dir, "${record.id}.json")
        check(tmp.renameTo(target)) { "cannot write the payment record" }
    }

    /**
     * نسخة احتياطية: كل السجلات وصورها في ملف ZIP واحد يكتبه المستخدم حيث يشاء (لا شبكة).
     * @return عدد الدفعات المصدَّرة.
     */
    fun exportTo(out: OutputStream): Int {
        val records = list()
        ZipOutputStream(out).use { zip ->
            for (r in records) {
                for (f in listOf(File(dir, "${r.id}.json"), imageOf(r))) {
                    if (!f.isFile) continue
                    zip.putNextEntry(ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        return records.size
    }

    /**
     * استرجاع نسخة [exportTo]: يضيف ما ليس موجودًا فقط (لا يستبدل دفعة موجودة بنفس المعرّف).
     * يقبل فقط أسماء ملفات السجلات والصور المعروفة (لا مسارات)، وسجلًا صالحًا فقط.
     * @return عدد الدفعات المضافة.
     */
    fun importFrom(input: InputStream): Int {
        dir.mkdirs()
        var added = 0
        val images = mutableListOf<String>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                when {
                    e.isDirectory -> Unit
                    RECORD_FILE.matches(name) -> {
                        val text = zip.readBytes().toString(Charsets.UTF_8)
                        val r = runCatching { json.decodeFromString(PaymentRecord.serializer(), text) }.getOrNull()
                        if (r != null && "${r.id}.json" == name && !File(dir, name).exists()) { write(r); added++ }
                    }
                    IMAGE_FILE.matches(name) -> {
                        val target = File(dir, name)
                        if (!target.exists()) {
                            val tmp = File(dir, "$name.tmp")
                            tmp.outputStream().use { zip.copyTo(it) }
                            if (!tmp.renameTo(target)) tmp.delete()
                            images += name
                        }
                    }
                }
            }
        }
        // صورة بلا سجل (نسخة ناقصة): لا تبقى يتيمة.
        val used = list().map { it.image }.toSet()
        for (n in images) if (n !in used) File(dir, n).delete()
        return added
    }

    /**
     * يحذف صور الإيصالات المؤرشفة منذ أكثر من [days] يومًا، ويُبقي بيانات الدفعة. لا يمس «للإدخال».
     * @return عدد الصور المحذوفة.
     */
    fun pruneArchivedImages(days: Int = ARCHIVE_IMAGE_DAYS): Int {
        val limit = clock() - days * 86_400_000L
        return archived().count { r -> (r.archivedAtMillis ?: Long.MAX_VALUE) < limit && imageOf(r).let { it.isFile && it.delete() } }
    }

    /** [record] محفوظة من قبل: [sameImage] = نفس اللقطة، وإلا نفس الاسم والمبلغ والتاريخ. */
    data class Duplicate(val record: PaymentRecord, val sameImage: Boolean)

    companion object {
        const val DIR_NAME = "payments"

        /** صور الأرشيف تُحذف بعد هذه المدة من «تم»؛ بيانات الدفعة تبقى. */
        const val ARCHIVE_IMAGE_DAYS = 90
        private val RECORD_FILE = Regex("""[0-9a-f-]{36}\.json""")
        private val IMAGE_FILE = Regex("""[0-9a-f-]{36}\.(png|jpg|webp)""")

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private fun normalizedName(name: String) = name.trim().replace(Regex("""\s+"""), " ").trimEnd('.', '…', ' ').lowercase()

        /**
         * مبلغ كتبه المستخدم أو اقترحه القارئ: «6200»، «6 200»، «6.200»، «6200,50»، «6200.50»، «74.650,00».
         * @return السنتيم، أو null إن لم يكن مبلغًا موجبًا واضحًا.
         */
        fun parseAmount(text: String): Long? {
            val t = text.trim().replace(" ", "").replace(" ", "").replace(" ", "")
            val m = AMOUNT.matchEntire(t) ?: return null
            val units = m.groupValues[1].replace(".", "").replace(",", "").toLongOrNull() ?: return null
            val cents = m.groupValues[2].ifEmpty { "0" }.padEnd(2, '0').toLong()
            return (units * 100 + cents).takeIf { it > 0 }
        }

        /** وحدات بفواصل آلاف اختيارية (. أو ,) ثم كسر اختياري برقم أو رقمين بعد الفاصل الآخر. */
        private val AMOUNT = Regex("""(\d{1,3}(?:[.,]\d{3})+|\d+)(?:[.,](\d{1,2}))?""")
    }
}
