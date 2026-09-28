package cz.zapisnik.app.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Přílohy u záznamů: attachments/<id>/<původní název>. Díky složce podle id vidí aplikace,
 * ve které se příloha otevře, skutečný název souboru.
 */
class AttachmentStore(context: Context) {
    private val dir = File(context.filesDir, "attachments").apply { mkdirs() }
    private val _version = MutableStateFlow(0)
    /** Zvýší se po stažení přílohy, aby se obrazovky překreslily. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun file(a: Attachment) = File(File(dir, a.id), cleanFileName(a.name))

    fun has(a: Attachment) = file(a).isFile

    /** Zkopíruje vybraný soubor do aplikace. */
    suspend fun import(resolver: ContentResolver, uri: Uri): Attachment = withContext(Dispatchers.IO) {
        var name = "soubor"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size > MAX_SIZE) throw IOException("„$name“ je větší než ${MAX_SIZE / 1_000_000} MB.")
        val a = Attachment(UUID.randomUUID().toString(), cleanFileName(name), resolver.getType(uri).orEmpty(), 0)
        val copied = write(a) { tmp ->
            val input = resolver.openInputStream(uri) ?: throw IOException("Soubor nejde otevřít.")
            input.use { src -> tmp.outputStream().use { src.copyTo(it) } }
            if (tmp.length() > MAX_SIZE) throw IOException("„$name“ je větší než ${MAX_SIZE / 1_000_000} MB.")
        }
        a.copy(size = copied.length())
    }

    /** Uloží soubor až celý, aby se neotevřel napůl stažený. */
    fun write(a: Attachment, writer: (File) -> Unit): File {
        val target = file(a)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".part")
        try {
            writer(tmp)
            if (!tmp.renameTo(target)) throw IOException("Soubor se nepodařilo uložit.")
            _version.value++
            return target
        } catch (e: Exception) {
            if (!target.exists()) File(dir, a.id).deleteRecursively()
            throw e
        } finally {
            tmp.delete()
        }
    }

    fun delete(ids: Collection<String>) = ids.forEach { File(dir, it).deleteRecursively() }

    /** Smaže přílohy, na které už nic neodkazuje. Čerstvé nechá, mohou patřit k rozepsanému záznamu. */
    fun cleanup(referenced: Set<String>, graceMs: Long = ImageStore.GRACE_MS) {
        val limit = System.currentTimeMillis() - graceMs
        dir.listFiles()?.forEach { f ->
            if (f.name !in referenced && f.lastModified() < limit) f.deleteRecursively()
        }
    }

    companion object {
        const val MAX_SIZE = 25_000_000L
    }
}

/** Velikost souboru pro lidi: 850 kB, 2,4 MB. */
fun formatSize(bytes: Long): String = when {
    bytes < 1_000 -> "$bytes B"
    bytes < 1_000_000 -> "${(bytes + 500) / 1_000} kB"
    else -> String.format(java.util.Locale("cs"), "%.1f MB", bytes / 1_000_000.0)
}
