package com.friendar.locator

import kotlin.math.pow

enum class Trend { Closer, Farther, Steady }

/**
 * Turns Bluetooth signal strength samples into a rough distance and a closer/farther trend.
 * Signal strength passes through walls and bodies that block UWB, but it is noisy,
 * so the distance is only good to roughly a factor of two.
 */
class SignalTracker(private val windowMs: Long = 12_000L) {
    private val samples = ArrayDeque<Pair<Long, Int>>()

    fun add(timeMs: Long, dbm: Int) {
        samples.addLast(timeMs to dbm)
        while (samples.first().first < timeMs - windowMs) samples.removeFirst()
    }

    /** Median of the last five samples. */
    fun smoothedDbm(): Float? {
        if (samples.isEmpty()) return null
        val recent = samples.takeLast(MEDIAN_SIZE).map { it.second }.sorted()
        return recent[recent.size / 2].toFloat()
    }

    /** Compares the start of the window with its end; null until enough history exists. */
    fun trend(): Trend? {
        if (samples.size < MIN_TREND_SAMPLES) return null
        if (samples.last().first - samples.first().first < MIN_TREND_SPAN_MS) return null
        val values = samples.map { it.second }
        val third = values.size / 3
        val diff = values.takeLast(third).average() - values.take(third).average()
        return when {
            diff >= TREND_DB -> Trend.Closer
            diff <= -TREND_DB -> Trend.Farther
            else -> Trend.Steady
        }
    }

    fun reset() = samples.clear()

    companion object {
        private const val MEDIAN_SIZE = 5
        private const val MIN_TREND_SAMPLES = 6
        private const val MIN_TREND_SPAN_MS = 5_000L
        private const val TREND_DB = 3.0

        private const val DBM_AT_ONE_METER = -55f
        private const val PATH_LOSS_EXPONENT = 2.7f

        fun estimateMeters(dbm: Float): Float =
            10f.pow((DBM_AT_ONE_METER - dbm) / (10f * PATH_LOSS_EXPONENT)).coerceIn(0.3f, 100f)
    }
}

/** Closer/farther trend from measured distances, such as Wi-Fi round-trip-time results. */
class RangeTrend(private val windowMs: Long = 12_000L) {
    private val samples = ArrayDeque<Pair<Long, Float>>()

    fun add(timeMs: Long, meters: Float) {
        samples.addLast(timeMs to meters)
        while (samples.first().first < timeMs - windowMs) samples.removeFirst()
    }

    fun trend(): Trend? {
        if (samples.size < MIN_SAMPLES) return null
        if (samples.last().first - samples.first().first < MIN_SPAN_MS) return null
        val values = samples.map { it.second }
        val third = values.size / 3
        val diff = values.takeLast(third).average() - values.take(third).average()
        return when {
            diff <= -CHANGE_M -> Trend.Closer
            diff >= CHANGE_M -> Trend.Farther
            else -> Trend.Steady
        }
    }

    fun reset() = samples.clear()

    private companion object {
        const val MIN_SAMPLES = 5
        const val MIN_SPAN_MS = 5_000L
        const val CHANGE_M = 1.5
    }
}

/** Distance band for a Bluetooth-based estimate, in feet. */
fun roughDistanceText(meters: Float): String {
    val feet = meters * 3.28084f
    return when {
        feet < 10f -> "Under 10 ft"
        feet < 25f -> "10–25 ft"
        feet < 50f -> "25–50 ft"
        feet < 100f -> "50–100 ft"
        else -> "100+ ft"
    }
}
