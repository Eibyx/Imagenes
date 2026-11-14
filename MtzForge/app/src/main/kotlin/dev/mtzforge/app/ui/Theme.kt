package dev.mtzforge.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val Light = lightColorScheme(
    primary = Color(0xFF5B45A8), onPrimary = Color.White,
    primaryContainer = Color(0xFFE7DEFF), onPrimaryContainer = Color(0xFF1B0063),
    secondary = Color(0xFF00897B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFB7F0E6), onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFF8A4F7D), surface = Color(0xFFFDF8FF), background = Color(0xFFFDF8FF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFCBBEFF), onPrimary = Color(0xFF2E1679),
    primaryContainer = Color(0xFF442D90), onPrimaryContainer = Color(0xFFE7DEFF),
    secondary = Color(0xFF7FD6C8), onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF005047), onSecondaryContainer = Color(0xFFB7F0E6),
    tertiary = Color(0xFFFDB0E8),
)

@Composable
fun MtzForgeTheme(dark: Boolean = isSystemInDarkTheme(), dynamic: Boolean = true, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)),
        content = content,
    )
}
