package dev.mtzforge.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mtzforge.app.ApplyMethod
import dev.mtzforge.app.MainViewModel
import dev.mtzforge.app.shizuku.ShizukuManager

private enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    FONTS("Fuentes", Icons.Outlined.TextFields),
    ICONS("Iconos", Icons.Outlined.Apps),
    FOLDER("Carpeta", Icons.Outlined.Folder),
    SETUP("Ajustes", Icons.Outlined.Settings),
}

private val FONT_MIMES = arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/vnd.ms-opentype", "application/octet-stream")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MtzForgeApp(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val shizuku by vm.shizuku.status.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showResult by remember { mutableStateOf(false) }

    val pickFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::loadFont) }
    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::loadPackApk) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::convertFolder) }
    val pickMtz = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.apply(ApplyMethod.IMPORT_MTZ, u) } }

    LaunchedEffect(state.output) { if (state.output != null) showResult = true }

    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("MTZ Forge") },
                actions = {
                    if (state.output != null) IconButton(onClick = { showResult = true }) { Icon(Icons.Outlined.Inventory2, "Último resultado") }
                },
                scrollBehavior = scroll,
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(t.icon, null) }, label = { Text(t.label) })
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            BusyBar(state.busy, state.progress)
            when (Tab.entries[tab]) {
                Tab.FONTS -> FontScreen(state.font, state.busy, { pickFont.launch(FONT_MIMES) }, vm::updateFont, vm::selectCollectionIndex, vm::buildFont)
                Tab.ICONS -> IconScreen(state.icons, state.busy,
                    { pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) },
                    vm::listInstalledPacks, vm::updateIcons, vm::buildIcons)
                Tab.FOLDER -> BatchScreen(state.batch, state.busy) { pickFolder.launch(null) }
                Tab.SETUP -> SetupScreen(shizuku, state.themesVersion,
                    onRequest = vm.shizuku::requestPermission,
                    onOpenShizuku = { vm.shizuku.openShizukuApp() },
                    onRefresh = vm.shizuku::refresh,
                    onOpenThemes = vm::openThemes,
                    onImportMtz = { pickMtz.launch(arrayOf("*/*")) },
                    importEnabled = shizuku == ShizukuManager.Status.READY && !state.busy)
            }
        }
    }

    val out = state.output
    if (showResult && out != null) {
        ModalBottomSheet(onDismissRequest = { showResult = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            ResultContent(out, shizuku == ShizukuManager.Status.READY, vm.hasLastFont, vm.hasLastIcons, state.busy,
                onShare = {
                    val i = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
                        .putExtra(Intent.EXTRA_STREAM, out.exported.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    ctx.startActivity(Intent.createChooser(i, "Compartir MTZ"))
                },
                onOpenThemes = vm::openThemes,
                onApply = { m -> if (m == ApplyMethod.IMPORT_MTZ) pickMtz.launch(arrayOf("*/*")) else vm.apply(m) },
            )
        }
    }

    state.installedPacks?.takeIf { it.isNotEmpty() }?.let { packs ->
        AlertDialog(onDismissRequest = vm::closePackPicker, confirmButton = {}, dismissButton = { TextButton(onClick = vm::closePackPicker) { Text("Cancelar") } },
            title = { Text("Packs instalados") },
            text = {
                LazyColumn {
                    items(packs) { p ->
                        ListItem(headlineContent = { Text(p.label) }, supportingContent = { Text(p.packageName) },
                            modifier = Modifier.clickable { vm.loadInstalledPack(p.packageName) },
                            leadingContent = { Icon(Icons.Outlined.Apps, null) })
                    }
                }
            })
    }

    val msg = state.message ?: state.error
    if (msg != null) {
        AlertDialog(onDismissRequest = vm::dismissMessages,
            confirmButton = { TextButton(onClick = vm::dismissMessages) { Text("Aceptar") } },
            title = { Text(if (state.error != null) "Algo salió mal" else "Listo") },
            text = { Text(msg, style = MaterialTheme.typography.bodyMedium) })
    }
}
