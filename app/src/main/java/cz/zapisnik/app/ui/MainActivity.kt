package cz.zapisnik.app.ui

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import cz.zapisnik.app.backup.GoogleAuth
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZapisnikTheme { App(vm, onExit = { finish() }) }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }
}

@Composable
private fun App(vm: AppViewModel, onExit: () -> Unit) {
    val account by vm.account.collectAsStateWithLifecycle()
    val stack by vm.stack.collectAsStateWithLifecycle()
    val signIn = rememberGoogleSignIn(onToken = vm::completeSignIn, onError = vm::signInFailed)

    if (!account.onboarded) {
        SignInScreen(vm, onSignIn = signIn, onSkip = vm::skipSignIn)
        return
    }

    BackHandler { if (!vm.back()) onExit() }

    when (val screen = stack.last()) {
        Screen.List -> ListScreen(vm)
        is Screen.Detail -> DetailScreen(vm, screen.id)
        is Screen.Edit -> EditScreen(vm, screen.id)
        Screen.Categories -> CategoriesScreen(vm)
        Screen.Account -> AccountScreen(vm, onSignIn = signIn)
    }
}

/** Vrací funkci, která spustí přihlášení Google s oprávněním k Disku. */
@Composable
fun rememberGoogleSignIn(onToken: (String) -> Unit, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK) {
            onError("Přihlášení bylo zrušeno.")
            return@rememberLauncherForActivityResult
        }
        try {
            val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(res.data)
            val token = result.accessToken
            if (token != null) onToken(token) else onError("Google nevrátil přístup k Disku.")
        } catch (e: ApiException) {
            onError(describe(e))
        }
    }
    return {
        scope.launch {
            try {
                val result = GoogleAuth.authorize(context)
                val pending = result.pendingIntent
                if (result.hasResolution() && pending != null) {
                    launcher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                } else {
                    result.accessToken?.let(onToken) ?: onError("Google nevrátil přístup k Disku.")
                }
            } catch (e: ApiException) {
                onError(describe(e))
            } catch (e: Exception) {
                onError(e.message ?: "Přihlášení se nepovedlo.")
            }
        }
    }
}

private fun describe(e: ApiException): String = when (e.statusCode) {
    CommonStatusCodes.DEVELOPER_ERROR ->
        "Přihlášení ještě není nastavené v Google Cloud (chyba 10). Zkontroluj OAuth klienta typu Android s balíčkem cz.zapisnik.app a otiskem SHA‑1."
    CommonStatusCodes.NETWORK_ERROR -> "Bez připojení k internetu se nejde přihlásit."
    CommonStatusCodes.CANCELED -> "Přihlášení bylo zrušeno."
    else -> "Přihlášení se nepovedlo (chyba ${e.statusCode})."
}
