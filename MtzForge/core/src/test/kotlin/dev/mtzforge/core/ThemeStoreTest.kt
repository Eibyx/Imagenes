package dev.mtzforge.core

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ThemeStoreTest {
    private val data = "/storage/emulated/0/" + ThemeStore.DATA_DIR_API30
    private fun font() = javaClass.getResourceAsStream("/fonts/ForgeTestSans-Regular.ttf")!!.readBytes()

    private fun fontMtz(): ByteArray = ByteArrayOutputStream().also {
        FontMtzBuilder.build(font(), FontMtzOptions(ThemeDescription("Forge \"Sans\"", author = "Yo")),
            Previews(mapOf(MtzPaths.PREVIEW_FONT_SMALL to byteArrayOf(1), MtzPaths.PREVIEW_FONT to byteArrayOf(2))), it)
    }.toByteArray()

    @Test fun fontMtzBecomesThemePlusFontsResources() {
        val mtz = fontMtz()
        val inst = ThemeStore.fromMtz(mtz, data, now = 1_700_000_000_000L)
        val id = inst.localId
        assertTrue(inst.hasFonts)
        assertTrue(font().contentEquals(inst.read("content/fonts/$id.mrc")!!))
        assertEquals(0, inst.read("content/theme/$id.mrc")!!.size)
        assertTrue("preview/theme/$id/preview_fonts_0.jpg" in inst.files)
        assertTrue(inst.files.keys.none { it.startsWith("rights/") }, "nunca se generan licencias")
        val fontsMeta = String(inst.read("meta/fonts/$id.mrm")!!)
        assertTrue("\"hash\": \"${ThemeStore.sha1(font())}\"" in fontsMeta)
        assertTrue("\"rightsPath\": null" in fontsMeta)
        assertTrue("\"resourceCode\": \"theme\"" in fontsMeta)
        val themeMeta = String(inst.read("meta/theme/$id.mrm")!!)
        assertTrue("\"contentPath\": \"/system/..$data/content/theme/$id.mrc\"" in themeMeta)
        assertTrue("Forge \\\"Sans\\\"" in themeMeta, "comillas escapadas en JSON")
        // Mismo MTZ => mismo id (reinstalar sobrescribe en vez de duplicar)
        assertEquals(id, ThemeStore.fromMtz(mtz, data).localId)
        System.getProperty("mtzforge.samples")?.let { dir ->
            val root = File(dir, "biblioteca-temas-ejemplo/.data").apply { deleteRecursively() }
            inst.writeAll { p, input -> File(root, p).apply { parentFile.mkdirs() }.outputStream().use { input.copyTo(it) } }
        }
    }

    @Test fun mergesFontAndIconsIntoOneTheme() {
        val filter = AppFilter.parseXml(File("../sample-iconpack/src/main/res/xml/appfilter.xml").readBytes())
        val icons = ByteArrayOutputStream().also {
            IconMtzBuilder.build(filter, IconMtzOptions(ThemeDescription("I")), Previews.NONE, it) { byteArrayOf(9) }
        }.toByteArray()
        val bos = ByteArrayOutputStream()
        fun tmp(b: ByteArray) = File.createTempFile("part", ".mtz").apply { deleteOnExit(); writeBytes(b) }
        ThemeMerger.merge(listOf(tmp(fontMtz()), tmp(icons)), ThemeDescription("Tema completo"), bos)
        val rep = MtzInspector.inspect(bos.toByteArray())
        assertTrue(rep.isValid, rep.errors.toString())
        assertEquals(setOf("fonts", "icons"), rep.components.values.toSet())
        val inst = ThemeStore.fromMtz(bos.toByteArray(), data)
        assertEquals(setOf("fonts", "icons"), inst.components.map { it.resourceCode }.toSet())
        assertTrue(inst.files.keys.any { it == "content/icons/${inst.localId}.mrc" })
    }

    @Test fun rejectsMtzWithoutComponents() {
        val bos = ByteArrayOutputStream()
        MtzWriter(bos).use { it.add(MtzPaths.DESCRIPTION, ThemeDescription("x").toXml().toByteArray()); it.add("raro.bin", byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { ThemeStore.fromMtz(bos.toByteArray(), data) }
    }

    @Test fun jsonEscaping() {
        assertEquals("{\"a\":\"x\\\"y\\\\z\\n\",\"b\":[1,null],\"c\":{}}", Json.obj("a" to "x\"y\\z\n", "b" to listOf(1, null), "c" to Json.obj()).render())
    }

    /**
     * Regresión del fallo visto en un teléfono real (Arcticons, ~160 MB de iconos):
     * con un heap limitado (ver build.gradle.kts) inspeccionar e instalar debe
     * funcionar en streaming sin cargar el componente entero.
     */
    @Test fun hugeIconsComponentIsHandledInStreaming() {
        val big = File.createTempFile("huge", ".mtz").apply { deleteOnExit() }
        val chunk = ByteArray(1024 * 1024) { (it * 31).toByte() }
        // ZIP interno "icons" de ~200 MB (PNG falsos almacenados), generado en un fichero temporal.
        val inner = File.createTempFile("icons", ".zip").apply { deleteOnExit() }
        inner.outputStream().buffered().use { o ->
            MtzWriter(o).use { w -> repeat(200) { i -> w.add(IconsArchive.DRAWABLE_DIR + "com.example.app$i.png", chunk, stored = true) } }
        }
        big.outputStream().buffered().use { o ->
            MtzWriter(o).use { w ->
                w.add(MtzPaths.DESCRIPTION, ThemeDescription("Enorme").toXml().toByteArray())
                inner.inputStream().use { w.add(MtzPaths.ICONS, it) }
            }
        }
        val rep = MtzInspector.inspect(big)
        assertTrue(rep.isValid, rep.errors.toString())
        assertEquals(200, rep.iconCount)
        val inst = ThemeStore.fromMtz(big, data)
        var copied = 0L
        inst.writeAll { _, input -> copied += input.copyTo(java.io.OutputStream.nullOutputStream()) }
        assertTrue(copied >= inner.length())
        val merged = File.createTempFile("merged", ".mtz").apply { deleteOnExit() }
        merged.outputStream().buffered().use { ThemeMerger.merge(listOf(big), ThemeDescription("M"), it) }
        assertEquals(200, MtzInspector.inspect(merged).iconCount)
    }
}
