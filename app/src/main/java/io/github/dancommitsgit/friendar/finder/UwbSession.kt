package io.github.dancommitsgit.friendar.finder

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingPosition
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
import androidx.core.uwb.UwbClientSessionScope
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import io.github.dancommitsgit.friendar.R
import io.github.dancommitsgit.friendar.link.Message
import io.github.dancommitsgit.friendar.link.Protocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * UWB ranging with the friend, set up over the Bluetooth link. Some phone pairs only range
 * when a particular one is the UWB controller, so the host tries each assignment in turn
 * until a measurement arrives, then sticks with it.
 */
internal class UwbSession(
    private val context: Context,
    private val viewScope: CoroutineScope,
    private val state: MutableStateFlow<FinderState>,
    private val send: (Message) -> Unit,
    private val log: (String) -> Unit,
    private val resetFilters: () -> Unit,
    private val forgetDirection: () -> Unit,
    private val onPosition: (RangingPosition) -> Unit,
) {
    private var rangingJob: Job? = null
    private var attemptJob: Job? = null
    private var roleResendJob: Job? = null
    private var pendingScope: UwbClientSessionScope? = null
    private val attempts = AttemptTracker()

    private val attempt: Int get() = attempts.attempt
    private val rangingStarted: Boolean get() = attempts.rangingStarted

    /** When the latest UWB position arrived, on the elapsed-realtime clock. */
    var lastPositionMs = 0L
        private set

    private val uwbManager: UwbManager? by lazy {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_UWB)) {
            null
        } else {
            runCatching { UwbManager.createInstance(context) }.getOrNull()
        }
    }

    private fun string(id: Int) = context.getString(id)

    /** Cancels the attempt in progress and forgets which assignment worked. */
    fun reset() {
        rangingJob?.cancel()
        rangingJob = null
        attemptJob?.cancel()
        attemptJob = null
        roleResendJob?.cancel()
        roleResendJob = null
        pendingScope = null
        attempts.reset()
    }

    fun onRole(message: Message.Role) {
        val controller = attempts.adoptRole(message.attempt, message.controllerIsHost) ?: return
        applyRole(controller)
    }

    suspend fun onJoin(message: Message.Join) {
        if (attempts.acceptsJoin(message.attempt)) startAsController(message)
    }

    fun onStart(message: Message.Start) {
        if (attempts.acceptsStart(message.attempt)) startAsControlee(message)
    }

    fun onRetry(message: Message.Retry) {
        if (attempts.isCurrent(message.attempt)) onAttemptFailed("peer asked to retry")
    }

    /** Restarts ranging when the measurements have stopped, and drops values that have gone stale. */
    fun tick() {
        val s = state.value
        if (s.phase != Phase.Ranging || !rangingStarted) return
        val silentMs = SystemClock.elapsedRealtime() - lastPositionMs
        val actions = staleActions(silentMs, hasDistance = s.distanceM != null, isHost = s.role == Role.Host)
        if (actions.dropDistance) state.update { it.copy(distanceM = null, directionLive = false) }
        if (actions.forgetDirection) forgetDirection()
        if (actions.restart) {
            log("UWB: no measurements for ${silentMs / 1000}s, restarting")
            startAttempt(attempt + 1)
        }
    }

    fun startAttempt(n: Int) {
        val controllerIsHost = attempts.begin(n)
        sendRole(attempt, controllerIsHost)
        val previous = attemptJob
        attemptJob = viewScope.launch {
            delay(ATTEMPT_TIMEOUT_MS)
            onAttemptFailed("no measurement after ${ATTEMPT_TIMEOUT_MS / 1000}s")
        }
        previous?.cancel()
        applyRole(controller = controllerIsHost)
    }

    /** A notification sent right after the joiner subscribes can be dropped, so repeat it until ranging starts. */
    private fun sendRole(forAttempt: Int, controllerIsHost: Boolean) {
        roleResendJob?.cancel()
        roleResendJob = viewScope.launch {
            repeat(ROLE_SENDS) {
                if (!attempts.roleResendDue(forAttempt)) return@launch
                send(Message.Role(forAttempt, controllerIsHost))
                delay(ROLE_RESEND_MS)
            }
        }
    }

    private fun scheduleAttempt(n: Int) {
        attemptJob?.cancel()
        attemptJob = viewScope.launch {
            delay(RETRY_DELAY_MS)
            startAttempt(n)
        }
    }

    private fun onAttemptFailed(reason: String) {
        when (attempts.failed(asJoiner = state.value.role == Role.Joiner)) {
            FailureAction.Ignore -> Unit

            FailureAction.AskHostToRetry -> {
                log("UWB: attempt ${attempt + 1} failed ($reason), asking host to retry")
                send(Message.Retry(attempt))
            }

            FailureAction.TryNext -> {
                log("UWB: attempt ${attempt + 1} failed ($reason)")
                scheduleAttempt(attempt + 1)
            }

            FailureAction.TryNextAndWarn -> {
                log("UWB: attempt ${attempt + 1} failed ($reason)")
                state.update {
                    it.copy(uwbActive = false, message = string(R.string.uwb_not_starting))
                }
                scheduleAttempt(attempt + 1)
            }
        }
    }

    private fun applyRole(controller: Boolean) {
        rangingJob?.cancel()
        pendingScope = null
        resetFilters()
        attempts.applyRole(controller)
        log("UWB: attempt ${attempt + 1}, this phone is the ${if (controller) "controller" else "controlee"}")
        val thisAttempt = attempt
        viewScope.launch {
            try {
                val reason = uwbUnavailableReason()
                if (reason != null) {
                    send(Message.NoUwb)
                    useSignalStrengthOnly(reason)
                    return@launch
                }
                if (!controller) {
                    val scope = uwbManager!!.controleeSessionScope()
                    if (!attempts.isCurrent(thisAttempt)) return@launch
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

    /** Falls back to the Bluetooth signal when UWB cannot be used. */
    fun useSignalStrengthOnly(reason: String) {
        log("UWB unavailable: $reason")
        attemptJob?.cancel()
        attemptJob = null
        state.update { it.copy(phase = Phase.Ranging, uwbActive = false, message = reason) }
    }

    private suspend fun startAsController(join: Message.Join) {
        try {
            val scope = uwbManager!!.controllerSessionScope()
            if (!attempts.isCurrent(join.attempt)) return
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
            state.update { it.copy(phase = Phase.Ranging, uwbActive = true, message = null) }
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
            state.update { it.copy(phase = Phase.Ranging, uwbActive = true, message = null) }
            range(scope, params)
        } catch (e: Exception) {
            log("UWB: ${e.javaClass.simpleName}: ${e.message}")
            onAttemptFailed("error ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun range(scope: UwbClientSessionScope, params: RangingParameters) {
        rangingJob?.cancel()
        rangingJob = viewScope.launch {
            try {
                scope.prepareSession(params).collect { result ->
                    when (result) {
                        is RangingResult.RangingResultPosition -> handlePosition(result.position)
                        is RangingResult.RangingResultPeerDisconnected -> {
                            log("UWB: peer out of range (${result.reason})")
                            state.update { it.copy(directionLive = false, distanceM = null) }
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

    private fun handlePosition(position: RangingPosition) {
        lastPositionMs = SystemClock.elapsedRealtime()
        if (attempts.measured(asHost = state.value.role == Role.Host)) {
            attemptJob?.cancel()
            log("UWB: ranging")
        }
        onPosition(position)
    }

    /** Logs what the platform ranging service reports, on Android 16 and later. */
    fun logPlatformCapabilities() {
        if (Build.VERSION.SDK_INT < 36) return
        runCatching {
            val manager = context.getSystemService(android.ranging.RangingManager::class.java) ?: return
            manager.registerCapabilitiesCallback(context.mainExecutor) { capabilities ->
                val names = mapOf(
                    android.ranging.RangingManager.UWB to "UWB",
                    android.ranging.RangingManager.BLE_CS to "Bluetooth channel sounding",
                    android.ranging.RangingManager.WIFI_NAN_RTT to "Wi-Fi RTT",
                    android.ranging.RangingManager.BLE_RSSI to "Bluetooth RSSI",
                )
                val text = capabilities.technologyAvailability.entries.joinToString(", ") {
                    val enabled = if (it.value == android.ranging.RangingCapabilities.ENABLED) "yes" else "no"
                    "${names[it.key] ?: it.key}: $enabled"
                }
                log("Android ranging: $text")
            }
        }
    }

    private companion object {
        const val ROLE_SENDS = 5
        const val ROLE_RESEND_MS = 1_500L
        const val ATTEMPT_TIMEOUT_MS = 12_000L
        const val RETRY_DELAY_MS = 2_000L
    }
}
