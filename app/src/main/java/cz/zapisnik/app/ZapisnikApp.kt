package cz.zapisnik.app

import android.app.Application
import cz.zapisnik.app.backup.BackupScheduler
import cz.zapisnik.app.backup.GoogleAccountStore
import cz.zapisnik.app.data.AppDatabase
import cz.zapisnik.app.data.Repository

class ZapisnikApp : Application() {
    lateinit var repository: Repository
        private set
    lateinit var accountStore: GoogleAccountStore
        private set
    lateinit var backupScheduler: BackupScheduler
        private set

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.create(this)
        accountStore = GoogleAccountStore(this)
        backupScheduler = BackupScheduler(this, accountStore)
        repository = Repository(db, backupScheduler)
    }
}
