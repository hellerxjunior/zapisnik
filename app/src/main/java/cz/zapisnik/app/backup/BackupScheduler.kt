package cz.zapisnik.app.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Po každé změně naplánuje synchronizaci s krátkým zpožděním, aby se více úprav nahrálo najednou.
 * Při otevření aplikace se synchronizuje hned, aby byly vidět změny z počítače.
 */
class BackupScheduler(private val context: Context, private val store: GoogleAccountStore) {
    fun requestBackup(delaySeconds: Long = 20) {
        if (!store.state.value.signedIn) return
        val work = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("zaloha", ExistingWorkPolicy.REPLACE, work)
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = (applicationContext as cz.zapisnik.app.ZapisnikApp).accountStore
        return try {
            if (BackupManager(applicationContext).sync()) {
                Result.success()
            } else {
                store.backupFailed("Synchronizace čeká na nové přihlášení ke Google účtu.")
                Result.failure()
            }
        } catch (e: Exception) {
            store.backupFailed(e.message ?: "Synchronizace se nepovedla.")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
