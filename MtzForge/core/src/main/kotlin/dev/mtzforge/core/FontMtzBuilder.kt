package dev.mtzforge.core

import java.io.OutputStream

/** Previsualizaciones ya renderizadas (la plataforma Android las genera con Canvas). */
class Previews(val entries: Map<String, ByteArray>) {
    init {
        entries.keys.forEach { require(it.startsWith(MtzPaths.PREVIEW_DIR)) { "La vista previa debe ir en preview/: $it" } }
    }
    companion object { val NONE = Previews(emptyMap()) }
}

data class FontMtzOptions(
    val description: ThemeDescription,
    /** Índice dentro de una colección TTC. */
    val collectionIndex: Int = 0,
    /** Añade además la fuente como fallback (DroidSansFallback.ttf). Experimental. */
    val includeFallback: Boolean = false,
    /** Modo "MTZ base": tema que ya funciona; se conserva todo salvo las fuentes. */
    val baseMtz: java.io.File? = null,
)

/**
 * Convierte TTF/OTF/TTC en un MTZ de fuente:
 *
 * ```
 * description.xml
 * fonts/Roboto-Regular.ttf          <- la fuente (TTC extraída a SFNT simple)
 * [fonts/DroidSansFallback.ttf]     <- opcional
 * preview/preview_fonts_small_0.png
 * preview/preview_fonts_0.jpg
 * ```
 */
object FontMtzBuilder {
    data class Result(val font: SfntFont, val warnings: List<String>)

    /** Valida y prepara los bytes de la fuente que se escribirán en el MTZ. */
    fun prepareFont(data: ByteArray, collectionIndex: Int = 0): Pair<ByteArray, SfntFont> {
        val single = SfntFont.extractFromCollection(data, collectionIndex)
        val font = SfntFont.parse(single)
        if (font.glyphCount < 2) throw FontFormatException("La fuente no contiene glifos")
        return single to font
    }

    fun build(fontData: ByteArray, options: FontMtzOptions, previews: Previews, out: OutputStream): Result {
        val (bytes, font) = prepareFont(fontData, options.collectionIndex)
        val warnings = font.warnings().toMutableList()
        if (SfntFont.fontCount(fontData) > 1) warnings += "Colección TTC: se usó la fuente #${options.collectionIndex}."
        MtzWriter(out).use { w ->
            val base = options.baseMtz
            if (base != null) {
                val replaced = mutableSetOf(MtzPaths.FONT)
                if (options.includeFallback) replaced += MtzPaths.FONT_FALLBACK
                w.copyFrom(base, except = replaced)
            } else w.add(MtzPaths.DESCRIPTION, options.description.toXml().toByteArray(Charsets.UTF_8))
            w.add(MtzPaths.FONT, bytes)
            if (options.includeFallback) w.add(MtzPaths.FONT_FALLBACK, bytes)
            if (base == null) previews.entries.toSortedMap().forEach { (p, d) -> w.add(p, d) }
        }
        return Result(font, warnings)
    }
}
