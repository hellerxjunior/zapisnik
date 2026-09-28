package cz.zapisnik.app.backup

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class UnauthorizedException : IOException("Přihlášení ke Google účtu vypršelo")

/** Minimální klient Google Drive REST API v3 pro jeden záložní soubor. */
class DriveClient(private val token: String) {

    fun email(): String? {
        val json = request("GET", "https://www.googleapis.com/oauth2/v3/userinfo")
        return JSONObject(json).optString("email").ifBlank { null }
    }

    /** Vrátí id složky, případně ji vytvoří. */
    fun ensureFolder(name: String): String {
        findId("name = '${esc(name)}' and mimeType = '$FOLDER' and trashed = false")?.let { return it }
        val meta = JSONObject().put("name", name).put("mimeType", FOLDER)
        val res = request("POST", "$API/files?fields=id", meta.toString(), "application/json; charset=UTF-8")
        return JSONObject(res).getString("id")
    }

    fun findFile(name: String, folderId: String): String? =
        findId("name = '${esc(name)}' and '$folderId' in parents and trashed = false")

    fun createFile(name: String, folderId: String, content: String): String {
        val boundary = "zapisnik" + System.nanoTime()
        val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray().put(folderId))
        val body = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(meta.toString()).append("\r\n")
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(content).append("\r\n")
            append("--").append(boundary).append("--\r\n")
        }
        val res = request("POST", "$UPLOAD/files?uploadType=multipart&fields=id", body, "multipart/related; boundary=$boundary")
        return JSONObject(res).getString("id")
    }

    fun updateFile(id: String, content: String) {
        request("PATCH", "$UPLOAD/files/$id?uploadType=media", content, "application/json; charset=UTF-8")
    }

    fun rename(id: String, name: String) {
        request("PATCH", "$API/files/$id?fields=id", JSONObject().put("name", name).toString(), "application/json; charset=UTF-8")
    }

    fun download(id: String): String = request("GET", "$API/files/$id?alt=media")

    /** Datum poslední změny souboru (RFC 3339), nebo null. */
    fun modifiedTime(id: String): String? =
        JSONObject(request("GET", "$API/files/$id?fields=modifiedTime")).optString("modifiedTime").ifBlank { null }

    private fun findId(q: String): String? {
        val url = "$API/files?spaces=drive&fields=files(id)&orderBy=modifiedTime%20desc&q=" + URLEncoder.encode(q, "UTF-8")
        val files = JSONObject(request("GET", url)).optJSONArray("files") ?: return null
        return if (files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    private fun request(method: String, url: String, body: String? = null, contentType: String? = null): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            if (method == "PATCH") {
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                conn.requestMethod = method
            }
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            if (code == 401) throw UnauthorizedException()
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("Google Disk odpověděl chybou $code. ${err.take(300)}")
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val FOLDER = "application/vnd.google-apps.folder"
    }
}
