package dev.mtzforge.app

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import dev.mtzforge.app.PreviewRenderer.encode
import dev.mtzforge.core.AppFilter
import dev.mtzforge.core.IconMtzBuilder
import dev.mtzforge.core.IconMtzOptions
import dev.mtzforge.core.IconsArchive
import dev.mtzforge.core.FontMtzBuilder
import dev.mtzforge.core.FontMtzOptions
import dev.mtzforge.core.MtzInspector
import dev.mtzforge.core.MtzPaths
import dev.mtzforge.core.Previews
import dev.mtzforge.core.ThemeDescription
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AndroidSideTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun testFont(): ByteArray =
        File("../core/src/test/resources/fonts/ForgeTestSans-Regular.ttf").readBytes()

    @Test fun previewsHaveExpectedFormatAndSize() {
        val tmp = File(app.cacheDir, "f.ttf").apply { writeBytes(testFont()) }
        val tf = Typeface.createFromFile(tmp)
        val small = PreviewRenderer.fontThumbnail("Forge Test Sans", tf)
        val big = PreviewRenderer.fontPreview("Forge Test Sans", tf)
        assertEquals(0x89.toByte(), small[0]); assertEquals('P'.code.toByte(), small[1])
        assertEquals(0xFF.toByte(), big[0]); assertEquals(0xD8.toByte(), big[1]) // JPEG SOI
        val b = BitmapFactory.decodeByteArray(big, 0, big.size)
        assertEquals(720, b.width); assertEquals(1280, b.height)
        val s = BitmapFactory.decodeByteArray(small, 0, small.size)
        assertEquals(480, s.width); assertEquals(80, s.height)
        // La miniatura debe contener píxeles dibujados (texto), no estar vacía.
        var painted = 0
        for (x in 0 until s.width step 2) for (y in 0 until s.height step 2) if (Color.alpha(s.getPixel(x, y)) > 0) painted++
        assertTrue("la miniatura no tiene texto dibujado", painted > 20)
        File(System.getProperty("user.dir"), "../samples").mkdirs()
        File("../samples/preview_fonts_0.jpg").writeBytes(big)
        File("../samples/preview_fonts_small_0.png").writeBytes(small)
    }

    @Test fun fullFontPipelineWithRealPreviews() {
        val tmp = File(app.cacheDir, "f.ttf").apply { writeBytes(testFont()) }
        val tf = Typeface.createFromFile(tmp)
        val previews = Previews(mapOf(
            MtzPaths.PREVIEW_FONT_SMALL to PreviewRenderer.fontThumbnail("Forge", tf),
            MtzPaths.PREVIEW_FONT to PreviewRenderer.fontPreview("Forge", tf),
        ))
        val bos = ByteArrayOutputStream()
        FontMtzBuilder.build(testFont(), FontMtzOptions(ThemeDescription("Forge Test Sans")), previews, bos)
        val rep = MtzInspector.inspect(bos.toByteArray())
        assertTrue(rep.errors.toString(), rep.isValid)
        assertTrue(rep.warnings.isEmpty())
        File("../samples/ForgeTestSans-con-previews.mtz").writeBytes(bos.toByteArray())
    }

    @Test fun iconGridRenders() {
        val icon = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val png = PreviewRenderer.iconGrid(List(10) { icon }, "Pack")
        val b = BitmapFactory.decodeByteArray(png, 0, png.size)
        assertEquals(720, b.width)
        assertEquals(Color.RED, b.getPixel(60 + 19 + 10, 200 + 10)) // primer icono
        assertNotNull(icon.encode(Bitmap.CompressFormat.PNG))
    }

    @Test fun applyIntentTargetsThemeManager() {
        assertTrue(ThemeManagerLauncher.applyMtz(app, "/storage/emulated/0/Download/MTZ Forge/x.mtz").isSuccess)
        val i: Intent = shadowOf(app).nextStartedActivity
        assertEquals("com.android.thememanager", i.component!!.packageName)
        assertEquals("com.android.thememanager.ApplyThemeForScreenshot", i.component!!.className)
        assertEquals("/storage/emulated/0/Download/MTZ Forge/x.mtz", i.getStringExtra("theme_file_path"))
        assertEquals("theme", i.getStringExtra("api_called_from"))
        assertTrue(i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun openThemesRequestsFontsTab() {
        assertTrue(ThemeManagerLauncher.open(app, "fonts"))
        val i = shadowOf(app).nextStartedActivity
        assertEquals("com.android.thememanager.activity.ThemeTabActivity", i.component!!.className)
        assertEquals("fonts", i.getStringExtra("REQUEST_RESOURCE_CODE"))
    }

    @Test fun sanitizesFileNames() {
        assertEquals("a_b_c.mtz", DownloadsExporter.sanitize("a/b:c"))
        assertEquals("tema.mtz", DownloadsExporter.sanitize("   "))
        assertEquals("x.MTZ", DownloadsExporter.sanitize("x.MTZ"))
    }

    /** MTZ de iconos con PNG reales (dibujados con Canvas) usando el appfilter del pack de muestra. */
    @Test fun iconMtzWithRealPngs() {
        val filter = AppFilter.parseXml(File("../sample-iconpack/src/main/res/xml/appfilter.xml").readBytes())
        fun draw(name: String, size: Int): Bitmap? {
            if (name == "ic_does_not_exist") return null
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(b)
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            p.color = Color.HSVToColor(floatArrayOf((name.hashCode() and 0xFFFF) % 360f, 0.6f, 0.85f))
            c.drawCircle(size / 2f, size / 2f, size * 0.46f, p)
            p.color = Color.WHITE; p.textAlign = android.graphics.Paint.Align.CENTER; p.textSize = size * 0.45f
            c.drawText(name.removePrefix("ic_").take(1).uppercase(), size / 2f, size * 0.66f, p)
            return b
        }
        val grid = PreviewRenderer.iconGrid(filter.drawables.mapNotNull { draw(it, 144) }, "Forge Sample Icons")
        val bos = ByteArrayOutputStream()
        val r = IconMtzBuilder.build(filter, IconMtzOptions(ThemeDescription("Forge Sample Icons")),
            Previews(mapOf(MtzPaths.PREVIEW_ICONS to grid)), bos) { d -> draw(d, 168)?.encode(Bitmap.CompressFormat.PNG) }
        assertEquals(listOf("ic_does_not_exist"), r.missing)
        val rep = MtzInspector.inspect(bos.toByteArray())
        assertTrue(rep.errors.toString(), rep.isValid)
        // Comprobar que los PNG internos se decodifican y miden 168x168
        val inner = java.util.zip.ZipInputStream(bos.toByteArray().inputStream()).use { z ->
            var data: ByteArray? = null
            while (true) { val e = z.nextEntry ?: break; if (e.name == MtzPaths.ICONS) data = z.readBytes() }
            data!!
        }
        val names = IconsArchive.list(inner)
        assertTrue(names.contains("res/drawable-xxhdpi/com.android.contacts.activities.TwelveKeyDialer.png"))
        java.util.zip.ZipInputStream(inner.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name.endsWith("com.whatsapp.png")) {
                    val bytes = z.readBytes(); val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    assertEquals(168, bmp.width); assertEquals(168, bmp.height)
                }
            }
        }
        File("../samples/ForgeSampleIcons.mtz").writeBytes(bos.toByteArray())
        File("../samples/preview_icons_0.png").writeBytes(grid)
    }
}
