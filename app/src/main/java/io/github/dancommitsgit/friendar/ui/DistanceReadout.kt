package io.github.dancommitsgit.friendar.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.lerp as lerpColor

/**
 * The main distance with a smaller line under it. When [swappable], tapping trades the two
 * places before [onSwap] changes the unit.
 */
@Composable
internal fun DistanceReadout(
    primary: String,
    secondary: String?,
    swappable: Boolean,
    primaryColor: Color,
    primarySize: TextUnit,
    metric: Boolean,
    onSwap: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var primaryHeight by remember { mutableFloatStateOf(0f) }
    var secondaryHeight by remember { mutableFloatStateOf(0f) }
    val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) { 4.dp.toPx() }
    val shrink = SECONDARY_SIZE.value / primarySize.value
    val travel = primaryHeight / 2 + gapPx + secondaryHeight / 2
    val p = progress.value

    // Once the unit has changed, the swapped layout and the reset one look the same.
    LaunchedEffect(metric) { progress.snapTo(0f) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(
            enabled = swappable,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) {
            scope.launch {
                progress.animateTo(1f, tween(SWAP_MS))
                onSwap()
            }
        },
    ) {
        Text(
            text = primary,
            fontSize = primarySize,
            fontWeight = FontWeight.Bold,
            color = lerpColor(primaryColor, muted, p),
            modifier = Modifier
                .onSizeChanged { primaryHeight = it.height.toFloat() }
                .graphicsLayer {
                    translationY = p * travel
                    scaleX = lerp(1f, shrink, p)
                    scaleY = lerp(1f, shrink, p)
                },
        )
        if (secondary != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = secondary,
                fontSize = SECONDARY_SIZE,
                color = lerpColor(muted, primaryColor, p),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .onSizeChanged { secondaryHeight = it.height.toFloat() }
                    .graphicsLayer {
                        translationY = -p * travel
                        scaleX = lerp(1f, 1f / shrink, p)
                        scaleY = lerp(1f, 1f / shrink, p)
                    },
            )
        }
    }
}

private val SECONDARY_SIZE = 16.sp
private const val SWAP_MS = 260
