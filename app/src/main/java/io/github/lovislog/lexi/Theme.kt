package io.github.lovislog.lexi

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Sky = Color(0xFFF8FAFE)
private val Ink = Color(0xFF0F172A)
private val Slate = Color(0xFF475569)
private val Shadow = Color(0xFF647FA3)

private fun palette(accent: Accent) = lightColorScheme(
    primary = when (accent) {
        Accent.BLUE -> Color(0xFF1A73E8)
        Accent.VIOLET -> Color(0xFF7C3AED)
        Accent.MINT -> Color(0xFF0D9488)
    },
    onPrimary = if (accent == Accent.MINT) Color(0xFF071F1B) else Color.White,
    primaryContainer = when (accent) {
        Accent.BLUE -> Color(0xFFE3EEFF)
        Accent.VIOLET -> Color(0xFFEDE5FF)
        Accent.MINT -> Color(0xFFD4F3EC)
    },
    onPrimaryContainer = when (accent) {
        Accent.BLUE -> Color(0xFF184B92)
        Accent.VIOLET -> Color(0xFF52249E)
        Accent.MINT -> Color(0xFF075C53)
    },
    secondary = Slate,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAF0F7),
    onSecondaryContainer = Ink,
    background = Sky,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF1F5FA),
    onSurfaceVariant = Slate,
    surfaceContainer = Color(0xFFF0F4FA),
    surfaceContainerHigh = Color(0xFFEAF0F7),
    surfaceContainerHighest = Color(0xFFE5EDF6),
    outline = Color(0xFF718197),
    outlineVariant = Color(0xFFE2E9F3),
)

private fun TextStyle.lexi() = copy(fontFamily = FontFamily.SansSerif, fontFeatureSettings = "tnum")
private val LexiTypography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.lexi(), displayMedium = t.displayMedium.lexi(), displaySmall = t.displaySmall.lexi(),
        headlineLarge = t.headlineLarge.lexi(), headlineMedium = t.headlineMedium.lexi(), headlineSmall = t.headlineSmall.lexi(),
        titleLarge = t.titleLarge.lexi(), titleMedium = t.titleMedium.lexi(), titleSmall = t.titleSmall.lexi(),
        bodyLarge = t.bodyLarge.lexi(), bodyMedium = t.bodyMedium.lexi(), bodySmall = t.bodySmall.lexi(),
        labelLarge = t.labelLarge.lexi(), labelMedium = t.labelMedium.lexi(), labelSmall = t.labelSmall.lexi(),
    )
}

@Composable
internal fun LexiTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val colors = remember(appearance.accent) { palette(appearance.accent) }
    CompositionLocalProvider(LocalAppearance provides appearance) {
        MaterialTheme(colorScheme = colors, typography = LexiTypography, content = content)
    }
}

@Composable
internal fun LookupReading(content: @Composable () -> Unit) {
    val base = MaterialTheme.typography
    val scale = LocalAppearance.current.readingSize.scale
    val typography = remember(base, scale) {
        fun TextStyle.scaled() = copy(
            fontSize = fontSize * scale,
            lineHeight = lineHeight * scale,
        )
        base.copy(
            headlineSmall = base.headlineSmall.scaled(),
            titleMedium = base.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp).scaled(),
            titleSmall = base.titleSmall.scaled(),
            bodyLarge = base.bodyLarge.scaled(),
            bodyMedium = base.bodyMedium.scaled(),
            bodySmall = base.bodySmall.scaled(),
            labelLarge = base.labelLarge.scaled(),
            labelMedium = base.labelMedium.scaled(),
        )
    }
    MaterialTheme(typography = typography, content = content)
}

@Composable
internal fun FrostedCard(
    modifier: Modifier = Modifier,
    radius: Int = 22,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(radius.dp)
    Surface(
        modifier.shadow(8.dp, shape, clip = false, ambientColor = Shadow.copy(alpha = 0.24f),
            spotColor = Shadow.copy(alpha = 0.22f)),
        shape = shape,
        color = Color.White.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.94f)),
        content = content,
    )
}
