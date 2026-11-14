package dev.mtzforge.app

import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import dev.mtzforge.app.shizuku.ShizukuManager
import dev.mtzforge.app.ui.BatchScreen
import dev.mtzforge.app.ui.FontScreen
import dev.mtzforge.app.ui.IconScreen
import dev.mtzforge.app.ui.MtzForgeTheme
import dev.mtzforge.app.ui.ResultContent
import dev.mtzforge.app.ui.SetupScreen
import dev.mtzforge.core.FontMtzBuilder
import dev.mtzforge.core.FontMtzOptions
import dev.mtzforge.core.Previews
import dev.mtzforge.core.ThemeDescription
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UiTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun shot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView.rootView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        File("../docs/capturas").mkdirs()
        File("../docs/capturas/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun thumb() = Bitmap.createBitmap(480, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(0) }.let {
        val tf = android.graphics.Typeface.DEFAULT
        val png = PreviewRenderer.fontThumbnail("Forge Test Sans", tf)
        android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size).asImageBitmap()
    }

    @Test fun fontScreen() {
        val ui = FontUi(fileName = "ForgeTestSans-Regular.ttf", summary = "Forge Test Sans Regular · TRUETYPE · 64 glifos",
            warnings = listOf("La fuente contiene glifos de color (ejemplo)"), collectionSize = 2, thumbnail = thumb(), title = "Forge Test Sans")
        compose.setContent { MtzForgeTheme(dynamic = false) { FontScreen(ui, false, {}, {}, {}, {}) } }
        compose.onNodeWithText("Generar MTZ de fuente").assertExists()
        shot("1-fuentes")
    }

    @Test fun iconScreen() {
        val sample = List(8) { i -> Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.HSVToColor(floatArrayOf(i * 45f, .6f, .85f))) }.asImageBitmap() }
        val ui = IconUi(label = "Forge Sample Icons", packageName = "dev.mtzforge.sampleicons", summary = "19 entradas · 17 iconos únicos", title = "Forge Sample Icons", sample = sample)
        compose.setContent { MtzForgeTheme(dynamic = false) { IconScreen(ui, false, {}, {}, {}, {}) } }
        shot("2-iconos")
    }

    @Test fun batchScreen() {
        val items = listOf(BatchItem("Lato-Regular.ttf", true, "→ Lato Regular.mtz"), BatchItem("roto.ttf", false, "Cabecera SFNT desconocida"), BatchItem("Inter.ttc", null))
        compose.setContent { MtzForgeTheme(dynamic = false) { BatchScreen(items, true) {} } }
        shot("3-carpeta")
    }

    @Test fun setupScreenDark() {
        compose.setContent { MtzForgeTheme(dark = true, dynamic = false) { androidx.compose.material3.Surface { SetupScreen(ShizukuManager.Status.NO_PERMISSION, "6.1.2.3", {}, {}, {}, {}) } } }
        compose.onNodeWithText("Conceder permiso").assertExists()
        shot("4-ajustes-oscuro")
    }

    @Test fun resultSheetRoutesMethods() {
        val app = RuntimeEnvironment.getApplication()
        val out = Output(OutputKind.FONT, "Forge Test Sans",
            DownloadsExporter.Exported(android.net.Uri.EMPTY, "/storage/emulated/0/Download/MTZ Forge/Forge Test Sans.mtz", "Forge Test Sans.mtz"),
            File(app.cacheDir, "x.mtz"), listOf("Componentes: fonts · 15.2 KB"))
        val clicked = ArrayList<ApplyMethod>()
        compose.setContent { MtzForgeTheme(dynamic = false) { ResultContent(out, shizukuReady = false, hasLastFont = true, hasLastIcons = false, busy = false, {}, {}, { clicked += it }) } }
        shot("5-resultado")
        compose.onNodeWithText(ApplyMethod.MTZ_PACKAGE.title).performClick()
        compose.onNodeWithText(ApplyMethod.FONT_LIBRARY.title).performClick() // deshabilitado sin Shizuku
        assertEquals(listOf(ApplyMethod.MTZ_PACKAGE), clicked)
    }
}

/** En Android 9 (sin Shizuku) el instalador escribe directamente: se comprueba la estructura .data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InstallerTest {
    @Test fun installsFontIntoLibraryLayout() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val font = File("../core/src/test/resources/fonts/ForgeTestSans-Regular.ttf").readBytes()
        val mtz = java.io.ByteArrayOutputStream().also {
            FontMtzBuilder.build(font, FontMtzOptions(ThemeDescription("Forge Test Sans")), Previews.NONE, it)
        }.toByteArray()
        val shizuku = ShizukuManager(app)
        val installer = XiaomiInstaller(app, shizuku)
        @Suppress("DEPRECATION")
        assertEquals(File(Environment.getExternalStorageDirectory(), "MIUI/theme/.data"), installer.dataDir)
        val mtzFile = File(app.cacheDir, "in.mtz").apply { writeBytes(mtz) }
        val r = installer.installToLibrary(mtzFile, "fonts")
        val data = installer.dataDir
        val contents = data.walkTopDown().filter { it.isFile }.map { it.relativeTo(data).path }.toList()
        assertTrue(contents.toString(), contents.any { it.startsWith("content/fonts/") && it.endsWith(".mrc") })
        assertTrue(contents.any { it.startsWith("meta/theme/") })
        assertTrue("nunca licencias", contents.none { it.startsWith("rights") })
        val i = shadowOf(app).nextStartedActivity
        assertTrue(i.dataString!!.startsWith("ViewLocalResource://view.local.resource#"))
        assertEquals("fonts", i.getStringExtra("REQUEST_RESOURCE_CODE"))
        assertTrue(r.launched)
    }
}

/** Carga del pack de muestra real desde fichero y desde un paquete .apks (base.apk dentro). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IconPackLoadTest {
    private val apk = File("../dist/ForgeSampleIcons-debug.apk")

    @Test fun loadsAppFilterFromApkFile() {
        val app = RuntimeEnvironment.getApplication()
        val pack = IconPack.fromApkUri(app, android.net.Uri.fromFile(apk))
        assertEquals("dev.mtzforge.sampleicons", pack.packageName)
        val filter = pack.loadAppFilter()
        assertEquals(17, filter.items.size) // 16 válidas + 1 con drawable inexistente; la inválida se ignora
        val bmp = pack.render("ic_camera", 168)
        assertEquals(168, bmp!!.width)
    }

    @Test fun rejectsNonApkWithClearMessage() {
        val app = RuntimeEnvironment.getApplication()
        val ttf = File("../core/src/test/resources/fonts/ForgeTestSans-Regular.ttf")
        val e = runCatching { IconPack.fromApkUri(app, android.net.Uri.fromFile(ttf)) }.exceptionOrNull()!!
        assertEquals("El fichero elegido no es un APK", e.message)
    }
}
