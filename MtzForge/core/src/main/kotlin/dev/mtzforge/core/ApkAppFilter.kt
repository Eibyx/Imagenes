package dev.mtzforge.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Lector mínimo del XML binario compilado de Android (AXML), según el formato
 * público de AOSP (ResourceTypes.h). Solo extrae elementos y atributos.
 */
object AxmlParser {
    private const val RES_XML_TYPE = 0x0003
    private const val STRING_POOL = 0x0001
    private const val START_ELEMENT = 0x0102

    fun isAxml(data: ByteArray) = data.size >= 8 && (data[0].toInt() and 0xFF) == 0x03 && data[1].toInt() == 0x00

    fun elements(data: ByteArray): List<Pair<String, Map<String, String>>> {
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        require(isAxml(data) && (b.getShort(0).toInt() and 0xFFFF) == RES_XML_TYPE) { "No es XML binario de Android" }
        var pos = b.getShort(2).toInt() and 0xFFFF
        var strings: List<String> = emptyList()
        val out = ArrayList<Pair<String, Map<String, String>>>()
        while (pos + 8 <= data.size) {
            val type = b.getShort(pos).toInt() and 0xFFFF
            val headerSize = b.getShort(pos + 2).toInt() and 0xFFFF
            val size = b.getInt(pos + 4)
            if (size <= 0 || pos + size > data.size) break
            when (type) {
                STRING_POOL -> strings = stringPool(b, pos)
                START_ELEMENT -> {
                    val ext = pos + headerSize
                    val name = strings.getOrNull(b.getInt(ext + 4)) ?: ""
                    val attrStart = b.getShort(ext + 8).toInt() and 0xFFFF
                    val attrSize = b.getShort(ext + 10).toInt() and 0xFFFF
                    val count = b.getShort(ext + 12).toInt() and 0xFFFF
                    val attrs = LinkedHashMap<String, String>()
                    for (i in 0 until count) {
                        val a = ext + attrStart + i * attrSize
                        val attrName = strings.getOrNull(b.getInt(a + 4)) ?: continue
                        val raw = b.getInt(a + 8)
                        val dataType = b.get(a + 15).toInt() and 0xFF
                        val value = b.getInt(a + 16)
                        attrs[attrName] = when {
                            raw != -1 -> strings.getOrNull(raw) ?: ""
                            dataType == 0x03 -> strings.getOrNull(value) ?: ""
                            dataType == 0x04 -> java.lang.Float.intBitsToFloat(value).toString()
                            dataType == 0x12 -> (value != 0).toString()
                            dataType == 0x01 -> "@0x%08x".format(value)
                            else -> value.toString()
                        }
                    }
                    out += name to attrs
                }
            }
            pos += size
        }
        return out
    }

    private fun stringPool(b: ByteBuffer, start: Int): List<String> {
        val count = b.getInt(start + 8)
        val flags = b.getInt(start + 16)
        val stringsStart = start + b.getInt(start + 20)
        val utf8 = flags and 0x100 != 0
        val offsets = start + (b.getShort(start + 2).toInt() and 0xFFFF)
        return (0 until count).map { i ->
            var p = stringsStart + b.getInt(offsets + i * 4)
            if (utf8) {
                p += if (b.get(p).toInt() and 0x80 != 0) 2 else 1 // longitud en UTF-16 (se ignora)
                var len = b.get(p).toInt() and 0xFF
                if (len and 0x80 != 0) { len = ((len and 0x7F) shl 8) or (b.get(p + 1).toInt() and 0xFF); p += 2 } else p += 1
                String(ByteArray(len) { b.get(p + it) }, Charsets.UTF_8)
            } else {
                var len = b.getShort(p).toInt() and 0xFFFF
                if (len and 0x8000 != 0) { len = ((len and 0x7FFF) shl 16) or (b.getShort(p + 2).toInt() and 0xFFFF); p += 4 } else p += 2
                String(CharArray(len) { b.getShort(p + it * 2).toInt().toChar() })
            }
        }
    }
}

/** Busca appfilter.xml leyendo el APK como ZIP (alternativa cuando Resources falla). */
object ApkAppFilter {
    data class Found(val entry: String, val filter: AppFilter)

    private val NAMED = Regex("(?i)^(appfilter.*|app_filter|icon_config)\\.xml$")

    /**
     * 1) Busca por nombre (appfilter*.xml, app_filter.xml, icon_config.xml en cualquier carpeta).
     * 2) Si no aparece (APK con nombres de recursos ofuscados, p. ej. res/aB.xml), revisa
     *    todos los XML y elige el que más entradas `<item component=…>` tenga.
     */
    fun find(apk: File): Found? = ZipFile(apk).use { z ->
        val xmls = z.entries().toList().filter { !it.isDirectory && it.name.endsWith(".xml", true) && it.name != "AndroidManifest.xml" }
        val named = xmls.filter { NAMED.matches(it.name.substringAfterLast('/')) }
            .sortedBy { if (it.name.startsWith("assets/")) 0 else 1 }
        for (e in named) parse(z.getInputStream(e).use { it.readBytes() })?.let { return@use Found(e.name, it) }
        var best: Found? = null
        for (e in xmls) {
            if (e in named || e.size > 64L * 1024 * 1024) continue
            val bytes = z.getInputStream(e).use { it.readBytes() }
            if (!mentionsComponent(bytes)) continue
            val f = parse(bytes) ?: continue
            if (best == null || f.items.size > best.filter.items.size) best = Found(e.name, f)
        }
        best
    }

    private fun parse(bytes: ByteArray): AppFilter? = runCatching {
        if (AxmlParser.isAxml(bytes)) AppFilter.fromElements(AxmlParser.elements(bytes).asSequence())
        else AppFilter.parseXml(bytes)
    }.getOrNull()?.takeIf { it.items.isNotEmpty() }

    /** Filtro rápido: el texto "ComponentInfo" en UTF-8 o UTF-16LE (pool de cadenas del AXML). */
    private val NEEDLE8 = "ComponentInfo".toByteArray(Charsets.UTF_8)
    private val NEEDLE16 = "ComponentInfo".toByteArray(Charsets.UTF_16LE)
    private fun mentionsComponent(b: ByteArray) = indexOf(b, NEEDLE8) >= 0 || indexOf(b, NEEDLE16) >= 0
    private fun indexOf(h: ByteArray, n: ByteArray): Int {
        outer@ for (i in 0..h.size - n.size) { for (j in n.indices) if (h[i + j] != n[j]) continue@outer; return i }
        return -1
    }

    /** Nombres de APK internos si el fichero es un paquete .apks/.xapk/.apkm. */
    fun innerApks(file: File): List<String> = runCatching {
        ZipFile(file).use { z -> z.entries().toList().filter { !it.isDirectory && it.name.endsWith(".apk", true) }.map { it.name } }
    }.getOrDefault(emptyList())

    /** Elige el APK base de un paquete: "base.apk" si existe, si no el más grande. */
    fun extractBaseApk(bundle: File, target: File): File {
        ZipFile(bundle).use { z ->
            val apks = z.entries().toList().filter { !it.isDirectory && it.name.endsWith(".apk", true) }
            require(apks.isNotEmpty()) { "El fichero no contiene ningún APK" }
            val base = apks.firstOrNull { it.name.substringAfterLast('/').equals("base.apk", true) } ?: apks.maxBy { it.size }
            z.getInputStream(base).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        return target
    }

    fun isZip(file: File): Boolean = file.length() >= 4 && file.inputStream().use { s ->
        val h = ByteArray(4); s.read(h) == 4 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte()
    }
}
