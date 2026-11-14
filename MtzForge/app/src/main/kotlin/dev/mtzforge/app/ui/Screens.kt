package dev.mtzforge.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mtzforge.app.ApplyMethod
import dev.mtzforge.app.BatchItem
import dev.mtzforge.app.FontUi
import dev.mtzforge.app.IconUi
import dev.mtzforge.app.Output
import dev.mtzforge.app.shizuku.ShizukuManager

@Composable
fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun SectionCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
private fun EmptyHint(text: String, icon: ImageVector) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Warnings(list: List<String>) {
    list.forEach {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Warning, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------- Fuentes ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FontScreen(
    font: FontUi, busy: Boolean,
    onPick: () -> Unit, onChange: ((FontUi) -> FontUi) -> Unit, onIndex: (Int) -> Unit, onBuild: () -> Unit,
) = ScreenColumn {
    SectionCard("Fuente TTF / OTF / TTC", Icons.Outlined.TextFields) {
        FilledTonalButton(onClick = onPick, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Elegir fuente")
        }
        if (font.fileName == null) {
            Text("También puedes abrir una fuente desde el gestor de archivos con «Abrir con… MTZ Forge».",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (font.fileName != null) {
        SectionCard("Vista previa", Icons.Outlined.FontDownload) {
            font.thumbnail?.let { Preview(it) }
            Text(font.fileName, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(font.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Warnings(font.warnings)
            if (font.collectionSize > 1) {
                Text("Colección con ${font.collectionSize} fuentes:", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(font.collectionSize) { i ->
                        FilterChip(selected = font.index == i, onClick = { onIndex(i) }, label = { Text("#${i + 1}") }, enabled = !busy)
                    }
                }
            }
        }
        SectionCard("Datos del tema", Icons.Outlined.Palette) {
            OutlinedTextField(font.title, { v -> onChange { it.copy(title = v) } }, label = { Text("Título") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(font.author, { v -> onChange { it.copy(author = v) } }, label = { Text("Autor (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SwitchRow("Usar también como fuente de reserva", "Añade DroidSansFallback.ttf (experimental)", font.fallback) { v -> onChange { it.copy(fallback = v) } }
            Button(onClick = onBuild, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Generar MTZ de fuente") }
        }
    }
}

@Composable
private fun Preview(img: ImageBitmap) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest), modifier = Modifier.fillMaxWidth()) {
        Image(img, null, Modifier.fillMaxWidth().height(72.dp).padding(8.dp), contentScale = ContentScale.Fit)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

// ---------------- Iconos ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IconScreen(
    icons: IconUi, busy: Boolean,
    onPickApk: () -> Unit, onPickInstalled: () -> Unit, onChange: ((IconUi) -> IconUi) -> Unit, onBuild: () -> Unit,
) = ScreenColumn {
    SectionCard("Pack de iconos → MTZ", Icons.Outlined.Apps) {
        Text("Compatible con packs que usan appfilter.xml (Nova, ADW, Lawnchair…).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onPickInstalled, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Instalado") }
            OutlinedButton(onClick = onPickApk, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Fichero APK") }
        }
    }
    if (icons.label == null) {
        EmptyHint("Elige un pack de iconos instalado o un fichero APK.", Icons.Outlined.Apps)
    } else {
        SectionCard(icons.label, Icons.Outlined.Palette) {
            Text(icons.packageName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(icons.summary, style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                icons.sample.forEach { Image(it, null, Modifier.size(48.dp)) }
            }
        }
        SectionCard("Opciones", Icons.Outlined.Palette) {
            OutlinedTextField(icons.title, { v -> onChange { it.copy(title = v) } }, label = { Text("Título") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(icons.size.toString(), { v -> onChange { it.copy(size = v.filter(Char::isDigit).take(3).toIntOrNull() ?: 0) } },
                label = { Text("Tamaño de icono (px)") }, supportingText = { Text("168 px observado en temas MIUI") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            SwitchRow("Solo apps instaladas", "Incluye solo los iconos de tus apps (${icons.installedMatches}); el MTZ es mucho más pequeño", icons.installedOnly) { v -> onChange { it.copy(installedOnly = v) } }
            SwitchRow("Iconos por actividad", "Crea <actividad>.png cuando una app usa iconos distintos", icons.activityIcons) { v -> onChange { it.copy(activityIcons = v) } }
            Button(onClick = onBuild, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Generar MTZ de iconos") }
        }
    }
}

// ---------------- Carpeta ----------------

@Composable
fun BatchScreen(items: List<BatchItem>, busy: Boolean, onPickFolder: () -> Unit) = ScreenColumn {
    SectionCard("Convertir una carpeta de fuentes", Icons.Outlined.Folder) {
        Text("Busca .ttf, .otf y .ttc (incluidas subcarpetas) y crea un MTZ por fuente en Descargas/MTZ Forge.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onPickFolder, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Outlined.Folder, null); Spacer(Modifier.width(8.dp)); Text("Elegir carpeta")
        }
    }
    if (items.isEmpty()) EmptyHint("Aún no has convertido ninguna carpeta.", Icons.Outlined.Folder)
    else ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            items.forEach { it ->
                ListItem(
                    headlineContent = { Text(it.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { if (it.detail.isNotEmpty()) Text(it.detail, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    leadingContent = {
                        when (it.ok) {
                            true -> Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.secondary)
                            false -> Icon(Icons.Outlined.Error, null, tint = MaterialTheme.colorScheme.error)
                            null -> Icon(Icons.Outlined.HourglassEmpty, null, tint = MaterialTheme.colorScheme.outline)
                        }
                    },
                )
            }
        }
    }
}

// ---------------- Shizuku / Xiaomi ----------------

@Composable
fun SetupScreen(
    shizuku: ShizukuManager.Status, themesVersion: String?,
    onRequest: () -> Unit, onOpenShizuku: () -> Unit, onRefresh: () -> Unit, onOpenThemes: () -> Unit,
    onImportMtz: () -> Unit = {}, importEnabled: Boolean = false,
) = ScreenColumn {
    SectionCard("Temas de Xiaomi", Icons.Outlined.Palette) {
        StatusLine(themesVersion != null, themesVersion?.let { "Detectado · versión $it" } ?: "No detectado en este dispositivo")
        OutlinedButton(onClick = onOpenThemes, modifier = Modifier.fillMaxWidth()) { Text("Abrir Temas") }
    }
    SectionCard("Shizuku", Icons.Outlined.Security) {
        StatusLine(shizuku == ShizukuManager.Status.READY, shizuku.label)
        Text("Los métodos 1, 3 y 4 escriben en la carpeta privada de Temas (Android/data). En Android 10+ eso requiere Shizuku, que se activa por depuración inalámbrica sin root.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (shizuku) {
            ShizukuManager.Status.NO_PERMISSION -> Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) { Text("Conceder permiso") }
            ShizukuManager.Status.NOT_RUNNING -> Button(onClick = onOpenShizuku, modifier = Modifier.fillMaxWidth()) { Text("Abrir Shizuku para iniciarlo") }
            ShizukuManager.Status.NOT_INSTALLED -> Text("Instala Shizuku desde Google Play o GitHub (RikkaApps/Shizuku).", style = MaterialTheme.typography.bodyMedium)
            ShizukuManager.Status.READY -> {}
        }
        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Comprobar de nuevo") }
    }
    SectionCard("Importar un MTZ existente", Icons.Outlined.InstallMobile) {
        Text(ApplyMethod.IMPORT_MTZ.subtitle + ".", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(onClick = onImportMtz, enabled = importEnabled, modifier = Modifier.fillMaxWidth()) { Text("Método 4 · Elegir .mtz") }
    }
    SectionCard("Sobre los métodos", Icons.Outlined.Security) {
        Text("MTZ Forge nunca crea ni copia licencias (.mra). Si tu versión de Temas exige licencia para contenido local, los métodos 1, 3 y 4 instalarán los ficheros pero Temas podría no aplicarlos. El método 2 y la importación manual del MTZ no dependen de ello.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
    AssistChip(onClick = {}, label = { Text(text) },
        leadingIcon = { Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.Warning, null, Modifier.size(18.dp)) },
        colors = AssistChipDefaults.assistChipColors(
            leadingIconContentColor = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary))
}

// ---------------- Resultado ----------------

@Composable
fun ResultContent(
    output: Output, shizukuReady: Boolean, hasLastFont: Boolean, hasLastIcons: Boolean, busy: Boolean,
    onShare: () -> Unit, onOpenThemes: () -> Unit, onApply: (ApplyMethod) -> Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(output.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${output.kind.label} · Descargas/MTZ Forge/${output.exported.displayName}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        output.details.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Share, null); Spacer(Modifier.width(6.dp)); Text("Compartir") }
            FilledTonalButton(onClick = onOpenThemes, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Palette, null); Spacer(Modifier.width(6.dp)); Text("Abrir Temas") }
        }
        Text("Aplicar en Xiaomi / HyperOS", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        ApplyMethod.entries.forEach { m ->
            val available = when (m) {
                ApplyMethod.FONT_LIBRARY -> hasLastFont
                ApplyMethod.FULL_THEME -> hasLastFont || hasLastIcons
                else -> true
            }
            MethodCard(m, enabled = !busy && available && (!m.needsShizuku || shizukuReady), shizukuMissing = m.needsShizuku && !shizukuReady) { onApply(m) }
        }
    }
}

@Composable
private fun MethodCard(m: ApplyMethod, enabled: Boolean, shizukuMissing: Boolean, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (m.needsShizuku) Text("Shizuku", style = MaterialTheme.typography.labelSmall,
                    color = if (shizukuMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
            }
            Text(m.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun BusyBar(busy: Boolean, progress: Float?) {
    AnimatedVisibility(busy) {
        if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
