package cz.zapisnik.app.backup

import cz.zapisnik.app.data.Snapshot
import cz.zapisnik.app.data.Tombstone

/**
 * Sloučení dvou verzí zápisníku (telefon a Google Disk). Stejný postup používá i webová verze.
 *
 * - Položky se párují podle id, vyhrává ta s novějším časem změny (při shodě první argument).
 * - Záznam o smazání vyhrává, pokud není starší než poslední úprava položky.
 * - Odkaz na kategorii, která už neexistuje, se vynuluje.
 */
object SyncMerge {
    fun merge(a: Snapshot, b: Snapshot): Snapshot {
        val deleted = (a.deleted + b.deleted)
            .groupBy { it.kind to it.id }
            .map { (_, list) -> list.maxBy { it.at } }
        val deletedAt = deleted.associate { (it.kind to it.id) to it.at }
        fun alive(kind: String, id: String, updated: Long) = (deletedAt[kind to id] ?: -1L) < updated

        val categories = (a.categories + b.categories)
            .groupBy { it.id }
            .map { (_, list) -> list.maxBy { it.updated } }
            .filter { alive(Tombstone.KIND_CATEGORY, it.id, it.updated) }
        val catIds = categories.map { it.id }.toSet()

        val entries = (a.entries + b.entries)
            .groupBy { it.id }
            .map { (_, list) -> list.maxBy { it.updated } }
            .filter { alive(Tombstone.KIND_ENTRY, it.id, it.updated) }
            .map { if (it.categoryId != null && it.categoryId !in catIds) it.copy(categoryId = null) else it }

        return normalize(Snapshot(entries, categories, deleted))
    }

    /** Id všech obrázků, na které odkazuje nějaký záznam. */
    fun referencedImages(s: Snapshot): Set<String> = s.entries.flatMap { it.images }.toSet()

    /** Všechny přílohy, na které odkazuje nějaký záznam (podle id). */
    fun referencedFiles(s: Snapshot): Map<String, cz.zapisnik.app.data.Attachment> =
        s.entries.flatMap { it.files }.associateBy { it.id }

    /** Seřadí vše podle id, aby šly dvě verze porovnat. */
    fun normalize(s: Snapshot) = Snapshot(
        entries = s.entries.sortedBy { it.id },
        categories = s.categories.sortedBy { it.id },
        deleted = s.deleted.sortedWith(compareBy({ it.kind }, { it.id })),
    )
}
