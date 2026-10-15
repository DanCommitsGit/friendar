package io.github.dancommitsgit.friendar

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingPosition
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
import androidx.core.uwb.UwbClientSessionScope
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.random.Random

enum class Role { Host, Joiner }

enum class Phase { Idle, Waiting, Ranging, Failed }

enum class RangeSource { Uwb, Wifi, Bluetooth }

enum class FlipMode { Yes, No, Auto }

enum class UnitMode { Auto, Metric, Imperial }

enum class ThemeMode { System, Light, Dark }

data class FinderState(
    /** Which side of the Bluetooth link this phone ended up on; null until linked. */
    val role: Role? = null,
    val phase: Phase = Phase.Idle,
    /** Precise UWB distance; null while UWB has no current measurement. */
    val distanceM: Float? = null,
    /** Degrees clockwise from the top of the screen, null until a direction is known. */
    val arrowDeg: Float? = null,
    /** Friend's height relative to the horizon, in degrees. */
    val elevationDeg: Float? = null,
    /** True while recent UWB readings are good enough to trust the direction. */
    val directionLive: Boolean = false,
    /** True when the phone is held flat and the arrow works like a compass needle. */
    val flat: Boolean = false,
    /** Smoothed Bluetooth signal strength. */
    val rssiDbm: Int? = null,
    /** Rough distance from Bluetooth signal strength; works through walls. */
    val signalDistanceM: Float? = null,
    /** Wi-Fi round-trip-time distance; works through walls, good to a few meters. */
    val awareDistanceM: Float? = null,
    val trend: Trend? = null,
    /** Best available distance, with where it came from; null when nothing is measured. */
    val rangeM: Float? = null,
    val rangeSource: RangeSource? = null,
    val myAccuracyM: Float? = null,
    /** True while the friend's shared GPS position is recent. */
    val friendGps: Boolean = false,
    /** Friend's estimated position in meters east and north of this phone. */
    val friend: FriendEstimate? = null,
    /** Direction this phone faces, degrees clockwise from true north; null without a compass reference. */
    val headingDeg: Float? = null,
    /** Which measurements the arrow direction comes from. */
    val arrowSource: DirectionSource? = null,
    /** How north was found and how far to trust it, for the details screen. */
    val frameInfo: String? = null,
    val uwbActive: Boolean = false,
    /** Why a failure or fallback happened. */
    val message: String? = null,
    /** Suggestion shown when a step is taking unusually long. */
    val hint: String? = null,
    val flipMode: FlipMode = FlipMode.Auto,
    /** What Auto resolves to: true when negating the UWB azimuth fits GPS and compass; null until learned. */
    val flipAuto: Boolean? = null,
    /** Whether the UWB azimuth is negated right now, however that was decided. */
    val mirror: Boolean = true,
    val unitMode: UnitMode = UnitMode.Auto,
    /** Show distances in meters instead of feet. */
    val metric: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.System,
    /** Keep searching while the app is not in front. */
    val background: Boolean = true,
    /** Show measurement sources and raw readings. */
    val debug: Boolean = false,
    val rawReading: String? = null,
    val log: List<String> = emptyList(),
)

class FinderViewModel(app: Application) : AndroidViewModel(app), LinkListener, AwareListener {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(freshState())
    val state: StateFlow<FinderState> = _state.asStateFlow()

    private val distanceFilter = DistanceFilter()
    private val directionFilter = DirectionFilter()
    private val arrowSmoother = AngleSmoother()
    private val signal = SignalTracker()

    @Volatile
    private var rotation: FloatArray? = null
    private var lastArrowUpdateMs = 0L
    private val orientation = Orientation(app) {
        rotation = it
        refreshArrow()
    }

    private val awareTrend = RangeTrend()
    private val frameCalibrator = FrameCalibrator()
    private val flipDetector = FlipDetector()

    private class Reading(val azimuthDeg: Float, val elevationDeg: Float, val rotation: FloatArray, val timeMs: Long)

