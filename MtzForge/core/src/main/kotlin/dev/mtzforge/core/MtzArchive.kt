package dev.mtzforge.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Rutas de componentes dentro de un MTZ (ZIP). Tabla deducida del análisis de
 * zFont 3 (clase de mapeo "ruta en MTZ" -> "resourceCode" de ThemeManager).
 */
object MtzPaths {
    const val DESCRIPTION = "description.xml"
    const val FONT = "fonts/Roboto-Regular.ttf"
    const val FONT_FALLBACK = "fonts/DroidSansFallback.ttf"
    const val ICONS = "icons"
    const val PREVIEW_DIR = "preview/"
    const val PREVIEW_FONT_SMALL = "preview/preview_fonts_small_0.png"
    const val PREVIEW_FONT = "preview/preview_fonts_0.jpg"
    const val PREVIEW_ICONS = "preview/preview_icons_0.jpg"

    /** ruta en el MTZ -> resourceCode (según la tabla encontrada en zFont 3). */
    val RESOURCE_CODES = mapOf(
        "com.android.contacts" to "contact",
        "framework-res" to "framework",
        "com.miui.home" to "launcher",
        "com.android.mms" to "mms",
        "com.android.systemui" to "statusbar",
        "lockscreen" to "lockstyle",
        "wallpaper/default_lock_wallpaper.jpg" to "lockscreen",
        "wallpaper/default_wallpaper.jpg" to "wallpaper",
        FONT to "fonts",
        FONT_FALLBACK to "fonts_fallback",
        "ringtones/alarm.mp3" to "alarm",
        "boots/bootanimation.zip" to "bootanimation",
        "ringtones/notification.mp3" to "notification",
        "ringtones/ringtone.mp3" to "ringtone",
        ICONS to "icons",
    )
}

/**
 * Escritor de MTZ. Un MTZ es un ZIP estándar; se generan entradas de directorio
 * explícitas (igual que el empaquetador observado) y orden determinista.
 */
class MtzWriter(private val out: OutputStream) : AutoCloseable {
    private val zip = ZipOutputStream(out)
    private val dirs = HashSet<String>()
    private val names = HashSet<String>()

    /** Fecha fija para que dos ejecuciones con la misma entrada den el mismo fichero. */
    var fixedTime: Long = 315_532_800_000L // 1980-01-01

    fun add(path: String, data: ByteArray, stored: Boolean = false) {
        require(!path.startsWith("/") && !path.contains("..") && !path.contains('\\')) { "Ruta no válida: $path" }
        require(names.add(path)) { "Entrada duplicada: $path" }
        ensureDirs(path)
        val e = ZipEntry(path).apply { time = fixedTime }
        if (stored) {
            e.method = ZipEntry.STORED
            e.size = data.size.toLong()
            e.compressedSize = data.size.toLong()
            e.crc = CRC32().apply { update(data) }.value
        }
        zip.putNextEntry(e)
        zip.write(data)
        zip.closeEntry()
    }

    /** Añade un fichero sin comprimir (STORED) en streaming: calcula el CRC en una primera pasada. */
    fun addStored(path: String, file: File) {
        require(!path.startsWith("/") && !path.contains("..") && !path.contains('\\')) { "Ruta no válida: $path" }
        require(names.add(path)) { "Entrada duplicada: $path" }
        ensureDirs(path)
        val crc = CRC32()
        file.inputStream().buffered().use { s -> val buf = ByteArray(64 * 1024); while (true) { val n = s.read(buf); if (n < 0) break; crc.update(buf, 0, n) } }
        val e = ZipEntry(path).apply {
            time = fixedTime; method = ZipEntry.STORED; size = file.length(); compressedSize = file.length(); this.crc = crc.value
        }
        zip.putNextEntry(e)
        file.inputStream().buffered().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    /**
     * Copia todas las entradas de un MTZ base salvo las indicadas (modo "MTZ base":
     * se conserva un tema que ya funciona y solo se sustituye un componente).
     */
    fun copyFrom(base: File, except: Set<String>): List<String> {
        val copied = ArrayList<String>()
        ZipFile(base).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory || e.name in except) continue
                if (e.name.startsWith("/") || e.name.contains("..")) continue
                if (e.method == ZipEntry.STORED) {
                    val tmp = File.createTempFile("base", ".bin").apply { deleteOnExit() }
                    try { z.getInputStream(e).use { i -> tmp.outputStream().use { i.copyTo(it) } }; addStored(e.name, tmp) } finally { tmp.delete() }
                } else z.getInputStream(e).use { add(e.name, it) }
                copied += e.name
            }
        }
        return copied
    }

    /** Añade una entrada comprimida leyendo de un stream (para contenidos grandes). */
    fun add(path: String, input: java.io.InputStream) {
        require(!path.startsWith("/") && !path.contains("..") && !path.contains('\\')) { "Ruta no válida: $path" }
        require(names.add(path)) { "Entrada duplicada: $path" }
        ensureDirs(path)
        zip.putNextEntry(ZipEntry(path).apply { time = fixedTime })
        input.copyTo(zip)
        zip.closeEntry()
    }

    private fun ensureDirs(path: String) {
        var i = path.indexOf('/')
        while (i > 0) {
            val d = path.substring(0, i + 1)
            if (dirs.add(d)) {
                zip.putNextEntry(ZipEntry(d).apply { time = fixedTime })
                zip.closeEntry()
            }
            i = path.indexOf('/', i + 1)
        }
    }

    override fun close() = zip.close()
}

