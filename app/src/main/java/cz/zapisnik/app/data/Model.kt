package cz.zapisnik.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import org.json.JSONArray
import org.json.JSONObject

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
    /** Id přiložených obrázků v pořadí; soubor v telefonu i na Disku se jmenuje id + ".jpg". */
    @ColumnInfo(defaultValue = "") val images: List<String> = emptyList(),
    /** Přiložené soubory (PDF, dokumenty…), v pořadí přidání. */
    @ColumnInfo(defaultValue = "") val files: List<Attachment> = emptyList(),
)

/** Příloha záznamu. Soubor je v telefonu i na Disku uložený podle [id], [name] je původní název pro zobrazení. */
data class Attachment(
    val id: String,
    val name: String,
    /** MIME typ, např. application/pdf; může být prázdný. */
    val type: String,
    val size: Long,
) : java.io.Serializable {
    /** Název souboru na Disku: id a přípona z původního názvu (stejně jako ve webové verzi). */
    val driveName: String get() = id + (EXT.find(name)?.value?.lowercase() ?: "")

    companion object {
        private val EXT = Regex("""\.[A-Za-z0-9]{1,8}$""")
    }
}

/** Seznam id obrázků se v databázi ukládá jako text oddělený čárkami (id jsou UUID). */
class Converters {
    @TypeConverter
    fun fromImages(images: List<String>): String = images.joinToString(",")

    @TypeConverter
    fun toImages(value: String): List<String> = value.split(',').filter { it.isNotBlank() }

    @TypeConverter
    fun fromFiles(files: List<Attachment>): String =
        if (files.isEmpty()) "" else JSONArray(files.map { attachmentJson(it) }).toString()

    @TypeConverter
    fun toFiles(value: String): List<Attachment> =
        if (value.isBlank()) emptyList() else JSONArray(value).let { a -> (0 until a.length()).mapNotNull { attachmentOf(a.optJSONObject(it)) } }
}

fun attachmentJson(a: Attachment): JSONObject =
    JSONObject().put("id", a.id).put("name", a.name).put("type", a.type).put("size", a.size)

/** Přečte přílohu ze zálohy; nebezpečné id (je to název souboru) zahodí. */
fun attachmentOf(o: JSONObject?): Attachment? {
    if (o == null) return null
    val id = o.opt("id") as? String ?: return null
    if (!validFileId(id)) return null
    return Attachment(id, cleanFileName(o.optString("name")), o.optString("type").take(100), o.optLong("size").coerceAtLeast(0))
}

/** Id obrázku nebo přílohy je zároveň název souboru, proto jen bezpečné znaky (UUID). */
fun validFileId(id: String) = id.length in 1..64 && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }

/** Původní název souboru bez znaků, které nejdou použít v cestě. */
fun cleanFileName(name: String): String =
    name.replace(Regex("""[\\/:*?"<>|\x00-\x1f]"""), "_").trim().trim('.').take(120).ifBlank { "soubor" }

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
