package io.github.dancommitsgit.friendar.finder

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.uwb.RangingPosition
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dancommitsgit.friendar.LocatorService
import io.github.dancommitsgit.friendar.R
import io.github.dancommitsgit.friendar.fusion.DistanceFilter
import io.github.dancommitsgit.friendar.fusion.Fix
import io.github.dancommitsgit.friendar.fusion.FriendTracker
import io.github.dancommitsgit.friendar.fusion.NorthReference
import io.github.dancommitsgit.friendar.fusion.Range
import io.github.dancommitsgit.friendar.fusion.RangeTrend
import io.github.dancommitsgit.friendar.fusion.SignalTracker
import io.github.dancommitsgit.friendar.fusion.Trend
import io.github.dancommitsgit.friendar.fusion.bestRange
import io.github.dancommitsgit.friendar.link.AwareLink
import io.github.dancommitsgit.friendar.link.AwareListener
import io.github.dancommitsgit.friendar.link.BleHost
import io.github.dancommitsgit.friendar.link.BleJoiner
import io.github.dancommitsgit.friendar.link.LinkListener
import io.github.dancommitsgit.friendar.link.Message
import io.github.dancommitsgit.friendar.link.Protocol
import io.github.dancommitsgit.friendar.sensors.LocationTracker
import io.github.dancommitsgit.friendar.sensors.Orientation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

class FinderViewModel(app: Application) : AndroidViewModel(app), LinkListener, AwareListener {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(freshState())
    val state: StateFlow<FinderState> = _state.asStateFlow()

    private val distanceFilter = DistanceFilter()
    private val tracker = FriendTracker()
    private val signal = SignalTracker()

    @Volatile
    private var rotation: FloatArray? = null
    private var lastArrowUpdateMs = 0L
    private val orientation = Orientation(app) {
        rotation = it
        refreshArrow()
    }

    private val awareTrend = RangeTrend()
    private var aware: AwareLink? = null
    private var locations: LocationTracker? = null
    private var lastAwareMs = 0L
    private var awareStdM = 0f
    private var lastShareMs = 0L

    private var host: BleHost? = null
    private var joiner: BleJoiner? = null
    private var watchdogJob: Job? = null
    private var tickerJob: Job? = null

    private var linkId = 0
    private var linked = false
    private var lastPeerMessageMs = 0L

    private val uwb = UwbSession(
        context = app,
        viewScope = viewModelScope,
        state = _state,
        send = ::send,
        log = ::log,
        resetFilters = ::resetFilters,
        forgetDirection = tracker::forgetDirection,
        onPosition = ::onPosition,
    )

    fun start() {
        stop()
        linkId = Random.nextInt()
        _state.value = freshState(Phase.Waiting)
        log("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        val pm = getApplication<Application>().packageManager
        log(
            "Wi-Fi Aware: ${pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)}, " +
                "Wi-Fi RTT: ${pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)}",
        )
        orientation.start()
        uwb.logPlatformCapabilities()
        if (_state.value.background) startService()
        aware = AwareLink(getApplication(), this).also { it.start() }
        locations = LocationTracker(getApplication(), ::onFix).also {
            if (!it.start()) log("Location: unavailable (permission or location is off)")
        }
        watchdogJob = viewModelScope.launch {
            delay(WATCHDOG_START_MS)
            while (true) {
                _state.update { it.copy(hint = hintFor(it)) }
                delay(WATCHDOG_INTERVAL_MS)
            }
        }
        tickerJob = viewModelScope.launch {
            while (true) {
                delay(TICK_MS)
                onTick()
            }
        }
        openLink()
    }

    fun setFlipMode(mode: FlipMode) {
        prefs.edit().putString(PREF_FLIP, mode.name).apply()
        val mirror = mirrorFor(mode, _state.value.flipAuto)
        if (mirror != _state.value.mirror) tracker.onMirrorChanged()
        _state.update { it.copy(flipMode = mode, mirror = mirror) }
    }

    fun setUnitMode(mode: UnitMode) {
        prefs.edit().putString(PREF_UNITS, mode.name).apply()
        _state.update { it.copy(unitMode = mode, metric = metricFor(mode)) }
    }

    /** Sets the unit explicitly, as when the distance readout is tapped. */
    fun setMetric(value: Boolean) = setUnitMode(if (value) UnitMode.Metric else UnitMode.Imperial)

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(PREF_THEME, mode.name).apply()
        _state.update { it.copy(themeMode = mode) }
    }