    /** Latest UWB angle with the phone attitude it was taken at, as reported before any flip. */
    @Volatile
    private var lastReading: Reading? = null

    /** Bearing to the friend from GPS, in the gyroscope frame; refreshed once a second. */
    @Volatile
    private var gpsBearing: Bearing? = null

    /** Yaw of the gyroscope frame from true north, from the compass and from UWB-versus-GPS calibration. */
    @Volatile
    private var frame: Bearing? = null
    private var lastGoodDirectionMs = 0L
    private var aware: AwareLink? = null
    private var locations: LocationTracker? = null
    private var myFix: Fix? = null
    private var friendFix: Fix? = null
    private var declinationDeg = 0f
    private var lastAwareMs = 0L
    private var awareStdM = 0f
    private var lastShareMs = 0L

    private var host: BleHost? = null
    private var joiner: BleJoiner? = null
    private var rangingJob: Job? = null
    private var watchdogJob: Job? = null
    private var tickerJob: Job? = null
    private var attemptJob: Job? = null
    private var pendingScope: UwbClientSessionScope? = null

    private var linkId = 0
    private var linked = false
    private var lastPeerMessageMs = 0L
    private var roleResendJob: Job? = null
    private var roleAppliedFor = -1
    private var attempt = 0
    private var failedAttempts = 0
    private var iAmController = false
    private var rangingStarted = false
    private var lastPositionMs = 0L

    /** Which side was UWB controller when ranging last worked; the host keeps using it. */
    private var workingControllerIsHost: Boolean? = null

