package dev.mtzforge.core

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import java.security.MessageDigest
import java.util.UUID

/**
 * Genera la estructura de la biblioteca local de la app Temas (`MIUI/theme/.data`)
 * a partir de un MTZ. Formato deducido del análisis de zFont 3 (ver docs):
 *
 * ```
 * content/<resourceCode>/<id>.mrc   contenido del componente
 * meta/<resourceCode>/<id>.mrm      metadatos JSON
 * preview/theme/<id>/<imagen>       vistas previas
 * ```
 * Todos los componentes comparten el mismo `<id>`; un recurso "theme" padre lista los
 * hijos en `subResources` y cada hijo apunta al padre en `parentResources`.
 *
 * IMPORTANTE: NO se genera ningún fichero de derechos (ficheros `.mra` en `rights/`) y `rightsPath`
 * es null. No se reutilizan ni falsifican licencias. Si la versión de Temas exige una
 * licencia válida para contenido local, la instalación no se aplicará.
 */
object ThemeStore {
    const val DATA_DIR_API30 = "Android/data/com.android.thememanager/files/MIUI/theme/.data"
    const val DATA_DIR_LEGACY = "MIUI/theme/.data"

    data class Component(val resourceCode: String, val mtzPath: String)

    /** Origen de un fichero a instalar: bytes pequeños generados o una entrada del MTZ (se lee en streaming). */
    sealed class Source {
        class Bytes(val data: ByteArray) : Source()
        class Entry(val name: String, val size: Long) : Source()
    }

    class Install(
        val localId: String,
        val title: String,
        /** MTZ de origen (las fuentes [Source.Entry] se leen de aquí). */
        val mtz: File,
        /** Ruta relativa a `.data` -> origen del contenido. */
        val files: Map<String, Source>,
        val components: List<Component>,
        val skipped: List<String>,
    ) {
        val hasFonts get() = components.any { it.resourceCode == "fonts" }
        val hasIcons get() = components.any { it.resourceCode == "icons" }

        /** Copia cada fichero mediante [sink] sin cargar componentes grandes en memoria. */
        fun writeAll(sink: (relPath: String, input: InputStream) -> Unit) {
            ZipFile(mtz).use { z ->
                for ((rel, src) in files) when (src) {
                    is Source.Bytes -> sink(rel, src.data.inputStream())
                    is Source.Entry -> z.getInputStream(z.getEntry(src.name)).use { sink(rel, it) }
                }
            }
        }

        /** Solo para tests / ficheros pequeños. */
        fun read(rel: String): ByteArray? = when (val s = files[rel]) {
            null -> null
            is Source.Bytes -> s.data
            is Source.Entry -> ZipFile(mtz).use { z -> z.getInputStream(z.getEntry(s.name)).use { it.readBytes() } }
        }
    }

    /** Variante en memoria (tests): escribe el MTZ en un temporal. */
    fun fromMtz(mtz: ByteArray, dataDirAbsolute: String, now: Long = System.currentTimeMillis()): Install {
        val tmp = File.createTempFile("mtz", ".mtz").apply { deleteOnExit(); writeBytes(mtz) }
        return fromMtz(tmp, dataDirAbsolute, now)
    }

    /**
     * Analiza el MTZ en streaming (memoria constante, válido para MTZ de cientos de MB).
     * @param dataDirAbsolute ruta absoluta de `.data` en el dispositivo; se usa en
     *        `contentPath` del recurso tema (como hace el formato observado).
     */
    fun fromMtz(mtz: File, dataDirAbsolute: String, now: Long = System.currentTimeMillis()): Install = ZipFile(mtz).use { z ->
        val entries = z.entries().toList().filter { !it.isDirectory }
        val descEntry = z.getEntry(MtzPaths.DESCRIPTION) ?: throw IllegalArgumentException("El MTZ no contiene description.xml")
        val desc = ThemeDescription.parse(z.getInputStream(descEntry).use { it.readBytes() })
        val mtzHash = sha1(mtz.inputStream())
        val id = UUID.nameUUIDFromBytes(mtzHash.toByteArray()).toString()
        val platform = if (desc.uiVersion > 0) desc.uiVersion else ThemeDescription.DEFAULT_UI_VERSION

        val files = LinkedHashMap<String, Source>()
        val previewEntries = entries.filter { it.name.startsWith(MtzPaths.PREVIEW_DIR) }
        val previews = previewEntries.map { it.name.substringAfterLast('/') }.distinct().sorted()
        previewEntries.forEach { e -> files["preview/theme/$id/${e.name.substringAfterLast('/')}"] = Source.Entry(e.name, e.size) }

        val components = ArrayList<Component>()
        val skipped = ArrayList<String>()
        for (e in entries) {
            val path = e.name
            if (path == MtzPaths.DESCRIPTION || path.startsWith(MtzPaths.PREVIEW_DIR)) continue
            val code = MtzPaths.RESOURCE_CODES[path]
            if (code == null || components.any { it.resourceCode == code }) { skipped += path; continue }
            components += Component(code, path)
            files["content/$code/$id.mrc"] = Source.Entry(path, e.size)
            val hash = z.getInputStream(e).use { sha1(it) }
            val (thumbs, prevs) = previewsFor(code, previews)
            files["meta/$code/$id.mrm"] = Source.Bytes(meta(
                id = id, desc = desc, hash = hash, size = e.size, platform = platform, now = now,
                thumbnails = thumbs, previews = prevs,
                parents = listOf(ref(id, "theme")), children = emptyList(), contentPath = null,
            ).toByteArray(Charsets.UTF_8))
        }
        if (components.isEmpty()) throw IllegalArgumentException("El MTZ no contiene componentes reconocidos")

        // Recurso padre "theme": contenido vacío, hash/tamaño del MTZ completo.
        files["content/theme/$id.mrc"] = Source.Bytes(ByteArray(0))
        files["meta/theme/$id.mrm"] = Source.Bytes(meta(
            id = id, desc = desc, hash = mtzHash, size = mtz.length(), platform = platform, now = now,
            thumbnails = previews.filter { "small" in it.lowercase() }, previews = previews.filter { "small" !in it.lowercase() },
            parents = emptyList(), children = components.map { ref(id, it.resourceCode) },
            contentPath = "/system/..${dataDirAbsolute.trimEnd('/')}/content/theme/$id.mrc",
        ).toByteArray(Charsets.UTF_8))
        Install(id, desc.title, mtz, files, components, skipped)
    }

