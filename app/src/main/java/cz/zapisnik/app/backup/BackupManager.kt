package cz.zapisnik.app.backup

import android.content.Context
import cz.zapisnik.app.ZapisnikApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Synchronizace přes Google Disk: složka „Zápisník“, soubor zapisnik-zaloha.json a podsložka „Obrázky“.
 * Stáhne verzi z Disku, sloučí ji s telefonem, nahraje nové obrázky, pak výsledek a nakonec stáhne chybějící obrázky.
 * Stejný postup používá webová verze.
 */
class BackupManager(private val context: Context) {
    private val app = context.applicationContext as ZapisnikApp
    private val store = app.accountStore
    private val images = app.images

    /** Vrátí false, když je potřeba se znovu přihlásit. */
    suspend fun sync(tokenOverride: String? = null): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            withDrive(tokenOverride) { drive ->
                val folder = drive.ensureFolder(FOLDER)
                val cached = store.driveFileId
                val id = cached?.takeIf { runCatching { drive.modifiedTime(it) }.isSuccess }
                    ?: drive.findFile(FILE, folder)
                val remote = id?.let { SyncMerge.normalize(BackupJson.decode(drive.download(it))) }
                val merged = app.repository.mergeIn(remote)

                // Obrázky nahrát dřív než seznam, aby je ostatní zařízení našla, až na ně uvidí odkaz.
                val imageFolder = drive.ensureFolder(IMAGES, folder)
                val remoteImages = drive.listFiles(imageFolder).associateBy { it.name }
                val referenced = SyncMerge.referencedImages(merged)
                referenced.filter { images.has(it) && fileName(it) !in remoteImages }
                    .forEach { drive.createImage(fileName(it), imageFolder, images.file(it)) }

                val now = System.currentTimeMillis()
                when {
                    id == null -> store.driveFileId = drive.createFile(FILE, folder, BackupJson.encode(merged, now))
                    merged != remote -> drive.updateFile(id, BackupJson.encode(merged, now))
                }
                if (id != null) store.driveFileId = id

                referenced.filter { !images.has(it) }.forEach { img ->
                    remoteImages[fileName(img)]?.let { f -> images.write(img) { tmp -> drive.downloadTo(f.id, tmp) } }
                }

                // Úklid: obrázky, na které už nic neodkazuje (smazané záznamy). Na Disku jdou do koše až po týdnu,
                // aby je nesmazalo zařízení, které ještě nemá nejnovější seznam.
                val stillUsed = referenced + app.repository.referencedImages()
                images.cleanup(stillUsed)
                val old = now - REMOTE_GRACE_MS
                remoteImages.values
                    .filter { it.name.removeSuffix(".jpg") !in stillUsed && it.modified in 1 until old }
                    .forEach { runCatching { drive.trash(it.id) } }

                store.backupDone(now)
            }
        }
    }

    private suspend fun withDrive(tokenOverride: String?, block: suspend (DriveClient) -> Unit): Boolean {
        var token = tokenOverride ?: GoogleAuth.silentToken(context) ?: return false
        try {
            block(DriveClient(token))
        } catch (e: UnauthorizedException) {
            GoogleAuth.invalidate(context, token)
            token = GoogleAuth.silentToken(context) ?: return false
            block(DriveClient(token))
        }
        return true
    }

    companion object {
        const val FOLDER = "Zápisník"
        const val FILE = "zapisnik-zaloha.json"
        const val IMAGES = "Obrázky"
        private const val REMOTE_GRACE_MS = 7 * 24 * 60 * 60 * 1000L
        fun fileName(imageId: String) = "$imageId.jpg"
        /** Jedna synchronizace naráz, i když ji spustí tlačítko a plánovač současně. */
        private val lock = Mutex()
    }
}
