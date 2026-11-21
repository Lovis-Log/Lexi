package io.github.lovislog.lexi

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF24513E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE9D7),
    onPrimaryContainer = Color(0xFF172E20),
    background = Color(0xFFF8F6EF),
    onBackground = Color(0xFF19251F),
    surface = Color(0xFFFFFDF6),
    onSurface = Color(0xFF19251F),
    surfaceVariant = Color(0xFFE7EBDF),
    onSurfaceVariant = Color(0xFF445147),
    outline = Color(0xFF6A7568),
    outlineVariant = Color(0xFFD3DACE),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFABCFB4),
    onPrimary = Color(0xFF173725),
    primaryContainer = Color(0xFF294636),
    onPrimaryContainer = Color(0xFFCDE9D1),
    background = Color(0xFF111915),
    onBackground = Color(0xFFE1E8DB),
    surface = Color(0xFF1B251D),
    onSurface = Color(0xFFE1E8DB),
    surfaceVariant = Color(0xFF2B392F),
    onSurfaceVariant = Color(0xFFBECBBE),
    outline = Color(0xFF87978C),
    outlineVariant = Color(0xFF3D4D41),
)

@Composable
internal fun LexiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Typography(), content = content)
}
