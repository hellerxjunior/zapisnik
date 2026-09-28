package cz.zapisnik.app.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ZapisnikDao {
    @Query("SELECT * FROM entries ORDER BY date DESC, updated DESC")
    fun entries(): Flow<List<Entry>>

    @Query("SELECT * FROM entries")
    suspend fun allEntries(): List<Entry>

    @Query("SELECT * FROM categories ORDER BY sortOrder, name")
    fun categories(): Flow<List<Category>>

    @Query("SELECT * FROM categories ORDER BY sortOrder, name")
    suspend fun allCategories(): List<Category>

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun entry(id: String): Entry?

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun category(id: String): Category?

    @Query("SELECT * FROM deleted")
    suspend fun allTombstones(): List<Tombstone>

    @Upsert
    suspend fun upsertEntry(entry: Entry)

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun deleteEntryRow(id: String)

    @Upsert
    suspend fun upsertTombstone(tombstone: Tombstone)

    @Transaction
    suspend fun deleteEntry(id: String, at: Long) {
        deleteEntryRow(id)
        upsertTombstone(Tombstone(Tombstone.KIND_ENTRY, id, at))
    }

    @Upsert
    suspend fun upsertCategory(category: Category)

    @Upsert
    suspend fun upsertCategories(categories: List<Category>)

    @Query("UPDATE entries SET categoryId = NULL, updated = MAX(:at, updated + 1) WHERE categoryId = :id")
    suspend fun clearCategory(id: String, at: Long)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteCategoryRow(id: String)

    @Transaction
    suspend fun deleteCategory(id: String, at: Long) {
        clearCategory(id, at)
        deleteCategoryRow(id)
        upsertTombstone(Tombstone(Tombstone.KIND_CATEGORY, id, at))
    }

    @Query("DELETE FROM entries")
    suspend fun clearEntries()

    @Query("DELETE FROM categories")
    suspend fun clearCategories()

    @Query("DELETE FROM deleted")
    suspend fun clearTombstones()

    @Upsert
    suspend fun upsertEntries(entries: List<Entry>)

    @Upsert
    suspend fun upsertTombstones(tombstones: List<Tombstone>)

    @Transaction
    suspend fun snapshot(): Snapshot = Snapshot(allEntries(), allCategories(), allTombstones())

    @Transaction
    suspend fun replaceAll(s: Snapshot) {
        clearEntries()
        clearCategories()
        clearTombstones()
        upsertCategories(s.categories)
        upsertEntries(s.entries)
        upsertTombstones(s.deleted)
    }
}
