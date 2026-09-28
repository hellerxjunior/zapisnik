package cz.zapisnik.app.backup

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AccountState(
    /** Uživatel prošel úvodní obrazovkou (přihlásil se, nebo zvolil pokračovat bez přihlášení). */
    val onboarded: Boolean,
    val email: String?,
    val lastBackupAt: Long,
    val lastError: String?,
) {
    val signedIn: Boolean get() = email != null
}

/** Malé úložiště stavu přihlášení a zálohy v SharedPreferences. */
class GoogleAccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("ucet", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AccountState> = _state.asStateFlow()

    private fun read() = AccountState(
        onboarded = prefs.getBoolean("onboarded", false),
        email = prefs.getString("email", null),
        lastBackupAt = prefs.getLong("lastBackupAt", 0L),
        lastError = prefs.getString("lastError", null),
    )

    private fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _state.value = read()
    }

    fun signedIn(email: String) = edit {
        putBoolean("onboarded", true); putString("email", email); remove("lastError")
    }

    fun skipSignIn() = edit { putBoolean("onboarded", true) }

    fun signOut() = edit { remove("email"); remove("driveFileId"); remove("lastError") }

    fun backupDone(at: Long) = edit { putLong("lastBackupAt", at); remove("lastError") }

    fun backupFailed(message: String) = edit { putString("lastError", message) }

    var driveFileId: String?
        get() = prefs.getString("driveFileId", null)
        set(value) { prefs.edit().putString("driveFileId", value).apply() }
}
