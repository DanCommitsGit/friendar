package io.github.dancommitsgit.friendar

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FA2FF),
    onPrimary = Color(0xFF002E6E),
    primaryContainer = Color(0xFF1D4A9E),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFB4BDC9),
    onSecondary = Color(0xFF1B222B),
    secondaryContainer = Color(0xFF2D3541),
    onSecondaryContainer = Color(0xFFE3E8EF),
    tertiary = Color(0xFF4ADE80),
    onTertiary = Color(0xFF00391B),
    tertiaryContainer = Color(0xFF0E4D2A),
    onTertiaryContainer = Color(0xFFB5F5CC),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFF1F4F8),
    surface = Color(0xFF0E1116),
    onSurface = Color(0xFFF1F4F8),
    surfaceVariant = Color(0xFF232A35),
    onSurfaceVariant = Color(0xFFB4BDC9),
    inverseSurface = Color(0xFFE3E8EF),
    inverseOnSurface = Color(0xFF1B222B),
    inversePrimary = Color(0xFF1F5FD6),
    outline = Color(0xFF7C8796),
    outlineVariant = Color(0xFF2D3541),
    surfaceDim = Color(0xFF0A0D11),
    surfaceBright = Color(0xFF2A313C),
    surfaceContainerLowest = Color(0xFF090B0E),
    surfaceContainerLow = Color(0xFF141920),
    surfaceContainer = Color(0xFF191F27),
    surfaceContainerHigh = Color(0xFF202732),
    surfaceContainerHighest = Color(0xFF2A313C),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F5FD6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E4FF),
    onPrimaryContainer = Color(0xFF00184A),
    secondary = Color(0xFF4B5563),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1E7EF),
    onSecondaryContainer = Color(0xFF151B24),
    tertiary = Color(0xFF15803D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC4F0D3),
    onTertiaryContainer = Color(0xFF00210E),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6F8FB),
    onBackground = Color(0xFF0E1116),
    surface = Color(0xFFF6F8FB),
    onSurface = Color(0xFF0E1116),
    surfaceVariant = Color(0xFFE1E7EF),
    onSurfaceVariant = Color(0xFF4B5563),
    inverseSurface = Color(0xFF232A35),
    inverseOnSurface = Color(0xFFF1F4F8),
    inversePrimary = Color(0xFF6FA2FF),
    outline = Color(0xFF727C8A),
    outlineVariant = Color(0xFFC8D0DB),
    surfaceDim = Color(0xFFD9DEE6),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F3F8),
    surfaceContainer = Color(0xFFEAEEF4),
    surfaceContainerHigh = Color(0xFFE4E9F0),
    surfaceContainerHighest = Color(0xFFDDE3EB),
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
