package cz.zapisnik.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.zapisnik.app.data.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Načte obrázek na pozadí; null, dokud se načítá nebo když ještě není stažený z Disku. */
@Composable
private fun rememberBitmap(store: ImageStore, id: String, maxSide: Int): Bitmap? {
    val version by store.version.collectAsStateWithLifecycle()
    val bitmap by produceState<Bitmap?>(null, id, maxSide, version) {
        value = withContext(Dispatchers.IO) { runCatching { store.load(id, maxSide) }.getOrNull() }
    }
    return bitmap
}

@Composable
fun ImageThumb(store: ImageStore, id: String, size: Dp, onClick: () -> Unit, onRemove: (() -> Unit)? = null) {
    val bitmap = rememberBitmap(store, id, 480)
    Box(Modifier.size(size)) {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), "Obrázek", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Outlined.CloudDownload, "Obrázek se ještě stahuje", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onRemove != null) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(28.dp)
                    .background(Color.Black.copy(alpha = .55f), CircleShape),
            ) { Icon(Icons.Default.Close, "Odebrat obrázek", tint = Color.White, modifier = Modifier.size(16.dp)) }
        }
    }
}

/** Náhledy obrázků; klepnutí otevře prohlížení přes celou obrazovku. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImageGallery(store: ImageStore, ids: List<String>, onRemove: ((String) -> Unit)? = null) {
    var open by remember { mutableStateOf<Int?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ids.forEachIndexed { i, id ->
            ImageThumb(store, id, 104.dp, onClick = { open = i }, onRemove = onRemove?.let { { it(id) } })
        }
    }
    open?.let { ImageViewer(store, ids, it) { open = null } }
}

@Composable
fun ImageViewer(store: ImageStore, ids: List<String>, start: Int, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = start.coerceIn(0, (ids.size - 1).coerceAtLeast(0))) { ids.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                val bitmap = rememberBitmap(store, ids[page], 2048)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (bitmap != null) {
                        Image(bitmap.asImageBitmap(), "Obrázek ${page + 1}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    } else {
                        Text("Obrázek se ještě stahuje z Disku…", color = Color.White)
                    }
                }
            }
            Row(Modifier.safeDrawingPadding().padding(8.dp).align(Alignment.TopEnd), verticalAlignment = Alignment.CenterVertically) {
                if (ids.size > 1) Text("${pager.currentPage + 1} / ${ids.size}", color = Color.White)
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Zavřít", tint = Color.White) }
            }
        }
    }
}

/** Obrázky v editoru: náhledy s odebráním a tlačítka pro galerii a fotoaparát. */
@Composable
fun ImageEditor(vm: AppViewModel, ids: List<String>, onChange: (List<String>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var cameraFile by rememberSaveable { mutableStateOf<String?>(null) }
    val current by rememberUpdatedState(ids)

    fun add(uris: List<Uri>, cleanup: () -> Unit = {}) {
        if (uris.isEmpty()) return cleanup()
        busy = true; error = null
        scope.launch {
            val added = vm.importImages(uris)
            cleanup()
            if (added.size < uris.size) error = "Některý obrázek se nepodařilo načíst."
            onChange(current + added)
            busy = false
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { add(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile?.let(::File)
        if (ok && f != null) add(listOf(Uri.fromFile(f))) { f.delete() } else f?.delete()
        cameraFile = null
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Obrázky", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ids.isNotEmpty()) ImageGallery(vm.images, ids, onRemove = { id -> onChange(ids - id) })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !busy,
            ) { Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Z galerie") }
            OutlinedButton(
                onClick = {
                    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                    val f = File(dir, "foto-${System.currentTimeMillis()}.jpg")
                    cameraFile = f.path
                    camera.launch(FileProvider.getUriForFile(context, context.packageName + ".files", f))
                },
                enabled = !busy,
            ) { Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Vyfotit") }
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

private const val MAX_PICK = 20
