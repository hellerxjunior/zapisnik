package cz.zapisnik.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.zapisnik.app.ZapisnikApp
import cz.zapisnik.app.backup.AccountState
import cz.zapisnik.app.backup.BackupManager
import cz.zapisnik.app.backup.DriveClient
import cz.zapisnik.app.data.Attachment
import cz.zapisnik.app.data.Category
import cz.zapisnik.app.data.Entry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface Screen {
    data object List : Screen
    data class Detail(val id: String) : Screen
    data class Edit(val id: String?) : Screen
    data object Categories : Screen
    data object Account : Screen
}

/** Stav probíhající synchronizace, zobrazený na obrazovce Účet a synchronizace. */
data class BackupUi(
    val busy: Boolean = false,
    val message: String? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ZapisnikApp
    private val repo = app.repository
    private val backup = BackupManager(application)
    val images = app.images
    val files = app.files

    init {
        // Obrázky z rozepsaných a zrušených záznamů.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { images.cleanup(repo.referencedImages()) }
            runCatching { files.cleanup(repo.referencedFiles()) }
        }
    }

    val entries: StateFlow<List<Entry>> = repo.entries.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val categories: StateFlow<List<Category>> = repo.categories.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val account: StateFlow<AccountState> = app.accountStore.state

    private val backStack = MutableStateFlow<List<Screen>>(listOf(Screen.List))
    val stack: StateFlow<List<Screen>> = backStack.asStateFlow()

    private val _backupUi = MutableStateFlow(BackupUi())
    val backupUi: StateFlow<BackupUi> = _backupUi.asStateFlow()

    val query = MutableStateFlow("")
    val filter = MutableStateFlow<String?>(null) // null = vše, "" = bez kategorie

    fun open(screen: Screen) { backStack.value = backStack.value + screen }
    fun back(): Boolean {
        if (backStack.value.size <= 1) return false
        backStack.value = backStack.value.dropLast(1); return true
    }
    fun replaceTop(screen: Screen) { backStack.value = backStack.value.dropLast(1) + screen }

    fun saveEntry(
        existing: Entry?, title: String, date: String, categoryId: String?, text: String,
        imageIds: List<String>, attachments: List<Attachment>,
    ) {
        val now = System.currentTimeMillis()
        val entry = Entry(
            id = existing?.id ?: UUID.randomUUID().toString(),
            title = title.trim(), date = date, categoryId = categoryId, text = text.trim(),
            created = existing?.created ?: now, updated = now, images = imageIds, files = attachments,
        )
        viewModelScope.launch { repo.saveEntry(entry) }
        replaceTop(Screen.Detail(entry.id))
    }

    /** Zmenší a uloží vybrané obrázky, vrátí jejich id (ty, které nešly načíst, vynechá). */
    suspend fun importImages(uris: List<Uri>): List<String> = uris.mapNotNull { uri ->
        runCatching { images.import(app.contentResolver, uri) }.getOrNull()
    }

    /** Zkopíruje vybrané soubory do aplikace. Vrátí přílohy a chybové hlášky těch, které nešly. */
    suspend fun importFiles(uris: List<Uri>): Pair<List<Attachment>, List<String>> {
        val errors = mutableListOf<String>()
        val added = uris.mapNotNull { uri ->
            runCatching { files.import(app.contentResolver, uri) }.onFailure { errors += (it.message ?: "Soubor nejde načíst.") }.getOrNull()
        }
        return added to errors
    }

    fun deleteEntry(id: String) {
        viewModelScope.launch { repo.deleteEntry(id) }
        backStack.value = listOf(Screen.List)
    }

    fun addCategory(name: String) = viewModelScope.launch { repo.addCategory(name.trim()) }
    fun updateCategory(c: Category) = viewModelScope.launch { repo.updateCategory(c) }
    fun deleteCategory(id: String) = viewModelScope.launch {
        if (filter.value == id) filter.value = null
        repo.deleteCategory(id)
    }

    /* ---------- přihlášení a záloha ---------- */

    fun skipSignIn() = app.accountStore.skipSignIn()

    fun signOut() {
        app.accountStore.signOut()
        _backupUi.value = BackupUi(message = "Odhlášeno. Data v telefonu zůstala.")
    }

    /** Po úspěšném přihlášení zjistí e‑mail a sloučí data v telefonu s tím, co už je na Disku. */
    fun completeSignIn(token: String) = viewModelScope.launch {
        _backupUi.value = BackupUi(busy = true, message = "Přihlašuji…")
        try {
            val email = withContext(Dispatchers.IO) { DriveClient(token).email() } ?: "účet Google"
            app.accountStore.signedIn(email)
            backup.sync(token)
            _backupUi.value = BackupUi(message = "Přihlášeno a synchronizováno.")
        } catch (e: Exception) {
            _backupUi.value = BackupUi(message = e.message ?: "Přihlášení se nepovedlo.")
        }
    }

    fun signInFailed(message: String) { _backupUi.value = BackupUi(message = message) }

    /** Při návratu do aplikace stáhne změny z ostatních zařízení. */
    fun onForeground() = app.backupScheduler.requestBackup(delaySeconds = 0)

    fun syncNow() = viewModelScope.launch {
        _backupUi.value = BackupUi(busy = true, message = "Synchronizuji…")
        _backupUi.value = try {
            if (backup.sync()) BackupUi(message = "Synchronizováno s Google Diskem.")
            else BackupUi(message = "Přihlas se prosím znovu, platnost přihlášení vypršela.")
        } catch (e: Exception) {
            app.accountStore.backupFailed(e.message ?: "Synchronizace se nepovedla.")
            BackupUi(message = e.message ?: "Synchronizace se nepovedla.")
        }
    }

    fun clearMessage() { _backupUi.value = _backupUi.value.copy(message = null) }
}
