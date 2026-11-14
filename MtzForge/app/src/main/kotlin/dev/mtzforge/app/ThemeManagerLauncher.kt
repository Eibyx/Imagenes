package dev.mtzforge.app

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Integración con la app Temas de Xiaomi (paquete `com.android.thememanager`).
 *
 * Los nombres de componentes y extras proceden del análisis estático de zFont 3;
 * NO se han probado en un dispositivo desde este proyecto (ver docs/).
 */
object ThemeManagerLauncher {
    const val PKG = "com.android.thememanager"
    private const val TAB_ACTIVITY = "com.android.thememanager.activity.ThemeTabActivity"
    private const val SETTINGS_ACTIVITY = "com.android.thememanager.activity.ThemeSettingsActivity"
    private const val APPLY_ACTIVITY = "com.android.thememanager.ApplyThemeForScreenshot"
    private const val FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

    fun installedVersion(context: Context): String? = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(PKG, PackageManager.PackageInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.getPackageInfo(PKG, 0)
        info.versionName ?: "?"
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * Abre Temas. [resourceCode] = "fonts", "icons" o "theme" intenta ir a la pestaña
     * correspondiente (extra REQUEST_RESOURCE_CODE); si falla se prueban alternativas.
     */
    fun open(context: Context, resourceCode: String?): Boolean {
        val candidates = buildList {
            if (resourceCode != null) add(Intent(Intent.ACTION_VIEW).setComponent(ComponentName(PKG, TAB_ACTIVITY))
                .putExtra("REQUEST_RESOURCE_CODE", resourceCode))
            context.packageManager.getLaunchIntentForPackage(PKG)?.let { add(it) }
            add(Intent(Intent.ACTION_MAIN).setComponent(ComponentName(PKG, SETTINGS_ACTIVITY)))
            add(Intent(Settings.ACTION_DISPLAY_SETTINGS))
        }
        return startFirst(context, candidates)
    }

    /**
     * EXPERIMENTAL. Pide a Temas que aplique un MTZ desde una ruta de fichero,
     * usando la actividad `ApplyThemeForScreenshot` con los extras
     * `theme_file_path` y `api_called_from`. Requiere que Temas pueda leer la ruta
     * (por eso se exporta a Descargas). Puede no existir o estar bloqueada en
     * algunas versiones de HyperOS.
     */
    fun applyMtz(context: Context, absolutePath: String): Result<Unit> = runCatching {
        val i = Intent(Intent.ACTION_VIEW)
            .setComponent(ComponentName(PKG, APPLY_ACTIVITY))
            .putExtra("theme_file_path", absolutePath)
            .putExtra("api_called_from", "theme")
            .addFlags(FLAGS)
        context.startActivity(i)
    }

    /**
     * Abre un recurso de la biblioteca local de Temas por su id
     * (`ViewLocalResource://view.local.resource#<id>`, extras REQUEST_RESOURCE_CODE y
     * REQUEST_APPLY_EVENT, observados en zFont). No verificado en dispositivo.
     */
    fun openLocalResource(context: Context, localId: String, resourceCode: String): Boolean {
        val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("ViewLocalResource://view.local.resource#$localId"))
            .setPackage(PKG)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .putExtra("REQUEST_RESOURCE_CODE", resourceCode)
            .putExtra("REQUEST_APPLY_EVENT", true)
            .addFlags(FLAGS)
        return startFirst(context, listOf(i))
    }

    private fun startFirst(context: Context, intents: List<Intent>): Boolean {
        for (i in intents) {
            try {
                context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }
}
