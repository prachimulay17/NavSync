package com.example.navsync.ml

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * RoNIN ML velocity provider for NavSync ESKF integration.
 * 
 * Collects IMU samples, transforms to HACF frame, runs RoNIN inference,
 * and converts velocity from HACF → ENU → NED for ESKF consumption.
 * 
 * Coordinate transformation chain:
 * 1. Device frame sensors → HACF (via GRV quaternion)
 * 2. HACF velocity → ENU velocity (via yaw offset from GPS bearing)
 * 3. ENU velocity → NED velocity (axis swap: vN_ned = vN_enu, vE_ned = vE_enu)
 */
class RoninVelocityProvider(
    context: Context,
    private val onVelocity: (velocityNorth: Double, velocityEast: Double, confidence: Double) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val roninModel = RoninOnnx(context)
    private val featureBuffer = FeatureBuffer()

    // Sensor references
    private val accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
    private val grvSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    // Sensor thread
    private var thread: HandlerThread? = null

    // Latest sensor values
    private val quat = FloatArray(4)  // [w, x, y, z]
    private val lastGyro = FloatArray(3)
    private val lastAccel = FloatArray(3)
    private var haveGyro = false
    private var haveAccel = false
    private var haveGrv = false

    // HACF → ENU yaw tracking (critical for coordinate transformation)
    private var yawRadHacfToEnu: Double = 0.0
    private var yawInitialized: Boolean = false

    // Inference stride (run inference every N samples)
    private var samplesSinceInference = 0
    private val inferenceStride = 50  // ~4 Hz at 200 Hz sampling

    // GPS state for yaw updates
    private var lastGpsSpeed: Float = 0f
    private var lastGpsBearing: Float = Float.NaN

    /**
     * Check if required sensors are available.
     */
    val missingSensors: List<String>
        get() = buildList {
            if (accelSensor == null) add("accelerometer")
            if (gyroSensor == null) add("gyroscope")
            if (grvSensor == null) add("rotation vector")
        }

    fun start() {
        if (thread != null) return
        val t = HandlerThread("navsync-ronin").also { it.start(); thread = it }
        val h = Handler(t.looper)
        listOfNotNull(accelSensor, gyroSensor, grvSensor).forEach { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST, h)
        }
        Log.i(TAG, "RoNIN velocity provider started")
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        thread?.quitSafely()
        thread = null
        haveGyro = false
        haveAccel = false
        haveGrv = false
        Log.i(TAG, "RoNIN velocity provider stopped")
    }

    /**
     * Update HACF yaw from GPS bearing when available.
     * 
     * Yaw bootstrapping:
     * - gnssHdg = GPS bearing converted to ENU angle (0° = East, CCW positive)
     * - imuHdg = RoNIN velocity direction in HACF frame
     * - yawRad = gnssHdg - imuHdg (rotation to align HACF with ENU)
     * 
     * @param bearingDeg GPS bearing (0° = North, clockwise)
     * @param speedMps GPS speed in m/s
     * @param vxHacf RoNIN velocity X component in HACF
     * @param vyHacf RoNIN velocity Y component in HACF
     */
    fun updateGpsBearing(bearingDeg: Float, speedMps: Float, vxHacf: Float, vyHacf: Float) {
        lastGpsBearing = bearingDeg
        lastGpsSpeed = speedMps

        if (!bearingDeg.isFinite() || speedMps < MIN_BOOTSTRAP_SPEED) return

        // GPS bearing → ENU angle (0°=North, CW → 0°=East, CCW)
        val gnssHdg = Math.toRadians(90.0 - bearingDeg)
        val imuHdg = atan2(vyHacf.toDouble(), vxHacf.toDouble())

        if (!yawInitialized) {
            // Bootstrap: set yaw directly
            yawRadHacfToEnu = wrapAngle(gnssHdg - imuHdg)
            yawInitialized = true
            Log.i(TAG, "Yaw bootstrapped: yawRad = %.3f (GPS=%.1f°, IMU=%.1f°)".format(
                yawRadHacfToEnu, bearingDeg, Math.toDegrees(imuHdg)
            ))
        } else {
            // Soft update: blend toward GPS bearing
            val targetYaw = gnssHdg - imuHdg
            val correction = wrapAngle(targetYaw - yawRadHacfToEnu)
            yawRadHacfToEnu = wrapAngle(yawRadHacfToEnu + 0.4 * correction)
            
            Log.d(TAG, "Yaw updated: yawRad = %.3f (correction = %.3f)".format(
                yawRadHacfToEnu, correction
            ))
        }
    }

    /**
     * Clear GPS bearing (during GNSS outage).
     * Preserves last valid yaw offset for dead reckoning.
     */
    fun clearGpsBearing() {
        lastGpsBearing = Float.NaN
        lastGpsSpeed = 0f
        // Keep yawRadHacfToEnu — use last valid value during outage
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                lastAccel[0] = event.values[0]
                lastAccel[1] = event.values[1]
                lastAccel[2] = event.values[2]
                haveAccel = true
                return  // Wait for gyro to drive sampling
            }

            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> {
                lastGyro[0] = event.values[0]
                lastGyro[1] = event.values[1]
                lastGyro[2] = event.values[2]
                haveGyro = true
                // Fall through — gyro drives sampling
            }

            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getQuaternionFromVector(quat, event.values)
                haveGrv = true
                return
            }

            else -> return
        }

        if (!haveGyro || !haveAccel || !haveGrv) return

        // Transform device frame → HACF frame
        val gyroHacf = Hacf.rotateWxyz(quat, lastGyro[0], lastGyro[1], lastGyro[2])
        val accelHacf = Hacf.rotateWxyz(quat, lastAccel[0], lastAccel[1], lastAccel[2])

        // Add to buffer
        featureBuffer.add(
            gyroHacf[0], gyroHacf[1], gyroHacf[2],
            accelHacf[0], accelHacf[1], accelHacf[2]
        )

        // Run inference every N samples
        samplesSinceInference++
        if (samplesSinceInference >= inferenceStride && featureBuffer.isFull) {
            samplesSinceInference = 0
            runInference()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Run RoNIN inference and transform velocity to NED frame.
     */
    private fun runInference() {
        val window = featureBuffer.copyWindow() ?: return

        try {
            // Step 1: Get velocity in HACF frame
            val (vxHacf, vyHacf) = roninModel.predictVelocity(window)
            
            Log.d(TAG, "RoNIN inference: vxHacf = %.3f m/s, vyHacf = %.3f m/s".format(vxHacf, vyHacf))

            // Update yaw if GPS bearing available
            if (lastGpsBearing.isFinite() && lastGpsSpeed > MIN_BOOTSTRAP_SPEED) {
                updateGpsBearing(lastGpsBearing, lastGpsSpeed, vxHacf, vyHacf)
            }

            // Step 2: Transform HACF → ENU using yaw offset
            if (!yawInitialized) {
                Log.w(TAG, "Yaw not initialized — waiting for GPS bearing")
                return
            }

            val cosY = cos(yawRadHacfToEnu)
            val sinY = sin(yawRadHacfToEnu)
            val vE_enu = cosY * vxHacf - sinY * vyHacf
            val vN_enu = sinY * vxHacf + cosY * vyHacf

            Log.d(TAG, "HACF→ENU: vE = %.3f m/s, vN = %.3f m/s (yaw = %.3f rad)".format(
                vE_enu, vN_enu, yawRadHacfToEnu
            ))

            // Step 3: ENU → NED (axis swap, no sign flip for horizontal)
            val vN_ned = vN_enu
            val vE_ned = vE_enu

            Log.d(TAG, "ENU→NED: vN = %.3f m/s, vE = %.3f m/s".format(vN_ned, vE_ned))

            // Step 4: Provide to ESKF with confidence
            val confidence = if (lastGpsBearing.isFinite()) 0.7 else 0.5
            onVelocity(vN_ned, vE_ned, confidence)

            Log.i(TAG, "RoNIN velocity: vN=%.3f m/s, vE=%.3f m/s, conf=%.2f".format(
                vN_ned, vE_ned, confidence
            ))

        } catch (e: Exception) {
            Log.e(TAG, "RoNIN inference failed: ${e.message}", e)
        }
    }

    /**
     * Wrap angle to [-π, π].
     */
    private fun wrapAngle(angle: Double): Double {
        var a = angle % (2 * PI)
        if (a > PI) a -= 2 * PI
        if (a < -PI) a += 2 * PI
        return a
    }

    fun close() {
        stop()
        roninModel.close()
    }

    companion object {
        private const val TAG = "RoninVelocityProvider"
        
        /** Minimum GPS speed for yaw bootstrapping (m/s) */
        private const val MIN_BOOTSTRAP_SPEED = 0.8f
    }
}
