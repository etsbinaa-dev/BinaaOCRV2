package com.binaa.ocr.core.sync

import com.binaa.ocr.core.archive.PaymentRecord
import com.binaa.ocr.core.archive.PaymentStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SyncTest {

    private val idA = "00000000-0000-0000-0000-00000000000a"
    private val idB = "00000000-0000-0000-0000-00000000000b"
    private val record = PaymentRecord(id = idA, savedAtMillis = 1_000, name = "Karim Saleh", amountCents = 620_000, date = "2026-10-01", image = "$idA.png")

    // ------------------------------------------------------------------ المفتاح

    @Test
    fun key_qrRoundTrip_andCheckCodeIsStable() {
        val key = SyncKey.generate()
        val back = SyncKey.fromQr(key.toQr())
        assertEquals(key, back)
        assertEquals(key.checkCode, back!!.checkCode)
        assertEquals(6, key.checkCode.length)
        assertNotEquals(key.checkCode, SyncKey.generate().checkCode)
    }

    @Test
    fun key_otherQrCodes_areRefused() {
        assertNull(SyncKey.fromQr("https://example.com"))
        assertNull(SyncKey.fromQr("BINAA-SYNC-1:short"))
        assertNull(SyncKey.fromQr("BINAA-SYNC-1:!!!"))
    }

    // ------------------------------------------------------------------ التشفير

    @Test
    fun bundle_roundTrip() {
        val key = SyncKey.generate()
        val image = byteArrayOf(1, 2, 3, 4, 5)
        val sealed = SyncBundle.seal(key, record, image)
        val (r, img) = SyncBundle.open(key, idA, sealed)
        assertEquals(record, r)
        assertArrayEquals(image, img)
    }

    @Test
    fun bundle_hidesTheContent() {
        val sealed = SyncBundle.seal(SyncKey.generate(), record, "RECEIPT".toByteArray())
        val text = String(sealed, Charsets.ISO_8859_1)
        assertTrue(!text.contains("Karim") && !text.contains("RECEIPT") && !text.contains("620000"))
    }

    @Test
    fun bundle_wrongKey_tampered_orRenamed_isRefused() {
        val key = SyncKey.generate()
        val sealed = SyncBundle.seal(key, record, byteArrayOf(9))
        assertTrue(runCatching { SyncBundle.open(SyncKey.generate(), idA, sealed) }.isFailure)
        assertTrue(runCatching { SyncBundle.open(key, idA, sealed.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() }) }.isFailure)
        assertTrue("a bundle cannot pass as another payment", runCatching { SyncBundle.open(key, idB, sealed) }.isFailure)
    }

    // ------------------------------------------------------------------ الحالة

    @Test
    fun state_saveAndLoad() {
        val f = Files.createTempDirectory("sync").toFile().resolve("state.json")
        val s = SyncState(mapOf(idA to 5L), setOf(idB))
        SyncState.save(f, s)
        assertEquals(s, SyncState.load(f))
        f.parentFile.deleteRecursively()
        assertEquals(SyncState(), SyncState.load(f))
    }

    // ------------------------------------------------------------------ الخطة

    private fun p(id: String, v: Long, fileId: String = "f-$id-$v") = RemoteFile(fileId, SyncPlan.paymentName(id, v), 0)
    private fun d(id: String, modified: Long = 0) = RemoteFile("t-$id", SyncPlan.tombstoneName(id), modified)
    private fun plan(local: Map<String, Long>, remote: List<RemoteFile>, state: SyncState = SyncState(), now: Long = 0) =
        SyncPlan.plan(local, state, remote, now)

    @Test
    fun newHere_isUploaded_newThere_isDownloaded() {
        assertEquals(listOf(SyncAction.Upload(idA, emptyList()), SyncAction.Download("f-$idB-7", idB, 7)), plan(mapOf(idA to 5), listOf(p(idB, 7))))
    }

    @Test
    fun newerVersionWins_eitherWay() {
        assertEquals(listOf(SyncAction.Upload(idA, listOf("f-$idA-5"))), plan(mapOf(idA to 9), listOf(p(idA, 5))))
        assertEquals(listOf(SyncAction.Download("f-$idA-9", idA, 9)), plan(mapOf(idA to 5), listOf(p(idA, 9))))
    }

    @Test
    fun sameVersion_isMarkedSyncedOnce() {
        assertEquals(listOf(SyncAction.MarkSynced(idA, 5)), plan(mapOf(idA to 5), listOf(p(idA, 5))))
        assertEquals(emptyList<SyncAction>(), plan(mapOf(idA to 5), listOf(p(idA, 5)), SyncState(mapOf(idA to 5))))
    }

    @Test
    fun olderRemoteCopies_areCleanedUp() {
        assertEquals(listOf(SyncAction.DeleteRemote("f-$idA-3"), SyncAction.MarkSynced(idA, 5)), plan(mapOf(idA to 5), listOf(p(idA, 3), p(idA, 5))))
    }

    @Test
    fun deletedHere_tombstoneAndRemoteCopyRemoved() {
        val actions = plan(emptyMap(), listOf(p(idA, 5)), SyncState(mapOf(idA to 5), setOf(idA)))
        assertEquals(listOf(SyncAction.Tombstone(idA), SyncAction.DeleteRemote("f-$idA-5"), SyncAction.Forget(idA)), actions)
    }

    @Test
    fun deletedThere_isDeletedHere_evenIfChangedHere() {
        assertEquals(listOf(SyncAction.DeleteLocal(idA), SyncAction.Forget(idA)), plan(mapOf(idA to 9), listOf(d(idA)), SyncState(mapOf(idA to 5))))
    }

    @Test
    fun syncedBefore_andGoneFromTheCloud_unchangedHere_isDeletedHere() {
        assertEquals(listOf(SyncAction.DeleteLocal(idA), SyncAction.Forget(idA)), plan(mapOf(idA to 5), emptyList(), SyncState(mapOf(idA to 5))))
    }

    @Test
    fun syncedBefore_goneFromTheCloud_butRestoredHere_isUploadedAgain() {
        assertEquals(listOf(SyncAction.Upload(idA, emptyList())), plan(mapOf(idA to 9), emptyList(), SyncState(mapOf(idA to 5))))
    }

    @Test
    fun syncedBefore_andPrunedHere_isRemovedFromTheCloud() {
        assertEquals(listOf(SyncAction.DeleteRemote("f-$idA-5"), SyncAction.Forget(idA)), plan(emptyMap(), listOf(p(idA, 5)), SyncState(mapOf(idA to 5))))
    }

    @Test
    fun oldTombstones_areRemoved() {
        val week = SyncPlan.TOMBSTONE_KEEP_MILLIS
        assertEquals(emptyList<SyncAction>(), plan(emptyMap(), listOf(d(idA, modified = 10)), now = 10 + week))
        assertEquals(listOf(SyncAction.DeleteRemote("t-$idA")), plan(emptyMap(), listOf(d(idA, modified = 10)), now = 11 + week))
    }

    @Test
    fun unknownFiles_areIgnored() {
        assertEquals(emptyList<SyncAction>(), plan(emptyMap(), listOf(RemoteFile("x", "notes.txt", 0), RemoteFile("y", "p-bad-1.bin", 0))))
    }

    @Test
    fun recordVersion_followsItsChanges() {
        assertEquals(1_000, record.version)
        val archived = record.copy(status = PaymentStatus.ARCHIVED, archivedAtMillis = 2_000, updatedAtMillis = 2_000)
        assertEquals(2_000, archived.version)
        assertEquals(3_000, archived.copy(status = PaymentStatus.PENDING, archivedAtMillis = null, updatedAtMillis = 3_000).version)
    }
}
