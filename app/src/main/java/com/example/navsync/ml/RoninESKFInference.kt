package com.example.navsync.ml

import android.content.Context
import android.util.Log
import com.example.navsync.data.SensorData
import com.example.navsync.eskf.ESKF
import com.example.navsync.eskf.ESKFConfiguration
import com.example.navsync.eskf.ESKFImpl
import com.example.navsync.eskf.ESKFResult
import com.example.navsync.eskf.DeviceOrientation
import com.example.navsync.eskf.ImuMeasurement
import com.example.navsync.inference.InferenceResult
import com.example.navsync.inference.NavSyncInference
import com.example.navsync.model.NavigationState
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * RoNIN + ESKF hybrid inference for NavSync.
 * 
 * Combines:
 * - ESKF for IMU-based prediction and state estimation
 * - RoNIN LSTM for ML-based velocity measurement
 * - HACF→ENU→NED coordinate transformation
 * - GPS bearing-based yaw tracking
 * 
 * Architecture:
 * 1. Collect IMU samples in HACF frame (via GRV quaternion)
 * 2. Run RoNIN inference every ~50 samples (~4 Hz at 200 Hz)
 * 3. Transform RoNIN velocity: HACF → ENU → NED
 * 4. Feed velocity to ESKF via updateWithVelocity()
 * 5. Use ESKF state for position/attitude estimation
 */
