package dev.mtzforge.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lector mínimo de fuentes SFNT (TrueType / OpenType-CFF / colecciones TTC).
 *
 * Implementación propia basada en la especificación pública OpenType
 * (https://learn.microsoft.com/typography/opentype/spec/otff). Solo lee lo
 * necesario para validar la fuente y obtener sus nombres.
 */
class FontFormatException(message: String) : Exception(message)

enum class Outline { TRUETYPE, CFF, CFF2 }

data class FontTable(val tag: String, val checksum: Long, val offset: Int, val length: Int)

data class SfntFont(
    val outline: Outline,
    val tables: List<FontTable>,
    val familyName: String?,
    val subfamilyName: String?,
    val fullName: String?,
    val postScriptName: String?,
    val glyphCount: Int,
    val isVariable: Boolean,
    val hasColorGlyphs: Boolean,
) {
    val displayName: String
        get() = fullName ?: listOfNotNull(familyName, subfamilyName).joinToString(" ").ifBlank { postScriptName ?: "Fuente" }

    fun table(tag: String): FontTable? = tables.firstOrNull { it.tag == tag }

    /** Advertencias que no impiden empaquetar, pero conviene mostrar al usuario. */
    fun warnings(): List<String> = buildList {
        if (isVariable) add("Fuente variable (fvar): el sistema puede mostrar solo la instancia por defecto.")
        if (outline != Outline.TRUETYPE) add("Contornos ${outline.name}: se empaqueta igual, pero el nombre interno del MTZ es .ttf.")
        if (table("OS/2") == null) add("Falta la tabla OS/2; algunos renderizadores calculan mal las métricas.")
        if (hasColorGlyphs) add("La fuente contiene glifos de color (COLR/CBDT/sbix).")
    }

    companion object {
        private val REQUIRED = listOf("cmap", "head", "hhea", "hmtx", "maxp", "name", "post")

        /** Número de fuentes en el fichero (1 salvo en TTC). */
        fun fontCount(data: ByteArray): Int {
            if (data.size < 12) throw FontFormatException("Fichero demasiado pequeño para ser una fuente")
            val b = be(data)
            return if (b.getInt(0) == TAG_TTCF) b.getInt(8) else 1
        }

        fun parse(data: ByteArray, index: Int = 0): SfntFont {
            val b = be(data)
            if (data.size < 12) throw FontFormatException("Fichero demasiado pequeño para ser una fuente")
            val base = fontOffset(b, index)
            val version = b.getInt(base)
            if (version != 0x00010000 && version != TAG_OTTO && version != TAG_TRUE) {
                throw FontFormatException(
                    if (version == TAG_WOFF || version == TAG_WOFF2) "WOFF/WOFF2 no está soportado: convierte a TTF/OTF primero"
                    else "Cabecera SFNT desconocida (0x%08X)".format(version)
                )
            }
            val numTables = b.getShort(base + 4).toInt() and 0xFFFF
            if (numTables == 0 || base + 12 + numTables * 16 > data.size) throw FontFormatException("Directorio de tablas inválido")
            val tables = (0 until numTables).map { i ->
                val p = base + 12 + i * 16
                val t = FontTable(tag(b.getInt(p)), b.getInt(p + 4).toLong() and 0xFFFFFFFFL, b.getInt(p + 8), b.getInt(p + 12))
                if (t.offset < 0 || t.length < 0 || t.offset.toLong() + t.length > data.size) {
                    throw FontFormatException("La tabla '${t.tag}' apunta fuera del fichero")
                }
                t
            }
            val tags = tables.map { it.tag }.toSet()
            val missing = REQUIRED.filter { it !in tags }
            if (missing.isNotEmpty()) throw FontFormatException("Faltan tablas obligatorias: ${missing.joinToString()}")
            val outline = when {
                "glyf" in tags && "loca" in tags -> Outline.TRUETYPE
                "CFF " in tags -> Outline.CFF
                "CFF2" in tags -> Outline.CFF2
                else -> throw FontFormatException("La fuente no tiene contornos (glyf/loca o CFF)")
            }
            val maxp = tables.first { it.tag == "maxp" }
            val glyphs = b.getShort(maxp.offset + 4).toInt() and 0xFFFF
            val names = readNames(b, tables.first { it.tag == "name" })
            return SfntFont(
                outline = outline,
                tables = tables,
                familyName = names[16] ?: names[1],
                subfamilyName = names[17] ?: names[2],
                fullName = names[4],
                postScriptName = names[6],
                glyphCount = glyphs,
                isVariable = "fvar" in tags,
                hasColorGlyphs = tags.any { it == "COLR" || it == "CBDT" || it == "sbix" || it == "SVG " },
            )
        }

        /**
         * Extrae la fuente [index] de una colección TTC y la reescribe como fichero
         * SFNT independiente (recalcula offsets y checkSumAdjustment de 'head').
         * Si [data] no es TTC, se devuelve tal cual.
         */
        fun extractFromCollection(data: ByteArray, index: Int): ByteArray {
            val b = be(data)
            if (b.getInt(0) != TAG_TTCF) return data
            val font = parse(data, index)
            val base = fontOffset(b, index)
            val version = b.getInt(base)
            val n = font.tables.size
            val sorted = font.tables.sortedBy { it.tag }
            val headerSize = 12 + 16 * n
            var size = headerSize
            sorted.forEach { size += pad4(it.length) }
            val out = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
            var entrySelector = 0
            while ((1 shl (entrySelector + 1)) <= n) entrySelector++
            val searchRange = (1 shl entrySelector) * 16
            out.putInt(version).putShort(n.toShort()).putShort(searchRange.toShort())
                .putShort(entrySelector.toShort()).putShort((n * 16 - searchRange).toShort())
            var offset = headerSize
            var headOffset = -1
            sorted.forEach { t ->
                out.putInt(tagInt(t.tag)).putInt(t.checksum.toInt()).putInt(offset).putInt(t.length)
                if (t.tag == "head") headOffset = offset
                offset += pad4(t.length)
            }
            sorted.forEach { t ->
                out.put(data, t.offset, t.length)
                repeat(pad4(t.length) - t.length) { out.put(0) }
            }
            val bytes = out.array()
            if (headOffset >= 0 && headOffset + 12 <= bytes.size) {
                val ob = be(bytes)
                ob.putInt(headOffset + 8, 0)
                val sum = checksum(bytes)
                ob.putInt(headOffset + 8, (0xB1B0AFBAL - sum).toInt())
            }
            return bytes
        }

        internal fun checksum(bytes: ByteArray): Long {
            var sum = 0L
            val b = be(bytes)
            var i = 0
            while (i + 4 <= bytes.size) { sum = (sum + (b.getInt(i).toLong() and 0xFFFFFFFFL)) and 0xFFFFFFFFL; i += 4 }
            if (i < bytes.size) {
                var last = 0L
                for (k in 0 until 4) last = (last shl 8) or (if (i + k < bytes.size) (bytes[i + k].toLong() and 0xFF) else 0L)
                sum = (sum + last) and 0xFFFFFFFFL
            }
            return sum
        }

        private fun fontOffset(b: ByteBuffer, index: Int): Int {
            if (b.getInt(0) != TAG_TTCF) {
                if (index != 0) throw FontFormatException("El fichero no es una colección; solo existe el índice 0")
                return 0
            }
            val count = b.getInt(8)
            if (index !in 0 until count) throw FontFormatException("Índice $index fuera de rango (la colección tiene $count fuentes)")
            val off = b.getInt(12 + 4 * index)
            if (off < 0 || off + 12 > b.limit()) throw FontFormatException("Offset de fuente inválido en TTC")
            return off
        }

        private fun readNames(b: ByteBuffer, t: FontTable): Map<Int, String> {
            val result = HashMap<Int, Pair<Int, String>>() // nameID -> (prioridad, valor)
            val count = b.getShort(t.offset + 2).toInt() and 0xFFFF
            val storage = t.offset + (b.getShort(t.offset + 4).toInt() and 0xFFFF)
            for (i in 0 until count) {
                val r = t.offset + 6 + i * 12
                if (r + 12 > t.offset + t.length) break
                val platform = b.getShort(r).toInt() and 0xFFFF
                val encoding = b.getShort(r + 2).toInt() and 0xFFFF
                val language = b.getShort(r + 4).toInt() and 0xFFFF
                val nameId = b.getShort(r + 6).toInt() and 0xFFFF
                val len = b.getShort(r + 8).toInt() and 0xFFFF
                val off = storage + (b.getShort(r + 10).toInt() and 0xFFFF)
                if (off + len > b.limit()) continue
                val raw = ByteArray(len).also { b.copyAt(off, it) }
                val (prio, text) = when {
                    platform == 3 && (encoding == 1 || encoding == 10) ->
                        (if (language == 0x0409) 3 else 2) to String(raw, Charsets.UTF_16BE)
                    platform == 0 -> 2 to String(raw, Charsets.UTF_16BE)
                    platform == 1 && encoding == 0 -> 1 to String(raw, Charsets.ISO_8859_1)
                    else -> continue
                }
                val clean = text.trim().trimEnd('\u0000')
                if (clean.isEmpty()) continue
                val prev = result[nameId]
                if (prev == null || prev.first < prio) result[nameId] = prio to clean
            }
            return result.mapValues { it.value.second }
        }

        private fun ByteBuffer.copyAt(index: Int, dst: ByteArray) {
            for (i in dst.indices) dst[i] = this.get(index + i)
        }

        private fun be(d: ByteArray) = ByteBuffer.wrap(d).order(ByteOrder.BIG_ENDIAN)
        private fun pad4(n: Int) = (n + 3) and 3.inv()
        private fun tag(v: Int) = String(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()), Charsets.ISO_8859_1)
        private fun tagInt(s: String): Int = s.toByteArray(Charsets.ISO_8859_1).fold(0) { acc, c -> (acc shl 8) or (c.toInt() and 0xFF) }

        private val TAG_TTCF = tagInt("ttcf")
        private val TAG_OTTO = tagInt("OTTO")
        private val TAG_TRUE = tagInt("true")
        private val TAG_WOFF = tagInt("wOFF")
        private val TAG_WOFF2 = tagInt("wOF2")
    }
}
