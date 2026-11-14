package dev.mtzforge.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mtzforge.app.PreviewRenderer.encode
import dev.mtzforge.app.shizuku.ShizukuManager
import dev.mtzforge.core.FontMtzBuilder
import dev.mtzforge.core.FontMtzOptions
import dev.mtzforge.core.IconMtzBuilder
import dev.mtzforge.core.IconMtzOptions
import dev.mtzforge.core.MtzInspector
import dev.mtzforge.core.MtzPaths
import dev.mtzforge.core.Previews
import dev.mtzforge.core.SfntFont
import dev.mtzforge.core.ThemeDescription
import dev.mtzforge.core.ThemeMerger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FontUi(
    val fileName: String? = null,
    val summary: String = "",
    val warnings: List<String> = emptyList(),
    val collectionSize: Int = 1,
    val thumbnail: ImageBitmap? = null,
    val title: String = "",
    val author: String = "",
    val index: Int = 0,
    val fallback: Boolean = false,
)

data class IconUi(
    val label: String? = null,
    val packageName: String = "",
    val summary: String = "",
    val title: String = "",
    val size: Int = 168,
    val activityIcons: Boolean = true,
    val sample: List<ImageBitmap> = emptyList(),
)

data class BatchItem(val name: String, val ok: Boolean?, val detail: String = "")

enum class OutputKind(val code: String, val label: String) { FONT("fonts", "Fuente"), ICONS("icons", "Iconos"), THEME("theme", "Tema") }

data class Output(
    val kind: OutputKind,
    val title: String,
    val exported: DownloadsExporter.Exported,
    val cached: File,
    val details: List<String>,
)

