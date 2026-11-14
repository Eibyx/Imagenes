package dev.mtzforge.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CoreTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/fonts/$name")!!.readBytes()

    private fun samples(): File? = System.getProperty("mtzforge.samples")?.let { File(it).apply { mkdirs() } }

    private fun entries(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) { val e = z.nextEntry ?: break; put(e.name, z.readBytes()) }
        }
    }

    @Test fun parsesTrueTypeNames() {
        val f = SfntFont.parse(res("ForgeTestSans-Regular.ttf"))
        assertEquals(Outline.TRUETYPE, f.outline)
        assertEquals("Forge Test Sans", f.familyName)
        assertEquals("Regular", f.subfamilyName)
        assertTrue(f.glyphCount > 60)
    }

    @Test fun parsesCffFont() {
        val f = SfntFont.parse(res("ForgeTestSerif-Regular.otf"))
        assertEquals(Outline.CFF, f.outline)
        assertEquals("Forge Test Serif", f.familyName)
    }

    @Test fun rejectsGarbage() {
        assertFailsWith<FontFormatException> { SfntFont.parse(res("not-a-font.ttf")) }
        assertFailsWith<FontFormatException> { SfntFont.parse(ByteArray(4)) }
    }

    @Test fun extractsFontsFromCollection() {
        val ttc = res("ForgeTestCollection.ttc")
        assertEquals(2, SfntFont.fontCount(ttc))
        val second = SfntFont.extractFromCollection(ttc, 1)
        val f = SfntFont.parse(second)
        assertEquals("Forge Test Bold", f.familyName)
        // checkSumAdjustment correcto => suma total del fichero = 0xB1B0AFBA
        assertEquals(0xB1B0AFBAL, SfntFont.checksum(second))
    }

    @Test fun descriptionRoundTripAndCdataEscaping() {
        val d = ThemeDescription(title = "Mi ]]> fuente & <b>", author = "Yo", description = "desc")
        val back = ThemeDescription.parse(d.toXml().toByteArray())
        assertEquals(d.title, back.title)
        assertEquals("Yo", back.author)
        assertEquals(8, back.uiVersion)
    }

    @Test fun buildsFontMtzWithExpectedLayout() {
        val bos = ByteArrayOutputStream()
        val previews = Previews(mapOf(MtzPaths.PREVIEW_FONT_SMALL to byteArrayOf(1), MtzPaths.PREVIEW_FONT to byteArrayOf(2)))
        val font = res("ForgeTestSans-Regular.ttf")
        FontMtzBuilder.build(font, FontMtzOptions(ThemeDescription("Forge Test Sans")), previews, bos)
        val e = entries(bos.toByteArray())
        assertTrue("description.xml" in e)
        assertTrue("fonts/" in e && "preview/" in e, "entradas de directorio explícitas")
        assertTrue(e[MtzPaths.FONT]!!.contentEquals(font), "la fuente se copia byte a byte")
        val rep = MtzInspector.inspect(bos.toByteArray())
        assertTrue(rep.isValid, rep.errors.toString())
        assertEquals("fonts", rep.components[MtzPaths.FONT])
        assertEquals("Forge Test Sans Regular", rep.fontName)
        samples()?.let { File(it, "ForgeTestSans.mtz").writeBytes(bos.toByteArray()) }
    }

    @Test fun ttcIsConvertedToSingleFontInMtz() {
        val bos = ByteArrayOutputStream()
        val r = FontMtzBuilder.build(res("ForgeTestCollection.ttc"), FontMtzOptions(ThemeDescription("Bold"), collectionIndex = 1, includeFallback = true), Previews.NONE, bos)
        assertEquals("Forge Test Bold", r.font.familyName)
        val e = entries(bos.toByteArray())
        assertEquals(1, SfntFont.fontCount(e[MtzPaths.FONT]!!))
        assertTrue(MtzPaths.FONT_FALLBACK in e)
    }

    @Test fun deterministicOutput() {
        fun once() = ByteArrayOutputStream().also {
            FontMtzBuilder.build(res("ForgeTestSerif-Regular.otf"), FontMtzOptions(ThemeDescription("S")), Previews.NONE, it)
        }.toByteArray()
        assertTrue(once().contentEquals(once()))
    }

    @Test fun parsesComponents() {
        assertEquals(AppComponent("com.a.b", "com.a.b.Main"), AppComponent.parse("ComponentInfo{com.a.b/.Main}"))
        assertEquals(AppComponent("com.a.b", "x.y.Z"), AppComponent.parse("ComponentInfo{com.a.b/x.y.Z}"))
        assertEquals(AppComponent("com.a.b", null), AppComponent.parse("com.a.b"))
        assertEquals(null, AppComponent.parse("ComponentInfo{:LAUNCHER_ACTION_APP_DRAWER}"))
        assertEquals(null, AppComponent.parse(""))
    }

    private val appfilter = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
          <iconback img1="iconback" /><iconmask img1="iconmask" /><iconupon img1="iconupon" /><scale factor="0.8" />
          <item component="ComponentInfo{com.android.chrome/com.google.android.apps.chrome.Main}" drawable="chrome" />
          <item component="ComponentInfo{com.android.chrome/org.chromium.chrome.browser.ChromeTabbedActivity}" drawable="chrome" />
          <item component="ComponentInfo{com.android.contacts/com.android.contacts.activities.PeopleActivity}" drawable="contacts" />
          <item component="ComponentInfo{com.android.contacts/com.android.contacts.activities.TwelveKeyDialer}" drawable="phone" />
          <item component="ComponentInfo{com.broken}" drawable="" />
          <item component=":CALENDAR" drawable="calendar" />
          <item component="ComponentInfo{org.example/.Start}" drawable="@drawable/example" />
        </resources>
    """.trimIndent().toByteArray()

    @Test fun parsesAppFilterAndPlansMiuiNames() {
        val f = AppFilter.parseXml(appfilter)
        assertEquals(5, f.items.size)
        assertEquals(listOf("iconback"), f.iconBack)
        assertEquals(0.8f, f.scale)
        val plan = IconMapping.plan(f)
        assertEquals("chrome", plan["com.android.chrome.png"])
        assertEquals(null, plan["com.google.android.apps.chrome.Main.png"], "mismo icono => solo nombre de paquete")
        assertEquals("contacts", plan["com.android.contacts.png"])
        assertEquals("phone", plan["com.android.contacts.activities.TwelveKeyDialer.png"])
        assertEquals("example", plan["org.example.png"])
    }

    @Test fun buildsIconMtz() {
        val f = AppFilter.parseXml(appfilter)
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        val bos = ByteArrayOutputStream()
        val r = IconMtzBuilder.build(f, IconMtzOptions(ThemeDescription("Iconos test")), Previews.NONE, bos) { d ->
            if (d == "phone") null else png
        }
        assertEquals(listOf("phone"), r.missing)
        val e = entries(bos.toByteArray())
        val inner = entries(assertNotNull(e[MtzPaths.ICONS]))
        assertTrue("res/drawable-xxhdpi/com.android.chrome.png" in inner)
        assertTrue("res/drawable-xxhdpi/icon_mask.png" in inner)
        val rep = MtzInspector.inspect(bos.toByteArray())
        assertTrue(rep.isValid, rep.errors.toString())
        assertEquals("icons", rep.components["icons"])
        assertEquals(r.written + 3, rep.iconCount) // + background/mask/border
    }

    @Test fun inspectorFlagsBrokenMtz() {
        val bos = ByteArrayOutputStream()
        MtzWriter(bos).use { it.add(MtzPaths.FONT, "nope".toByteArray()) }
        val rep = MtzInspector.inspect(bos.toByteArray())
        assertTrue(!rep.isValid)
        assertTrue(rep.errors.any { "description.xml" in it })
        assertTrue(rep.errors.any { "no es una fuente" in it })
    }

    @Test fun writerRejectsUnsafePaths() {
        MtzWriter(ByteArrayOutputStream()).use { w ->
            assertFailsWith<IllegalArgumentException> { w.add("../evil", byteArrayOf()) }
            w.add("a/b", byteArrayOf())
            assertFailsWith<IllegalArgumentException> { w.add("a/b", byteArrayOf()) }
        }
    }

    @Test fun sampleIconPackAppFilterIsUnderstood() {
        val f = AppFilter.parseXml(File("../sample-iconpack/src/main/res/xml/appfilter.xml").readBytes())
        assertEquals(0.85f, f.scale)
        val plan = IconMapping.plan(f)
        assertEquals("ic_contacts", plan["com.android.contacts.png"])
        assertEquals("ic_phone", plan["com.android.contacts.activities.TwelveKeyDialer.png"])
        assertEquals("ic_themes", plan["com.android.thememanager.png"])
        assertTrue(plan.keys.none { it.startsWith(":") })
        val bos = ByteArrayOutputStream()
        val tmpDir = kotlin.io.path.createTempDirectory("mtzforge").toFile()
        val r = IconMtzBuilder.build(f, IconMtzOptions(ThemeDescription("Forge Sample Icons")), Previews.NONE, bos, tempDir = tmpDir) { d ->
            if (d == "ic_does_not_exist") null else byteArrayOf(1, 2, 3)
        }
        assertEquals(listOf("ic_does_not_exist"), r.missing)
        assertEquals(0, tmpDir.listFiles()!!.size, "el ZIP temporal debe borrarse")
        val rep = MtzInspector.inspect(bos.toByteArray())
        assertTrue(rep.isValid, rep.errors.toString())
        assertEquals(r.written + 3, rep.iconCount) // + icon_background, icon_mask, icon_border
    }
}
