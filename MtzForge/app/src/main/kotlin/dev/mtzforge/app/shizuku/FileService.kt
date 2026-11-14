package dev.mtzforge.app.shizuku

import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import kotlin.system.exitProcess

/**
 * Implementación del UserService de Shizuku. Se ejecuta en un proceso aparte con el
 * uid de shell, que sí puede escribir en `Android/data/<otra app>/` en Android 11+.
 * Las rutas se restringen a la carpeta de la app Temas.
 */
class FileService : IFileService.Stub() {

    override fun destroy() = exitProcess(0)

    override fun uid(): Int = Process.myUid()

    override fun writeFile(path: String, source: ParcelFileDescriptor): Boolean {
        val target = checked(path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
            FileOutputStream(tmp).use { input.copyTo(it) }
        }
        return tmp.renameTo(target) || (tmp.copyTo(target, overwrite = true).exists().also { tmp.delete() })
    }

    override fun mkdirs(path: String): Boolean = checked(path).let { it.isDirectory || it.mkdirs() }

    override fun exists(path: String): Boolean = checked(path).exists()

    override fun deleteRecursively(path: String): Boolean = checked(path).deleteRecursively()

    override fun forceStopThemes(): Boolean =
        Runtime.getRuntime().exec(arrayOf("am", "force-stop", THEMES_PKG)).waitFor() == 0

    private fun checked(path: String): File {
        val f = File(path).canonicalFile
        val ok = ALLOWED.any { root -> f.path.startsWith(root) } && !path.contains("..")
        if (!ok) throw SecurityException("Ruta no permitida: $path")
        return f
    }

    companion object {
        const val THEMES_PKG = "com.android.thememanager"
        private val ALLOWED = listOf(
            "/storage/emulated/0/Android/data/$THEMES_PKG/",
            "/storage/emulated/0/MIUI/theme/",
            "/data/media/0/Android/data/$THEMES_PKG/",
            "/data/media/0/MIUI/theme/",
        )
    }
}
