package cz.zapisnik.app.backup

import android.content.Context
import cz.zapisnik.app.ZapisnikApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Synchronizace přes Google Disk: složka „Zápisník“, soubor zapisnik-zaloha.json.
 * Stáhne verzi z Disku, sloučí ji s telefonem a výsledek nahraje zpět. Stejný soubor používá webová verze.
 */
class BackupManager(private val context: Context) {
    private val app = context.applicationContext as ZapisnikApp
    private val store = app.accountStore

    /** Vrátí false, když je potřeba se znovu přihlásit. */
    suspend fun sync(tokenOverride: String? = null): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            withDrive(tokenOverride) { drive ->
                val cached = store.driveFileId
                val id = cached?.takeIf { runCatching { drive.modifiedTime(it) }.isSuccess }
                    ?: drive.findFile(FILE, drive.ensureFolder(FOLDER))
                val remote = id?.let { SyncMerge.normalize(BackupJson.decode(drive.download(it))) }
                val merged = app.repository.mergeIn(remote)
                val now = System.currentTimeMillis()
                when {
                    id == null -> store.driveFileId = drive.createFile(FILE, drive.ensureFolder(FOLDER), BackupJson.encode(merged, now))
                    merged != remote -> drive.updateFile(id, BackupJson.encode(merged, now))
                }
                if (id != null) store.driveFileId = id
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
        /** Jedna synchronizace naráz, i když ji spustí tlačítko a plánovač současně. */
        private val lock = Mutex()
    }
}
