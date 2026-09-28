package cz.zapisnik.app.backup

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

object GoogleAuth {
    /** Přístup jen k souborům, které vytvořila tato aplikace. */
    const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

    val request: AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_FILE), Scope("email")))
        .build()

    suspend fun authorize(context: Context): AuthorizationResult =
        Identity.getAuthorizationClient(context).authorize(request).await()

    /** Token bez interakce s uživatelem, nebo null, když je potřeba se znovu přihlásit. */
    suspend fun silentToken(context: Context): String? {
        val result = authorize(context)
        return if (result.hasResolution()) null else result.accessToken
    }

    fun invalidate(context: Context, token: String) {
        runCatching { GoogleAuthUtil.clearToken(context, token) }
    }
}
