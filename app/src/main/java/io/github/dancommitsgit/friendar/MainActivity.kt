package io.github.dancommitsgit.friendar

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch

internal val Blue = Color(0xFF6FA2FF)
internal val Green = Color(0xFF4ADE80)
internal val Amber = Color(0xFFFFB74D)
internal val Ink = Color(0xFF0E1116)
internal val OnInk = Color(0xFFF1F4F8)
internal val Muted = Color(0xFFB4BDC9)
internal val Raised = Color(0xFF232A35)

private val AppColors = darkColorScheme(
    primary = Blue,
    onPrimary = Ink,
    background = Ink,
    onBackground = OnInk,
    surface = Ink,
    onSurface = OnInk,
    secondaryContainer = Raised,
    onSecondaryContainer = OnInk,
    outline = Muted,
)

class MainActivity : ComponentActivity() {
    private val viewModel: FinderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    var page by rememberSaveable { mutableStateOf(Page.Main) }
                    BackHandler(enabled = page != Page.Main) {
                        page = if (page == Page.Settings) Page.Main else Page.Settings
                    }
                    when (page) {
                        Page.Main -> if (state.phase == Phase.Idle) {
                            HomeScreen(
                                onStart = viewModel::start,
                                onSettings = { page = Page.Settings },
                            )
                        } else {
                            FinderScreen(
                                state,
                                onStop = viewModel::stop,
                                onMetric = viewModel::setMetric,
                                onSettings = { page = Page.Settings },
                            )
                        }

                        Page.Settings -> SettingsScreen(
                            state,
                            onBack = { page = Page.Main },
                            onFlip = viewModel::setFlipMode,
                            onUnits = viewModel::setUnitMode,
                            onBackground = viewModel::setBackground,
                            onDebug = viewModel::setDebug,
                            onOpenPhone = { page = Page.Phone },
                            onOpenPermissions = { page = Page.Permissions },
                        )

                        Page.Phone -> PhoneScreen(onBack = { page = Page.Settings })
                        Page.Permissions -> PermissionsScreen(onBack = { page = Page.Settings })
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) viewModel.onAppHidden()
    }
}

private enum class Page { Main, Settings, Phone, Permissions }

private val requiredPermissions = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.BLUETOOTH_ADVERTISE,
    Manifest.permission.UWB_RANGING,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.NEARBY_WIFI_DEVICES,
    Manifest.permission.POST_NOTIFICATIONS,
)

private val bluetoothPermissions = setOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.BLUETOOTH_ADVERTISE,
)

@Composable
private fun HomeScreen(onStart: () -> Unit, onSettings: () -> Unit) {
    val context = LocalView.current.context
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        // Every other permission only adds a ranging method, so the app still runs without it.
        val bluetoothOk = bluetoothPermissions.all { grants[it] == true }
        denied = !bluetoothOk
        if (bluetoothOk) pending?.invoke()
    }

    fun withPermissions(action: () -> Unit) {
        val missing = requiredPermissions.any {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            pending = action
            launcher.launch(requiredPermissions)
        } else {
            action()
        }
    }

    Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        TextButton(onClick = onSettings, modifier = Modifier.align(Alignment.TopEnd)) {
            Text(stringResource(R.string.settings), color = Muted)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(48.dp))
            Button(
                onClick = { withPermissions(onStart) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
            ) {
                Text(stringResource(R.string.home_find), fontSize = 20.sp)
            }

            if (denied) {
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(R.string.permission_needed),
                    color = Amber,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = { openAppSettings(context) }) {
                    Text(stringResource(R.string.open_settings))
                }
            }
        }
    }
}

