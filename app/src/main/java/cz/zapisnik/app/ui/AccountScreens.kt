package cz.zapisnik.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SignInScreen(vm: AppViewModel, onSignIn: () -> Unit, onSkip: () -> Unit) {
    val ui by vm.backupUi.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(24.dp)) {
        Column(Modifier.align(Alignment.Center).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.width(16.dp).height(40.dp).background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(4.dp)))
            Text("Zápisník", fontSize = 38.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                "Zapisuj si opravy, servisy a další důležité události. Záznamy zůstávají v telefonu a po přihlášení se přes tvůj Google Disk synchronizují s počítačem.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onSignIn,
                enabled = !ui.busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
            ) {
                if (ui.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("Přihlásit se účtem Google", fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Pokračovat bez přihlášení (bez synchronizace)")
            }
            ui.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(vm: AppViewModel, onSignIn: () -> Unit) {
    val account by vm.account.collectAsStateWithLifecycle()
    val ui by vm.backupUi.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Účet a synchronizace", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = { vm.clearMessage(); vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zpět") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (account.signedIn) {
                        Text("Přihlášen jako", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        Text(account.email ?: "", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (account.lastBackupAt > 0) "Poslední synchronizace: ${fmtTimestamp(account.lastBackupAt)}" else "Zatím nesynchronizováno",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        account.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    } else {
                        Text("Nejsi přihlášen", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Záznamy jsou jen v tomto telefonu. Po přihlášení se budou synchronizovat přes Google Disk s webovou verzí Zápisníku a dalšími telefony.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (account.signedIn) {
                Button(onClick = vm::syncNow, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("Synchronizovat teď") }
                TextButton(onClick = vm::signOut, enabled = !ui.busy) { Text("Odhlásit se") }
            } else {
                Button(onClick = onSignIn, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("Přihlásit se účtem Google") }
            }

            if (ui.busy) CircularProgressIndicator(Modifier.size(24.dp))
            ui.message?.let { Text(it) }

            Text(
                "Data se ukládají do souboru zapisnik-zaloha.json ve složce Zápisník na tvém Google Disku. Synchronizace běží při otevření aplikace a chvíli po každé změně. Aplikace vidí jen soubory, které sama vytvořila.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
        }
    }
}
