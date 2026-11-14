package dev.mtzforge.core

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Metadatos de un tema MIUI/HyperOS (`description.xml` en la raíz del MTZ).
 *
 * Esquema deducido del análisis: raíz `<theme>` con `version`, `uiVersion`,
 * `author`, `designer`, `title`, `description` y las listas localizadas
 * `authors/designers/titles/descriptions` (atributo `locale`).
 * zFont 3.9.2.2 escribe `uiVersion` = 8; aquí es configurable.
 */
data class ThemeDescription(
    val title: String,
    val author: String = "MTZ Forge",
    val designer: String = author,
    val version: String = "1.0",
    val uiVersion: Int = DEFAULT_UI_VERSION,
    val description: String = "",
    val locales: List<String> = listOf("en_US", "zh_CN", "es_ES"),
) {
    init {
        require(title.isNotBlank()) { "El título no puede estar vacío" }
    }

    fun toXml(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n")
        append("<theme>\n")
        tag("version", version)
        append("    <uiVersion>").append(uiVersion).append("</uiVersion>\n")
        tag("title", title)
        tag("designer", designer)
        tag("author", author)
        tag("description", description)
        localized("titles", "title", title)
        localized("designers", "designer", designer)
        localized("authors", "author", author)
        localized("descriptions", "description", description)
        append("</theme>\n")
    }

    private fun StringBuilder.tag(name: String, value: String, indent: String = "    ", attrs: String = "") {
        append(indent).append('<').append(name).append(attrs).append('>')
            .append(cdata(value)).append("</").append(name).append(">\n")
    }

    private fun StringBuilder.localized(group: String, item: String, value: String) {
        append("    <").append(group).append(">\n")
        locales.forEach { tag(item, value, "        ", " locale=\"${escapeAttr(it)}\"") }
        append("    </").append(group).append(">\n")
    }

    companion object {
        /** Valor que escribe zFont 3 en los MTZ de fuente. */
        const val DEFAULT_UI_VERSION = 8
        /** Valor de IconPack-to-MTZ (MTZ de iconos probado en un POCO F8 Ultra con HyperOS). */
        const val ICONS_UI_VERSION = 15

        /** CDATA seguro: divide cualquier "]]>" del texto. */
        fun cdata(s: String) = "<![CDATA[" + s.replace("]]>", "]]]]><![CDATA[>") + "]]>"

        private fun escapeAttr(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

        /** Lee un description.xml (propio o de terceros) para inspección. */
        fun parse(xml: ByteArray): ThemeDescription {
            val f = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            }
            val root = f.newDocumentBuilder().parse(ByteArrayInputStream(xml)).documentElement
            if (root.tagName != "theme" && root.tagName != "MIUI-Theme") throw IllegalArgumentException("La raíz de description.xml debe ser <theme> o <MIUI-Theme>")
            fun direct(name: String): String? {
                val nodes = root.childNodes
                for (i in 0 until nodes.length) {
                    val n = nodes.item(i)
                    if (n is Element && n.tagName == name) return n.textContent.trim()
                }
                return null
            }
            fun firstLocalized(group: String): String? {
                val g = root.getElementsByTagName(group)
                if (g.length == 0) return null
                val items = (g.item(0) as Element).childNodes
                for (i in 0 until items.length) {
                    val n = items.item(i)
                    if (n is Element) return n.textContent.trim()
                }
                return null
            }
            val title = direct("title") ?: firstLocalized("titles") ?: throw IllegalArgumentException("description.xml sin <title>")
            val author = direct("author") ?: firstLocalized("authors") ?: ""
            return ThemeDescription(
                title = title,
                author = author,
                designer = direct("designer") ?: firstLocalized("designers") ?: author,
                version = direct("version") ?: "",
                uiVersion = direct("uiVersion")?.toIntOrNull() ?: 0,
                description = direct("description") ?: firstLocalized("descriptions") ?: "",
            )
        }
    }
}
