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
        tracker.calibrate(now, _state.value.distanceM)
        updateMap()
        val s = _state.value
        if (s.phase != Phase.Ranging || !rangingStarted) return
        val silentMs = SystemClock.elapsedRealtime() - lastPositionMs
        if (silentMs > UWB_STALE_MS && s.distanceM != null) {
            _state.update { it.copy(distanceM = null, directionLive = false) }
        }
        if (silentMs > UWB_FORGET_DIRECTION_MS) tracker.forgetDirection()
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
            val result = tracker.addUwbReading(
                azimuth, elevation, r, _state.value.mirror, distanceTrust, lastPositionMs,
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
        val IMPERIAL_COUNTRIES = setOf("US", "LR", "MM")
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
        const val UWB_STALE_MS = 3_000L
        const val UWB_FORGET_DIRECTION_MS = 30_000L
        const val UWB_RESTART_MS = 20_000L
        const val DISTANCE_JITTER_M = 1.5f
        const val ARROW_INTERVAL_MS = 33L
    }
}