    private fun previewsFor(code: String, names: List<String>): Pair<List<String>, List<String>> {
        val key = when (code) { "fonts", "fonts_fallback" -> "font"; "lockstyle" -> "lockscreen"; else -> code }
        val mine = names.filter { key in it.lowercase() }
        return mine.filter { "small" in it.lowercase() } to mine.filter { "small" !in it.lowercase() }
    }

    private fun ref(id: String, code: String) = Json.obj(
        "localId" to id, "resourceCode" to code, "extraMeta" to Json.obj(), "metaPath" to null, "contentPath" to null,
    )

    private fun meta(
        id: String, desc: ThemeDescription, hash: String, size: Long, platform: Int, now: Long,
        thumbnails: List<String>, previews: List<String>, parents: List<Json.Obj>, children: List<Json.Obj>,
        contentPath: String?,
    ): String = Json.obj(
        "localId" to id,
        "onlineId" to null,
        "assemblyId" to null,
        "productId" to null,
        "hash" to hash,
        "platform" to platform,
        "size" to size,
        "updatedTime" to now,
        "version" to desc.version.ifBlank { "1.0" },
        "authors" to Json.obj("fallback" to desc.author),
        "designers" to Json.obj("fallback" to desc.designer),
        "titles" to Json.obj("fallback" to desc.title),
        "descriptions" to Json.obj("fallback" to desc.description),
        "builtInThumbnails" to Json.obj("fallback" to thumbnails),
        "builtInPreviews" to Json.obj("fallback" to previews),
        "thumbnails" to emptyList<String>(),
        "previews" to emptyList<String>(),
        "parentResources" to parents,
        "subResources" to children,
        "extraMeta" to Json.obj(),
        "metaPath" to null,
        "contentPath" to contentPath,
        "rightsPath" to null,
    ).render(indent = 4)

    fun sha1(data: ByteArray): String = sha1(data.inputStream())

    fun sha1(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-1")
        input.use { s -> val buf = ByteArray(64 * 1024); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Escritor JSON mínimo (sin dependencias). */
object Json {
    class Obj(val fields: List<Pair<String, Any?>>) {
        fun render(indent: Int = 0): String = StringBuilder().also { write(it, this, indent, 0) }.toString()
    }

    fun obj(vararg f: Pair<String, Any?>) = Obj(f.toList())

    private fun write(sb: StringBuilder, v: Any?, indent: Int, level: Int) {
        val nl = if (indent > 0) "\n" else ""
        fun pad(l: Int) = " ".repeat(indent * l)
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Number, is Boolean -> sb.append(v.toString())
            is Obj -> if (v.fields.isEmpty()) sb.append("{}") else {
                sb.append('{').append(nl)
                v.fields.forEachIndexed { i, (k, x) ->
                    sb.append(pad(level + 1)); quote(sb, k); sb.append(if (indent > 0) ": " else ":")
                    write(sb, x, indent, level + 1)
                    if (i < v.fields.size - 1) sb.append(',')
                    sb.append(nl)
                }
                sb.append(pad(level)).append('}')
            }
            is List<*> -> if (v.isEmpty()) sb.append("[]") else {
                sb.append('[').append(nl)
                v.forEachIndexed { i, x ->
                    sb.append(pad(level + 1)); write(sb, x, indent, level + 1)
                    if (i < v.size - 1) sb.append(',')
                    sb.append(nl)
                }
                sb.append(pad(level)).append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }
}

/** Combina varios MTZ (p. ej. fuente + iconos) en un MTZ de tema completo. */
object ThemeMerger {
    /** Combina leyendo cada MTZ en streaming. Si una ruta se repite, gana el primer MTZ. */
    fun merge(parts: List<File>, description: ThemeDescription, out: java.io.OutputStream): List<String> {
        val dropped = ArrayList<String>()
        val seen = HashSet<String>()
        var recognized = false
        MtzWriter(out).use { w ->
            w.add(MtzPaths.DESCRIPTION, description.toXml().toByteArray(Charsets.UTF_8))
            for (p in parts) ZipFile(p).use { z ->
                for (e in z.entries()) {
                    if (e.isDirectory || e.name == MtzPaths.DESCRIPTION) continue
                    if (!seen.add(e.name)) { dropped += e.name; continue }
                    if (e.name in MtzPaths.RESOURCE_CODES) recognized = true
                    z.getInputStream(e).use { w.add(e.name, it) }
                }
            }
        }
        if (!recognized) throw IllegalArgumentException("Nada que combinar")
        return dropped
    }
}