    fun setBackground(value: Boolean) {
        prefs.edit().putBoolean(PREF_BACKGROUND, value).apply()
        _state.update { it.copy(background = value) }
        if (_state.value.phase == Phase.Idle) return
        if (value) startService() else stopService()
    }

    /** Called when the app leaves the foreground. */
    fun onAppHidden() {
        val s = _state.value
        if (!s.background && s.phase != Phase.Idle) stop()
    }

    fun setDebug(value: Boolean) {
        prefs.edit().putBoolean(PREF_DEBUG, value).apply()
        _state.update { it.copy(debug = value) }
    }

    private fun freshState(phase: Phase = Phase.Idle): FinderState {
        val flipMode = enumPref(PREF_FLIP, FlipMode.Auto)
        val unitMode = enumPref(PREF_UNITS, UnitMode.Auto)
        val learned = if (prefs.contains(PREF_FLIP_LEARNED)) prefs.getBoolean(PREF_FLIP_LEARNED, true) else null
        return FinderState(
            phase = phase,
            flipMode = flipMode,
            flipAuto = learned,
            mirror = mirrorFor(flipMode, learned),
            unitMode = unitMode,
            metric = metricFor(unitMode),
            themeMode = enumPref(PREF_THEME, ThemeMode.System),
            background = prefs.getBoolean(PREF_BACKGROUND, true),
            debug = prefs.getBoolean(PREF_DEBUG, false),
        )
    }

    private inline fun <reified T : Enum<T>> enumPref(key: String, default: T): T =
        prefs.getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    private fun string(id: Int) = getApplication<Application>().getString(id)

    fun stop() {
        closeLink()
        watchdogJob?.cancel()
        watchdogJob = null
        tickerJob?.cancel()
        tickerJob = null
        orientation.stop()
        aware?.stop()
        aware = null
        locations?.stop()
        locations = null
        tracker.clear()
        awareTrend.reset()
        lastAwareMs = 0L
        stopService()
        _state.value = freshState()
    }

    private fun startService() {
        val app = getApplication<Application>()
        runCatching {
            ContextCompat.startForegroundService(app, Intent(app, LocatorService::class.java))
        }
    }

    private fun stopService() {
        val app = getApplication<Application>()
        app.stopService(Intent(app, LocatorService::class.java))
    }

    override fun onCleared() {
        stop()
    }

    /** Both phones advertise and scan; the one with the lower nonce does the connecting. */
    private fun openLink() {
        linked = false
        host = BleHost(getApplication(), linkId, this).also { it.start() }
        joiner = BleJoiner(getApplication(), linkId, this).also { it.start() }
    }

    private fun closeLink() {
        linked = false
        uwb.reset()
        host?.stop()
        host = null
        joiner?.stop()
        joiner = null
        resetFilters()
        signal.reset()
    }

    private fun relink(reason: String) {
        closeLink()
        log(reason)
        _state.update {
            it.copy(
                role = null,
                phase = Phase.Waiting,
                distanceM = null,
                arrowDeg = null,
                elevationDeg = null,
                directionLive = false,
                rssiDbm = null,
                signalDistanceM = null,
                trend = null,
                uwbActive = false,
                message = reason,
                hint = null,
                rawReading = null,
            )
        }
        openLink()
    }

    private fun hintFor(s: FinderState): String? = when (hintKindFor(s)) {
        Hint.Waiting -> string(R.string.hint_waiting)
        Hint.Blocked -> string(R.string.hint_blocked)
        null -> null
    }

    private fun resetFilters() {
        distanceFilter.reset()
        tracker.forgetDirection()
    }

    private fun send(message: Message) {
        val bytes = Protocol.encode(message)
        host?.send(bytes)
        joiner?.send(bytes)
    }

    private fun fail(text: String) {
        log("Failed: $text")
        _state.update { it.copy(phase = Phase.Failed, message = text) }
    }

    override fun onLog(text: String) = log(text)

    private fun log(text: String) {
        Log.d(TAG, text)
        _state.update { it.copy(log = (it.log + text).takeLast(MAX_LOG_LINES)) }
    }

    // LinkListener: invoked from Bluetooth threads.

