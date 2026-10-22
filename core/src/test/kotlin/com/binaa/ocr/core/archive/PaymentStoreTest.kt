package com.binaa.ocr.core.archive

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalTime

class PaymentStoreTest {

    private val root: File = Files.createTempDirectory("payments").toFile()
    private var now = 1_000L
    private val store = PaymentStore(File(root, PaymentStore.DIR_NAME)) { now }

    @After
    fun clean() {
        root.deleteRecursively()
    }

    private fun image(name: String = "shot.png", bytes: ByteArray = byteArrayOf(1, 2, 3, 4)): File =
        File(root, "intake").apply { mkdirs() }.let { File(it, name).apply { writeBytes(bytes) } }

    private fun save(name: String = "Karim Saleh", cents: Long = 620000, date: LocalDate = LocalDate.of(2026, 10, 1), time: LocalTime? = LocalTime.of(12, 38), img: File = image()) =
        store.save(name, cents, date, time, "BANKILY", "INCOMING", img)

    @Test
    fun save_movesTheImage_andTheRecordIsPending() {
        val img = image(bytes = byteArrayOf(9, 8, 7))
        val r = save(img = img)
        assertFalse("the intake image is moved, not copied", img.exists())
        assertArrayEquals(byteArrayOf(9, 8, 7), store.imageOf(r).readBytes())
        assertEquals(PaymentStatus.PENDING, r.status)
        assertEquals("2026-10-01", r.date)
        assertEquals("12:38", r.time)
        assertEquals("6200", r.amountText)
        assertEquals(listOf(r), store.pending())
        assertTrue(store.archived().isEmpty())
    }

    @Test
    fun markEntered_movesToTheArchive_andBack() {
        val r = save()
        now = 2_000L
        val archived = store.markEntered(r.id)!!
        assertEquals(PaymentStatus.ARCHIVED, archived.status)
        assertEquals(2_000L, archived.archivedAtMillis)
        assertTrue(store.pending().isEmpty())
        assertEquals(listOf(archived), store.archived())
        val back = store.markPending(r.id)!!
        assertEquals(PaymentStatus.PENDING, back.status)
        assertNull(back.archivedAtMillis)
        assertNull(store.markEntered("00000000-0000-0000-0000-000000000000"))
    }

    @Test
    fun list_isNewestDateFirst() {
        val a = save(date = LocalDate.of(2026, 9, 22), img = image("a.png"))
        val b = save(date = LocalDate.of(2026, 10, 1), img = image("b.png"))
        val c = save(date = LocalDate.of(2026, 10, 1), time = LocalTime.of(9, 41), img = image("c.jpeg"))
        assertEquals(listOf(b.id, c.id, a.id), store.list().map { it.id })
        assertTrue(c.image.endsWith(".jpg"))
    }

    @Test
    fun aCorruptedRecord_isIgnored_notTheWholeList() {
        val r = save()
        File(store.dir, "broken.json").writeText("{ not json")
        File(store.dir, "00000000-0000-0000-0000-000000000001.json").writeText("""{"id":"00000000-0000-0000-0000-000000000001"}""")
        assertEquals(listOf(r.id), store.list().map { it.id })
    }