data class UiState(
    val font: FontUi = FontUi(),
    val icons: IconUi = IconUi(),
    val batch: List<BatchItem> = emptyList(),
    val busy: Boolean = false,
    val progress: Float? = null,
    val output: Output? = null,
    val message: String? = null,
    val error: String? = null,
    val themesVersion: String? = null,
    val installedPacks: List<IconPack.Companion.Installed>? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    val shizuku = ShizukuManager(app).also { it.start() }
    private val installer = XiaomiInstaller(app, shizuku)

    private val _state = MutableStateFlow(UiState(themesVersion = ThemeManagerLauncher.installedVersion(app)))
    val state: StateFlow<UiState> = _state

    private var fontBytes: ByteArray? = null
    private var iconPack: IconPack? = null

    private val lastFont = File(app.filesDir, "last_font.mtz")
    private val lastIcons = File(app.filesDir, "last_icons.mtz")
    val hasLastFont get() = lastFont.exists()
    val hasLastIcons get() = lastIcons.exists()

    override fun onCleared() { shizuku.stop() }

    fun dismissMessages() = _state.update { it.copy(message = null, error = null) }
    fun updateFont(f: (FontUi) -> FontUi) = _state.update { it.copy(font = f(it.font)) }
    fun updateIcons(f: (IconUi) -> IconUi) = _state.update { it.copy(icons = f(it.icons)) }

    private fun work(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, progress = null, error = null, message = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Throwable) {
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _state.update { it.copy(busy = false, progress = null) }
            }
        }
    }

    // ---------------- Fuentes ----------------

    fun loadFont(uri: Uri) = work {
        val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val name = displayName(uri) ?: "fuente"
        val count = SfntFont.fontCount(bytes)
        val (single, font) = FontMtzBuilder.prepareFont(bytes, 0)
        val thumb = PreviewRenderer.fontThumbnail(font.displayName, typefaceOf(single))
        fontBytes = bytes
        _state.update {
            it.copy(font = it.font.copy(
                fileName = name,
                summary = "${font.displayName} · ${font.outline} · ${font.glyphCount} glifos",
                warnings = font.warnings(),
                collectionSize = count,
                thumbnail = decode(thumb),
                title = font.displayName,
                index = 0,
            ))
        }
    }

    fun selectCollectionIndex(i: Int) = work {
        val bytes = fontBytes ?: return@work
        val (single, font) = FontMtzBuilder.prepareFont(bytes, i)
        val thumb = PreviewRenderer.fontThumbnail(font.displayName, typefaceOf(single))
        _state.update { it.copy(font = it.font.copy(index = i, title = font.displayName, thumbnail = decode(thumb),
            summary = "${font.displayName} · ${font.outline} · ${font.glyphCount} glifos", warnings = font.warnings())) }
    }

    fun buildFont() = work {
        val bytes = fontBytes ?: error("Elige primero una fuente")
        val f = _state.value.font
        val title = f.title.trim().ifEmpty { f.fileName?.substringBeforeLast('.') ?: "Fuente" }
        val out = buildFontMtz(bytes, f.index, title, f.author, f.fallback)
        out.cached.copyTo(lastFont, overwrite = true)
        _state.update { it.copy(output = out) }
    }

    private fun buildFontMtz(bytes: ByteArray, index: Int, title: String, author: String, fallback: Boolean, keep: Boolean = true): Output {
        val (single, _) = FontMtzBuilder.prepareFont(bytes, index)
        val tf = typefaceOf(single)
        val previews = Previews(mapOf(
            MtzPaths.PREVIEW_FONT_SMALL to PreviewRenderer.fontThumbnail(title, tf),
            MtzPaths.PREVIEW_FONT to PreviewRenderer.fontPreview(title, tf),
        ))
        val desc = ThemeDescription(title = title, author = author.trim().ifEmpty { "MTZ Forge" }, description = "Fuente empaquetada con MTZ Forge")
        val tmp = File(ctx.cacheDir, "font_${System.nanoTime()}.mtz")
        val r = tmp.outputStream().use { FontMtzBuilder.build(bytes, FontMtzOptions(desc, index, fallback), previews, it) }
        return finish(tmp, title, OutputKind.FONT, r.warnings, keep)
    }

    // ---------------- Carpeta de fuentes (lote) ----------------

    fun convertFolder(tree: Uri) = work {
        val files = listFontsInTree(tree)
        if (files.isEmpty()) error("La carpeta no contiene ficheros .ttf, .otf o .ttc")
        _state.update { it.copy(batch = files.map { f -> BatchItem(f.second, null) }) }
        val author = _state.value.font.author
        files.forEachIndexed { i, (uri, name) ->
            val item = runCatching {
                val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val (_, font) = FontMtzBuilder.prepareFont(bytes, 0)
                val title = font.displayName.ifBlank { name.substringBeforeLast('.') }
                val out = buildFontMtz(bytes, 0, title, author, false, keep = false)
                BatchItem(name, true, "→ ${out.exported.displayName}")
            }.getOrElse { BatchItem(name, false, it.message ?: "error") }
            _state.update { s -> s.copy(batch = s.batch.toMutableList().also { l -> l[i] = item }, progress = (i + 1f) / files.size) }
        }
        val ok = _state.value.batch.count { it.ok == true }
        _state.update { it.copy(message = "Lote terminado: $ok de ${files.size} fuentes convertidas en Descargas/${DownloadsExporter.SUBDIR}") }
    }

    private fun listFontsInTree(tree: Uri): List<Pair<Uri, String>> {
        val result = ArrayList<Pair<Uri, String>>()
        fun walk(docId: String, depth: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            ctx.contentResolver.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0); val name = c.getString(1) ?: continue; val mime = c.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { if (depth < 4) walk(id, depth + 1) }
                    else if (name.substringAfterLast('.', "").lowercase() in setOf("ttf", "otf", "ttc")) {
                        result += DocumentsContract.buildDocumentUriUsingTree(tree, id) to name
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), 0)
        return result.sortedBy { it.second.lowercase() }
    }

    // ---------------- Iconos ----------------

    fun listInstalledPacks() = work {
        val packs = IconPack.listInstalled(ctx)
        _state.update { it.copy(installedPacks = packs, message = if (packs.isEmpty()) "No hay packs de iconos instalados" else null) }
    }

    fun closePackPicker() = _state.update { it.copy(installedPacks = null) }

    fun loadInstalledPack(pkg: String) { closePackPicker(); loadPack { IconPack.fromInstalled(ctx, pkg) } }
    fun loadPackApk(uri: Uri) = loadPack { IconPack.fromApkUri(ctx, uri) }

    private fun loadPack(loader: () -> IconPack) = work {
        val pack = loader()
        val filter = pack.loadAppFilter()
        val sample = filter.drawables.asSequence().mapNotNull { pack.render(it, 144) }.take(12).map { it.asImageBitmap() }.toList()
        iconPack = pack
        _state.update {
            it.copy(icons = it.icons.copy(label = pack.label, packageName = pack.packageName,
                summary = "${filter.items.size} entradas · ${filter.drawables.size} iconos únicos",
                title = pack.label, sample = sample))
        }
    }

    fun buildIcons() = work {
        val pack = iconPack ?: error("Elige primero un pack de iconos")
        val ui = _state.value.icons
        val title = ui.title.trim().ifEmpty { pack.label }
        val size = ui.size.coerceIn(48, 512)
        val filter = pack.loadAppFilter()
        val sample = filter.drawables.asSequence().mapNotNull { pack.render(it, 144) }.take(24).toList()
        val previews = Previews(mapOf(MtzPaths.PREVIEW_ICONS to PreviewRenderer.iconGrid(sample, title)))
        val desc = ThemeDescription(title = title, description = "Iconos convertidos desde ${pack.packageName} con MTZ Forge")
        val tmp = File(ctx.cacheDir, "icons_${System.nanoTime()}.mtz")
        val r = tmp.outputStream().use {
            IconMtzBuilder.build(filter, IconMtzOptions(desc, ui.activityIcons), previews, it, tempDir = ctx.cacheDir,
                progress = { d, t -> if (d % 20 == 0 || d == t) _state.update { s -> s.copy(progress = d.toFloat() / t) } },
            ) { d -> pack.render(d, size)?.encode(Bitmap.CompressFormat.PNG) }
        }
        val notes = buildList {
            add("${r.written} iconos escritos")
            if (r.missing.isNotEmpty()) add("${r.missing.size} iconos no encontrados en el APK (p. ej. ${r.missing.take(3).joinToString()})")
        }
        val out = finish(tmp, title, OutputKind.ICONS, notes)
        out.cached.copyTo(lastIcons, overwrite = true)
        _state.update { it.copy(output = out) }
    }

    // ---------------- Aplicación en Xiaomi ----------------

    fun apply(method: ApplyMethod, importUri: Uri? = null) = work {
        val result = when (method) {
            ApplyMethod.FONT_LIBRARY -> {
                val src = currentOrLast(OutputKind.FONT, lastFont) ?: error("Genera primero un MTZ de fuente")
                installer.installToLibrary(src.readBytes(), "fonts")
            }
            ApplyMethod.MTZ_PACKAGE -> {
                val o = _state.value.output ?: error("Genera primero un MTZ")
                installer.applyMtzPackage(o.cached, o.exported.absolutePath)
            }
            ApplyMethod.FULL_THEME -> {
                val parts = listOfNotNull(lastFont.takeIf { it.exists() }, lastIcons.takeIf { it.exists() })
                if (parts.isEmpty()) error("Genera antes una fuente y/o un pack de iconos")
                val title = listOfNotNull(_state.value.font.title.takeIf { lastFont.exists() && it.isNotBlank() },
                    _state.value.icons.title.takeIf { lastIcons.exists() && it.isNotBlank() }).joinToString(" + ").ifEmpty { "Tema MTZ Forge" }
                val tmp = File(ctx.cacheDir, "theme_${System.nanoTime()}.mtz")
                tmp.outputStream().use { ThemeMerger.merge(parts.map { it.readBytes() }, ThemeDescription(title = title, description = "Tema combinado con MTZ Forge"), it) }
                val out = finish(tmp, title, OutputKind.THEME, listOf("Combinado: " + parts.joinToString { if (it == lastFont) "fuente" else "iconos" }))
                _state.update { it.copy(output = out) }
                installer.installToLibrary(out.cached.readBytes(), "theme")
            }
            ApplyMethod.IMPORT_MTZ -> {
                val uri = importUri ?: error("Elige un fichero .mtz")
                val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val rep = MtzInspector.inspect(bytes)
                if (!rep.isValid) error("MTZ no válido: ${rep.errors.joinToString()}")
                installer.installToLibrary(bytes, if (rep.components.values.toSet() == setOf("fonts")) "fonts" else "theme")
            }
        }
        _state.update { it.copy(message = result.message) }
    }

    fun openThemes() {
        val code = _state.value.output?.kind?.code
        if (!ThemeManagerLauncher.open(ctx, code)) _state.update { it.copy(error = "No se encontró la app Temas (com.android.thememanager)") }
    }

    private fun currentOrLast(kind: OutputKind, last: File): File? =
        _state.value.output?.takeIf { it.kind == kind && it.cached.exists() }?.cached ?: last.takeIf { it.exists() }

    // ---------------- Utilidades ----------------

    /** Valida el MTZ releyéndolo, lo exporta a Descargas y conserva una copia interna. */
    private fun finish(tmp: File, title: String, kind: OutputKind, notes: List<String>, keep: Boolean = true): Output {
        val rep = MtzInspector.inspect(tmp)
        if (!rep.isValid) { tmp.delete(); error("Validación fallida: ${rep.errors.joinToString()}") }
        val exported = DownloadsExporter.export(ctx, tmp, title)
        val cached = File(ctx.filesDir, "current_${kind.code}.mtz")
        val size = tmp.length()
        if (keep) tmp.copyTo(cached, overwrite = true)
        tmp.delete()
        val details = listOf("Componentes: ${rep.components.values.joinToString()} · %.1f KB".format(size / 1024.0)) + notes + rep.warnings
        return Output(kind, title, exported, cached, details)
    }

    private fun typefaceOf(single: ByteArray): Typeface {
        val tmp = File(ctx.cacheDir, "preview_${single.size}_${single.contentHashCode()}.ttf")
        if (!tmp.exists()) tmp.writeBytes(single)
        return runCatching { Typeface.createFromFile(tmp) }.getOrDefault(Typeface.DEFAULT)
    }

    private fun decode(png: ByteArray) = BitmapFactory.decodeByteArray(png, 0, png.size).asImageBitmap()

    private fun displayName(uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment
}