    override fun onReady(asHost: Boolean) {
        viewModelScope.launch {
            if (linked || _state.value.phase != Phase.Waiting) return@launch
            linked = true
            lastPeerMessageMs = SystemClock.elapsedRealtime()
            if (asHost) {
                joiner?.stop()
                joiner = null
            } else {
                host?.stop()
                host = null
            }
            _state.update {
                it.copy(
                    role = if (asHost) Role.Host else Role.Joiner,
                    phase = Phase.Ranging,
                    message = null,
                    hint = null,
                )
            }
            if (asHost) uwb.startAttempt(0)
        }
    }

    override fun onMessage(bytes: ByteArray) {
        val message = Protocol.decode(bytes) ?: return
        viewModelScope.launch {
            if (!linked) return@launch
            lastPeerMessageMs = SystemClock.elapsedRealtime()
            when (message) {
                is Message.Role -> uwb.onRole(message)
                is Message.Join -> uwb.onJoin(message)
                is Message.Start -> uwb.onStart(message)
                is Message.Retry -> uwb.onRetry(message)

                is Message.Rssi -> onSignal(message.dbm)
                Message.NoUwb -> uwb.useSignalStrengthOnly(string(R.string.peer_no_uwb))
                is Message.Location -> onFriendLocation(message)
                Message.Heartbeat -> Unit
            }
        }
    }

    // AwareListener: invoked on the main thread.

    override fun onAwareRange(meters: Float, stdDevMm: Int) {
        val now = SystemClock.elapsedRealtime()
        lastAwareMs = now
        awareStdM = stdDevMm / 1000f
        awareTrend.add(now, meters)
        _state.update { it.copy(awareDistanceM = meters) }
        updateMap()
    }

    override fun onAwareMessage(bytes: ByteArray) {
        val message = Protocol.decode(bytes)
        if (message is Message.Location) onFriendLocation(message)
    }

    override fun onAwareLog(text: String) = log(text)

    private fun onFriendLocation(message: Message.Location) {
        tracker.onFriendFix(
            Fix(
                lat = message.latE7 / 1e7,
                lon = message.lonE7 / 1e7,
                accuracyM = message.accuracyM.toFloat(),
                timeMs = SystemClock.elapsedRealtime(),
            ),
        )
        updateMap()
    }

    private fun onFix(fix: Fix) {
        val declinationDeg = GeomagneticField(
            fix.lat.toFloat(), fix.lon.toFloat(), 0f, System.currentTimeMillis(),
        ).declination
        tracker.onMyFix(fix, declinationDeg)
        _state.update { it.copy(myAccuracyM = fix.accuracyM) }
        updateMap()
    }

    private fun shareLocation(now: Long) {
        val fix = tracker.myFix ?: return
        if (now - lastShareMs < SHARE_INTERVAL_MS) return
        lastShareMs = now
        val message = Message.Location(
            latE7 = (fix.lat * 1e7).roundToInt(),
            lonE7 = (fix.lon * 1e7).roundToInt(),
            accuracyM = fix.accuracyM.roundToInt(),
        )
        if (linked) send(message)
        aware?.send(Protocol.encode(message))
    }

    private fun currentTrend(now: Long): Trend? =
        if (now - lastAwareMs < AWARE_STALE_MS) awareTrend.trend() ?: signal.trend() else signal.trend()

    private fun currentRange(now: Long): Range? {
        val s = _state.value
        val wifi = s.awareDistanceM?.takeIf { now - lastAwareMs < AWARE_STALE_MS }
        return bestRange(s.distanceM, wifi, awareStdM, s.signalDistanceM)
    }

    private fun northReference(): NorthReference? =
        orientation.northOffsetRad?.let { NorthReference(it, orientation.northReliable) }

    private fun checkFlip(now: Long) {
        val s = _state.value
        val verdict = tracker.checkFlip(now, s.distanceM, northReference(), s.flipAuto) ?: return
        log(
            "Flip: ${if (verdict.reversed) "azimuth is reversed" else "azimuth is correct"} " +
                "(score %.1f)".format(Locale.US, verdict.score),
        )
        prefs.edit().putBoolean(PREF_FLIP_LEARNED, verdict.reversed).apply()
        val mirror = mirrorFor(s.flipMode, verdict.reversed)
        if (mirror != s.mirror) tracker.onMirrorChanged()
        _state.update { it.copy(flipAuto = verdict.reversed, mirror = mirror) }
    }

    private fun updateMap() {
        val now = SystemClock.elapsedRealtime()
        val range = currentRange(now)
        val position = tracker.estimate(now, range, northReference())
        _state.update {
            it.copy(
                rangeM = range?.meters,
                rangeSource = range?.source,
                friend = position.friend,
                friendGps = position.friendGps,
                frameInfo = position.frameInfo,
                trend = currentTrend(now),
            )
        }
    }

