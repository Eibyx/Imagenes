package dev.mtzforge.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Exporta ficheros a Descargas/MTZ Forge (MediaStore en Android 10+, ruta directa antes). */
object DownloadsExporter {
    const val SUBDIR = "MTZ Forge"
    private const val MIME = "application/octet-stream"

    data class Exported(val uri: Uri, val absolutePath: String, val displayName: String)

    fun export(context: Context, source: File, displayName: String): Exported {
        val name = sanitize(displayName)
        return if (Build.VERSION.SDK_INT >= 29) viaMediaStore(context, source, name) else legacy(source, name)
    }

    private fun viaMediaStore(context: Context, source: File, name: String): Exported {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore no pudo crear el fichero en Descargas")
        try {
            resolver.openOutputStream(uri)!!.use { out -> source.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        // MediaStore puede renombrar (p. ej. "x (1).mtz") si ya existe: se lee el nombre final.
        var finalName = name
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) finalName = c.getString(0) ?: name
        }
        @Suppress("DEPRECATION")
        val path = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$SUBDIR/$finalName").absolutePath
        return Exported(uri, path, finalName)
    }

    @Suppress("DEPRECATION")
    private fun legacy(source: File, name: String): Exported {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBDIR).apply { mkdirs() }
        var target = File(dir, name)
        var n = 1
        while (target.exists()) target = File(dir, name.removeSuffix(".mtz") + " ($n).mtz").also { n++ }
        source.copyTo(target)
        return Exported(Uri.fromFile(target), target.absolutePath, target.name)
    }

    fun sanitize(s: String): String {
        val base = s.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(80).ifBlank { "tema" }
        return if (base.endsWith(".mtz", ignoreCase = true)) base else "$base.mtz"
    }
}
