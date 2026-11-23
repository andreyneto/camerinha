package dev.andreyneto.camerinha

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Shapes
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

/** Container tones the tv ColorScheme has no roles for. */
object Tones {
    val containerLow = Color(0xFF271813)
    val containerHigh = Color(0xFF362621)
    val containerHighest = Color(0xFF42312C)

    /** Behind text drawn over live video. */
    val videoScrim = Color.Black.copy(alpha = 0.72f)

    /** Cards that float over the grid, where gutters and focus rings would show through a scrim. */
    val floatingCard = Color(0xF22B1C17)
}

/** TV overscan safe area (on a 960x540dp screen): text and controls stay inside it. */
object Safe {
    val horizontal = 48.dp
    val vertical = 27.dp
}

/** One focus rule everywhere: rows grow a little, buttons a bit more; video tiles never scale. */
object FocusScale {
    const val ROW = 1.025f
    const val BUTTON = 1.05f
}

/**
 * Brand orange #FF5A1F (the owner's personal colour, from agent-app) as primary, exact; the rest is
 * the dark Material 3 "vibrant" scheme generated from it (2021 spec, as Flutter's fromSeed does).
 * The generated primary is a pale peach, so the brand orange replaces it, with near-black text
 * on top (6.3:1 contrast). The video stage stays pure black.
 */
private val CamerinhaColors = darkColorScheme(
    primary = Color(0xFFFF5A1F),
    onPrimary = Color(0xFF1E0700),
    primaryContainer = Color(0xFF852400),
    onPrimaryContainer = Color(0xFFFFDBD0),
    secondary = Color(0xFFF1BB98),
    onSecondary = Color(0xFF49280F),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF9DCD4),
    surface = Color(0xFF1E100C),
    onSurface = Color(0xFFF9DCD4),
    surfaceVariant = Color(0xFF58423B),
    onSurfaceVariant = Color(0xFFDFC0B6),
    border = Color(0xFFA78A82),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * Google Sans Flex (SIL OFL 1.1, see fonts/GoogleSansFlex-OFL.txt), bundled as the variable font:
 * google/fonts ships no static instances. Compose applies the weight axis on API 26+.
 */
private val GoogleSansFlex = FontFamily(
    Font(
        R.font.google_sans_flex,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.google_sans_flex,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Float = 0f) = TextStyle(
    fontFamily = GoogleSansFlex,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

private val CamerinhaTypography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = GoogleSansFlex, fontWeight = FontWeight.Bold),
        displayMedium = base.displayMedium.copy(fontFamily = GoogleSansFlex, fontWeight = FontWeight.Bold),
        // Pane titles.
        displaySmall = style(36, 44, FontWeight.Bold),
        headlineLarge = base.headlineLarge.copy(fontFamily = GoogleSansFlex, fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontFamily = GoogleSansFlex, fontWeight = FontWeight.Bold),
        // Empty and error titles.
        headlineSmall = style(24, 32, FontWeight.Bold),
        // Camera name in the switch banner.
        titleLarge = style(22, 28, FontWeight.Bold),
        // Row headlines, section headers.
        titleMedium = style(18, 24, FontWeight.Bold),
        titleSmall = style(16, 22, FontWeight.Bold),
        // Row values.
        bodyLarge = style(16, 24, FontWeight.Normal),
        // Hints.
        bodyMedium = style(14, 20, FontWeight.Normal),
        bodySmall = style(14, 20, FontWeight.Normal),
        // Buttons, video chips.
        labelLarge = style(16, 20, FontWeight.Bold),
        // Status eyebrow only; write it uppercase.
        labelMedium = style(12, 16, FontWeight.Bold, tracking = 1.5f),
        labelSmall = style(12, 16, FontWeight.Bold, tracking = 1.5f),
    )
}

private val CamerinhaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    // Tiles, rows, cards, error blocks.
    large = RoundedCornerShape(20.dp),
    // Buttons and dialogs.
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun CamerinhaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CamerinhaColors,
        typography = CamerinhaTypography,
        shapes = CamerinhaShapes,
        content = content,
    )
}
