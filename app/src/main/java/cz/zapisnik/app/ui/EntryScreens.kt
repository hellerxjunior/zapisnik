package cz.zapisnik.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(vm: AppViewModel, id: String) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val cats by vm.categories.collectAsStateWithLifecycle()
    val e = entries.find { it.id == id }
    val cat = cats.find { it.id == e?.categoryId }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zpět") } },
                actions = {
                    if (e != null) OutlinedButton(onClick = { vm.open(Screen.Edit(e.id)) }, modifier = Modifier.padding(end = 8.dp)) {
                        Icon(Icons.Default.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Upravit")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        if (e == null) { EmptyState("Záznam nenalezen", "Mohl být mezitím smazán."); return@Scaffold }
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(fmtDate(e.date), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (cat != null) Text(cat.name, color = categoryColor(cat.color), fontWeight = FontWeight.Bold)
            }
            SelectionContainer {
                Text(e.title.ifBlank { "Bez nadpisu" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        e.text.ifBlank { "Bez poznámky" },
                        modifier = Modifier.padding(16.dp),
                        color = if (e.text.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            if (e.images.isNotEmpty()) ImageGallery(vm.images, e.images)
            if (e.files.isNotEmpty()) AttachmentList(vm.files, e.files)
            Text(
                "Naposledy upraveno ${fmtTimestamp(e.updated)}",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(vm: AppViewModel, id: String?) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val cats by vm.categories.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val existing = remember(id) { entries.find { it.id == id } }

    var title by rememberSaveable { mutableStateOf(existing?.title ?: "") }
    var date by rememberSaveable { mutableStateOf(existing?.date ?: today()) }
    var categoryId by rememberSaveable {
        mutableStateOf(existing?.categoryId ?: filter?.takeIf { f -> f.isNotEmpty() } ?: cats.firstOrNull()?.id)
    }
    var text by rememberSaveable { mutableStateOf(existing?.text ?: "") }
    var images by rememberSaveable { mutableStateOf(existing?.images ?: emptyList()) }
    var files by rememberSaveable { mutableStateOf(existing?.files ?: emptyList()) }
    var titleError by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var catMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (existing != null) "Upravit záznam" else "Nový záznam", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.Default.Close, "Zrušit") } },
                actions = {
                    Button(
                        onClick = {
                            if (title.isBlank()) titleError = true
                            else vm.saveEntry(existing, title, date, categoryId?.takeIf { c -> cats.any { it.id == c } }, text, images, files)
                        },
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text("Uložit") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it; titleError = false },
                label = { Text("Nadpis") },
                placeholder = { Text("Např. Výměna brzdových destiček") },
                isError = titleError,
                supportingText = if (titleError) {{ Text("Doplň nadpis, podle něj záznam později najdeš.") }} else null,
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { showDate = true },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Icon(Icons.Default.CalendarMonth, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(fmtDate(date))
                }
                ExposedDropdownMenuBox(expanded = catMenu, onExpandedChange = { catMenu = it }, modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = cats.find { it.id == categoryId }?.name ?: "Bez kategorie",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Kategorie") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catMenu) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                        cats.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c.name) },
                                leadingIcon = { Box(Modifier.size(10.dp).background(categoryColor(c.color), CircleShape)) },
                                onClick = { categoryId = c.id; catMenu = false },
                            )
                        }
                        DropdownMenuItem(text = { Text("Bez kategorie") }, onClick = { categoryId = null; catMenu = false })
                    }
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Poznámka") },
                placeholder = { Text("Co se dělalo, kdo to dělal, cena, stav tachometru, na co nezapomenout příště…") },
                minLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            ImageEditor(vm, images) { images = it }
            AttachmentEditor(vm, files) { files = it }
            if (existing != null) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.padding(bottom = 24.dp),
                ) { Text("Smazat záznam") }
            }
        }
    }

    if (showDate) {
        val initial = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                    showDate = false
                }) { Text("Vybrat") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Zrušit") } },
        ) { DatePicker(state = state) }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Smazat záznam?") },
            text = { Text("„${existing.title}“ se smaže z telefonu i z ostatních zařízení. Tohle nejde vrátit.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteEntry(existing.id) }) {
                    Text("Smazat", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Nechat") } },
        )
    }
}
