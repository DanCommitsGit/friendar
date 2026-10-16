package io.github.dancommitsgit.friendar.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA3B0FF),
    onPrimary = Color(0xFF1E2A78),
    primaryContainer = Color(0xFF35409A),
    onPrimaryContainer = Color(0xFFE0E4FF),
    secondary = Color(0xFFC4C9E3),
    onSecondary = Color(0xFF2C3150),
    secondaryContainer = Color(0xFF333A57),
    onSecondaryContainer = Color(0xFFE4E7F7),
    tertiary = Color(0xFF4ADE80),
    onTertiary = Color(0xFF00391B),
    tertiaryContainer = Color(0xFF0E4D2A),
    onTertiaryContainer = Color(0xFFB5F5CC),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1119),
    onBackground = Color(0xFFECEEF7),
    surface = Color(0xFF0F1119),
    onSurface = Color(0xFFECEEF7),
    surfaceVariant = Color(0xFF262A3A),
    onSurfaceVariant = Color(0xFFC2C6D8),
    inverseSurface = Color(0xFFE4E7F2),
    inverseOnSurface = Color(0xFF1B1E2B),
    inversePrimary = Color(0xFF4A58C6),
    outline = Color(0xFF858AA0),
    outlineVariant = Color(0xFF323650),
    surfaceDim = Color(0xFF0B0D14),
    surfaceBright = Color(0xFF2B2F40),
    surfaceContainerLowest = Color(0xFF090A10),
    surfaceContainerLow = Color(0xFF151824),
    surfaceContainer = Color(0xFF1A1D2A),
    surfaceContainerHigh = Color(0xFF222636),
    surfaceContainerHighest = Color(0xFF2C3042),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF4A58C6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E4FF),
    onPrimaryContainer = Color(0xFF1E2670),
    secondary = Color(0xFF5A5F78),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3E6F4),
    onSecondaryContainer = Color(0xFF1B1F33),
    tertiary = Color(0xFF15803D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC4F0D3),
    onTertiaryContainer = Color(0xFF00210E),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7F8FD),
    onBackground = Color(0xFF14161F),
    surface = Color(0xFFF7F8FD),
    onSurface = Color(0xFF14161F),
    surfaceVariant = Color(0xFFE3E5F1),
    onSurfaceVariant = Color(0xFF454A5E),
    inverseSurface = Color(0xFF2D303D),
    inverseOnSurface = Color(0xFFF2F3FA),
    inversePrimary = Color(0xFFA3B0FF),
    outline = Color(0xFF767B91),
    outlineVariant = Color(0xFFC7CADA),
    surfaceDim = Color(0xFFDBDDE8),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F2F9),
    surfaceContainer = Color(0xFFEBEDF6),
    surfaceContainerHigh = Color(0xFFE5E7F1),
    surfaceContainerHighest = Color(0xFFDFE1EC),
)

private val DarkWarning = Color(0xFFFFB74D)
private val LightWarning = Color(0xFF9A5B00)

private val LocalWarning = staticCompositionLocalOf { DarkWarning }

/** Approximate or slow readings and hints; the color scheme has no role for this. */
internal val MaterialTheme.warning: Color
    @Composable @ReadOnlyComposable get() = LocalWarning.current

/** The distance is close enough to say the friend is here; stored as the tertiary role. */
internal val MaterialTheme.success: Color
    @Composable @ReadOnlyComposable get() = colorScheme.tertiary

/** Unselected toggle buttons stand out from both the page and the cards. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun connectedToggleColors() = ToggleButtonDefaults.colors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FriendarTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalWarning provides if (dark) DarkWarning else LightWarning) {
        MaterialExpressiveTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}
