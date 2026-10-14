package io.github.dancommitsgit.friendar

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Phone attitude as a row-major 3x3 matrix mapping phone frame to a world frame
 * with z up. Uses the gyroscope-based game rotation vector so indoor magnetic
 * interference cannot shift the frame; its yaw reference is arbitrary but steady.
 *
 * The magnetic rotation vector is read alongside it only to learn how far the
 * game frame's yaw is from north, as [northOffsetRad].
 */
class Orientation(context: Context, private val onRotation: (FloatArray) -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)

    private var game: FloatArray? = null
    private var magnetic: FloatArray? = null
    private var attitudeFromMagnetic = false
    private var offsetCos = 0f
    private var offsetSin = 0f
    private var offsetSamples = 0

    /** Yaw of the game frame relative to magnetic north, in radians; null until known. */
    @Volatile
    var northOffsetRad: Float? = null
        private set

    /** True when the compass sensor reports at least medium accuracy. */
    @Volatile
    var northReliable = false
        private set

    fun start() {
        val gameSensor = sensors?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val magneticSensor = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val attitude = gameSensor ?: magneticSensor ?: return
        attitudeFromMagnetic = gameSensor == null
        sensors.registerListener(this, attitude, SensorManager.SENSOR_DELAY_GAME)
        if (gameSensor != null && magneticSensor != null) {
            sensors.registerListener(this, magneticSensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensors?.unregisterListener(this)
        game = null
        magnetic = null
        northOffsetRad = null
        offsetCos = 0f
        offsetSin = 0f
        offsetSamples = 0
    }

    override fun onSensorChanged(event: SensorEvent) {
        val matrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                game = matrix
                onRotation(matrix)
                updateOffset()
            }

            Sensor.TYPE_ROTATION_VECTOR -> {
                magnetic = matrix
                if (attitudeFromMagnetic) {
                    northOffsetRad = 0f
                    onRotation(matrix)
                } else {
                    updateOffset()
                }
            }
        }
    }

    /** Yaw difference between the two frames: the rotation about z taking game-frame vectors to magnetic-frame ones. */
    private fun updateOffset() {
        val g = game ?: return
        val m = magnetic ?: return
        val m00 = m[0] * g[0] + m[1] * g[1] + m[2] * g[2]
        val m10 = m[3] * g[0] + m[4] * g[1] + m[5] * g[2]
        val phi = atan2(m10, m00)
        val a = if (offsetSamples < WARMUP_SAMPLES) 0.2f else 0.02f
        offsetSamples++
        offsetCos = (1 - a) * offsetCos + a * cos(phi)
        offsetSin = (1 - a) * offsetSin + a * sin(phi)
        if (offsetSamples >= WARMUP_SAMPLES) northOffsetRad = atan2(offsetSin, offsetCos)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            northReliable = accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        }
    }

    private companion object {
        const val WARMUP_SAMPLES = 25
    }
}
