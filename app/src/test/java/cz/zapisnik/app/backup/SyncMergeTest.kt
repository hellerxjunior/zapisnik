package cz.zapisnik.app.backup

import cz.zapisnik.app.data.Category
import cz.zapisnik.app.data.DefaultCategories
import cz.zapisnik.app.data.Entry
import cz.zapisnik.app.data.Snapshot
import cz.zapisnik.app.data.Tombstone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Zkušební data, stejné případy jako web/sync.test.mjs. Nic se nenahrává na Disk. */
class SyncMergeTest {
    private fun e(id: String, updated: Long, title: String = id, categoryId: String? = "auto") =
        Entry(id, title, "2026-09-28", categoryId, "", 1, updated)

    private fun snap(
        entries: List<Entry> = emptyList(),
        categories: List<Category> = DefaultCategories,
        deleted: List<Tombstone> = emptyList(),
    ) = Snapshot(entries, categories, deleted)

    @Test
    fun novejsiUpravaVyhravaAChybejiciSeDoplni() {
        val phone = snap(listOf(e("a", 10, "telefon"), e("b", 5)))
        val pc = snap(listOf(e("a", 20, "počítač"), e("c", 7)))
        val m = SyncMerge.merge(phone, pc)
        assertEquals(listOf("a", "b", "c"), m.entries.map { it.id })
        assertEquals("počítač", m.entries[0].title)
        assertEquals(m, SyncMerge.merge(pc, phone))
    }

    @Test
    fun priShodeCasuVyhravaPrvniArgument() {
        val m = SyncMerge.merge(snap(listOf(e("a", 10, "telefon"))), snap(listOf(e("a", 10, "web"))))
        assertEquals("telefon", m.entries.single().title)
    }

    @Test
    fun smazaniVyhravaNadStarsiVerziAleNeNadPozdejsiUpravou() {
        val phone = snap(deleted = listOf(Tombstone(Tombstone.KIND_ENTRY, "a", 15)))
        assertEquals(0, SyncMerge.merge(phone, snap(listOf(e("a", 10)))).entries.size)
        assertEquals(1, SyncMerge.merge(phone, snap(listOf(e("a", 20)))).entries.size)
        // záznam o smazání se drží dál, aby se položka nevrátila z dalšího zařízení
        assertEquals(1, SyncMerge.merge(phone, snap(listOf(e("a", 10)))).deleted.size)
    }

    @Test
    fun smazanaKategorieZmiziAZaznamyJsouBezKategorie() {
        val phone = snap(
            categories = DefaultCategories.filter { it.id != "auto" },
            deleted = listOf(Tombstone(Tombstone.KIND_CATEGORY, "auto", 5)),
        )
        val m = SyncMerge.merge(phone, snap(listOf(e("a", 1))))
        assertFalse(m.categories.any { it.id == "auto" })
        assertNull(m.entries.single().categoryId)
    }

    @Test
    fun slouceniJeIdempotentniAFormatProjdeTamAZpet() {
        val a = snap(listOf(e("a", 3), e("b", 4, categoryId = null)), deleted = listOf(Tombstone(Tombstone.KIND_ENTRY, "x", 2)))
        val m = SyncMerge.merge(a, a)
        assertEquals(SyncMerge.normalize(a), m)
        assertEquals(m, SyncMerge.merge(m, m))
        assertEquals(m, SyncMerge.normalize(BackupJson.decode(BackupJson.encode(m, 100))))
    }

    @Test
    fun prectesVerzi1ZeStareAplikace() {
        val v1 = """{"app":"zapisnik","version":1,"exported":1,
            "categories":[{"id":"auto","name":"Auto","color":0,"sortOrder":0}],
            "entries":[{"id":"a","title":"Olej","date":"2026-01-02","categoryId":null,"text":"","created":1,"updated":3}]}"""
        val s = BackupJson.decode(v1)
        assertEquals(0L, s.categories.single().updated)
        assertEquals(emptyList<Tombstone>(), s.deleted)
        assertNull(s.entries.single().categoryId)
    }

    /** Soubor zapsaný webovou verzí (JSON.stringify) musí telefon přečíst stejně. */
    @Test
    fun prectesSouborZWebu() {
        val web = """{"app":"zapisnik","version":2,"exported":5,
            "categories":[{"id":"dum","name":"Dům","color":2,"sortOrder":1,"updated":4}],
            "entries":[{"id":"w","title":"Kotel","date":"2026-09-01","categoryId":"dum","text":"servis","created":2,"updated":4}],
            "deleted":[{"kind":"entry","id":"z","at":3}]}"""
        val m = SyncMerge.merge(snap(), BackupJson.decode(web))
        assertEquals("Kotel", m.entries.single().title)
        assertEquals("dum", m.entries.single().categoryId)
        assertEquals(4L, m.categories.first { it.id == "dum" }.updated)
        assertEquals(listOf("z"), m.deleted.map { it.id })
    }

    @Test
    fun odmitneCiziSoubor() {
        assertThrows(IllegalArgumentException::class.java) { BackupJson.decode("""{"app":"jina"}""") }
    }
}
