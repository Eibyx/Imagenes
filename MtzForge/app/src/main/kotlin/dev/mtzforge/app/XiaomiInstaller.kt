package dev.mtzforge.app

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import dev.mtzforge.app.shizuku.IFileService
import dev.mtzforge.app.shizuku.ShizukuManager
import dev.mtzforge.core.ThemeStore
import java.io.File

/**
 * Las 4 opciones de aplicación para Xiaomi/HyperOS.
 *
 * Diferencia deliberada con zFont: aquí NUNCA se escribe un fichero de derechos
 * (.mra) ni se piden licencias a ningún servidor. Solo se instala el contenido del
 * usuario. Si la versión de Temas exige licencia para contenido local, los métodos
 * 1, 3 y 4 no se aplicarán (ver docs/INGENIERIA_INVERSA.md).
 */
enum class ApplyMethod(val title: String, val subtitle: String, val needsShizuku: Boolean) {
    FONT_LIBRARY("Método 1 · Biblioteca de fuentes", "Instala la fuente en la biblioteca local de Temas y abre la pestaña Fuentes", true),
    MTZ_PACKAGE("Método 2 · Paquete MTZ", "Pide a Temas que aplique el MTZ directamente (con Shizuku lo copia antes a la carpeta de Temas)", false),
    FULL_THEME("Método 3 · Tema completo", "Combina tu última fuente y tus últimos iconos en un tema y lo instala", true),
    IMPORT_MTZ("Método 4 · Importar MTZ", "Instala cualquier MTZ que elijas en la biblioteca local de Temas", true),
}

class XiaomiInstaller(private val context: Context, private val shizuku: ShizukuManager) {

    data class Outcome(val message: String, val launched: Boolean)

    val dataDir: File
        @Suppress("DEPRECATION")
        get() = File(Environment.getExternalStorageDirectory(),
            if (Build.VERSION.SDK_INT >= 30) ThemeStore.DATA_DIR_API30 else ThemeStore.DATA_DIR_LEGACY)

    /** Métodos 1, 3 y 4: escribe la estructura .data y abre el recurso en Temas. */
    suspend fun installToLibrary(mtz: ByteArray, openAs: String): Outcome {
        val install = ThemeStore.fromMtz(mtz, dataDir.absolutePath)
        val writer = writer()
        install.files.forEach { (rel, data) -> writer.write(File(dataDir, rel), data) }
        writer.forceStopThemes()
        val code = if (openAs == "fonts" && !install.hasFonts) "theme" else openAs
        val launched = ThemeManagerLauncher.openLocalResource(context, install.localId, code) ||
            ThemeManagerLauncher.open(context, code)
        val comps = install.components.joinToString { it.resourceCode }
        val skipped = if (install.skipped.isEmpty()) "" else "\nComponentes no reconocidos (omitidos): ${install.skipped.joinToString()}"
        return Outcome(
            "«${install.title}» instalado en la biblioteca de Temas ($comps, id ${install.localId.take(8)}…).$skipped\n" +
                "Busca el elemento en Temas y aplícalo. Si no aparece o no se aplica, tu versión de Temas probablemente exige licencia.",
            launched,
        )
    }

    /** Método 2: con Shizuku copia el MTZ dentro de .data; si no, usa la ruta de Descargas. */
    suspend fun applyMtzPackage(mtz: File, downloadsPath: String?): Outcome {
        val path = if (shizuku.status.value == ShizukuManager.Status.READY || Build.VERSION.SDK_INT < 29) {
            val target = File(dataDir, "mtzforge.mtz")
            writer().write(target, mtz.readBytes())
            target.absolutePath
        } else downloadsPath ?: throw IllegalStateException("Exporta primero el MTZ a Descargas o activa Shizuku")
        ThemeManagerLauncher.applyMtz(context, path).getOrThrow()
        return Outcome("Se pidió a Temas aplicar:\n$path\nSi Temas lo acepta, reinicia el teléfono para ver el cambio.", true)
    }

    private interface Writer {
        fun write(target: File, data: ByteArray)
        fun forceStopThemes()
    }

    private suspend fun writer(): Writer =
        if (shizuku.status.value == ShizukuManager.Status.READY) ShizukuWriter(shizuku.fileService())
        else if (Build.VERSION.SDK_INT < 29) DirectWriter
        else throw IllegalStateException("Este método necesita Shizuku en Android 10 o superior: ${shizuku.status.value.label}")

    /** Android 8–9: con WRITE_EXTERNAL_STORAGE se puede escribir directamente. */
    private object DirectWriter : Writer {
        override fun write(target: File, data: ByteArray) {
            target.parentFile?.mkdirs()
            target.writeBytes(data)
        }
        override fun forceStopThemes() {}
    }

    private inner class ShizukuWriter(private val svc: IFileService) : Writer {
        override fun write(target: File, data: ByteArray) {
            val tmp = File.createTempFile("w", ".bin", context.cacheDir)
            try {
                tmp.writeBytes(data)
                ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    check(svc.writeFile(target.absolutePath, pfd)) { "No se pudo escribir ${target.name}" }
                }
            } finally {
                tmp.delete()
            }
        }
        override fun forceStopThemes() { runCatching { svc.forceStopThemes() } }
    }
}
