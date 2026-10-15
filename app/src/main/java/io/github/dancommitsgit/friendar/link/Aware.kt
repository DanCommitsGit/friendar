package io.github.dancommitsgit.friendar.link

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

private const val SERVICE_NAME = "friendar"

/** Callbacks arrive on the main thread. */
interface AwareListener {
    fun onAwareRange(meters: Float, stdDevMm: Int)
    fun onAwareMessage(bytes: ByteArray)
    fun onAwareLog(text: String)
}

/**
 * Finds the other phone over Wi-Fi Aware and measures its distance with Wi-Fi round-trip
 * time. Wi-Fi passes through walls that block UWB. Both phones publish and subscribe,
 * so each can range to the other and send it short messages.
 */
@SuppressLint("MissingPermission")
class AwareLink(private val context: Context, private val listener: AwareListener) {
    private val manager = context.getSystemService(WifiAwareManager::class.java)
    private val rtt = context.getSystemService(WifiRttManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var session: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private var peer: PeerHandle? = null
    private var receiver: BroadcastReceiver? = null
    private var stopped = true
    private var ranging = false
    private var failures = 0
    private var messageId = 0

    fun start() {
        val pm = context.packageManager
        if (manager == null || !pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            listener.onAwareLog("Wi-Fi Aware: not supported")
            return
        }
        stopped = false
        val stateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                closeSessions()
                if (!stopped && manager.isAvailable) attach()
            }
        }
        receiver = stateReceiver
        ContextCompat.registerReceiver(
            context,
            stateReceiver,
            IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (manager.isAvailable) {
            attach()
        } else {
            listener.onAwareLog("Wi-Fi Aware: not available right now (is Wi-Fi on?)")
        }
        handler.postDelayed(::rangeTick, RANGE_INTERVAL_MS)
    }

    fun send(bytes: ByteArray) {
        val target = peer ?: return
        val discovery = subscribeSession ?: return
        runCatching { discovery.sendMessage(target, messageId++ and 0x7FFFFFFF, bytes) }
    }

    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        closeSessions()
    }

    private fun closeSessions() {
        peer = null
        ranging = false
        runCatching { publishSession?.close() }
        runCatching { subscribeSession?.close() }
        runCatching { session?.close() }
        publishSession = null
        subscribeSession = null
        session = null
    }

    private fun attach() {
        manager?.attach(
            object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    if (stopped) {
                        session.close()
                        return
                    }
                    this@AwareLink.session = session
                    listener.onAwareLog("Wi-Fi Aware: attached")
                    publish(session)
                    subscribe(session)
                }

                override fun onAttachFailed() {
                    listener.onAwareLog("Wi-Fi Aware: attach failed")
                }
            },
            handler,
        )
    }

    private fun publish(session: WifiAwareSession) {
        val config = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setRangingEnabled(true)
            .build()
        session.publish(
            config,
            object : DiscoverySessionCallback() {
                override fun onPublishStarted(session: PublishDiscoverySession) {
                    publishSession = session
                    listener.onAwareLog("Wi-Fi Aware: publishing")
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    listener.onAwareMessage(message)
                }

                override fun onSessionConfigFailed() {
                    listener.onAwareLog("Wi-Fi Aware: publish failed")
                }
            },
            handler,
        )
    }

    private fun subscribe(session: WifiAwareSession) {
        val config = SubscribeConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .build()
        session.subscribe(
            config,
            object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                    subscribeSession = session
                    listener.onAwareLog("Wi-Fi Aware: subscribed")
                }

                override fun onServiceDiscovered(
                    peerHandle: PeerHandle,
                    serviceSpecificInfo: ByteArray?,
                    matchFilter: MutableList<ByteArray>?,
                ) {
                    if (peer == null) listener.onAwareLog("Wi-Fi Aware: friend found")
                    peer = peerHandle
                    failures = 0
                }

                override fun onServiceLost(peerHandle: PeerHandle, reason: Int) {
                    if (peerHandle == peer) {
                        peer = null
                        listener.onAwareLog("Wi-Fi Aware: friend out of range")
                    }
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    listener.onAwareMessage(message)
                }

                override fun onSessionConfigFailed() {
                    listener.onAwareLog("Wi-Fi Aware: subscribe failed")
                }
            },
            handler,
        )
    }

    private fun rangeTick() {
        if (stopped) return
        handler.postDelayed(::rangeTick, RANGE_INTERVAL_MS)
        val target = peer ?: return
        val mgr = rtt ?: return
        if (ranging || !mgr.isAvailable) return
        ranging = true
        try {
            mgr.startRanging(
                RangingRequest.Builder().addWifiAwarePeer(target).build(),
                context.mainExecutor,
                object : RangingResultCallback() {
                    override fun onRangingFailure(code: Int) {
                        ranging = false
                        noteFailure("code $code")
                    }

                    override fun onRangingResults(results: List<RangingResult>) {
                        ranging = false
                        val result = results.firstOrNull() ?: return
                        if (result.status == RangingResult.STATUS_SUCCESS) {
                            if (failures > 0) listener.onAwareLog("Wi-Fi RTT: measuring")
                            failures = 0
                            listener.onAwareRange(result.distanceMm / 1000f, result.distanceStdDevMm)
                        } else {
                            noteFailure("status ${result.status}")
                        }
                    }
                },
            )
        } catch (e: SecurityException) {
            ranging = false
            noteFailure("permission missing")
        } catch (e: IllegalArgumentException) {
            ranging = false
            noteFailure(e.message ?: "bad request")
        }
    }

    private fun noteFailure(reason: String) {
        failures++
        if (failures == 1 || failures % 20 == 0) listener.onAwareLog("Wi-Fi RTT: failed ($reason)")
    }

    private companion object {
        const val RANGE_INTERVAL_MS = 1000L
    }
}
