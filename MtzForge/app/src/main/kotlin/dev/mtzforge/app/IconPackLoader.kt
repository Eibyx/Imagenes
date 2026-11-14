package dev.mtzforge.app

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.DisplayMetrics
import dev.mtzforge.core.AppFilter
import dev.mtzforge.core.ApkAppFilter
import java.io.File
import org.xmlpull.v1.XmlPullParser

/**
 * Lee packs de iconos con formato appfilter (ADW/Nova/etc.), instalados o desde
 * un fichero APK. Implementación propia que usa solo APIs públicas de Android.
 */
class IconPack(val packageName: String, val label: String, private val res: Resources, private val apkFile: File? = null) {

    fun loadAppFilter(): AppFilter {
        // 1) res/xml/appfilter.xml (XML binario compilado)
        val id = res.getIdentifier("appfilter", "xml", packageName)
        if (id != 0) {
            res.getXml(id).use { parser ->
                val elements = sequence {
                    while (true) {
                        val ev = parser.next()
                        if (ev == XmlPullParser.END_DOCUMENT) break
                        if (ev == XmlPullParser.START_TAG) {
                            val attrs = (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) }
                            yield(parser.name to attrs)
                        }
                    }
                }
                val f = AppFilter.fromElements(elements.toList().asSequence())
                if (f.items.isNotEmpty()) return f
            }
        }
        // 2) assets/appfilter.xml (texto plano)
        runCatching { res.assets.open("appfilter.xml").use { AppFilter.parseXml(it.readBytes()) } }
            .getOrNull()?.takeIf { it.items.isNotEmpty() }?.let { return it }
        // 3) Leer el APK como ZIP y buscar cualquier appfilter.xml (binario o texto)
        apkFile?.takeIf { it.isFile }?.let { f -> ApkAppFilter.find(f)?.let { return it.filter } }
        throw IllegalArgumentException("No se encontró appfilter.xml en $label. Este APK no parece un pack de iconos compatible (formato Nova/ADW).")
    }

    private fun drawableId(name: String): Int =
        res.getIdentifier(name, "drawable", packageName).takeIf { it != 0 }
            ?: res.getIdentifier(name, "mipmap", packageName)

    /** Renderiza el drawable a un bitmap cuadrado de [size] px (admite vectores/adaptativos). */
    fun render(name: String, size: Int): Bitmap? {
        val id = drawableId(name)
        if (id == 0) return null
        val d: Drawable = runCatching { res.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXHIGH, null) }.getOrNull()
            ?: runCatching { res.getDrawable(id, null) }.getOrNull() ?: return null
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        return bmp
    }

    companion object {
        /** Acciones de intent con las que los packs de iconos se anuncian. */
        val ICON_PACK_ACTIONS = listOf(
            "org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.gau.go.launcherex.theme",
            "com.teslacoilsw.launcher.THEME", "com.anddoes.launcher.THEME",
        )

        data class Installed(val packageName: String, val label: String)

        /** Paquetes con icono en el lanzador (requiere <queries> MAIN/LAUNCHER). */
        fun launchablePackages(context: Context): Set<String> {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val pm = context.packageManager
            val list = if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(i, PackageManager.ResolveInfoFlags.of(0))
            else @Suppress("DEPRECATION") pm.queryIntentActivities(i, 0)
            return list.mapTo(HashSet()) { it.activityInfo.packageName }
        }

        fun listInstalled(context: Context): List<Installed> {
            val pm = context.packageManager
            val seen = LinkedHashMap<String, Installed>()
            for (action in ICON_PACK_ACTIONS) {
                val list = if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(Intent(action), PackageManager.ResolveInfoFlags.of(0))
                else @Suppress("DEPRECATION") pm.queryIntentActivities(Intent(action), 0)
                for (ri in list) {
                    val pkg = ri.activityInfo.packageName
                    if (pkg !in seen) seen[pkg] = Installed(pkg, ri.loadLabel(pm).toString())
                }
            }
            return seen.values.sortedBy { it.label.lowercase() }
        }

        fun fromInstalled(context: Context, pkg: String): IconPack {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            return IconPack(pkg, pm.getApplicationLabel(ai).toString(), pm.getResourcesForApplication(ai), File(ai.sourceDir))
        }

        /**
         * Carga un APK no instalado: se copia a la caché y se abren sus recursos con
         * getPackageArchiveInfo + getResourcesForApplication (sourceDir apuntando al fichero).
         */
        fun fromApkUri(context: Context, uri: Uri): IconPack {
            val raw = File(context.cacheDir, "iconpack_input.bin")
            context.contentResolver.openInputStream(uri)!!.use { input -> raw.outputStream().use { input.copyTo(it) } }
            if (!ApkAppFilter.isZip(raw)) throw IllegalArgumentException("El fichero elegido no es un APK")
            val file = File(context.cacheDir, "iconpack.apk")
            // Paquetes .apks / .xapk / .apkm: contienen varios APK; se usa el base.
            val inner = ApkAppFilter.innerApks(raw)
            if (inner.isNotEmpty() && ZipFileHasNoManifest(raw)) ApkAppFilter.extractBaseApk(raw, file) else raw.copyTo(file, overwrite = true)
            raw.delete()
            return fromApkFile(context, file)
        }

        @Suppress("FunctionName")
        private fun ZipFileHasNoManifest(f: File) = java.util.zip.ZipFile(f).use { it.getEntry("AndroidManifest.xml") == null }

        fun fromApkFile(context: Context, file: File): IconPack {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val info = pm.getPackageArchiveInfo(file.absolutePath, 0)
                ?: throw IllegalArgumentException("El fichero no es un APK válido")
            val ai: ApplicationInfo = info.applicationInfo ?: throw IllegalArgumentException("APK sin ApplicationInfo")
            ai.sourceDir = file.absolutePath
            ai.publicSourceDir = file.absolutePath
            val res = pm.getResourcesForApplication(ai)
            val label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(info.packageName)
            return IconPack(info.packageName, label, res, file)
        }
    }
}