class RoninESKFInference(
    private val context: Context,
    private val configuration: ESKFConfiguration = ESKFConfiguration.navSync()
) : NavSyncInference {
    
    private val eskf: ESKF = ESKFImpl(configuration)
    private val featureBuffer = FeatureBuffer()
    private var roninModel: RoninOnnx? = null
    
    private var initialized = false
    private var lastTimestampMs = 0L
    private var sampleCount = 0
    
    // HACF → ENU yaw tracking
    private var yawRadHacfToEnu: Double = 0.0
    private var yawInitialized: Boolean = false
    
    // GPS state for yaw updates
    private var lastGpsSpeed: Double = 0.0
    private var lastGpsBearing: Double = Double.NaN
    
    // GRV quaternion from real Android Game Rotation Vector sensor
    private val grvQuaternion = FloatArray(4) { 0f }  // [w, x, y, z]
    private var haveGrv = false
    
    init {
        try {
            roninModel = RoninOnnx(context)
            Log.i(TAG, "✅ RoNIN model loaded successfully from ronin_lstm.onnx")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load RoNIN model: ${e.message}", e)
        }
    }
    
    override fun initialize(lastState: NavigationState) {
        try {
            // Initialize ESKF
            val deviceOrientation = DeviceOrientation.flat()
            eskf.initialize(lastState, configuration, deviceOrientation)
            
            // GRV will be initialized when first sensor data is processed
            haveGrv = false
            Log.i(TAG, "GRV will be initialized from first sensor data")
            
            // Bootstrap yaw if GPS available
            if (lastState.gnssAvailable && lastState.speedKmh > MIN_BOOTSTRAP_SPEED_KMH) {
                lastGpsBearing = lastState.headingDegrees
                lastGpsSpeed = lastState.speedKmh / 3.6  // km/h → m/s
                // Yaw will be bootstrapped on first RoNIN inference
            }
            
            // Reset buffers
            featureBuffer.reset()
            sampleCount = 0
            initialized = true
            lastTimestampMs = 0L
            
            Log.i(TAG, "RoNIN-ESKF initialized at lat=${lastState.latitude}, lon=${lastState.longitude}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize RoNIN-ESKF: ${e.message}", e)
            initialized = false
        }
    }
    
    override fun estimatePosition(
        sensorData: SensorData,
        previousState: NavigationState,
        deltaTimeMs: Long
    ): InferenceResult {
        if (!initialized || roninModel == null) {
            Log.w(TAG, "RoNIN-ESKF not initialized")
            return createFallbackResult(previousState)
        }
        
        try {
            val deltaTimeSeconds = deltaTimeMs / 1000.0
            if (deltaTimeSeconds <= 0 || deltaTimeSeconds > 1.0) {
                Log.w(TAG, "Invalid delta time: ${deltaTimeSeconds}s")
                return eskf.getCurrentState().toInferenceResult()
            }
            
            // Update GPS state
            if (previousState.gnssAvailable) {
                lastGpsBearing = previousState.headingDegrees
                lastGpsSpeed = previousState.speedKmh / 3.6  // km/h → m/s
            }
            
            // Convert SensorData to HACF frame and add to buffer
            val (gyroHacf, accelHacf) = transformToHacf(sensorData)
            featureBuffer.add(
                gyroHacf[0], gyroHacf[1], gyroHacf[2],
                accelHacf[0], accelHacf[1], accelHacf[2]
            )
            
            sampleCount++
            
            // Run RoNIN inference every INFERENCE_STRIDE samples
            if (sampleCount >= INFERENCE_STRIDE && featureBuffer.isFull) {
                Log.d(TAG, "🔄 400-sample window ready, triggering RoNIN inference (samples: ${featureBuffer.count})")
                sampleCount = 0
                runRoninInference()
            } else if (sampleCount % 20 == 0) {
                // Log buffer fill progress periodically
                Log.v(TAG, "Buffer filling: ${featureBuffer.count}/${FeatureBuffer.WINDOW} samples")
            }
            
            // Always run ESKF prediction with IMU
            val imuMeasurement = ImuMeasurement.fromSensorData(sensorData)
            val result = eskf.predict(imuMeasurement, deltaTimeSeconds)
            
            lastTimestampMs = sensorData.timestampMs
            
            return result.toInferenceResult()
            
        } catch (e: Exception) {
            Log.e(TAG, "Estimation failed: ${e.message}", e)
            return createFallbackResult(previousState)
        }
    }
    
    override fun reset() {
        eskf.reset()
        featureBuffer.reset()
        initialized = false
        yawInitialized = false
        yawRadHacfToEnu = 0.0
        sampleCount = 0
        lastTimestampMs = 0L
        haveGrv = false
        Log.d(TAG, "RoNIN-ESKF reset")
    }
    
    /**
     * Transform device-frame sensors to HACF frame using GRV quaternion.
     */
    private fun transformToHacf(sensorData: SensorData): Pair<FloatArray, FloatArray> {
        // Update GRV from real sensor data (not simulated)
        val grv = sensorData.gameRotationVector
        if (grv != null && grv.size >= 3) {
            // Convert rotation vector to quaternion using Android SensorManager
            android.hardware.SensorManager.getQuaternionFromVector(grvQuaternion, grv)
            haveGrv = true
        } else if (!haveGrv) {
            // Fallback: update from orientation yaw only (simulation mode)
            updateGrvFromOrientation(sensorData)
        }
        
        // Convert to float arrays
        val gyro = floatArrayOf(
            sensorData.gyroRoll.toFloat(),
            sensorData.gyroPitch.toFloat(),
            sensorData.gyroYaw.toFloat()
        )
        val accel = floatArrayOf(
            sensorData.accelerationX.toFloat(),
            sensorData.accelerationY.toFloat(),
            sensorData.accelerationZ.toFloat()
        )
        
        // Apply HACF transformation
        val gyroHacf = Hacf.rotateWxyz(grvQuaternion, gyro[0], gyro[1], gyro[2])
        val accelHacf = Hacf.rotateWxyz(grvQuaternion, accel[0], accel[1], accel[2])
        
        return Pair(gyroHacf, accelHacf)
    }
    
    /**
     * Run RoNIN inference and feed velocity measurement to ESKF.
     */
    private fun runRoninInference() {
        val window = featureBuffer.copyWindow() ?: return
        val model = roninModel ?: return
        
        try {
            // Step 1: Get velocity in HACF frame
            Log.d(TAG, "🧠 Executing RoNIN ONNX inference with 400×6 window...")
            val (vxHacf, vyHacf) = model.predictVelocity(window)
            
            Log.i(TAG, "🔍 RoNIN HACF velocity: vx_hacf=%.3f m/s, vy_hacf=%.3f m/s".format(vxHacf, vyHacf))
            
            // Step 2: Bootstrap/update yaw if GPS available
            if (!lastGpsBearing.isNaN() && lastGpsSpeed > MIN_BOOTSTRAP_SPEED_MS) {
                updateYawFromGps(vxHacf, vyHacf, lastGpsBearing, lastGpsSpeed)
            }
            
            // Step 3: Transform HACF → ENU → NED
            if (!yawInitialized) {
                Log.w(TAG, "Yaw not initialized, skipping velocity measurement")
                return
            }
            
            val (vN_ned, vE_ned) = transformHacfToNed(vxHacf, vyHacf)
            
            Log.i(TAG, "🔄 Coordinate transform: yaw_offset=%.3f° | NED velocity: vN=%.3f m/s, vE=%.3f m/s".format(
                Math.toDegrees(yawRadHacfToEnu), vN_ned, vE_ned
            ))
            
            // Step 4: Feed to ESKF
            val velocityUncertainty = if (!lastGpsBearing.isNaN()) 0.8 else 1.2  // σ in m/s
            Log.d(TAG, "⚡ Sending to ESKF: updateWithVelocity(vN=%.3f, vE=%.3f, σ=%.2f)".format(
                vN_ned, vE_ned, velocityUncertainty
            ))
            eskf.updateWithVelocity(vN_ned, vE_ned, velocityUncertainty)
            
            Log.i(TAG, "✅ RoNIN→ESKF velocity measurement applied successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "RoNIN inference failed: ${e.message}", e)
        }
    }
    
    /**
     * Transform HACF velocity → NED velocity via ENU.
     * 
     * Chain: HACF → ENU (via yaw) → NED (axis swap)
     */
    private fun transformHacfToNed(vxHacf: Float, vyHacf: Float): Pair<Double, Double> {
        // Step 1: HACF → ENU rotation
        val cosY = cos(yawRadHacfToEnu)
        val sinY = sin(yawRadHacfToEnu)
        val vE_enu = cosY * vxHacf - sinY * vyHacf
        val vN_enu = sinY * vxHacf + cosY * vyHacf
        
        // Step 2: ENU → NED (axis labels only, no sign flip)
        val vN_ned = vN_enu
        val vE_ned = vE_enu
        
        return Pair(vN_ned, vE_ned)
    }
    
    /**
     * Update HACF→ENU yaw from GPS bearing and RoNIN velocity direction.
     */
    private fun updateYawFromGps(
        vxHacf: Float,
        vyHacf: Float,
        gpsBearingDeg: Double,
        gpsSpeedMs: Double
    ) {
        // GPS bearing → ENU angle (0°=North, CW → 0°=East, CCW)
        val gnssHdg = Math.toRadians(90.0 - gpsBearingDeg)
        
        // RoNIN velocity direction in HACF
        val imuHdg = atan2(vyHacf.toDouble(), vxHacf.toDouble())
        
        if (!yawInitialized) {
            // Bootstrap: set yaw directly
            yawRadHacfToEnu = wrapAngle(gnssHdg - imuHdg)
            yawInitialized = true
            Log.i(TAG, "Yaw bootstrapped: %.3f rad (GPS=%.1f°, RoNIN=%.1f°)".format(
                yawRadHacfToEnu, gpsBearingDeg, Math.toDegrees(imuHdg)
            ))
        } else {
            // Soft update: blend toward GPS bearing
            val targetYaw = gnssHdg - imuHdg
            val correction = wrapAngle(targetYaw - yawRadHacfToEnu)
            yawRadHacfToEnu = wrapAngle(yawRadHacfToEnu + YAW_UPDATE_GAIN * correction)
            
            Log.d(TAG, "Yaw updated: %.3f rad (correction=%.3f)".format(
                yawRadHacfToEnu, correction
            ))
        }
    }
    
    /**
     * Initialize GRV quaternion from heading (simulated).
     */
    private fun initializeGrvFromHeading(headingRad: Double) {
        // Create rotation quaternion: yaw rotation about Z-axis
        val halfYaw = headingRad / 2.0
        grvQuaternion[0] = cos(halfYaw).toFloat()  // w
        grvQuaternion[1] = 0f  // x
        grvQuaternion[2] = 0f  // y
        grvQuaternion[3] = sin(halfYaw).toFloat()  // z
        haveGrv = true
    }
    
    /**
     * Update GRV quaternion from orientation (simulated).
     */
    private fun updateGrvFromOrientation(sensorData: SensorData) {
        // In simulation, approximate GRV from orientation yaw
        val yawRad = Math.toRadians(sensorData.orientationYaw)
        val halfYaw = yawRad / 2.0
        grvQuaternion[0] = cos(halfYaw).toFloat()  // w
        grvQuaternion[1] = 0f  // x
        grvQuaternion[2] = 0f  // y
        grvQuaternion[3] = sin(halfYaw).toFloat()  // z
    }
    
    /**
     * Create fallback result on error.
     */
    private fun createFallbackResult(previousState: NavigationState): InferenceResult {
        return InferenceResult(
            latitude = previousState.latitude,
            longitude = previousState.longitude,
            speedKmh = previousState.speedKmh * 0.98,
            headingDegrees = previousState.headingDegrees,
            confidence = 0.1
        )
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
        roninModel?.close()
    }
    
    /**
     * Get current ESKF state with velocity information.
     * FOR VALIDATION/DIAGNOSTIC USE ONLY.
     */
    fun getESKFState(): ESKFResult {
        return eskf.getCurrentState()
    }
    
    companion object {
        private const val TAG = "RoninESKFInference"
        
        /** Inference stride: run RoNIN every N samples (~4 Hz at 200 Hz) */
        private const val INFERENCE_STRIDE = 50
        
        /** Minimum GPS speed for yaw bootstrapping (m/s) */
        private const val MIN_BOOTSTRAP_SPEED_MS = 0.8
        
        /** Minimum GPS speed for yaw bootstrapping (km/h) */
        private const val MIN_BOOTSTRAP_SPEED_KMH = 2.88
        
        /** Yaw update gain (blend factor) */
        private const val YAW_UPDATE_GAIN = 0.4
    }
}
