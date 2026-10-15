package io.github.dancommitsgit.friendar.fusion

enum class RangeSource { Uwb, Wifi, Bluetooth }

/** A distance to the friend with its 1-sigma uncertainty and the measurement it came from. */
data class Range(val meters: Float, val sigmaM: Float, val source: RangeSource)

private const val UWB_SIGMA_M = 0.5f
private const val WIFI_MIN_SIGMA_M = 2f
private const val SIGNAL_SIGMA_FRACTION = 0.5f

/**
 * The most precise distance available: UWB, then Wi-Fi RTT, then Bluetooth signal strength.
 * Pass null for a measurement that is missing or stale. Null when nothing is measured.
 */
fun bestRange(uwbM: Float?, wifiM: Float?, wifiStdM: Float, signalM: Float?): Range? = when {
    uwbM != null -> Range(uwbM, UWB_SIGMA_M, RangeSource.Uwb)
    wifiM != null -> Range(wifiM, maxOf(WIFI_MIN_SIGMA_M, wifiStdM), RangeSource.Wifi)
    signalM != null -> Range(signalM, signalM * SIGNAL_SIGMA_FRACTION, RangeSource.Bluetooth)
    else -> null
}