@Composable
private fun FinderScreen(
    state: FinderState,
    onStop: () -> Unit,
    onMetric: (Boolean) -> Unit,
    onSettings: () -> Unit,
) {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val close = isClose(state.distanceM)
    val arrowColor by animateColorAsState(
        when {
            close -> Green
            state.directionLive && state.arrowSource == DirectionSource.Uwb -> Blue
            else -> Amber
        },
        label = "arrowColor",
    )
    val showArrow = state.phase == Phase.Ranging && state.arrowDeg != null
    val roughDistance = state.distanceM == null && state.rangeSource == RangeSource.Bluetooth
    var showMap by rememberSaveable { mutableStateOf(false) }
    val graphicSize = if (state.debug) 160.dp else 240.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onSettings) {
                Text(stringResource(R.string.settings), color = Muted)
            }
        }
        Text(
            text = statusLine(state),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        val note = state.hint ?: state.message.takeIf {
            state.phase == Phase.Ranging || state.phase == Phase.Waiting
        }
        if (note != null) {
            Spacer(Modifier.height(8.dp))
            Text(note, color = Amber, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val chipColors = FilterChipDefaults.filterChipColors(
                labelColor = OnInk,
                selectedLabelColor = OnInk,
                selectedContainerColor = Raised,
            )
            FilterChip(
                selected = !showMap,
                onClick = { showMap = false },
                label = { Text(stringResource(R.string.chip_arrow)) },
                colors = chipColors,
            )
            FilterChip(
                selected = showMap,
                onClick = { showMap = true },
                label = { Text(stringResource(R.string.chip_map)) },
                colors = chipColors,
            )
        }

        Spacer(Modifier.weight(1f))

        if (showMap) {
            MapView(state, modifier = Modifier.fillMaxWidth().height(if (state.debug) 200.dp else 280.dp))
            mapNote(state)?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    color = Muted,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else if (showArrow) {
            Arrow(
                angleDeg = state.arrowDeg,
                color = arrowColor,
                modifier = Modifier.size(graphicSize),
            )
        } else {
            Spacer(Modifier.size(graphicSize))
        }
        Spacer(Modifier.height(16.dp))

        val swapTarget = swapDistanceText(state)
        DistanceReadout(
            primary = distanceText(state),
            secondary = swapTarget ?: sourceLine(state),
            swappable = swapTarget != null,
            primaryColor = if (close) Green else MaterialTheme.colorScheme.onBackground,
            primarySize = if (roughDistance) 40.sp else 56.sp,
            metric = state.metric,
            onSwap = { onMetric(!state.metric) },
        )
        levelLine(state)?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        }
        if (state.debug) {
            Spacer(Modifier.height(4.dp))
            Text(debugLine(state), color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
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
                    Text(it, color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                state.rawReading?.let {
                    Text(it, color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                state.log.forEach {
                    Text(it, color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Button(
            onClick = onStop,
            colors = ButtonDefaults.buttonColors(
                containerColor = Raised,
                contentColor = OnInk,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(stringResource(R.string.stop), fontSize = 18.sp)
        }
    }
}

/**
 * The main distance with a smaller line under it. When [swappable], tapping trades the two
 * places before [onSwap] changes the unit.
 */
@Composable
private fun DistanceReadout(
    primary: String,
    secondary: String?,
    swappable: Boolean,
    primaryColor: Color,
    primarySize: TextUnit,
    metric: Boolean,
    onSwap: () -> Unit,
) {
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
            color = lerpColor(primaryColor, Muted, p),
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
                color = lerpColor(Muted, primaryColor, p),
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

@Composable
private fun Arrow(angleDeg: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.rotate(angleDeg)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w / 2, h * 0.04f)
            lineTo(w * 0.86f, h * 0.9f)
            lineTo(w / 2, h * 0.7f)
            lineTo(w * 0.14f, h * 0.9f)
            close()
        }
        drawPath(path, color)
        drawCircle(Color.White.copy(alpha = 0.2f), radius = 6f, center = Offset(w / 2, h * 0.7f))
    }
}

private const val FEET_PER_METER = 3.28084f
private val MapFeetSteps = listOf(30, 60, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000)
private val MapMeterSteps = listOf(10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000)

private fun scaleLabel(extent: Int, metric: Boolean): String = when {
    metric && extent >= 1000 -> String.format(Locale.US, "%.1f km", extent / 1000f)
    metric -> "$extent m"
    extent >= 5280 -> String.format(Locale.US, "%.1f mi", extent / 5280f)
    else -> "$extent ft"
}

@Composable
private fun MapView(state: FinderState, modifier: Modifier = Modifier) {
    val friend = state.friend
    val northLabel = stringResource(R.string.map_north)
    val emptyLabel = stringResource(R.string.map_no_position)
    Canvas(modifier = modifier) {
        val cx = size.width / 2
        val cy = size.height / 2
        val half = min(size.width, size.height) / 2 - 16.dp.toPx()
        val needed = max(
            friend?.let { hypot(it.eastM, it.northM) + it.sigmaM } ?: 0f,
            state.rangeM ?: 0f,
        ) * 1.15f
        val extentUnits: Int
        val extentM: Float
        if (state.metric) {
            extentUnits = MapMeterSteps.firstOrNull { it >= needed } ?: MapMeterSteps.last()
            extentM = extentUnits.toFloat()
        } else {
            extentUnits = MapFeetSteps.firstOrNull { it / FEET_PER_METER >= needed } ?: MapFeetSteps.last()
            extentM = extentUnits / FEET_PER_METER
        }
        val scale = half / extentM
        val center = Offset(cx, cy)

        // With a heading the map turns so the direction you face is up; without one, north is up.
        val heading = state.headingDeg
        val turn = Math.toRadians((heading ?: 0f).toDouble())
        val cosTurn = cos(turn).toFloat()
        val sinTurn = sin(turn).toFloat()

        val text = android.graphics.Paint().apply {
            isAntiAlias = true
            color = Muted.toArgb()
            textSize = 12.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val ring = Muted.copy(alpha = 0.35f)
        drawCircle(ring, radius = half, center = center, style = Stroke(1.dp.toPx()))
        drawCircle(ring, radius = half / 2, center = center, style = Stroke(1.dp.toPx()))
        drawContext.canvas.nativeCanvas.apply {
            val northRadius = half + 10.dp.toPx()
            drawText(northLabel, cx - sinTurn * northRadius, cy + 4.dp.toPx() - cosTurn * northRadius, text)
            drawText(scaleLabel(extentUnits, state.metric), cx, cy + half + 14.dp.toPx(), text)
            drawText(scaleLabel(extentUnits / 2, state.metric), cx, cy + half / 2 + 14.dp.toPx(), text)
        }

        val range = state.rangeM
        val close = isClose(state.distanceM)
        val tone = when {
            close -> Green
            state.rangeSource == RangeSource.Uwb -> Blue
            else -> Amber
        }
        if (range != null) {
            drawCircle(
                tone.copy(alpha = 0.6f),
                radius = range * scale,
                center = center,
                style = Stroke(
                    2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)),
                ),
            )
        }
        if (friend != null) {
            val right = friend.eastM * cosTurn - friend.northM * sinTurn
            val ahead = friend.northM * cosTurn + friend.eastM * sinTurn
            val p = Offset(cx + right * scale, cy - ahead * scale)
            drawCircle(tone.copy(alpha = 0.15f), radius = friend.sigmaM * scale, center = p)
            drawLine(tone.copy(alpha = 0.6f), center, p, strokeWidth = 2.dp.toPx())
            drawCircle(tone, radius = 9.dp.toPx(), center = p)
        }
        if (heading != null) {
            val len = 24.dp.toPx()
            val wing = 9.dp.toPx()
            drawPath(
                Path().apply {
                    moveTo(cx, cy - len)
                    lineTo(cx - wing, cy)
                    lineTo(cx + wing, cy)
                    close()
                },
                Blue.copy(alpha = 0.5f),
            )
        }
        drawCircle(Blue, radius = 8.dp.toPx(), center = center)
        drawCircle(Color.White, radius = 3.dp.toPx(), center = center)
        if (friend == null && range == null) {
            drawContext.canvas.nativeCanvas.drawText(emptyLabel, cx, cy + 40.dp.toPx(), text)
        }
    }
}

/** A caption under the map when its position is less trustworthy; null when nothing needs saying. */
@Composable
private fun mapNote(state: FinderState): String? {
    val friend = state.friend
    return when {
        friend?.source == EstimateSource.RangeAndBearing ->
            if (state.arrowSource == DirectionSource.Gps) stringResource(R.string.map_note_gps_direction) else null

        friend?.source == EstimateSource.RangeAndGps -> stringResource(R.string.map_note_gps_direction)
        friend?.source == EstimateSource.GpsOnly -> stringResource(
            R.string.map_note_gps_only,
            if (state.metric) "30 m" else "100 ft",
        )

        state.rangeM != null -> stringResource(R.string.map_note_ring)
        else -> stringResource(R.string.map_note_waiting)
    }
}

/** Where the readings come from, for the debug view. */
private fun debugLine(state: FinderState): String {
    val range = when (state.rangeSource) {
        RangeSource.Uwb -> "UWB"
        RangeSource.Wifi -> "Wi-Fi"
        RangeSource.Bluetooth -> "Bluetooth"
        null -> "-"
    }
    val direction = when (state.arrowSource) {
        DirectionSource.Uwb -> "UWB"
        DirectionSource.Gps -> "GPS"
        DirectionSource.Both -> "UWB+GPS"
        null -> "-"
    }
    val position = when (state.friend?.source) {
        EstimateSource.RangeAndBearing -> "range+bearing"
        EstimateSource.RangeAndGps -> "range+GPS"
        EstimateSource.GpsOnly -> "GPS only"
        null -> "-"
    }
    return "range: $range, direction: $direction, position: $position"
}

@Composable
private fun statusLine(state: FinderState): String {
    val id = when (state.phase) {
        Phase.Waiting -> R.string.status_looking
        Phase.Ranging -> when {
            isClose(state.distanceM) -> R.string.status_here
            state.distanceM == null -> when (state.trend) {
                Trend.Closer -> R.string.status_closer
                Trend.Farther -> R.string.status_farther
                Trend.Steady -> R.string.status_walk
                null -> R.string.status_measuring
            }

            state.arrowDeg == null -> R.string.status_sweep
            state.arrowSource == DirectionSource.Gps -> R.string.status_gps
            !state.directionLive && state.flat -> R.string.status_tilt
            !state.directionLive -> R.string.status_aim
            else -> R.string.status_follow
        }

        Phase.Failed -> return state.message ?: stringResource(R.string.status_failed)
        Phase.Idle -> return ""
    }
    return stringResource(id)
}

private fun formatDistance(meters: Float, metric: Boolean): String {
    val value = if (metric) meters else meters * FEET_PER_METER
    val unit = if (metric) "m" else "ft"
    return if (value < 10f) {
        String.format(Locale.US, "%.1f %s", value, unit)
    } else {
        "${value.roundToInt()} $unit"
    }
}

private fun distanceText(state: FinderState): String {
    val meters = state.distanceM
    if (meters != null) return formatDistance(meters, state.metric)
    val range = state.rangeM ?: return "—"
    return when (state.rangeSource) {
        RangeSource.Wifi -> if (state.metric) {
            "~${max(2, range.roundToInt())} m"
        } else {
            "~${max(5, (range * FEET_PER_METER / 5f).roundToInt() * 5)} ft"
        }

        RangeSource.Bluetooth -> roughDistanceText(range, state.metric)
        else -> "—"
    }
}

/** The same distance in the other unit; only precise readings are shown both ways. */
private fun swapDistanceText(state: FinderState): String? =
    state.distanceM?.let { formatDistance(it, !state.metric) }

/** For readings that are not precise, a line that says how rough they are. */
@Composable
private fun sourceLine(state: FinderState): String? {
    if (state.phase != Phase.Ranging || state.distanceM != null) return null
    return when (state.rangeSource) {
        RangeSource.Wifi -> stringResource(R.string.via_wifi)
        RangeSource.Bluetooth -> stringResource(R.string.via_bluetooth)
        else -> null
    }
}

@Composable
private fun levelLine(state: FinderState): String? {
    if (state.phase != Phase.Ranging || state.distanceM == null) return null
    val elevation = state.elevationDeg ?: return null
    return when {
        elevation > 25f -> stringResource(R.string.above_you)
        elevation < -25f -> stringResource(R.string.below_you)
        else -> null
    }
}
