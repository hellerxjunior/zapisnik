package cz.zapisnik.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.zapisnik.app.data.Category
import cz.zapisnik.app.data.Entry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(vm: AppViewModel) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val cats by vm.categories.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val catMap = remember(cats) { cats.associateBy { it.id } }

    val shown = remember(entries, query, filter, catMap) {
        val byCat = entries.filter { e ->
            when (filter) {
                null -> true
                "" -> e.categoryId == null || e.categoryId !in catMap
                else -> e.categoryId == filter
            }
        }
        val q = fold(query.trim())
        if (q.isEmpty()) byCat
        else byCat.mapNotNull { e ->
            val t = fold(e.title)
            val score = when {
                t.startsWith(q) -> 3
                t.contains(q) -> 2
                fold(e.text).contains(q) -> 1
                else -> 0
            }
            if (score > 0) score to e else null
        }.sortedWith(compareByDescending<Pair<Int, Entry>> { it.first }.thenByDescending { it.second.date }).map { it.second }
    }
    val uncategorized = entries.count { it.categoryId == null || it.categoryId !in catMap }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(10.dp).height(24.dp).background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(3.dp)))
                        Spacer(Modifier.width(10.dp))
                        Text("Zápisník", fontWeight = FontWeight.ExtraBold, fontSize = 26.sp)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.open(Screen.Categories) }) { Icon(Icons.Outlined.Category, "Kategorie") }
                    IconButton(onClick = { vm.open(Screen.Account) }) { Icon(Icons.Outlined.AccountCircle, "Účet a synchronizace") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.open(Screen.Edit(null)) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Nový záznam", fontWeight = FontWeight.Bold) },
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("Hledat podle nadpisu…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Close, "Vymazat hledání") }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { CatChip("Vše", null, entries.size, filter == null) { vm.filter.value = null } }
                items(cats, key = { it.id }) { c ->
                    CatChip(c.name, categoryColor(c.color), entries.count { it.categoryId == c.id }, filter == c.id) { vm.filter.value = c.id }
                }
                if (uncategorized > 0) item {
                    CatChip("Bez kategorie", categoryColor(null), uncategorized, filter == "") { vm.filter.value = "" }
                }
            }
            Text(
                if (query.isNotBlank() || filter != null) "Nalezeno: ${shown.size}" else countLabel(shown.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            when {
                entries.isEmpty() -> EmptyState(
                    "Zatím tu nic není",
                    "Zapiš si první událost, ať ji příště nemusíš dohledávat. Třeba výměnu oleje se stavem tachometru, revizi kotle nebo nápad na dárek.",
                )
                shown.isEmpty() -> EmptyState("Nic nenalezeno", "Zkus jiné slovo nebo vyber kategorii Vše.")
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    var lastMonth: String? = null
                    shown.forEach { e ->
                        if (query.isBlank()) {
                            val m = monthLabel(e.date)
                            if (m != lastMonth) {
                                lastMonth = m
                                item(key = "m-$m") {
                                    Text(
                                        m.uppercase(), fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 1.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp),
                                    )
                                }
                            }
                        }
                        item(key = e.id) { EntryCard(e, catMap[e.categoryId], query) { vm.open(Screen.Detail(e.id)) } }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatChip(label: String, color: Color?, count: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text("$label  $count") },
        leadingIcon = if (color != null) {{ Box(Modifier.size(9.dp).background(color, CircleShape)) }} else null,
        shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

@Composable
private fun EntryCard(e: Entry, cat: Category?, query: String, onClick: () -> Unit) {
    val stripe = categoryColor(cat?.color)
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(stripe))
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        highlight(e.title.ifBlank { "Bez nadpisu" }, query, MaterialTheme.colorScheme.secondary.copy(alpha = .45f)),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(fmtDate(e.date), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (cat != null) Text(cat.name, color = stripe, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                if (e.text.isNotBlank()) Text(
                    e.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun highlight(text: String, query: String, bg: Color): AnnotatedString {
    val q = fold(query.trim())
    if (q.isEmpty()) return AnnotatedString(text)
    val i = fold(text).indexOf(q)
    if (i < 0 || i + q.length > text.length) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        addStyle(SpanStyle(background = bg), i, i + q.length)
    }
}

@Composable
fun EmptyState(title: String, body: String) {
    Column(
        Modifier.padding(16.dp).fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(14.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
