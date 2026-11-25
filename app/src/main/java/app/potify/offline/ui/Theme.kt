package app.potify.offline.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Sage: soft green-grey surfaces with a deep sage accent, and a minimal dark mode. */
private val SageLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF2E7A67),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEDE6),
    onPrimaryContainer = Color(0xFF0F3B30),
    inversePrimary = Color(0xFF5FB89F),
    secondary = Color(0xFF2E7A67),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDEDE6),
    onSecondaryContainer = Color(0xFF0F3B30),
    tertiary = Color(0xFF5B6F8A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDE5F0),
    onTertiaryContainer = Color(0xFF1B2A3D),
    background = Color(0xFFF3F6F3),
    onBackground = Color(0xFF1B2620),
    surface = Color(0xFFF3F6F3),
    onSurface = Color(0xFF1B2620),
    surfaceVariant = Color(0xFFE6ECE8),
    onSurfaceVariant = Color(0xFF66766D),
    surfaceTint = Color(0xFF2E7A67),
    inverseSurface = Color(0xFF26302B),
    inverseOnSurface = Color(0xFFEFF3F0),
    error = Color(0xFFB4433A),
    onError = Color.White,
    errorContainer = Color(0xFFF7DEDA),
    onErrorContainer = Color(0xFF5C1610),
    outline = Color(0xFFB9C6BF),
    outlineVariant = Color(0xFFE0E8E3),
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFE2E8E4),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFCFA),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEDF2EE),
    surfaceContainerHighest = Color(0xFFE6ECE8),
)

private val SageDark: ColorScheme = darkColorScheme(
    primary = Color(0xFF5FB89F),
    onPrimary = Color(0xFF06261E),
    primaryContainer = Color(0xFF1D3530),
    onPrimaryContainer = Color(0xFFCDEBE0),
    inversePrimary = Color(0xFF2E7A67),
    secondary = Color(0xFF5FB89F),
    onSecondary = Color(0xFF06261E),
    secondaryContainer = Color(0xFF1D3530),
    onSecondaryContainer = Color(0xFFCDEBE0),
    tertiary = Color(0xFFA9BCD8),
    onTertiary = Color(0xFF15233A),
    tertiaryContainer = Color(0xFF2A3A52),
    onTertiaryContainer = Color(0xFFDDE5F0),
    background = Color(0xFF101312),
    onBackground = Color(0xFFEEF2EF),
    surface = Color(0xFF101312),
    onSurface = Color(0xFFEEF2EF),
    surfaceVariant = Color(0xFF232926),
    onSurfaceVariant = Color(0xFF97A59C),
    surfaceTint = Color(0xFF5FB89F),
    inverseSurface = Color(0xFFE6ECE8),
    inverseOnSurface = Color(0xFF1B2620),
    error = Color(0xFFF0A49B),
    onError = Color(0xFF4A120C),
    errorContainer = Color(0xFF5C1E17),
    onErrorContainer = Color(0xFFF7DEDA),
    outline = Color(0xFF46504B),
    outlineVariant = Color(0xFF29302C),
    scrim = Color.Black,
    surfaceBright = Color(0xFF2A302D),
    surfaceDim = Color(0xFF101312),
    surfaceContainerLowest = Color(0xFF0B0E0D),
    surfaceContainerLow = Color(0xFF151917),
    surfaceContainer = Color(0xFF1A1F1C),
    surfaceContainerHigh = Color(0xFF212724),
    surfaceContainerHighest = Color(0xFF29302C),
)

/** Placeholder tile colors for tracks without artwork. */
data class ArtPalette(val tiles: List<Color>, val onTile: Color)

private val LightArt = ArtPalette(
    listOf(Color(0xFFCFE4D7), Color(0xFFC8D7EA), Color(0xFFDCCBEA), Color(0xFFEBD5BC)),
    Color(0xFF1B2620).copy(alpha = 0.55f),
)
private val DarkArt = ArtPalette(
    listOf(Color(0xFF26402F), Color(0xFF27384D), Color(0xFF3A2E4D), Color(0xFF4A3828)),
    Color(0xFFEEF2EF).copy(alpha = 0.6f),
)

val LocalArtPalette = staticCompositionLocalOf { LightArt }

private val PotifyTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, letterSpacing = (-0.2).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.Medium),
        bodyMedium = bodyMedium.copy(letterSpacing = 0.1.sp),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

private val PotifyShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun PotifyTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalArtPalette provides if (dark) DarkArt else LightArt) {
        MaterialTheme(
            colorScheme = if (dark) SageDark else SageLight,
            typography = PotifyTypography,
            shapes = PotifyShapes,
            content = content,
        )
    }
}

val Muted: TextStyle
    @Composable get() = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