    override fun onRssi(dbm: Int) {
        viewModelScope.launch { onSignal(dbm) }
        send(Message.Rssi(dbm))
    }

    override fun onDisconnected() {
        viewModelScope.launch {
            if (linked) relink(string(R.string.link_lost))
        }
    }

    override fun onError(message: String) {
        viewModelScope.launch { fail(message) }
    }

    private fun onSignal(dbm: Int) {
        signal.add(SystemClock.elapsedRealtime(), dbm)
        val smooth = signal.smoothedDbm()
        _state.update {
            it.copy(
                rssiDbm = smooth?.roundToInt() ?: dbm,
                signalDistanceM = smooth?.let(SignalTracker::estimateMeters),
            )
        }
        updateMap()
    }

    private fun onTick() {
        val now = SystemClock.elapsedRealtime()
        if (_state.value.phase == Phase.Idle) return
        if (now - lastAwareMs > AWARE_STALE_MS && _state.value.awareDistanceM != null) {
            _state.update { it.copy(awareDistanceM = null) }
        }
        if (linked) {
            send(Message.Heartbeat)
            if (now - lastPeerMessageMs > PEER_SILENT_MS) {
                relink(string(R.string.link_lost))
                return
            }
        }
        shareLocation(now)
        checkFlip(now)
        tracker.calibrate(now, _state.value.distanceM)
        updateMap()
        uwb.tick()
    }

    private fun onPosition(position: RangingPosition) {
        val rawDistance = position.distance?.value
        val distance = rawDistance?.let(distanceFilter::add)
        // Erratic distance readings are typical of reflected signals, so trust their angles less.
        val distanceTrust = if (rawDistance != null && distance != null) {
            (1f - abs(rawDistance - distance) / DISTANCE_JITTER_M).coerceIn(0.2f, 1f)
        } else {
            1f
        }
        val azimuth = position.azimuth?.value
        val elevation = position.elevation?.value ?: 0f
        val r = rotation
        var raw = String.format(
            Locale.US, "d=%s az=%s el=%s",
            rawDistance?.let { "%.2f".format(Locale.US, it) } ?: "-",
            azimuth?.let { "%.0f".format(Locale.US, it) } ?: "-",
            position.elevation?.value?.let { "%.0f".format(Locale.US, it) } ?: "-",
        )
        if (azimuth != null && r != null) {
            val result = tracker.addUwbReading(
                azimuth, elevation, r, _state.value.mirror, distanceTrust, uwb.lastPositionMs,
            )
            raw += String.format(
                Locale.US, " off=%.0f w=%.2f%s",
                result.offBoresightDeg, result.weight, if (result.used) "" else " skip",
            )
        }
        _state.update {
            it.copy(
                phase = Phase.Ranging,
                uwbActive = true,
                distanceM = distance ?: it.distanceM,
                message = null,
                hint = null,
                rawReading = raw,
            )
        }
        refreshArrow()
    }

    private fun refreshArrow() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastArrowUpdateMs < ARROW_INTERVAL_MS) return
        lastArrowUpdateMs = now
        val r = rotation ?: return
        val reading = tracker.arrow(now, r)
        _state.update {
            if (it.phase != Phase.Ranging) {
                it
            } else {
                it.copy(
                    arrowDeg = reading.arrowDeg,
                    elevationDeg = reading.elevationDeg,
                    directionLive = reading.directionLive,
                    arrowSource = reading.source,
                    headingDeg = reading.headingDeg,
                    flat = reading.flat,
                )
            }
        }
    }

    private companion object {
        const val TAG = "Friendar"
        const val PREF_FLIP = "flip"
        const val PREF_FLIP_LEARNED = "flip_learned"
        const val PREF_UNITS = "units"
        const val PREF_THEME = "theme"
        const val PREF_BACKGROUND = "background"
        const val PREF_DEBUG = "debug"
        const val MAX_LOG_LINES = 20
        const val WATCHDOG_START_MS = 12_000L
        const val PEER_SILENT_MS = 8_000L
        const val WATCHDOG_INTERVAL_MS = 4_000L
        const val TICK_MS = 1_000L
        const val SHARE_INTERVAL_MS = 2_000L
        const val AWARE_STALE_MS = 5_000L
        const val DISTANCE_JITTER_M = 1.5f
        const val ARROW_INTERVAL_MS = 33L
    }
}
