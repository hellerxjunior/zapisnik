package cz.zapisnik.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "entries", indices = [Index("categoryId"), Index("date")])
data class Entry(
    @PrimaryKey val id: String,
    val title: String,
    /** Datum ve formátu RRRR-MM-DD. */
    val date: String,
    val categoryId: String?,
    val text: String,
    val created: Long,
    val updated: Long,
)

@Entity(tableName = "categories")
data class Category(
    @PrimaryKey val id: String,
    val name: String,
    /** Index do [CategoryColors]. */
    val color: Int,
    val sortOrder: Int,
    /** Čas poslední změny, podle něj se při synchronizaci pozná novější verze. */
    @ColumnInfo(defaultValue = "0") val updated: Long = 0,
)

/** Záznam o smazání, aby se smazaná položka při synchronizaci nevrátila z jiného zařízení. */
@Entity(tableName = "deleted", primaryKeys = ["kind", "id"])
data class Tombstone(
    /** [KIND_ENTRY] nebo [KIND_CATEGORY]. */
    val kind: String,
    val id: String,
    val at: Long,
) {
    companion object {
        const val KIND_ENTRY = "entry"
        const val KIND_CATEGORY = "category"
    }
}

/** Kompletní obsah zápisníku, tak jak se ukládá na Google Disk. */
data class Snapshot(
    val entries: List<Entry>,
    val categories: List<Category>,
    val deleted: List<Tombstone>,
)

val DefaultCategories = listOf(
    Category("auto", "Auto", 0, 0),
    Category("dum", "Dům", 2, 1),
    Category("prace", "Práce", 5, 2),
    Category("rodina", "Rodina", 6, 3),
    Category("napady", "Nápady", 4, 4),
)
