package cz.zapisnik.app.backup

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

class UnauthorizedException : IOException("Přihlášení ke Google účtu vypršelo")

/** Soubor ve složce na Disku. */
data class RemoteFile(val id: String, val name: String, val modified: Long)

/** Minimální klient Google Drive REST API v3 pro záložní soubor a obrázky. */
class DriveClient(private val token: String) {

    fun email(): String? {
        val json = text("GET", "https://www.googleapis.com/oauth2/v3/userinfo")
        return JSONObject(json).optString("email").ifBlank { null }
    }

    /** Vrátí id složky (případně podsložky v [parentId]), a když neexistuje, vytvoří ji. */
    fun ensureFolder(name: String, parentId: String? = null): String {
        val inParent = if (parentId != null) " and '$parentId' in parents" else ""
        findId("name = '${esc(name)}' and mimeType = '$FOLDER' and trashed = false$inParent")?.let { return it }
        val meta = JSONObject().put("name", name).put("mimeType", FOLDER)
        if (parentId != null) meta.put("parents", JSONArray().put(parentId))
        val res = text("POST", "$API/files?fields=id", meta.toString().toByteArray(), JSON)
        return JSONObject(res).getString("id")
    }

    fun findFile(name: String, folderId: String): String? =
        findId("name = '${esc(name)}' and '$folderId' in parents and trashed = false")

    /** Všechny soubory ve složce (bez koše). */
    fun listFiles(folderId: String): List<RemoteFile> {
        val out = mutableListOf<RemoteFile>()
        var page: String? = null
        do {
            val q = URLEncoder.encode("'$folderId' in parents and trashed = false", "UTF-8")
            val url = "$API/files?spaces=drive&pageSize=1000&fields=nextPageToken,files(id,name,modifiedTime)&q=$q" +
                (page?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val res = JSONObject(text("GET", url))
            val files = res.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val modified = runCatching { Instant.parse(f.optString("modifiedTime")).toEpochMilli() }.getOrDefault(0L)
                out += RemoteFile(f.getString("id"), f.optString("name"), modified)
            }
            page = res.optString("nextPageToken").ifBlank { null }
        } while (page != null)
        return out
    }

    fun createFile(name: String, folderId: String, content: String): String =
        upload(name, folderId, content.toByteArray(Charsets.UTF_8), "application/json; charset=UTF-8")

    fun createImage(name: String, folderId: String, file: File): String = upload(name, folderId, file.readBytes(), "image/jpeg")

    private fun upload(name: String, folderId: String, content: ByteArray, type: String): String {
        val boundary = "zapisnik" + System.nanoTime()
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(folderId))
        val body = ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n".toByteArray())
            write("--$boundary\r\nContent-Type: $type\r\n\r\n".toByteArray())
            write(content)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val res = text("POST", "$UPLOAD/files?uploadType=multipart&fields=id", body, "multipart/related; boundary=$boundary")
        return JSONObject(res).getString("id")
    }

    fun updateFile(id: String, content: String) {
        text("PATCH", "$UPLOAD/files/$id?uploadType=media", content.toByteArray(Charsets.UTF_8), "application/json; charset=UTF-8")
    }

    fun rename(id: String, name: String) {
        text("PATCH", "$API/files/$id?fields=id", JSONObject().put("name", name).toString().toByteArray(), JSON)
    }

    /** Přesune soubor do koše na Disku (dá se odtud ještě 30 dní obnovit). */
    fun trash(id: String) {
        text("PATCH", "$API/files/$id?fields=id", JSONObject().put("trashed", true).toString().toByteArray(), JSON)
    }

    fun download(id: String): String = text("GET", "$API/files/$id?alt=media")

    fun downloadTo(id: String, target: File) {
        val conn = open("GET", "$API/files/$id?alt=media", null, null)
        try {
            conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
    }

    /** Datum poslední změny souboru (RFC 3339), nebo null. */
    fun modifiedTime(id: String): String? =
        JSONObject(text("GET", "$API/files/$id?fields=modifiedTime")).optString("modifiedTime").ifBlank { null }

    private fun findId(q: String): String? {
        val url = "$API/files?spaces=drive&fields=files(id)&orderBy=modifiedTime%20desc&q=" + URLEncoder.encode(q, "UTF-8")
        val files = JSONObject(text("GET", url)).optJSONArray("files") ?: return null
        return if (files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    private fun text(method: String, url: String, body: ByteArray? = null, contentType: String? = null): String {
        val conn = open(method, url, body, contentType)
        try {
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Odešle požadavek; při chybě vyhodí výjimku a spojení zavře. */
    private fun open(method: String, url: String, body: ByteArray?, contentType: String?): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            if (method == "PATCH") {
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                conn.requestMethod = method
            }
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            if (code == 401) throw UnauthorizedException()
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("Google Disk odpověděl chybou $code. ${err.take(300)}")
            }
            return conn
        } catch (e: Exception) {
            conn.disconnect()
            throw e
        }
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val FOLDER = "application/vnd.google-apps.folder"
        private const val JSON = "application/json; charset=UTF-8"
    }
}
