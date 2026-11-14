package dev.mtzforge.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import dev.mtzforge.app.PreviewRenderer.encode
import dev.mtzforge.core.FontMtzBuilder
import dev.mtzforge.core.FontMtzOptions
import dev.mtzforge.core.IconMtzBuilder
import dev.mtzforge.core.IconMtzOptions
import dev.mtzforge.core.MtzInspector
import dev.mtzforge.core.MtzPaths
import dev.mtzforge.core.Previews
import dev.mtzforge.core.SfntFont
import dev.mtzforge.core.ThemeDescription
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var fontInfo: TextView
    private lateinit var fontPreview: ImageView
    private lateinit var fontTitle: EditText
    private lateinit var fontAuthor: EditText
    private lateinit var ttcIndex: EditText
    private lateinit var optFallback: CheckBox
    private lateinit var buildFont: Button
    private lateinit var iconInfo: TextView
    private lateinit var iconTitle: EditText
    private lateinit var iconSize: EditText
    private lateinit var optActivityIcons: CheckBox
    private lateinit var buildIcons: Button
    private lateinit var progress: ProgressBar
    private lateinit var resultText: TextView
    private lateinit var applyMtz: Button
    private lateinit var share: Button

    private var fontBytes: ByteArray? = null
    private var fontFileName: String = "fuente"
    private var iconPack: IconPack? = null
    private var lastExport: DownloadsExporter.Exported? = null
    private var lastKind: String = "fonts"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        fontInfo = findViewById(R.id.fontInfo)
        fontPreview = findViewById(R.id.fontPreview)
        fontTitle = findViewById(R.id.fontTitle)
        fontAuthor = findViewById(R.id.fontAuthor)
        ttcIndex = findViewById(R.id.ttcIndex)
        optFallback = findViewById(R.id.optFallback)
        buildFont = findViewById(R.id.buildFont)
        iconInfo = findViewById(R.id.iconInfo)
        iconTitle = findViewById(R.id.iconTitle)
        iconSize = findViewById(R.id.iconSize)
        optActivityIcons = findViewById(R.id.optActivityIcons)
        buildIcons = findViewById(R.id.buildIcons)
        progress = findViewById(R.id.progress)
        resultText = findViewById(R.id.resultText)
        applyMtz = findViewById(R.id.applyMtz)
        share = findViewById(R.id.share)

        val tm = ThemeManagerLauncher.installedVersion(this)
        findViewById<TextView>(R.id.themesStatus).text =
            if (tm != null) "Temas de Xiaomi detectado (versión $tm)" else "Temas de Xiaomi no detectado en este dispositivo"

        findViewById<Button>(R.id.pickFont).setOnClickListener { pick(REQ_FONT, arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/vnd.ms-opentype", "application/octet-stream")) }
        findViewById<Button>(R.id.pickApk).setOnClickListener { pick(REQ_APK, arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }
        findViewById<Button>(R.id.pickInstalled).setOnClickListener { chooseInstalledPack() }
        buildFont.setOnClickListener { buildFontMtz() }
        buildIcons.setOnClickListener { buildIconMtz() }
        findViewById<Button>(R.id.openThemes).setOnClickListener {
            if (!ThemeManagerLauncher.open(this, lastKind)) toast(getString(R.string.themes_missing))
        }
        applyMtz.setOnClickListener { confirmApply() }
        share.setOnClickListener { shareLast() }

        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 9)
        }
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun handleIncoming(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) loadFont(uri)
    }

    private fun pick(code: Int, mimes: Array<String>) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, mimes)
        startActivityForResult(i, code)
    }

    @Deprecated("API de plataforma; se evita depender de AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_FONT -> loadFont(uri)
            REQ_APK -> loadIconPack { IconPack.fromApkUri(this, uri) }
        }
    }

    // ---------- Fuentes ----------

    private fun loadFont(uri: Uri) = background({
        val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val name = displayName(uri) ?: "fuente"
        val count = SfntFont.fontCount(bytes)
        val (single, font) = FontMtzBuilder.prepareFont(bytes, 0)
        val tf = typefaceOf(single)
        Triple(bytes to name, font to count, tf)
    }) { (file, info, tf) ->
        fontBytes = file.first
        fontFileName = file.second
        val (font, count) = info
        val warn = font.warnings()
        fontInfo.text = buildString {
            append("${file.second}\n${font.displayName} · ${font.outline} · ${font.glyphCount} glifos")
            if (count > 1) append("\nColección TTC con $count fuentes")
            warn.forEach { append("\n⚠ ").append(it) }
        }
        ttcIndex.visibility = if (count > 1) View.VISIBLE else View.GONE
        if (fontTitle.text.isBlank()) fontTitle.setText(font.displayName)
        val thumb = PreviewRenderer.fontThumbnail(font.displayName, tf)
        fontPreview.setImageBitmap(BitmapFactory.decodeByteArray(thumb, 0, thumb.size))
        fontPreview.visibility = View.VISIBLE
        buildFont.isEnabled = true
    }

    private fun typefaceOf(single: ByteArray): Typeface {
        val tmp = File(cacheDir, "preview_font.ttf").apply { writeBytes(single) }
        return runCatching { Typeface.createFromFile(tmp) }.getOrDefault(Typeface.DEFAULT)
    }

    private fun buildFontMtz() {
        val data = fontBytes ?: return
        val title = fontTitle.text.toString().trim().ifEmpty { fontFileName.substringBeforeLast('.') }
        val author = fontAuthor.text.toString().trim().ifEmpty { "MTZ Forge" }
        val index = ttcIndex.text.toString().toIntOrNull() ?: 0
        val fallback = optFallback.isChecked
        background({
            val (single, _) = FontMtzBuilder.prepareFont(data, index)
            val tf = typefaceOf(single)
            val previews = Previews(mapOf(
                MtzPaths.PREVIEW_FONT_SMALL to PreviewRenderer.fontThumbnail(title, tf),
                MtzPaths.PREVIEW_FONT to PreviewRenderer.fontPreview(title, tf),
            ))
            val out = File(cacheDir, "build.mtz")
            val desc = ThemeDescription(title = title, author = author, description = "Fuente empaquetada con MTZ Forge")
            val r = out.outputStream().use { FontMtzBuilder.build(data, FontMtzOptions(desc, index, fallback), previews, it) }
            finish(out, title, "fonts", r.warnings)
        }, ::showResult)
    }

    // ---------- Iconos ----------

    private fun chooseInstalledPack() {
        background({ IconPack.listInstalled(this) }) { packs ->
            if (packs.isEmpty()) { toast(getString(R.string.no_packs)); return@background }
            AlertDialog.Builder(this)
                .setTitle(R.string.pick_installed)
                .setItems(packs.map { it.label }.toTypedArray()) { _, i -> loadIconPack { IconPack.fromInstalled(this, packs[i].packageName) } }
                .show()
        }
    }

    private fun loadIconPack(loader: () -> IconPack) = background({
        val pack = loader()
        pack to pack.loadAppFilter()
    }) { (pack, filter) ->
        iconPack = pack
        iconInfo.text = "${pack.label} (${pack.packageName})\n${filter.items.size} entradas · ${filter.drawables.size} drawables únicos"
        if (iconTitle.text.isBlank()) iconTitle.setText(pack.label)
        buildIcons.isEnabled = true
    }

    private fun buildIconMtz() {
        val pack = iconPack ?: return
        val title = iconTitle.text.toString().trim().ifEmpty { pack.label }
        val size = (iconSize.text.toString().toIntOrNull() ?: 168).coerceIn(48, 512)
        val activityIcons = optActivityIcons.isChecked
        background({
            val filter = pack.loadAppFilter()
            val sample = ArrayList<Bitmap>()
            filter.drawables.take(48).forEach { d -> if (sample.size < 24) pack.render(d, 144)?.let { sample += it } }
            val previews = Previews(mapOf(MtzPaths.PREVIEW_ICONS to PreviewRenderer.iconGrid(sample, title)))
            val out = File(cacheDir, "build.mtz")
            val desc = ThemeDescription(title = title, description = "Iconos convertidos desde ${pack.packageName} con MTZ Forge")
            val r = out.outputStream().use {
                IconMtzBuilder.build(filter, IconMtzOptions(desc, activityIcons), previews, it, tempDir = cacheDir,
                    progress = { done, total -> if (done % 25 == 0 || done == total) runOnUiThread { progress.max = total; progress.progress = done } },
                ) { d -> pack.render(d, size)?.encode(Bitmap.CompressFormat.PNG) }
            }
            val notes = buildList {
                add("${r.written} iconos escritos")
                if (r.missing.isNotEmpty()) add("${r.missing.size} drawables no encontrados en el APK (p. ej. ${r.missing.take(3).joinToString()})")
            }
            finish(out, title, "icons", notes)
        }, ::showResult)
    }

    // ---------- Resultado ----------

    private data class Outcome(val exported: DownloadsExporter.Exported, val kind: String, val notes: List<String>, val report: String)

    /** Valida el MTZ recién creado leyéndolo de nuevo y lo copia a Descargas. */
    private fun finish(out: File, title: String, kind: String, notes: List<String>): Outcome {
        val rep = MtzInspector.inspect(out)
        if (!rep.isValid) throw IllegalStateException("Validación fallida: ${rep.errors.joinToString()}")
        val exported = DownloadsExporter.export(this, out, title)
        out.delete()
        val report = "Componentes: ${rep.components.values.joinToString()} · ${rep.entries.size} entradas · " +
            "%.1f KB".format(File(exported.absolutePath).takeIf { it.exists() }?.length()?.div(1024.0) ?: 0.0)
        return Outcome(exported, kind, notes + rep.warnings, report)
    }

    private fun showResult(o: Outcome) {
        lastExport = o.exported
        lastKind = o.kind
        resultText.text = buildString {
            append("✔ Guardado en Descargas/${DownloadsExporter.SUBDIR}/${o.exported.displayName}\n")
            append(o.exported.absolutePath).append('\n').append(o.report)
            o.notes.forEach { append("\n• ").append(it) }
        }
        applyMtz.isEnabled = true
        share.isEnabled = true
    }

    private fun confirmApply() {
        val e = lastExport ?: return
        AlertDialog.Builder(this)
            .setMessage(R.string.apply_warning)
            .setPositiveButton(R.string.continue_) { _, _ ->
                ThemeManagerLauncher.applyMtz(this, e.absolutePath).onFailure { toast("No se pudo iniciar Temas: ${it.message}") }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun shareLast() {
        val e = lastExport ?: return
        val i = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, e.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, getString(R.string.share)))
    }

    // ---------- Utilidades ----------

    private fun <T> background(work: () -> T, done: (T) -> Unit) {
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        setBusy(true)
        worker.execute {
            val r = runCatching(work)
            runOnUiThread {
                progress.visibility = View.GONE
                progress.isIndeterminate = false
                setBusy(false)
                r.onSuccess(done).onFailure { err ->
                    AlertDialog.Builder(this).setTitle("Error").setMessage(err.message ?: err.toString())
                        .setPositiveButton(android.R.string.ok, null).show()
                }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        buildFont.isEnabled = !busy && fontBytes != null
        buildIcons.isEnabled = !busy && iconPack != null
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_FONT = 1
        private const val REQ_APK = 2
    }
}
