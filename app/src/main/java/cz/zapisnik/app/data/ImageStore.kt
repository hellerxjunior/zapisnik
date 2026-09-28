package cz.zapisnik.app.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Obrázky u záznamů: soubory images/<id>.jpg v úložišti aplikace.
 * Při přidání se fotka otočí podle EXIF a zmenší na nejvýš [MAX_SIDE] px, aby se rychle synchronizovala.
 */
class ImageStore(context: Context) {
    private val dir = File(context.filesDir, "images").apply { mkdirs() }
    private val _version = MutableStateFlow(0)
    /** Zvýší se po stažení obrázku, aby se obrazovky překreslily. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun file(id: String) = File(dir, "$id.jpg")

    fun has(id: String) = file(id).length() > 0

    /** Zkopíruje a zmenší obrázek z galerie nebo fotoaparátu. Vrátí jeho nové id. */
    suspend fun import(resolver: ContentResolver, uri: Uri): String = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: throw IOException("Obrázek nejde otevřít.")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Soubor není obrázek.")

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("Obrázek nejde načíst.")

        val rotation = runCatching {
            resolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        }.getOrNull() ?: 0
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
        val matrix = Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
        val bitmap = if (scale < 1f || rotation != 0) {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
        } else decoded

        val id = UUID.randomUUID().toString()
        val tmp = File(dir, "$id.tmp")
        try {
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            if (!tmp.renameTo(file(id))) throw IOException("Obrázek se nepodařilo uložit.")
        } finally {
            bitmap.recycle()
            tmp.delete()
        }
        id
    }

    /** Uloží stažený soubor až celý, aby se nezobrazoval napůl stažený obrázek. */
    fun write(id: String, writer: (File) -> Unit) {
        val tmp = File(dir, "$id.part")
        try {
            writer(tmp)
            if (!tmp.renameTo(file(id))) throw IOException("Obrázek se nepodařilo uložit.")
            _version.value++
        } finally {
            tmp.delete()
        }
    }

    fun delete(ids: Collection<String>) = ids.forEach { file(it).delete() }

    /** Smaže obrázky, na které už žádný záznam neodkazuje. Čerstvé nechá, mohou patřit k rozepsanému záznamu. */
    fun cleanup(referenced: Set<String>, graceMs: Long = GRACE_MS) {
        val limit = System.currentTimeMillis() - graceMs
        dir.listFiles()?.forEach { f ->
            val id = f.name.substringBeforeLast('.')
            if (id !in referenced && f.lastModified() < limit) f.delete()
        }
    }

    /** Načte zmenšený obrázek pro zobrazení, nebo null, když ještě není stažený. */
    fun load(id: String, maxSide: Int): Bitmap? {
        val f = file(id)
        if (!f.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    companion object {
        const val MAX_SIDE = 1600
        const val QUALITY = 82
        const val GRACE_MS = 60 * 60 * 1000L
    }
}
