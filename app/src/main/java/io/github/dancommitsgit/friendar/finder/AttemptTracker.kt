package io.github.dancommitsgit.friendar.finder

/** What to do after a UWB attempt has failed. */
internal enum class FailureAction {
    /** Ranging already started, so the failure is about an old attempt. */
    Ignore,

    /** The joiner cannot change the assignment itself. */
    AskHostToRetry,
    TryNext,

    /** As [TryNext], and the user should be told UWB is not starting. */
    TryNextAndWarn,
}

/** What to do about UWB measurements that have gone quiet. */
internal class StaleActions(val dropDistance: Boolean, val forgetDirection: Boolean, val restart: Boolean)

internal fun staleActions(silentMs: Long, hasDistance: Boolean, isHost: Boolean) = StaleActions(
    dropDistance = silentMs > UWB_STALE_MS && hasDistance,
    forgetDirection = silentMs > UWB_FORGET_DIRECTION_MS,
    restart = isHost && silentMs > UWB_RESTART_MS,
)

/**
 * Which UWB attempt is current, which side is the UWB controller, and which assignment
 * worked. Attempt numbers travel in one byte, so they wrap at 256.
 */
internal class AttemptTracker {
    var attempt = 0
        private set
    var iAmController = false
        private set
    var rangingStarted = false
        private set

    private var failedAttempts = 0
    private var roleAppliedFor = -1

    /** Which side was UWB controller when ranging last worked; the host keeps using it. */
    private var workingControllerIsHost: Boolean? = null

    fun reset() {
        rangingStarted = false
        attempt = 0
        failedAttempts = 0
        roleAppliedFor = -1
        workingControllerIsHost = null
    }

    /** Starts attempt [n] as the host. Returns whether the host is the UWB controller for it. */
    fun begin(n: Int): Boolean {
        attempt = n and 0xFF
        return workingControllerIsHost ?: (attempt % 2 == 0)
    }

    /**
     * Takes the assignment the host announced. Returns whether this phone is the controller,
     * or null when that attempt's assignment has already been taken.
     */
    fun adoptRole(forAttempt: Int, controllerIsHost: Boolean): Boolean? {
        if (forAttempt == roleAppliedFor) return null
        roleAppliedFor = forAttempt
        attempt = forAttempt
        return !controllerIsHost
    }

    fun applyRole(controller: Boolean) {
        rangingStarted = false
        iAmController = controller
    }

    fun isCurrent(forAttempt: Int) = forAttempt == attempt

    fun acceptsJoin(forAttempt: Int) = iAmController && isCurrent(forAttempt)

    fun acceptsStart(forAttempt: Int) = !iAmController && isCurrent(forAttempt)

    /** The host repeats its role announcement until ranging starts or the attempt changes. */
    fun roleResendDue(forAttempt: Int) = !rangingStarted && isCurrent(forAttempt)

    fun failed(asJoiner: Boolean): FailureAction {
        if (rangingStarted) return FailureAction.Ignore
        if (asJoiner) return FailureAction.AskHostToRetry
        failedAttempts++
        return if (failedAttempts >= QUIET_AFTER_ATTEMPTS) FailureAction.TryNextAndWarn else FailureAction.TryNext
    }

    /** Records a measurement. Returns true when it is the first one of this attempt. */
    fun measured(asHost: Boolean): Boolean {
        if (rangingStarted) return false
        rangingStarted = true
        failedAttempts = 0
        workingControllerIsHost = if (asHost) iAmController else !iAmController
        return true
    }

    private companion object {
        const val QUIET_AFTER_ATTEMPTS = 2
    }
}

internal const val UWB_STALE_MS = 3_000L
internal const val UWB_FORGET_DIRECTION_MS = 30_000L
internal const val UWB_RESTART_MS = 20_000L
