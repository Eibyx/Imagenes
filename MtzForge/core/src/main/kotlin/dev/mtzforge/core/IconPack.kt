package dev.mtzforge.core

import java.io.ByteArrayInputStream
import java.io.OutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Componente Android (paquete + actividad) declarado en appfilter.xml. */
data class AppComponent(val packageName: String, val className: String?) {
    companion object {
        private val PKG = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

        /** Acepta `ComponentInfo{pkg/cls}`, `pkg/cls` y `pkg`. Clases relativas (".Main") se expanden. */
        fun parse(raw: String): AppComponent? {
            var s = raw.trim()
            if (s.startsWith("ComponentInfo{") && s.endsWith("}")) s = s.substring(14, s.length - 1)
            if (s.isEmpty()) return null
            val slash = s.indexOf('/')
            val pkg = (if (slash >= 0) s.substring(0, slash) else s).trim()
            if (!PKG.matches(pkg)) return null
            var cls = if (slash >= 0) s.substring(slash + 1).trim() else null
            if (cls != null) {
                if (cls.startsWith(".")) cls = pkg + cls
                if (!PKG.matches(cls)) cls = null
            }
            return AppComponent(pkg, cls)
        }
    }
}

/**
 * Modelo de appfilter.xml (formato de facto de los packs de iconos de launchers
 * de terceros: `<item component=".." drawable=".."/>`, `<iconback>`, `<iconmask>`,
 * `<iconupon>`, `<scale>`).
 */
data class AppFilter(
    val items: List<Pair<AppComponent, String>>,
    val iconBack: List<String> = emptyList(),
    val iconMask: String? = null,
    val iconUpon: String? = null,
    val scale: Float? = null,
) {
    val drawables: Set<String> get() = items.mapTo(LinkedHashSet()) { it.second }

    companion object {
        /** Construye a partir de elementos ya leídos (por ej. con XmlResourceParser en Android). */
        fun fromElements(elements: Sequence<Pair<String, Map<String, String>>>): AppFilter {
            val items = ArrayList<Pair<AppComponent, String>>()
            val backs = ArrayList<String>()
            var mask: String? = null
            var upon: String? = null
            var scale: Float? = null
            for ((name, attrs) in elements) {
                when (name) {
                    "item" -> {
                        val c = attrs["component"]?.let(AppComponent::parse) ?: continue
                        val d = attrs["drawable"]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                        items += c to cleanDrawable(d)
                    }
                    "iconback" -> attrs.entries.filter { it.key.startsWith("img") }.sortedBy { it.key }
                        .forEach { backs += cleanDrawable(it.value) }
                    "iconmask" -> mask = attrs["img1"]?.let(::cleanDrawable) ?: mask
                    "iconupon" -> upon = attrs["img1"]?.let(::cleanDrawable) ?: upon
                    "scale" -> scale = attrs["factor"]?.toFloatOrNull() ?: scale
                }
            }
            return AppFilter(items, backs, mask, upon, scale)
        }

        /** Parsea appfilter.xml en texto plano (carpeta assets/ del APK). */
        fun parseXml(xml: ByteArray): AppFilter {
            val f = DocumentBuilderFactory.newInstance().apply {
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            }
            val doc = f.newDocumentBuilder().parse(ByteArrayInputStream(xml))
            val all = doc.getElementsByTagName("*")
            val seq = (0 until all.length).asSequence().map { i ->
                val e = all.item(i) as Element
                val a = e.attributes
                e.tagName to (0 until a.length).associate { a.item(it).nodeName to a.item(it).nodeValue }
            }
            return fromElements(seq)
        }

        /** "@drawable/foo" -> "foo"; quita extensiones accidentales. */
        fun cleanDrawable(s: String): String = s.trim().removePrefix("@drawable/").removeSuffix(".png")
    }
}

/**
 * Plan de ficheros para el componente `icons` de MIUI/HyperOS.
 *
 * Convención observada en un recurso `icons` real (incluido como asset en el
 * APK analizado): `res/drawable-xxhdpi/<paquete>.png` y, cuando una app tiene
 * varias actividades con icono distinto, `res/drawable-xxhdpi/<clase.actividad>.png`.
 */
