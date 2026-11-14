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
        assertTrue(font().contentEquals(inst.files["content/fonts/$id.mrc"]!!))
        assertEquals(0, inst.files["content/theme/$id.mrc"]!!.size)
        assertTrue("preview/theme/$id/preview_fonts_0.jpg" in inst.files)
        assertTrue(inst.files.keys.none { it.startsWith("rights/") }, "nunca se generan licencias")
        val fontsMeta = String(inst.files["meta/fonts/$id.mrm"]!!)
        assertTrue("\"hash\": \"${ThemeStore.sha1(font())}\"" in fontsMeta)
        assertTrue("\"rightsPath\": null" in fontsMeta)
        assertTrue("\"resourceCode\": \"theme\"" in fontsMeta)
        val themeMeta = String(inst.files["meta/theme/$id.mrm"]!!)
        assertTrue("\"contentPath\": \"/system/..$data/content/theme/$id.mrc\"" in themeMeta)
        assertTrue("Forge \\\"Sans\\\"" in themeMeta, "comillas escapadas en JSON")
        // Mismo MTZ => mismo id (reinstalar sobrescribe en vez de duplicar)
        assertEquals(id, ThemeStore.fromMtz(mtz, data).localId)
        System.getProperty("mtzforge.samples")?.let { dir ->
            val root = File(dir, "biblioteca-temas-ejemplo/.data").apply { deleteRecursively() }
            inst.files.forEach { (p, d) -> File(root, p).apply { parentFile.mkdirs() }.writeBytes(d) }
        }
    }

    @Test fun mergesFontAndIconsIntoOneTheme() {
        val filter = AppFilter.parseXml(File("../sample-iconpack/src/main/res/xml/appfilter.xml").readBytes())
        val icons = ByteArrayOutputStream().also {
            IconMtzBuilder.build(filter, IconMtzOptions(ThemeDescription("I")), Previews.NONE, it) { byteArrayOf(9) }
        }.toByteArray()
        val bos = ByteArrayOutputStream()
        ThemeMerger.merge(listOf(fontMtz(), icons), ThemeDescription("Tema completo"), bos)
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
}