/** Resultado de inspeccionar un MTZ. */
data class MtzReport(
    val entries: List<String>,
    val description: ThemeDescription?,
    val components: Map<String, String>,
    val fontName: String?,
    val iconCount: Int,
    val errors: List<String>,
    val warnings: List<String>,
) {
    val isValid get() = errors.isEmpty()
}

/** Inspección/validación estructural de un MTZ (propio o ajeno). */
object MtzInspector {
    /** Lee el fichero en streaming: nunca carga en memoria el componente `icons` completo. */
    fun inspect(file: File): MtzReport = ZipFile(file).use { z ->
        val map = LinkedHashMap<String, () -> java.io.InputStream>()
        for (e in z.entries()) if (!e.isDirectory) map[e.name] = { z.getInputStream(e) }
        val all = z.entries().toList().map { it.name }
        inspect(all, map)
    }

    fun inspect(bytes: ByteArray): MtzReport {
        val data = LinkedHashMap<String, ByteArray>()
        val all = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                all += e.name
                if (!e.isDirectory) data[e.name] = zin.readBytes()
            }
        }
        return inspect(all, data.mapValues { (_, v) -> { ByteArrayInputStream(v) } })
    }

    private fun inspect(all: List<String>, files: Map<String, () -> java.io.InputStream>): MtzReport {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        fun read(open: () -> java.io.InputStream) = open().use { it.readBytes() }
        all.filter { it.startsWith("/") || it.contains("..") }.forEach { errors += "Ruta insegura en el ZIP: $it" }
        val desc = files[MtzPaths.DESCRIPTION]?.let {
            runCatching { ThemeDescription.parse(read(it)) }.onFailure { e -> errors += "description.xml inválido: ${e.message}" }.getOrNull()
        } ?: run {
            if (MtzPaths.DESCRIPTION !in files) errors += "Falta description.xml en la raíz"
            null
        }
        val components = LinkedHashMap<String, String>()
        files.keys.forEach { p -> MtzPaths.RESOURCE_CODES[p]?.let { components[p] = it } }
        var fontName: String? = null
        files[MtzPaths.FONT]?.let {
            runCatching { SfntFont.parse(read(it)) }
                .onSuccess { f -> fontName = f.displayName; warnings += f.warnings() }
                .onFailure { e -> errors += "${MtzPaths.FONT} no es una fuente válida: ${e.message}" }
        }
        var icons = 0
        files[MtzPaths.ICONS]?.let {
            runCatching { it().use { s -> IconsArchive.list(s) } }
                .onSuccess { names ->
                    icons = names.count { n -> n.startsWith(IconsArchive.DRAWABLE_DIR) && n.endsWith(".png") }
                    if (icons == 0) warnings += "El componente 'icons' no contiene iconos en ${IconsArchive.DRAWABLE_DIR}"
                }
                .onFailure { e -> errors += "El componente 'icons' no es un ZIP válido: ${e.message}" }
        }
        if (components.isEmpty()) errors += "El MTZ no contiene ningún componente reconocido"
        if (files.keys.none { it.startsWith(MtzPaths.PREVIEW_DIR) }) warnings += "Sin imágenes de vista previa (preview/)"
        return MtzReport(all, desc, components, fontName, icons, errors, warnings)
    }
}

/** Utilidades para el sub-ZIP `icons` (sin extensión) de un MTZ. */
object IconsArchive {
    const val DRAWABLE_DIR = "res/drawable-xxhdpi/"

    fun list(bytes: ByteArray): List<String> = list(ByteArrayInputStream(bytes))

    /** Lista las entradas leyendo en streaming (memoria constante). */
    fun list(input: java.io.InputStream): List<String> = buildList {
        val z = ZipInputStream(input.buffered())
        while (true) { val e = z.nextEntry ?: break; add(e.name) }
    }

}