    @Test
    fun noTemporaryFilesAreLeft() {
        save()
        assertTrue(store.dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun theSameScreenshotSavedBefore_isADuplicate_evenWithOtherValues() {
        val first = save(img = image("a.png", byteArrayOf(5, 5, 5)))
        assertEquals(PaymentStore.sha256(store.imageOf(first)), first.imageSha256)
        val again = image("b.png", byteArrayOf(5, 5, 5))
        val d = store.duplicatesOf(PaymentStore.sha256(again), "Someone Else", 100, LocalDate.of(2020, 1, 1))
        assertEquals(listOf(PaymentStore.Duplicate(first, sameImage = true)), d)
    }

    @Test
    fun sameNameAmountAndDate_isADuplicate_ignoringCaseAndSpaces() {
        val first = save(name = "Karim Saleh", cents = 620000, date = LocalDate.of(2026, 10, 1))
        val d = store.duplicatesOf(null, "  karim   SALEH ", 620000, LocalDate.of(2026, 10, 1))
        assertEquals(listOf(PaymentStore.Duplicate(first, sameImage = false)), d)
        // اسم أو مبلغ أو تاريخ مختلف ⇒ ليست مكررة.
        assertTrue(store.duplicatesOf(null, "Karim Salem", 620000, LocalDate.of(2026, 10, 1)).isEmpty())
        assertTrue(store.duplicatesOf(null, "Karim Saleh", 620100, LocalDate.of(2026, 10, 1)).isEmpty())
        assertTrue(store.duplicatesOf(null, "Karim Saleh", 620000, LocalDate.of(2026, 10, 2)).isEmpty())
        assertTrue("incomplete draft", store.duplicatesOf(null, "Karim Saleh", null, LocalDate.of(2026, 10, 1)).isEmpty())
    }

    @Test
    fun aRecordWithoutAnImageHash_fromAnOlderVersion_isStillRead() {
        val r = save()
        val json = File(store.dir, "${r.id}.json")
        json.writeText(json.readText().replace(Regex(""",\s*"imageSha256":\s*"[0-9a-f]+""""), ""))
        assertEquals(null, store.list().single().imageSha256)
    }

    @Test
    fun delete_removesTheRecordAndItsImage_only() {
        val keep = save(img = image("a.png"))
        val gone = save(img = image("b.png", byteArrayOf(7)))
        val goneImage = store.imageOf(gone)
        assertTrue(store.delete(gone.id))
        assertEquals(listOf(keep.id), store.list().map { it.id })
        assertFalse(goneImage.exists())
        assertTrue(store.imageOf(keep).exists())
        assertFalse("already gone", store.delete(gone.id))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aBlankName_isRefused() {
        save(name = "  ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun aZeroAmount_isRefused() {
        save(cents = 0)
    }

    @Test
    fun parseAmount_acceptsTheReceiptAndTypedForms() {
        assertEquals(620000L, PaymentStore.parseAmount("6200"))
        assertEquals(620000L, PaymentStore.parseAmount("6 200"))
        assertEquals(620000L, PaymentStore.parseAmount("6.200"))
        assertEquals(620000L, PaymentStore.parseAmount("6,200"))
        assertEquals(620050L, PaymentStore.parseAmount("6200.50"))
        assertEquals(620050L, PaymentStore.parseAmount("6200,5"))
        assertEquals(7465000L, PaymentStore.parseAmount("74.650,00"))
        assertEquals(3000000L, PaymentStore.parseAmount("30 000,00"))
        assertNull(PaymentStore.parseAmount(""))
        assertNull(PaymentStore.parseAmount("0"))
        assertNull(PaymentStore.parseAmount("12a"))
        assertNull(PaymentStore.parseAmount("1.2.3"))
    }

    @Test
    fun exportThenImport_intoAnEmptyStore_restoresRecordsAndImages() {
        val a = save(img = image(bytes = byteArrayOf(5, 6)))
        val b = save(name = "Sara Ali", img = image())
        store.markEntered(b.id)
        val zip = java.io.ByteArrayOutputStream()
        assertEquals(2, store.exportTo(zip))
        val other = PaymentStore(File(root, "restored")) { now }
        assertEquals(2, other.importFrom(zip.toByteArray().inputStream()))
        assertEquals(store.list(), other.list())
        assertArrayEquals(byteArrayOf(5, 6), other.imageOf(a).readBytes())
        assertEquals("importing the same backup again adds nothing", 0, other.importFrom(zip.toByteArray().inputStream()))
    }

    @Test
    fun import_ignoresPathsAndUnknownFiles() {
        val zip = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(zip).use { z ->
            for (n in listOf("../evil.json", "notes.txt", "00000000-0000-0000-0000-000000000000.png")) {
                z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(byteArrayOf(1)); z.closeEntry()
            }
        }
        assertEquals(0, store.importFrom(zip.toByteArray().inputStream()))
        assertFalse(File(root, "evil.json").exists())
        assertTrue("an image without its record is not kept", store.dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun prune_removesOnlyOldArchivedImages_andKeepsTheRecords() {
        val old = save(img = image())
        val recent = save(name = "Sara Ali", img = image())
        val pending = save(name = "Omar", img = image())
        store.markEntered(old.id)
        now += 50L * 86_400_000L
        store.markEntered(recent.id)
        now += 50L * 86_400_000L
        assertEquals(1, store.pruneArchivedImages())
        assertFalse(store.imageOf(old).exists())
        assertTrue(store.imageOf(recent).exists())
        assertTrue(store.imageOf(pending).exists())
        assertEquals(3, store.list().size)
    }
}