    private val uwbManager: UwbManager? by lazy {
        val pm = getApplication<Application>().packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_UWB)) {
            null
        } else {
            runCatching { UwbManager.createInstance(getApplication()) }.getOrNull()
        }
    }

    fun start() {
        stop()
        linkId = Random.nextInt()
        attempt = 0
        failedAttempts = 0
        rangingStarted = false
        workingControllerIsHost = null
        flipDetector.reset()
        lastReading = null
        _state.value = freshState(Phase.Waiting)
        log("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        val pm = getApplication<Application>().packageManager
        log(
            "Wi-Fi Aware: ${pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)}, " +
                "Wi-Fi RTT: ${pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)}",
        )
        orientation.start()
        logRangingCapabilities()
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
        if (mirror != _state.value.mirror) onMirrorChanged()
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

    /** Both test phones report the azimuth with the wrong sign, so that is the guess before anything is learned. */
    private fun mirrorFor(mode: FlipMode, auto: Boolean?) = when (mode) {
        FlipMode.Yes -> true
        FlipMode.No -> false
        FlipMode.Auto -> auto ?: true
    }

    private fun metricFor(mode: UnitMode) = when (mode) {
        UnitMode.Metric -> true
        UnitMode.Imperial -> false
        UnitMode.Auto -> Locale.getDefault().country !in IMPERIAL_COUNTRIES
    }

    /** Readings and calibration made with the other sign no longer describe the friend's direction. */
    private fun onMirrorChanged() {
        directionFilter.reset()
        arrowSmoother.reset()
        frameCalibrator.reset()
    }

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
        myFix = null
        friendFix = null
        awareTrend.reset()
        frameCalibrator.reset()
        gpsBearing = null
        frame = null
        lastAwareMs = 0L
        lastReading = null
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
        rangingJob?.cancel()
        rangingJob = null
        attemptJob?.cancel()
        attemptJob = null
        roleResendJob?.cancel()
        roleResendJob = null
        roleAppliedFor = -1
        pendingScope = null
        host?.stop()
        host = null
        joiner?.stop()
        joiner = null
        rangingStarted = false
        attempt = 0
        failedAttempts = 0
        workingControllerIsHost = null
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

    private fun hintFor(s: FinderState): String? = when {
        s.phase == Phase.Waiting -> string(R.string.hint_waiting)

        s.phase == Phase.Ranging && s.uwbActive && s.distanceM == null && s.rssiDbm != null ->
            string(R.string.hint_blocked)

        else -> null
    }

    private fun resetFilters() {
        distanceFilter.reset()
        directionFilter.reset()
        arrowSmoother.reset()
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
            if (asHost) startAttempt(0)
        }
    }

    override fun onMessage(bytes: ByteArray) {
        val message = Protocol.decode(bytes) ?: return
        viewModelScope.launch {
            if (!linked) return@launch
            lastPeerMessageMs = SystemClock.elapsedRealtime()
            when (message) {
                is Message.Role -> if (message.attempt != roleAppliedFor) {
                    roleAppliedFor = message.attempt
                    attempt = message.attempt
                    applyRole(controller = !message.controllerIsHost)
                }

                is Message.Join ->
                    if (iAmController && message.attempt == attempt) startAsController(message)

                is Message.Start ->
                    if (!iAmController && message.attempt == attempt) startAsControlee(message)

                is Message.Retry ->
                    if (message.attempt == attempt) onAttemptFailed("peer asked to retry")

                is Message.Rssi -> onSignal(message.dbm)
                Message.NoUwb -> useSignalStrengthOnly(string(R.string.peer_no_uwb))
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
        friendFix = Fix(
            lat = message.latE7 / 1e7,
            lon = message.lonE7 / 1e7,
            accuracyM = message.accuracyM.toFloat(),
            timeMs = SystemClock.elapsedRealtime(),
        )
        updateMap()
    }

    private fun onFix(fix: Fix) {
        myFix = fix
        declinationDeg = GeomagneticField(
            fix.lat.toFloat(), fix.lon.toFloat(), 0f, System.currentTimeMillis(),
        ).declination
        _state.update { it.copy(myAccuracyM = fix.accuracyM) }
        updateMap()
    }

    private fun shareLocation(now: Long) {
        val fix = myFix ?: return
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

    private data class Range(val meters: Float, val sigmaM: Float, val source: RangeSource)

    private fun bestRange(now: Long): Range? {
        val s = _state.value
        val awareDistance = s.awareDistanceM?.takeIf { now - lastAwareMs < AWARE_STALE_MS }
        return when {
            s.distanceM != null -> Range(s.distanceM, UWB_SIGMA_M, RangeSource.Uwb)
            awareDistance != null -> Range(awareDistance, maxOf(WIFI_MIN_SIGMA_M, awareStdM), RangeSource.Wifi)
            s.signalDistanceM != null -> Range(s.signalDistanceM, s.signalDistanceM * 0.5f, RangeSource.Bluetooth)
            else -> null
        }
    }

    /**
     * Where the gyroscope frame points relative to true north, blending the compass with
     * UWB-versus-GPS calibration.
     */
    private fun frameOffset(): Bearing? {
        val magnetic = orientation.northOffsetRad?.let {
            val deg = Math.toDegrees(it.toDouble()).toFloat() - declinationDeg
            Bearing(wrapPositive(deg), if (orientation.northReliable) COMPASS_SIGMA_DEG else COMPASS_POOR_SIGMA_DEG)
        }
        return BearingFusion.combine(listOfNotNull(magnetic, frameCalibrator.estimate()))
    }

    private fun freshFixes(now: Long): Pair<Fix, Fix>? {
        val me = myFix?.takeIf { now - it.timeMs < FIX_MAX_AGE_MS } ?: return null
        val friend = friendFix?.takeIf { now - it.timeMs < FIX_MAX_AGE_MS } ?: return null
        return me to friend
    }

    /** Learns the frame's yaw from moments when UWB has a clear line and both GPS fixes are good. */
    private fun calibrate(now: Long) {
        val (me, friend) = freshFixes(now) ?: return
        val direction = directionFilter.direction() ?: return
        if (now - lastGoodDirectionMs > DIRECTION_LIVE_MS) return
        val range = _state.value.distanceM ?: return
        val positionSigma = hypot(me.accuracyM, friend.accuracyM)
        if (positionSigma > CALIBRATION_MAX_GPS_ERROR_M) return
        if (range < maxOf(CALIBRATION_MIN_RANGE_M, 3f * positionSigma)) return
        val offset = Geo.offset(me, friend)
        val trueBearing = wrapPositive(Math.toDegrees(atan2(offset[0], offset[1]).toDouble()).toFloat())
        val gpsSigma = Math.toDegrees(atan2(positionSigma, range).toDouble()).toFloat()
        frameCalibrator.add(
            wrapPositive(bearingOf(direction) - trueBearing),
            hypot(gpsSigma, UWB_SIGMA_DEG),
        )
    }

    /**
     * Compares where each azimuth sign would put the friend with the GPS bearing, using the
     * compass alone for north so that a wrong sign cannot leak into the reference.
     */
    private fun checkFlip(now: Long) {
        val reading = lastReading?.takeIf { now - it.timeMs < FLIP_READING_MAX_AGE_MS } ?: return
        val (me, friend) = freshFixes(now) ?: return
        val range = _state.value.distanceM ?: return
        val north = orientation.northOffsetRad?.takeIf { orientation.northReliable } ?: return
        val positionSigma = hypot(me.accuracyM, friend.accuracyM)
        if (positionSigma > CALIBRATION_MAX_GPS_ERROR_M) return
        if (range < maxOf(CALIBRATION_MIN_RANGE_M, 3f * positionSigma)) return

        val reported = Geometry.deviceVector(reading.azimuthDeg, reading.elevationDeg)
        if (Geometry.weight(Geometry.offBoresightDeg(reported)) < FLIP_MIN_WEIGHT) return
        val negated = Geometry.deviceVector(-reading.azimuthDeg, reading.elevationDeg)

        val offset = Geo.offset(me, friend)
        val trueBearing = wrapPositive(Math.toDegrees(atan2(offset[0], offset[1]).toDouble()).toFloat())
        val frameDeg = wrapPositive(Math.toDegrees(north.toDouble()).toFloat() - declinationDeg)
        val gpsSigma = Math.toDegrees(atan2(positionSigma, range).toDouble()).toFloat()
        flipDetector.add(
            reportedBearing = bearingOf(Geometry.toWorld(reading.rotation, reported)),
            negatedBearing = bearingOf(Geometry.toWorld(reading.rotation, negated)),
            referenceBearing = wrapPositive(trueBearing + frameDeg),
            sigmaDeg = hypot(gpsSigma, COMPASS_SIGMA_DEG),
        )
        val verdict = flipDetector.verdict ?: return
        if (verdict == _state.value.flipAuto) return
        log("Flip: ${if (verdict) "azimuth is reversed" else "azimuth is correct"} (score %.1f)".format(Locale.US, flipDetector.score))
        prefs.edit().putBoolean(PREF_FLIP_LEARNED, verdict).apply()
        val mirror = mirrorFor(_state.value.flipMode, verdict)
        if (mirror != _state.value.mirror) onMirrorChanged()
        _state.update { it.copy(flipAuto = verdict, mirror = mirror) }
    }

    /** One direction from UWB and GPS, in the gyroscope frame, with the vertical angle from UWB. */
    private class Fused(val bearing: Bearing, val elevationDeg: Float, val source: DirectionSource) {
        val world: FloatArray
            get() {
                val b = Math.toRadians(bearing.deg.toDouble())
                val e = Math.toRadians(elevationDeg.toDouble())
                return floatArrayOf(
                    (kotlin.math.sin(b) * kotlin.math.cos(e)).toFloat(),
                    (kotlin.math.cos(b) * kotlin.math.cos(e)).toFloat(),
                    kotlin.math.sin(e).toFloat(),
                )
            }
    }

    private fun fuseDirection(now: Long): Fused? {
        val direction = directionFilter.direction()
        val age = now - lastGoodDirectionMs
        val uwb = direction?.let {
            val stale = maxOf(0L, age - DIRECTION_LIVE_MS) / 1000f
            Bearing(bearingOf(it), minOf(UWB_SIGMA_DEG + stale * UWB_AGING_DEG_PER_S, 90f))
        }
        val gps = gpsBearing
        val combined = BearingFusion.combine(listOfNotNull(uwb, gps)) ?: return null
        val source = when {
            uwb != null && gps != null -> {
                val wUwb = 1f / (uwb.sigmaDeg * uwb.sigmaDeg)
                val wGps = 1f / (gps.sigmaDeg * gps.sigmaDeg)
                when {
                    wUwb >= 4f * wGps -> DirectionSource.Uwb
                    wGps >= 4f * wUwb -> DirectionSource.Gps
                    else -> DirectionSource.Both
                }
            }

            uwb != null -> DirectionSource.Uwb
            else -> DirectionSource.Gps
        }
        val elevation = if (direction != null && age < ELEVATION_HOLD_MS) {
            Geometry.worldElevationDeg(direction)
        } else {
            0f
        }
        return Fused(combined, elevation, source)
    }

    private fun updateMap() {
        val now = SystemClock.elapsedRealtime()
        val range = bestRange(now)
        val fixes = freshFixes(now)
        val frameNow = frameOffset()
        frame = frameNow

        // GPS bearing to the friend, moved into the gyroscope frame.
        val gpsDirection = if (fixes != null && frameNow != null) {
            val (me, friend) = fixes
            val offset = Geo.offset(me, friend)
            val length = hypot(offset[0], offset[1])
            val positionSigma = hypot(me.accuracyM, friend.accuracyM)
            if (length < MIN_GPS_BASELINE_M) {
                null
            } else {
                val trueBearing = wrapPositive(Math.toDegrees(atan2(offset[0], offset[1]).toDouble()).toFloat())
                val reference = range?.meters ?: length
                val sigma = Math.toDegrees(atan2(positionSigma, reference).toDouble()).toFloat()
                    .coerceIn(MIN_GPS_SIGMA_DEG, MAX_GPS_SIGMA_DEG)
                Bearing(wrapPositive(trueBearing + frameNow.deg), hypot(sigma, frameNow.sigmaDeg))
            }
        } else {
            null
        }
        gpsBearing = gpsDirection

        val fused = fuseDirection(now)
        val trueBearing = if (fused != null && frameNow != null) {
            wrapPositive(fused.bearing.deg - frameNow.deg)
        } else {
            null
        }
        val bearingSigma = fused?.let { hypot(it.bearing.sigmaDeg, frameNow?.sigmaDeg ?: 0f) } ?: 0f
        val estimate = Fusion.estimate(
            me = fixes?.first,
            friend = fixes?.second,
            rangeM = range?.meters,
            rangeSigmaM = range?.sigmaM ?: 0f,
            bearingDeg = trueBearing,
            bearingSigmaDeg = bearingSigma,
        )
        val calibrated = frameCalibrator.estimate()
        val info = frameNow?.let {
            val how = when {
                calibrated != null && orientation.northOffsetRad != null -> "compass + UWB/GPS"
                calibrated != null -> "UWB/GPS"
                else -> "compass"
            }
            "north: $how, ±%.0f°".format(Locale.US, it.sigmaDeg)
        }
        _state.update {
            it.copy(
                rangeM = range?.meters,
                rangeSource = range?.source,
                friend = estimate,
                friendGps = fixes != null,
                frameInfo = info,
                trend = currentTrend(now),
            )
        }
    }

    private fun logRangingCapabilities() {
        if (Build.VERSION.SDK_INT < 36) return
        runCatching {
            val app = getApplication<Application>()
            val manager = app.getSystemService(android.ranging.RangingManager::class.java) ?: return
            manager.registerCapabilitiesCallback(app.mainExecutor) { capabilities ->
                val names = mapOf(
                    android.ranging.RangingManager.UWB to "UWB",
                    android.ranging.RangingManager.BLE_CS to "Bluetooth channel sounding",
                    android.ranging.RangingManager.WIFI_NAN_RTT to "Wi-Fi RTT",
                    android.ranging.RangingManager.BLE_RSSI to "Bluetooth RSSI",
                )
                val text = capabilities.technologyAvailability.entries.joinToString(", ") {
                    val state = if (it.value == android.ranging.RangingCapabilities.ENABLED) "yes" else "no"
                    "${names[it.key] ?: it.key}: $state"
                }
                log("Android ranging: $text")
            }
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
        calibrate(now)
        updateMap()
        val s = _state.value
        if (s.phase != Phase.Ranging || !rangingStarted) return
        val silentMs = SystemClock.elapsedRealtime() - lastPositionMs
        if (silentMs > UWB_STALE_MS && s.distanceM != null) {
            _state.update { it.copy(distanceM = null, directionLive = false) }
        }
        if (silentMs > UWB_FORGET_DIRECTION_MS) {
            directionFilter.reset()
            arrowSmoother.reset()
        }
        if (s.role == Role.Host && silentMs > UWB_RESTART_MS) {
            log("UWB: no measurements for ${silentMs / 1000}s, restarting")
            startAttempt(attempt + 1)
        }
    }

    /**
     * Some phone pairs only range when a particular one is the UWB controller, so the
     * host tries each assignment in turn until a measurement arrives, then sticks with it.
     */
    private fun startAttempt(n: Int) {
        attempt = n and 0xFF
        val controllerIsHost = workingControllerIsHost ?: (attempt % 2 == 0)
        sendRole(attempt, controllerIsHost)
        val previous = attemptJob
        attemptJob = viewModelScope.launch {
            delay(ATTEMPT_TIMEOUT_MS)
            onAttemptFailed("no measurement after ${ATTEMPT_TIMEOUT_MS / 1000}s")
        }
        previous?.cancel()
        applyRole(controller = controllerIsHost)
    }

    /** A notification sent right after the joiner subscribes can be dropped, so repeat it until ranging starts. */
    private fun sendRole(forAttempt: Int, controllerIsHost: Boolean) {
        roleResendJob?.cancel()
        roleResendJob = viewModelScope.launch {
            repeat(ROLE_SENDS) {
                if (rangingStarted || attempt != forAttempt) return@launch
                send(Message.Role(forAttempt, controllerIsHost))
                delay(ROLE_RESEND_MS)
            }
        }
    }

    private fun scheduleAttempt(n: Int) {
        attemptJob?.cancel()
        attemptJob = viewModelScope.launch {
            delay(RETRY_DELAY_MS)
            startAttempt(n)
        }
    }

    private fun onAttemptFailed(reason: String) {
        if (rangingStarted) return
        if (_state.value.role == Role.Joiner) {
            log("UWB: attempt ${attempt + 1} failed ($reason), asking host to retry")
            send(Message.Retry(attempt))
            return
        }
        log("UWB: attempt ${attempt + 1} failed ($reason)")
        failedAttempts++
        if (failedAttempts >= QUIET_AFTER_ATTEMPTS) {
            _state.update {
                it.copy(uwbActive = false, message = string(R.string.uwb_not_starting))
            }
        }
        scheduleAttempt(attempt + 1)
    }

    private fun applyRole(controller: Boolean) {
        rangingJob?.cancel()
        pendingScope = null
        rangingStarted = false
        resetFilters()
        iAmController = controller
        log("UWB: attempt ${attempt + 1}, this phone is the ${if (controller) "controller" else "controlee"}")
        val thisAttempt = attempt
        viewModelScope.launch {
            try {
                val reason = uwbUnavailableReason()
                if (reason != null) {
                    send(Message.NoUwb)
                    useSignalStrengthOnly(reason)
                    return@launch
                }
                if (!controller) {
                    val scope = uwbManager!!.controleeSessionScope()
                    if (thisAttempt != attempt) return@launch
                    pendingScope = scope
                    logCapabilities(scope)
                    send(Message.Join(thisAttempt, scope.localAddress.address))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("UWB: ${e.javaClass.simpleName}: ${e.message}")
                onAttemptFailed("error ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun uwbUnavailableReason(): String? {
        val manager = uwbManager ?: return string(R.string.uwb_no_hardware)
        return if (manager.isAvailable()) null else string(R.string.uwb_off)
    }

    private fun logCapabilities(scope: UwbClientSessionScope) {
        val c = scope.rangingCapabilities
        log(
            "UWB: distance=${c.isDistanceSupported} azimuth=${c.isAzimuthalAngleSupported} " +
                "elevation=${c.isElevationAngleSupported} channels=${c.supportedChannels}",
        )
    }

    private fun useSignalStrengthOnly(reason: String) {
        log("UWB unavailable: $reason")
        attemptJob?.cancel()
        attemptJob = null
        _state.update { it.copy(phase = Phase.Ranging, uwbActive = false, message = reason) }
    }

    private suspend fun startAsController(join: Message.Join) {
        try {
            val scope = uwbManager!!.controllerSessionScope()
            if (join.attempt != attempt) return
            logCapabilities(scope)
            val channel = scope.uwbComplexChannel
            log("UWB: channel ${channel.channel}, preamble ${channel.preambleIndex}")
            val sessionId = Random.nextInt()
            val key = Random.nextBytes(Protocol.SESSION_KEY_SIZE)
            val params = RangingParameters(
                uwbConfigType = RangingParameters.CONFIG_UNICAST_DS_TWR,
                sessionId = sessionId,
                subSessionId = 0,
                sessionKeyInfo = key,
                subSessionKeyInfo = null,
                complexChannel = channel,
                peerDevices = listOf(UwbDevice(UwbAddress(join.uwbAddress))),
                updateRateType = RangingParameters.RANGING_UPDATE_RATE_FREQUENT,
            )
            send(
                Message.Start(
                    attempt = attempt,
                    uwbAddress = scope.localAddress.address,
                    channel = channel.channel,
                    preambleIndex = channel.preambleIndex,
                    sessionId = sessionId,
                    sessionKey = key,
                ),
            )
            _state.update { it.copy(phase = Phase.Ranging, uwbActive = true, message = null) }
            range(scope, params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("UWB: ${e.javaClass.simpleName}: ${e.message}")
            onAttemptFailed("error ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun startAsControlee(start: Message.Start) {
        val scope = pendingScope ?: return
        try {
            log("UWB: got session, channel ${start.channel}")
            val params = RangingParameters(
                uwbConfigType = RangingParameters.CONFIG_UNICAST_DS_TWR,
                sessionId = start.sessionId,
                subSessionId = 0,
                sessionKeyInfo = start.sessionKey,
                subSessionKeyInfo = null,
                complexChannel = UwbComplexChannel(start.channel, start.preambleIndex),
                peerDevices = listOf(UwbDevice(UwbAddress(start.uwbAddress))),
                updateRateType = RangingParameters.RANGING_UPDATE_RATE_FREQUENT,
            )
            _state.update { it.copy(phase = Phase.Ranging, uwbActive = true, message = null) }
            range(scope, params)
        } catch (e: Exception) {
            log("UWB: ${e.javaClass.simpleName}: ${e.message}")
            onAttemptFailed("error ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun range(scope: UwbClientSessionScope, params: RangingParameters) {
        rangingJob?.cancel()
        rangingJob = viewModelScope.launch {
            try {
                scope.prepareSession(params).collect { result ->
                    when (result) {
                        is RangingResult.RangingResultPosition -> onPosition(result.position)
                        is RangingResult.RangingResultPeerDisconnected -> {
                            log("UWB: peer out of range (${result.reason})")
                            _state.update { it.copy(directionLive = false, distanceM = null) }
                        }

                        is RangingResult.RangingResultInitialized -> log("UWB: session started")
                        is RangingResult.RangingResultFailure -> {
                            log("UWB: ranging failed (${result.reason})")
                            onAttemptFailed("ranging failure ${result.reason}")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("UWB: ${e.javaClass.simpleName}: ${e.message}")
                onAttemptFailed("error ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun onPosition(position: RangingPosition) {
        lastPositionMs = SystemClock.elapsedRealtime()
        if (!rangingStarted) {
            rangingStarted = true
            failedAttempts = 0
            attemptJob?.cancel()
            workingControllerIsHost = if (_state.value.role == Role.Host) iAmController else !iAmController
            log("UWB: ranging")
        }
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
            lastReading = Reading(azimuth, elevation, r, lastPositionMs)
            val signed = if (_state.value.mirror) -azimuth else azimuth
            val local = Geometry.deviceVector(signed, elevation)
            val off = Geometry.offBoresightDeg(local)
            val weight = Geometry.weight(off) * distanceTrust
            val used = directionFilter.add(Geometry.toWorld(r, local), weight)
            if (used) lastGoodDirectionMs = SystemClock.elapsedRealtime()
            raw += String.format(Locale.US, " off=%.0f w=%.2f%s", off, weight, if (used) "" else " skip")
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
        val fused = fuseDirection(now)
        val world = fused?.world
        val raw = world?.let { Geometry.arrowDegrees(r, it) }
        val arrow = if (raw != null) {
            arrowSmoother.add(raw)
        } else {
            arrowSmoother.reset()
            null
        }
        val elevation = world?.let { Geometry.worldElevationDeg(it) }
        val live = directionFilter.confidence() > LIVE_CONFIDENCE &&
            now - lastGoodDirectionMs < DIRECTION_LIVE_MS
        val flat = Geometry.isFlat(r)
        val heading = frame?.let {
            wrapPositive(Geometry.headingDeg(r, Math.toRadians(it.deg.toDouble()).toFloat()))
        }
        _state.update {
            if (it.phase != Phase.Ranging) {
                it
            } else {
                it.copy(
                    arrowDeg = arrow,
                    elevationDeg = elevation,
                    directionLive = live,
                    arrowSource = fused?.source,
                    headingDeg = heading,
                    flat = flat,
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
        const val FLIP_READING_MAX_AGE_MS = 1_500L
        const val FLIP_MIN_WEIGHT = 0.2f
        val IMPERIAL_COUNTRIES = setOf("US", "LR", "MM")
        const val UWB_SIGMA_DEG = 12f
        const val UWB_AGING_DEG_PER_S = 2f
        const val ELEVATION_HOLD_MS = 10_000L
        const val COMPASS_SIGMA_DEG = 20f
        const val COMPASS_POOR_SIGMA_DEG = 40f
        const val MIN_GPS_BASELINE_M = 0.5f
        const val MIN_GPS_SIGMA_DEG = 5f
        const val MAX_GPS_SIGMA_DEG = 120f
        const val CALIBRATION_MAX_GPS_ERROR_M = 15f
        const val CALIBRATION_MIN_RANGE_M = 8f
        const val MAX_LOG_LINES = 20
        const val WATCHDOG_START_MS = 12_000L
        const val PEER_SILENT_MS = 8_000L
        const val ROLE_SENDS = 5
        const val ROLE_RESEND_MS = 1_500L
        const val WATCHDOG_INTERVAL_MS = 4_000L
        const val TICK_MS = 1_000L
        const val ATTEMPT_TIMEOUT_MS = 12_000L
        const val RETRY_DELAY_MS = 2_000L
        const val QUIET_AFTER_ATTEMPTS = 2
        const val SHARE_INTERVAL_MS = 2_000L
        const val AWARE_STALE_MS = 5_000L
        const val FIX_MAX_AGE_MS = 60_000L
        const val UWB_SIGMA_M = 0.5f
        const val WIFI_MIN_SIGMA_M = 2f
        const val UWB_STALE_MS = 3_000L
        const val UWB_FORGET_DIRECTION_MS = 30_000L
        const val UWB_RESTART_MS = 20_000L
        const val DIRECTION_LIVE_MS = 2_000L
        const val DISTANCE_JITTER_M = 1.5f
        const val ARROW_INTERVAL_MS = 33L
        const val LIVE_CONFIDENCE = 1.0f
    }
}
