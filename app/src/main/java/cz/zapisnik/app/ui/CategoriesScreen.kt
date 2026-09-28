package cz.zapisnik.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.zapisnik.app.data.Category
import cz.zapisnik.app.data.Repository

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CategoriesScreen(vm: AppViewModel) {
    val cats by vm.categories.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Category?>(null) }
    var deleting by remember { mutableStateOf<Category?>(null) }
    var newName by remember { mutableStateOf("") }
    var newError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Kategorie", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zpět") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(cats, key = { it.id }) { c ->
                val count = entries.count { it.categoryId == c.id }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().clickable { editing = c },
                ) {
                    Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(22.dp).background(categoryColor(c.color), RoundedCornerShape(6.dp)))
                        Text(c.name, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.titleMedium)
                        Text("$count", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onClick = { deleting = c }) { Icon(Icons.Default.Delete, "Smazat kategorii ${c.name}") }
                    }
                }
            }
            item {
                val err = newError
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it; newError = null },
                        placeholder = { Text("Nová kategorie, např. Zahrada") },
                        isError = err != null,
                        supportingText = if (err != null) {{ Text(err) }} else null,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            val n = newName.trim()
                            when {
                                n.isEmpty() -> newError = "Napiš název kategorie."
                                cats.any { fold(it.name) == fold(n) } -> newError = "Taková kategorie už existuje."
                                else -> { vm.addCategory(n); newName = "" }
                            }
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Přidat") }
                }
            }
            item {
                Text(
                    "Klepnutím na kategorii ji přejmenuješ nebo změníš barvu. Přejmenování se projeví u všech jejích záznamů.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    editing?.let { c ->
        var name by remember(c.id) { mutableStateOf(c.name) }
        var color by remember(c.id) { mutableStateOf(c.color) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Upravit kategorii") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Název") }, singleLine = true)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        (0 until Repository.CATEGORY_COLOR_COUNT).forEach { i ->
                            Box(
                                Modifier.size(34.dp)
                                    .border(2.dp, if (i == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background, CircleShape)
                                    .padding(4.dp)
                                    .background(categoryColor(i), CircleShape)
                                    .clickable { color = i },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = { vm.updateCategory(c.copy(name = name.trim(), color = color)); editing = null },
                ) { Text("Uložit") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Zrušit") } },
        )
    }

    deleting?.let { c ->
        val count = entries.count { it.categoryId == c.id }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Smazat kategorii „${c.name}“?") },
            text = {
                Text(if (count > 0) "Záznamy v ní (${count}) zůstanou zachované, jen budou bez kategorie." else "Kategorie je prázdná.")
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteCategory(c.id); deleting = null }) {
                    Text("Smazat", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Nechat") } },
        )
    }
}
