package cz.zapisnik.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.zapisnik.app.data.Attachment
import cz.zapisnik.app.data.AttachmentStore
import cz.zapisnik.app.data.formatSize
import kotlinx.coroutines.launch

/** Seznam příloh; klepnutí otevře soubor v aplikaci, která ho umí zobrazit. */
@Composable
fun AttachmentList(store: AttachmentStore, files: List<Attachment>, onRemove: ((Attachment) -> Unit)? = null) {
    val context = LocalContext.current
    val version by store.version.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        files.forEach { a ->
            val ready = remember(a, version) { store.has(a) }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().clickable { openAttachment(context, store, a) },
            ) {
                Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when {
                            !ready -> Icons.Outlined.CloudDownload
                            a.type == "application/pdf" || a.name.endsWith(".pdf", true) -> Icons.Outlined.PictureAsPdf
                            else -> Icons.Outlined.Description
                        },
                        null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(a.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (ready) formatSize(a.size) else "${formatSize(a.size)} · stahuje se z Disku",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (onRemove != null) IconButton(onClick = { onRemove(a) }) { Icon(Icons.Default.Close, "Odebrat přílohu ${a.name}") }
                }
            }
        }
    }
}

private fun openAttachment(context: Context, store: AttachmentStore, a: Attachment) {
    if (!store.has(a)) {
        Toast.makeText(context, "Příloha se ještě stahuje z Disku.", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", store.file(a))
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, a.type.ifBlank { context.contentResolver.getType(uri) ?: "*/*" })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, a.name))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "V telefonu není aplikace, která tenhle soubor otevře.", Toast.LENGTH_LONG).show()
    }
}

/** Přílohy v editoru: seznam s odebráním a tlačítko pro výběr souborů. */
@Composable
fun AttachmentEditor(vm: AppViewModel, files: List<Attachment>, onChange: (List<Attachment>) -> Unit) {
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(files)
    var busy by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true; errors = emptyList()
        scope.launch {
            val (added, failed) = vm.importFiles(uris)
            onChange(current + added)
            errors = failed
            busy = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Přílohy", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (files.isNotEmpty()) AttachmentList(vm.files, files, onRemove = { a -> onChange(files - a) })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }, enabled = !busy) {
                Icon(Icons.Outlined.AttachFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Přidat soubor")
            }
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        Text(
            "PDF, dokumenty, tabulky… nejvýš ${AttachmentStore.MAX_SIZE / 1_000_000} MB na soubor.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
