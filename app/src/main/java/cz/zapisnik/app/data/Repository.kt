package cz.zapisnik.app.data

import androidx.room.withTransaction
import cz.zapisnik.app.backup.BackupScheduler
import cz.zapisnik.app.backup.SyncMerge
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class Repository(
    private val db: AppDatabase,
    private val backupScheduler: BackupScheduler,
    private val images: ImageStore,
) {
    private val dao = db.dao()
    val entries: Flow<List<Entry>> = dao.entries()
    val categories: Flow<List<Category>> = dao.categories()

    suspend fun saveEntry(entry: Entry) {
        val old = dao.entry(entry.id)
        dao.upsertEntry(entry.copy(updated = after(old?.updated)))
        dropUnused(old?.images.orEmpty() - entry.images.toSet())
        backupScheduler.requestBackup()
    }

    suspend fun deleteEntry(id: String) {
        val old = dao.entry(id)
        dao.deleteEntry(id, after(old?.updated))
        dropUnused(old?.images.orEmpty())
        backupScheduler.requestBackup()
    }

    suspend fun referencedImages(): Set<String> = dao.allEntries().flatMap { it.images }.toSet()

    /** Smaže soubory odebraných obrázků, pokud je nepoužívá jiný záznam. Na Disku je uklidí synchronizace. */
    private suspend fun dropUnused(removed: Collection<String>) {
        if (removed.isEmpty()) return
        val used = referencedImages()
        images.delete(removed.filter { it !in used })
    }

    suspend fun addCategory(name: String) {
        val existing = dao.allCategories()
        val used = existing.map { it.color }.toSet()
        val color = (0 until CATEGORY_COLOR_COUNT).firstOrNull { it !in used } ?: (existing.size % CATEGORY_COLOR_COUNT)
        val order = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
        dao.upsertCategory(Category(UUID.randomUUID().toString(), name, color, order, System.currentTimeMillis()))
        backupScheduler.requestBackup()
    }

    suspend fun updateCategory(category: Category) {
        dao.upsertCategory(category.copy(updated = after(dao.category(category.id)?.updated)))
        backupScheduler.requestBackup()
    }

    suspend fun deleteCategory(id: String) {
        dao.deleteCategory(id, after(dao.category(id)?.updated))
        backupScheduler.requestBackup()
    }

    /**
     * Sloučí data z Disku s místními v jedné transakci, výsledek uloží do telefonu a vrátí ho.
     * Úprava udělaná během synchronizace tak nezmizí, jen počká na další synchronizaci.
     */
    suspend fun mergeIn(remote: Snapshot?): Snapshot = db.withTransaction {
        val local = SyncMerge.normalize(dao.snapshot())
        val merged = if (remote == null) local else SyncMerge.merge(local, remote)
        if (merged != local) dao.replaceAll(merged)
        merged
    }

    /** Čas změny vždy větší než předchozí, i když má jiné zařízení hodiny napřed. */
    private fun after(previous: Long?) = maxOf(System.currentTimeMillis(), (previous ?: 0L) + 1)

    companion object {
        const val CATEGORY_COLOR_COUNT = 8
    }
}
