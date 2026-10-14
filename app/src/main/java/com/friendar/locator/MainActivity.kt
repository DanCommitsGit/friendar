package com.friendar.locator

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val Blue = Color(0xFF6FA2FF)
private val Green = Color(0xFF4ADE80)
private val Amber = Color(0xFFFFB74D)
private val Ink = Color(0xFF0E1116)
private val OnInk = Color(0xFFF1F4F8)
private val Muted = Color(0xFFB4BDC9)
private val Raised = Color(0xFF232A35)

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
                    if (state.phase == Phase.Idle) {
                        HomeScreen(onStart = viewModel::start)
                    } else {
                        FinderScreen(state, onStop = viewModel::stop, onMirror = viewModel::setMirror)
                    }
                }
            }
        }
    }
}

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
private fun HomeScreen(onStart: () -> Unit) {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Friendar",
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
            Text("Find my friend", fontSize = 20.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Tap it on both phones.",
            color = Muted,
            textAlign = TextAlign.Center,
        )

        if (denied) {
            Spacer(Modifier.height(24.dp))
            Text(
                "Allow Nearby devices in the app's permissions to connect.",
                color = Amber,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun FinderScreen(state: FinderState, onStop: () -> Unit, onMirror: (Boolean) -> Unit) {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    var showDetails by remember { mutableStateOf(false) }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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
                label = { Text("Arrow") },
                colors = chipColors,
            )
            FilterChip(
                selected = showMap,
                onClick = { showMap = true },
                label = { Text("Map") },
                colors = chipColors,
            )
        }

        Spacer(Modifier.weight(1f))

        if (showMap) {
            MapView(state, modifier = Modifier.fillMaxWidth().height(if (showDetails) 200.dp else 280.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                mapNote(state),
                color = Muted,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else if (showArrow) {
            Arrow(
                angleDeg = state.arrowDeg,
                color = arrowColor,
                modifier = Modifier.size(if (showDetails) 160.dp else 240.dp),
            )
        } else {
            Spacer(Modifier.size(if (showDetails) 160.dp else 240.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = distanceText(state),
            fontSize = if (roughDistance) 40.sp else 56.sp,
            fontWeight = FontWeight.Bold,
            color = if (close) Green else MaterialTheme.colorScheme.onBackground,
        )
        secondaryLine(state)?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        }

        Spacer(Modifier.weight(1f))

        if (showDetails) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 140.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Flip arrow left and right", color = Muted)
                    Switch(checked = state.mirror, onCheckedChange = onMirror)
                }
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
        TextButton(onClick = { showDetails = !showDetails }) {
            Text(if (showDetails) "Hide details" else "Details", color = Muted)
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
            Text("Stop", fontSize = 18.sp)
        }
    }
}

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

private val MapFeetSteps = listOf(30, 60, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000)

private fun feetLabel(feet: Int): String =
    if (feet >= 5280) String.format(Locale.US, "%.1f mi", feet / 5280f) else "$feet ft"

@Composable
private fun MapView(state: FinderState, modifier: Modifier = Modifier) {
    val friend = state.friend
    Canvas(modifier = modifier) {
        val cx = size.width / 2
        val cy = size.height / 2
        val half = min(size.width, size.height) / 2 - 16.dp.toPx()
        val needed = max(
            friend?.let { hypot(it.eastM, it.northM) + it.sigmaM } ?: 0f,
            state.rangeM ?: 0f,
        ) * 1.15f
        val extentFeet = MapFeetSteps.firstOrNull { it / 3.28084f >= needed } ?: MapFeetSteps.last()
        val extentM = extentFeet / 3.28084f
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
            drawText("N", cx - sinTurn * northRadius, cy + 4.dp.toPx() - cosTurn * northRadius, text)
            drawText(feetLabel(extentFeet), cx, cy + half + 14.dp.toPx(), text)
            drawText(feetLabel(extentFeet / 2), cx, cy + half / 2 + 14.dp.toPx(), text)
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
            drawContext.canvas.nativeCanvas.drawText("No position yet", cx, cy + 40.dp.toPx(), text)
        }
    }
}

private fun mapNote(state: FinderState): String {
    val friend = state.friend
    val source = when (state.rangeSource) {
        RangeSource.Uwb -> "UWB"
        RangeSource.Wifi -> "Wi-Fi"
        RangeSource.Bluetooth -> "Bluetooth"
        null -> ""
    }
    return when {
        friend?.source == EstimateSource.RangeAndBearing -> when (state.arrowSource) {
            DirectionSource.Gps -> "Distance from $source, direction from GPS."
            DirectionSource.Both -> "Distance from $source, direction from UWB and GPS."
            else -> "Distance from $source, direction from UWB."
        }

        friend?.source == EstimateSource.RangeAndGps -> "Distance from $source, direction from GPS."
        friend?.source == EstimateSource.GpsOnly ->
            "GPS only. Indoors it can be off by 30 m or more."

        state.rangeM != null -> "Your friend is somewhere on the dashed ring."
        else -> "Waiting for a position."
    }
}

private fun statusLine(state: FinderState): String = when (state.phase) {
    Phase.Waiting -> "Looking for your friend"
    Phase.Ranging -> when {
        isClose(state.distanceM) -> "Right here"
        state.distanceM == null -> when (state.trend) {
            Trend.Closer -> "Getting closer"
            Trend.Farther -> "Getting farther"
            Trend.Steady -> "Keep walking to see which way is closer"
            null -> "Measuring"
        }

        state.arrowDeg == null -> "Point the back of the phone around the room"
        state.arrowSource == DirectionSource.Gps -> "Rough direction from GPS. Walk that way and watch the distance."
        !state.directionLive && state.flat -> "Tilt the phone up and aim its back at your friend"
        !state.directionLive -> "Aim the back of the phone at your friend"
        else -> "Follow the arrow"
    }

    Phase.Failed -> state.message ?: "Something went wrong"
    Phase.Idle -> ""
}

private fun distanceText(state: FinderState): String {
    val meters = state.distanceM
    if (meters == null) {
        return when (state.rangeSource) {
            RangeSource.Wifi -> {
                val feet = (state.rangeM ?: return "—") * 3.28084f
                "~${max(5, (feet / 5f).roundToInt() * 5)} ft"
            }

            RangeSource.Bluetooth -> roughDistanceText(state.rangeM ?: return "—")
            else -> "—"
        }
    }
    val feet = meters * 3.28084f
    return if (feet < 10f) {
        String.format(Locale.US, "%.1f ft", feet)
    } else {
        "${feet.roundToInt()} ft"
    }
}

private fun secondaryLine(state: FinderState): String? {
    if (state.phase != Phase.Ranging) return null
    val meters = state.distanceM
    if (meters == null) {
        return when (state.rangeSource) {
            RangeSource.Wifi -> "Wi-Fi distance, works through walls"
            RangeSource.Bluetooth -> "Rough estimate from Bluetooth signal"
            else -> null
        }
    }
    val metric = String.format(Locale.US, "%.1f m", meters)
    val elevation = state.elevationDeg
    val level = when {
        elevation == null -> null
        elevation > 25f -> "above you"
        elevation < -25f -> "below you"
        else -> null
    }
    return if (level != null) "$metric · $level" else metric
}
