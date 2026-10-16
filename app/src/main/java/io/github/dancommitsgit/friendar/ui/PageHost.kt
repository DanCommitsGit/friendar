package io.github.dancommitsgit.friendar.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** [depth] is how many steps below the main screen a page sits; moving to a higher depth slides forward. */
internal enum class Page(val depth: Int) { Main(0), Settings(1), Phone(2), Permissions(2) }

private val PageCorner = 28.dp

/** The properties of a page transition move linearly over this; the spring below decides how fast. */
private const val PAGE_MS = 450

/** How far through the transition a full back gesture drags the page; letting go finishes the rest. */
private const val GESTURE_REACH = 0.3f

/**
 * Shows the top of [stack] and animates pushes and pops. A back gesture drags the page away and
 * reveals the one below it, and lets go again if the gesture is cancelled.
 */
@Composable
internal fun PageHost(
    stack: List<Page>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Page) -> Unit,
) {
    val current = stack.last()
    val saveable = rememberSaveableStateHolder()
    val seekable = remember { SeekableTransitionState(current) }
    val transition = rememberTransition(seekable, label = "page")
    // Critically damped, so the fraction never passes 1 and the page never overshoots its place.
    val settle = remember { spring<Float>(dampingRatio = 1f, stiffness = 380f) }

    LaunchedEffect(current) { seekable.animateTo(current, settle) }

    PredictiveBackHandler(enabled = stack.size > 1) { events ->
        val below = stack[stack.size - 2]
        try {
            events.collect { event ->
                seekable.seekTo(event.progress * GESTURE_REACH, below)
            }
            onBack()
        } catch (e: CancellationException) {
            // animateTo(current) would start a new below-to-current transition and play the push
            // animations, so the fraction is rewound on the same transition instead.
            withContext(NonCancellable) {
                val fraction = Animatable(seekable.fraction)
                coroutineScope {
                    val follow = launch { snapshotFlow { fraction.value }.collect { seekable.seekTo(it, below) } }
                    fraction.animateTo(0f, settle)
                    follow.cancel()
                }
                seekable.snapTo(current)
            }
            throw e
        }
    }

    transition.AnimatedContent(
        modifier = modifier,
        transitionSpec = {
            val offset = tween<IntOffset>(PAGE_MS, easing = LinearEasing)
            val scale = tween<Float>(PAGE_MS, easing = LinearEasing)
            val quick = tween<Float>(PAGE_MS / 3, easing = LinearEasing)
            val forward = targetState.depth > initialState.depth
            // Going forward the new page slides over the old one; going back it is dragged away to
            // show the old one, which is not faded so the page under the finger stays solid.
            val enter = if (forward) {
                slideInHorizontally(offset) { it } + fadeIn(quick)
            } else {
                slideInHorizontally(offset) { -it / 4 }
            }
            val exit = if (forward) {
                slideOutHorizontally(offset) { -it / 4 } + fadeOut(quick)
            } else {
                slideOutHorizontally(offset) { it } + scaleOut(scale, targetScale = 0.9f)
            }
            // The page that is deeper in the stack stays on top while either one is moving.
            ContentTransform(enter, exit, targetContentZIndex = targetState.depth.toFloat(), sizeTransform = null)
        },
        contentKey = { it },
    ) { page ->
        // Corners and the dim on the page underneath arrive early so a short drag already shows an edge.
        val corner = this.transition.animateDp(
            transitionSpec = { tween(PAGE_MS / 3, easing = LinearEasing) },
            label = "corner",
        ) { if (it == EnterExitState.Visible) 0.dp else PageCorner }
        val dim = this.transition.animateFloat(
            transitionSpec = { tween(PAGE_MS / 2, easing = LinearEasing) },
            label = "dim",
        ) { if (it == EnterExitState.Visible) 0f else 0.32f }
        val underneath = page.depth < maxOf(seekable.currentState.depth, seekable.targetState.depth)
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val radius = corner.value.coerceAtLeast(0.dp)
                    shape = RoundedCornerShape(radius)
                    clip = radius > 0.dp
                    shadowElevation = (radius / PageCorner) * 16.dp.toPx()
                }
                .background(MaterialTheme.colorScheme.background)
                .drawWithContent {
                    drawContent()
                    if (underneath) drawRect(Color.Black, alpha = dim.value.coerceIn(0f, 1f))
                },
        ) {
            saveable.SaveableStateProvider(page) { content(page) }
        }
    }
}
