package dev.mtzforge.core

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

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

    data class Install(
        val localId: String,
        val title: String,
        /** Ruta relativa a `.data` -> contenido. */
        val files: Map<String, ByteArray>,
        val components: List<Component>,
        val skipped: List<String>,
    ) {
        val hasFonts get() = components.any { it.resourceCode == "fonts" }
        val hasIcons get() = components.any { it.resourceCode == "icons" }
    }

    /**
     * @param dataDirAbsolute ruta absoluta de `.data` en el dispositivo; se usa en
     *        `contentPath` del recurso tema (como hace el formato observado).
     */
    fun fromMtz(mtz: ByteArray, dataDirAbsolute: String, now: Long = System.currentTimeMillis()): Install {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(mtz)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) entries[e.name] = z.readBytes()
            }
        }
        val descBytes = entries[MtzPaths.DESCRIPTION] ?: throw IllegalArgumentException("El MTZ no contiene description.xml")
        val desc = ThemeDescription.parse(descBytes)
        val id = UUID.nameUUIDFromBytes(sha1(mtz).toByteArray()).toString()
        val platform = if (desc.uiVersion > 0) desc.uiVersion else ThemeDescription.DEFAULT_UI_VERSION

        val files = LinkedHashMap<String, ByteArray>()
        val previews = entries.keys.filter { it.startsWith(MtzPaths.PREVIEW_DIR) && !it.endsWith("/") }
            .map { it.substringAfterLast('/') }.distinct().sorted()
        entries.filterKeys { it.startsWith(MtzPaths.PREVIEW_DIR) }.forEach { (p, d) ->
            files["preview/theme/$id/${p.substringAfterLast('/')}"] = d
        }

        val components = ArrayList<Component>()
        val skipped = ArrayList<String>()
        for ((path, data) in entries) {
            if (path == MtzPaths.DESCRIPTION || path.startsWith(MtzPaths.PREVIEW_DIR)) continue
            val code = MtzPaths.RESOURCE_CODES[path]
            if (code == null) { skipped += path; continue }
            if (components.any { it.resourceCode == code }) { skipped += path; continue }
            components += Component(code, path)
            files["content/$code/$id.mrc"] = data
            val (thumbs, prevs) = previewsFor(code, previews)
            files["meta/$code/$id.mrm"] = meta(
                id = id, desc = desc, hash = sha1(data), size = data.size.toLong(), platform = platform, now = now,
                thumbnails = thumbs, previews = prevs,
                parents = listOf(ref(id, "theme")), children = emptyList(), contentPath = null,
            ).toByteArray(Charsets.UTF_8)
        }
        if (components.isEmpty()) throw IllegalArgumentException("El MTZ no contiene componentes reconocidos")

        // Recurso padre "theme": contenido vacío, hash/tamaño del MTZ completo.
        files["content/theme/$id.mrc"] = ByteArray(0)
        files["meta/theme/$id.mrm"] = meta(
            id = id, desc = desc, hash = sha1(mtz), size = mtz.size.toLong(), platform = platform, now = now,
            thumbnails = previews.filter { "small" in it.lowercase() }, previews = previews.filter { "small" !in it.lowercase() },
            parents = emptyList(), children = components.map { ref(id, it.resourceCode) },
            contentPath = "/system/..${dataDirAbsolute.trimEnd('/')}/content/theme/$id.mrc",
        ).toByteArray(Charsets.UTF_8)
        return Install(id, desc.title, files, components, skipped)
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

    fun sha1(data: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(data).joinToString("") { "%02x".format(it) }
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
    fun merge(parts: List<ByteArray>, description: ThemeDescription, out: java.io.OutputStream): List<String> {
        val taken = LinkedHashMap<String, ByteArray>()
        val dropped = ArrayList<String>()
        for (p in parts) ZipInputStream(ByteArrayInputStream(p)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory || e.name == MtzPaths.DESCRIPTION) continue
                if (e.name in taken) dropped += e.name else taken[e.name] = z.readBytes()
            }
        }
        if (taken.keys.none { it in MtzPaths.RESOURCE_CODES }) throw IllegalArgumentException("Nada que combinar")
        MtzWriter(out).use { w ->
            w.add(MtzPaths.DESCRIPTION, description.toXml().toByteArray(Charsets.UTF_8))
            taken.toSortedMap().forEach { (k, v) -> w.add(k, v) }
        }
        return dropped
    }
}
