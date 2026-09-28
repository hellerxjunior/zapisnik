package cz.zapisnik.app.backup

import cz.zapisnik.app.data.Category
import cz.zapisnik.app.data.Entry
import cz.zapisnik.app.data.Snapshot
import cz.zapisnik.app.data.Tombstone
import cz.zapisnik.app.data.attachmentJson
import cz.zapisnik.app.data.attachmentOf
import cz.zapisnik.app.data.validFileId
import org.json.JSONArray
import org.json.JSONObject

/**
 * Formát souboru na Disku. Verze 2 přidává čas změny kategorií a seznam smazaných položek,
 * pole images (id obrázků) a files (přílohy) u záznamu jsou volitelná, starší soubory je nemají.
 */
object BackupJson {
    fun encode(s: Snapshot, now: Long): String {
        val cats = JSONArray()
        s.categories.forEach {
            cats.put(
                JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)
                    .put("sortOrder", it.sortOrder).put("updated", it.updated)
            )
        }
        val ents = JSONArray()
        s.entries.forEach {
            ents.put(
                JSONObject()
                    .put("id", it.id).put("title", it.title).put("date", it.date)
                    .put("categoryId", it.categoryId ?: JSONObject.NULL)
                    .put("text", it.text).put("created", it.created).put("updated", it.updated)
                    .put("images", JSONArray(it.images))
                    .put("files", JSONArray(it.files.map(::attachmentJson)))
            )
        }
        val deleted = JSONArray()
        s.deleted.forEach { deleted.put(JSONObject().put("kind", it.kind).put("id", it.id).put("at", it.at)) }
        return JSONObject()
            .put("app", "zapisnik").put("version", 2).put("exported", now)
            .put("categories", cats).put("entries", ents).put("deleted", deleted)
            .toString(2)
    }

    fun decode(json: String): Snapshot {
        val root = JSONObject(json)
        require(root.optString("app") == "zapisnik") { "Soubor není záloha Zápisníku" }
        val cats = root.getJSONArray("categories").let { a ->
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Category(o.getString("id"), o.getString("name"), o.optInt("color"), o.optInt("sortOrder", i), o.optLong("updated"))
            }
        }
        val ents = root.getJSONArray("entries").let { a ->
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Entry(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    date = o.optString("date"),
                    categoryId = if (o.isNull("categoryId")) null else o.optString("categoryId"),
                    text = o.optString("text"),
                    created = o.optLong("created"),
                    updated = o.optLong("updated"),
                    images = o.optJSONArray("images")?.let { im -> (0 until im.length()).mapNotNull { im.opt(it) as? String } }.orEmpty()
                        .filter { validFileId(it) },
                    files = o.optJSONArray("files")?.let { f -> (0 until f.length()).mapNotNull { attachmentOf(f.optJSONObject(it)) } }.orEmpty(),
                )
            }
        }
        val deleted = root.optJSONArray("deleted")?.let { a ->
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Tombstone(o.getString("kind"), o.getString("id"), o.optLong("at"))
            }
        }.orEmpty()
        return Snapshot(ents, cats, deleted)
    }
}
