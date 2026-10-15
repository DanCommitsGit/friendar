package io.github.dancommitsgit.friendar.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import io.github.dancommitsgit.friendar.fusion.Fix

/** Delivers the best recent position from the GPS, fused and network providers on the main thread. */
@SuppressLint("MissingPermission")
class LocationTracker(private val context: Context, private val onFix: (Fix) -> Unit) : LocationListener {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var best: Fix? = null
    private var gpsFixMs = 0L

    /** Returns false when location permission is missing or no provider exists. */
    fun start(): Boolean {
        val fine = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return false
        val lm = manager ?: return false
        var any = false
        for (provider in PROVIDERS) {
            if (!lm.allProviders.contains(provider)) continue
            try {
                lm.requestLocationUpdates(provider, UPDATE_MS, 0f, this, Looper.getMainLooper())
                any = true
            } catch (_: SecurityException) {
                continue
            } catch (_: IllegalArgumentException) {
                continue
            }
        }
        return any
    }

    fun stop() {
        manager?.removeUpdates(this)
        best = null
    }

    override fun onLocationChanged(location: Location) {
        val now = SystemClock.elapsedRealtime()
        if (location.provider == LocationManager.GPS_PROVIDER) gpsFixMs = now
        // Network fixes are coarse, so ignore them while GPS is current.
        if (location.provider != LocationManager.GPS_PROVIDER && now - gpsFixMs < GPS_PREFERRED_MS) return
        val fix = Fix(location.latitude, location.longitude, location.accuracy, now)
        best = fix
        onFix(fix)
    }

    @Deprecated("Required on API 29 and below")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

    private companion object {
        val PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.FUSED_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )
        const val UPDATE_MS = 1000L
        const val GPS_PREFERRED_MS = 10_000L
    }
}
