package dev.mtzforge.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Comportamientos alineados con los MTZ de referencia (IconPack-to-MTZ / IconEditor). */
class ReferenceAlignmentTest {
    private val font = javaClass.getResourceAsStream("/fonts/ForgeTestSans-Regular.ttf")!!.readBytes()
    private fun tmp(prefix: String) = File.createTempFile(prefix, ".mtz").apply { deleteOnExit() }

    /** Tema base sintético: description propia, un componente ajeno, fuente antigua e iconos antiguos. */
    private fun baseTheme(): File = tmp("basetheme").also { f ->
        ZipOutputStream(f.outputStream()).use { z ->
            fun put(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            put("description.xml", "<MIUI-Theme><title>Base que funciona</title><uiVersion>17</uiVersion></MIUI-Theme>".toByteArray())
            put("com.miui.home", byteArrayOf(7, 7, 7))
            put("fonts/Roboto-Regular.ttf", byteArrayOf(1))
            put("icons", byteArrayOf(2))
            put("preview/preview_launcher_0.jpg", byteArrayOf(3))
        }
    }

    @Test fun fontIntoBaseThemeKeepsEverythingElse() {
        val out = tmp("fontbase")
        out.outputStream().use { FontMtzBuilder.build(font, FontMtzOptions(ThemeDescription("x"), baseMtz = baseTheme()), Previews.NONE, it) }
        ZipFile(out).use { z ->
            assertEquals("Base que funciona", ThemeDescription.parse(z.getInputStream(z.getEntry("description.xml")).readBytes()).title)
            assertTrue(font.contentEquals(z.getInputStream(z.getEntry(MtzPaths.FONT)).readBytes()))
            assertTrue(z.getEntry("com.miui.home") != null && z.getEntry("preview/preview_launcher_0.jpg") != null)
        }
        assertTrue(MtzInspector.inspect(out).isValid)
    }

    @Test fun iconsIntoBaseThemeAreStoredAndReplaceOnlyIcons() {
        val filter = AppFilter.parseXml(File("../sample-iconpack/src/main/res/xml/appfilter.xml").readBytes())
        val out = tmp("iconbase")
        out.outputStream().use {
            IconMtzBuilder.build(filter, IconMtzOptions(ThemeDescription("x"), baseMtz = baseTheme()), Previews.NONE, it,
                tempDir = out.parentFile) { byteArrayOf(9, 9) }
        }
        ZipFile(out).use { z ->
            assertEquals(ZipEntry.STORED, z.getEntry("icons").method)
            assertEquals(1, z.getInputStream(z.getEntry(MtzPaths.FONT)).readBytes().size, "la fuente de la base se conserva")
            assertTrue(z.getEntry("preview/preview_icons_0.jpg") == null)
        }
        assertTrue(MtzInspector.inspect(out).iconCount > 10)
    }

    @Test fun standaloneIconsUseJpgPreviewAndStoredIcons() {
        val filter = AppFilter.parseXml(File("../sample-iconpack/src/main/res/xml/appfilter.xml").readBytes())
        val bos = ByteArrayOutputStream()
        IconMtzBuilder.build(filter, IconMtzOptions(ThemeDescription("x", uiVersion = ThemeDescription.ICONS_UI_VERSION)),
            Previews(mapOf(MtzPaths.PREVIEW_ICONS to byteArrayOf(1))), bos) { byteArrayOf(1) }
        val f = tmp("standalone").apply { writeBytes(bos.toByteArray()) }
        ZipFile(f).use { z ->
            assertEquals(ZipEntry.STORED, z.getEntry("icons").method)
            assertTrue(z.getEntry("preview/preview_icons_0.jpg") != null)
            assertEquals(15, ThemeDescription.parse(z.getInputStream(z.getEntry("description.xml")).readBytes()).uiVersion)
        }
    }

    @Test fun packageDefaultIsMostFrequentDrawable() {
        val f = AppFilter.fromElements(sequenceOf(
            "item" to mapOf("component" to "ComponentInfo{com.a/com.a.One}", "drawable" to "rare"),
            "item" to mapOf("component" to "ComponentInfo{com.a/com.a.Two}", "drawable" to "common"),
            "item" to mapOf("component" to "ComponentInfo{com.a/com.a.Three}", "drawable" to "common"),
        ))
        val plan = IconMapping.plan(f)
        assertEquals("common", plan["com.a.png"])
        assertEquals("rare", plan["com.a.One.png"])
        assertEquals(null, plan["com.a.Two.png"])
    }

    @Test fun findsAppFilterWithObfuscatedResourceName() {
        // APK sintético: el appfilter compilado del pack de muestra, pero renombrado a res/Zq.xml
        val axml = ZipFile(File("../dist/ForgeSampleIcons-debug.apk")).use { z -> z.getInputStream(z.getEntry("res/xml/appfilter.xml")).readBytes() }
        val apk = File.createTempFile("obf", ".apk").apply { deleteOnExit() }
        ZipOutputStream(apk.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("res/aB.xml")); z.write("<vector/>".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("res/Zq.xml")); z.write(axml); z.closeEntry()
            z.putNextEntry(ZipEntry("res/raw/zz.xml")); z.write("<resources><item component=\"ComponentInfo{a.b/a.b.C}\" drawable=\"x\"/></resources>".toByteArray()); z.closeEntry()
        }
        val found = ApkAppFilter.find(apk)!!
        assertEquals("res/Zq.xml", found.entry) // gana el que tiene más entradas
        assertEquals(17, found.filter.items.size)
    }
}