object IconMapping {
    /** nombre de fichero (sin directorio) -> nombre de drawable del pack. */
    fun plan(filter: AppFilter, activityIcons: Boolean = true): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val byPkg = filter.items.groupBy({ it.first.packageName }, { it.first to it.second })
        for ((pkg, entries) in byPkg) {
            // Icono por defecto del paquete: el drawable más frecuente (desempate: el primero).
            val default = entries.groupingBy { it.second }.eachCount().let { counts ->
                entries.map { it.second }.distinct().maxBy { counts.getValue(it) }
            }
            result.putIfAbsent("$pkg.png", default)
            val distinct = entries.map { it.second }.toSet()
            if (activityIcons && distinct.size > 1) {
                for ((c, d) in entries) {
                    val cls = c.className ?: continue
                    if (cls != pkg && d != default) result.putIfAbsent("$cls.png", d)
                }
            }
        }
        return result
    }
}

data class IconMtzOptions(
    val description: ThemeDescription,
    val activityIcons: Boolean = true,
    /** Modo "MTZ base": tema que ya funciona; se conserva todo salvo `icons`. */
    val baseMtz: java.io.File? = null,
)

/**
 * Construye un MTZ de iconos:
 * ```
 * description.xml
 * icons                       <- ZIP interno (sin extensión)
 *   res/drawable-xxhdpi/<pkg>.png ...
 *   [res/drawable-xxhdpi/icon_background.png | icon_mask.png | icon_border.png]
 * preview/preview_icons_0.png
 * ```
 * Los PNG los proporciona la plataforma (renderizados al tamaño elegido).
 */
object IconMtzBuilder {
    data class Result(val written: Int, val missing: List<String>)

    /**
     * Los PNG se escriben en streaming a un ZIP temporal en [tempDir] (o en memoria si
     * es null), para no retener miles de iconos en RAM.
     */
    fun build(
        filter: AppFilter,
        options: IconMtzOptions,
        previews: Previews,
        out: OutputStream,
        tempDir: java.io.File? = null,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
        renderPng: (drawable: String) -> ByteArray?,
    ): Result {
        val plan = IconMapping.plan(filter, options.activityIcons)
        if (plan.isEmpty()) throw IllegalArgumentException("El appfilter no contiene entradas válidas")
        val tmp = tempDir?.let { java.io.File.createTempFile("icons", ".zip", it) }
        val mem = if (tmp == null) java.io.ByteArrayOutputStream() else null
        try {
            val missing = LinkedHashSet<String>()
            val failed = HashSet<String>()
            var written = 0
            var done = 0
            MtzWriter(tmp?.outputStream()?.buffered() ?: mem!!).use { inner ->
                // Solo se cachean los drawables que aparecen en más de un fichero.
                val uses = plan.values.groupingBy { it }.eachCount()
                val shared = HashMap<String, ByteArray>()
                for ((file, drawable) in plan) {
                    val png = when {
                        drawable in failed -> null
                        (uses[drawable] ?: 0) > 1 -> shared[drawable] ?: renderPng(drawable)?.also { shared[drawable] = it }
                        else -> renderPng(drawable)
                    }
                    if (png != null) { inner.add(IconsArchive.DRAWABLE_DIR + file, png, stored = true); written++ }
                    else { failed += drawable; missing += drawable }
                    progress(++done, plan.size)
                }
                // Capas genéricas para apps sin icono propio (mapeo experimental).
                fun layer(name: String?, target: String) {
                    val png = name?.let(renderPng) ?: return
                    if (target !in plan) inner.add(IconsArchive.DRAWABLE_DIR + target, png, stored = true)
                }
                layer(filter.iconBack.firstOrNull(), "icon_background.png")
                layer(filter.iconMask, "icon_mask.png")
                layer(filter.iconUpon, "icon_border.png")
            }
            if (written == 0) throw IllegalStateException("No se pudo renderizar ningún icono del pack")
            MtzWriter(out).use { w ->
                val base = options.baseMtz
                if (base != null) w.copyFrom(base, except = setOf(MtzPaths.ICONS))
                else w.add(MtzPaths.DESCRIPTION, options.description.toXml().toByteArray(Charsets.UTF_8))
                // `icons` va sin comprimir (STORED), como en los MTZ de referencia.
                if (tmp != null) w.addStored(MtzPaths.ICONS, tmp) else w.add(MtzPaths.ICONS, mem!!.toByteArray(), stored = true)
                if (base == null) previews.entries.toSortedMap().forEach { (p, d) -> w.add(p, d) }
            }
            return Result(written, missing.toList())
        } finally {
            tmp?.delete()
        }
    }
}
