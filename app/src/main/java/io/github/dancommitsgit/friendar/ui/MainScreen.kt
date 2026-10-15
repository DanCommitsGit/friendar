package io.github.dancommitsgit.friendar.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.dancommitsgit.friendar.R
import io.github.dancommitsgit.friendar.finder.FinderState
import io.github.dancommitsgit.friendar.finder.Phase
import io.github.dancommitsgit.friendar.fusion.DirectionSource
import io.github.dancommitsgit.friendar.fusion.EstimateSource
import io.github.dancommitsgit.friendar.fusion.FriendEstimate
import io.github.dancommitsgit.friendar.fusion.RangeSource
import io.github.dancommitsgit.friendar.fusion.Trend
import io.github.dancommitsgit.friendar.fusion.isClose

/**
 * One screen for both states. The top bar and the action button stay put; the middle swaps
 * between the idle hero and the live search, and the button changes from Find to Stop.
 */
@Composable
internal fun MainScreen(
    state: FinderState,
    showMap: Boolean,
    onShowMap: (Boolean) -> Unit,
    permissionDenied: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onMetric: (Boolean) -> Unit,
    onSettings: () -> Unit,
) {
    val searching = state.phase != Phase.Idle
    val view = LocalView.current
    DisposableEffect(view, searching) {
        view.keepScreenOn = searching
        onDispose { view.keepScreenOn = false }
    }
    val motion = MaterialTheme.motionScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onSettings) {
                Icon(AppIcons.Settings, contentDescription = stringResource(R.string.settings))
            }
        }
        AnimatedContent(
            targetState = searching,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.92f)) togetherWith
                    (fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 0.96f))
            },
            label = "main",
        ) { active ->
            if (active) {
                SearchContent(state, showMap, onShowMap, onMetric)
            } else {
                IdleContent(permissionDenied)
            }
        }
        Spacer(Modifier.height(16.dp))
        PrimaryAction(searching, onStart, onStop)
        Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun IdleContent(permissionDenied: Boolean) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialShapes.Cookie9Sided.toShape()
    val turn = rememberInfiniteTransition(label = "hero")
    val rotation by turn.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(60_000, easing = LinearEasing), RepeatMode.Restart),
        label = "rotation",
    )
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.size(200.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationZ = rotation }
                    .clip(shape)
                    .background(scheme.primaryContainer),
            )
            Arrow(angleDeg = 0f, color = scheme.onPrimaryContainer, modifier = Modifier.size(84.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmallEmphasized,
            textAlign = TextAlign.Center,
        )
        AnimatedVisibility(
            visible = permissionDenied,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 24.dp)) {
                Text(
                    stringResource(R.string.permission_needed),
                    color = MaterialTheme.warning,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = { openAppSettings(context) }) {
                    Text(stringResource(R.string.open_settings))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PrimaryAction(searching: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val container by animateColorAsState(
        if (searching) scheme.secondaryContainer else scheme.primary,
        motion.defaultEffectsSpec(),
        label = "container",
    )
    val content by animateColorAsState(
        if (searching) scheme.onSecondaryContainer else scheme.onPrimary,
        motion.defaultEffectsSpec(),
        label = "content",
    )
    Button(
        onClick = { if (searching) onStop() else onStart() },
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ButtonDefaults.MediumContainerHeight),
    ) {
        AnimatedContent(
            targetState = searching,
            transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
            label = "action",
        ) { active ->
            Text(
                stringResource(if (active) R.string.stop else R.string.home_find),
                style = MaterialTheme.typography.titleLargeEmphasized,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchContent(
    state: FinderState,
    showMap: Boolean,
    onShowMap: (Boolean) -> Unit,
    onMetric: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val close = isClose(state.distanceM)
    val arrowColor by animateColorAsState(
        when {
            close -> MaterialTheme.success
            state.directionLive && state.arrowSource == DirectionSource.Uwb -> scheme.primary
            else -> MaterialTheme.warning
        },
        motion.defaultEffectsSpec(),
        label = "arrowColor",
    )
    val showArrow = state.phase == Phase.Ranging && state.arrowDeg != null
    val roughDistance = state.distanceM == null && state.rangeSource == RangeSource.Bluetooth
    val graphicSize = if (state.debug) 160.dp else 240.dp

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().animateContentSize(motion.defaultSpatialSpec()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedContent(
                targetState = statusLine(state),
                transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
                label = "status",
            ) { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val note = state.hint ?: state.message.takeIf {
                state.phase == Phase.Ranging || state.phase == Phase.Waiting
            }
            if (note != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    note,
                    color = MaterialTheme.warning,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            ToggleButton(
                checked = !showMap,
                onCheckedChange = { onShowMap(false) },
                colors = connectedToggleColors(),
                shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
            ) { Text(stringResource(R.string.chip_arrow)) }
            ToggleButton(
                checked = showMap,
                onCheckedChange = { onShowMap(true) },
                colors = connectedToggleColors(),
                shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
            ) { Text(stringResource(R.string.chip_map)) }
        }

        Spacer(Modifier.weight(1f))

        AnimatedContent(
            targetState = when {
                showMap -> Graphic.Map
                showArrow -> Graphic.Arrow
                else -> Graphic.Waiting
            },
            transitionSpec = {
                (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.85f)) togetherWith
                    fadeOut(motion.fastEffectsSpec())
            },
            label = "graphic",
        ) { graphic ->
            when (graphic) {
                Graphic.Map -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MapView(state, modifier = Modifier.fillMaxWidth().height(if (state.debug) 200.dp else 280.dp))
                    mapNote(state)?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            it,
                            color = scheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                Graphic.Arrow -> Arrow(
                    angleDeg = state.arrowDeg ?: 0f,
                    color = arrowColor,
                    modifier = Modifier.size(graphicSize),
                )

                Graphic.Waiting -> Box(modifier = Modifier.size(graphicSize), contentAlignment = Alignment.Center) {
                    LoadingIndicator(modifier = Modifier.size(96.dp))
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        val swapTarget = swapDistanceText(state)
        DistanceReadout(
            primary = distanceText(state),
            secondary = swapTarget ?: sourceLine(state),
            swappable = swapTarget != null,
            primaryColor = when {
                close -> MaterialTheme.success
                state.distanceM == null && state.rangeM == null -> scheme.onSurfaceVariant
                else -> scheme.onBackground
            },
            primarySize = if (roughDistance) 40.sp else 56.sp,
            metric = state.metric,
            onSwap = { onMetric(!state.metric) },
        )
        levelLine(state)?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        }
        if (state.debug) {
            Spacer(Modifier.height(4.dp))
            Text(debugLine(state), color = scheme.onSurfaceVariant, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }

        Spacer(Modifier.weight(1f))

        if (state.debug) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
            ) {
                state.frameInfo?.let {
                    Text(it, color = scheme.onSurfaceVariant, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                state.rawReading?.let {
                    Text(it, color = scheme.onSurfaceVariant, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                state.log.forEach {
                    Text(it, color = scheme.onSurfaceVariant, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

private enum class Graphic { Arrow, Map, Waiting }

@Composable
private fun Main(initial: FinderState, showMap: Boolean = false, permissionDenied: Boolean = false) {
    var state by remember { mutableStateOf(initial) }
    var map by remember { mutableStateOf(showMap) }
    PreviewTheme {
        MainScreen(
            state = state,
            showMap = map,
            onShowMap = { map = it },
            permissionDenied = permissionDenied,
            onStart = { state = FinderState(phase = Phase.Waiting, metric = state.metric) },
            onStop = { state = FinderState(metric = state.metric) },
            onMetric = { state = state.copy(metric = it) },
            onSettings = {},
        )
    }
}

private val uwbRanging = FinderState(
    phase = Phase.Ranging,
    distanceM = 4.2f,
    rangeM = 4.2f,
    rangeSource = RangeSource.Uwb,
    arrowDeg = 35f,
    directionLive = true,
    arrowSource = DirectionSource.Uwb,
    uwbActive = true,
    friendGps = true,
    headingDeg = 20f,
    friend = FriendEstimate(eastM = 2.4f, northM = 3.4f, sigmaM = 0.8f, source = EstimateSource.RangeAndBearing),
)

@PhonePreviews
@Composable
private fun MainIdlePreview() = Main(FinderState())

@PhonePreviews
@Composable
private fun MainPermissionDeniedPreview() = Main(FinderState(), permissionDenied = true)

@PhonePreviews
@Composable
private fun MainWaitingPreview() = Main(FinderState(phase = Phase.Waiting))

@PhonePreviews
@Composable
private fun MainFailedPreview() = Main(FinderState(phase = Phase.Failed, message = "Bluetooth is turned off"))

@PhonePreviews
@Composable
private fun MainArrowPreview() = Main(uwbRanging)

@PhonePreviews
@Composable
private fun MainArrowImperialPreview() = Main(uwbRanging.copy(metric = false))

@PhonePreviews
@Composable
private fun MainClosePreview() = Main(uwbRanging.copy(distanceM = 0.9f, rangeM = 0.9f))

@PhonePreviews
@Composable
private fun MainBluetoothOnlyPreview() = Main(
    FinderState(
        phase = Phase.Ranging,
        rangeM = 9f,
        rangeSource = RangeSource.Bluetooth,
        rssiDbm = -71,
        signalDistanceM = 9f,
        trend = Trend.Closer,
        message = "Ultra-wideband is not available",
    ),
)

@PhonePreviews
@Composable
private fun MainMapPreview() = Main(uwbRanging, showMap = true)

@PhonePreviews
@Composable
private fun MainDebugPreview() = Main(
    uwbRanging.copy(
        debug = true,
        frameInfo = "North from compass, good to 10\u00b0",
        rawReading = "az 12.0\u00b0 el -3.0\u00b0 d 4.20 m",
        log = listOf("Linked as joiner", "UWB session started", "First reading"),
    ),
)
