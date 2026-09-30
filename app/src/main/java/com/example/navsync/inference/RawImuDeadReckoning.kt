package com.example.navsync.inference

import com.example.navsync.data.SensorData
import com.example.navsync.model.NavigationState
import kotlin.math.*

/**
 * Raw IMU Dead Reckoning implementation using smartphone sensor data.
 * 
 * This baseline implements offline IMU-based position estimation using:
 * - Raw accelerometer data (gravity compensated)
 * - Gyroscope for attitude integration
 * - Magnetometer/orientation for frame transformation
 * - Double integration: acceleration → velocity → position
 * 
 * Key features:
 * - Phone-frame to navigation-frame transformation
 * - Gravity compensation using device gravity vector
 * - 10Hz IMU processing with timestamp-based integration
 * - Accumulates genuine dead reckoning drift (no cheating)
 * 
 * Limitations:
 * - No Kalman filtering or sensor fusion
 * - No map matching or constraints
 * - No zero velocity updates (ZUPT)
 * - Simple attitude integration (no full quaternion)
 * 
 * This serves as a realistic baseline for comparison with AI/ML approaches.
 */
class RawImuDeadReckoning : NavSyncInference {
    
    // Navigation state
    private var latitude = 0.0
    private var longitude = 0.0
    private var velocityNorth = 0.0  // m/s in North direction
    private var velocityEast = 0.0   // m/s in East direction  
    private var heading = 0.0        // degrees, 0 = North
    
    // Integration state
    private var lastTimestampMs = 0L
    private var confidence = 1.0
    
    // Constants
    private companion object {
        const val EARTH_RADIUS_KM = 6371.0
        const val METERS_PER_DEGREE_LAT = 111320.0
        const val CONFIDENCE_DECAY_RATE = 0.995  // Per update
    }
    
    override fun initialize(lastState: NavigationState) {
        // Start from last valid GNSS position
        latitude = lastState.latitude
        longitude = lastState.longitude
        
        // Convert speed/heading to North/East velocity components
        val speedMps = lastState.speedKmh / 3.6
        val headingRad = Math.toRadians(lastState.headingDegrees)
        velocityNorth = speedMps * cos(headingRad)
        velocityEast = speedMps * sin(headingRad)
        
        heading = lastState.headingDegrees
        confidence = lastState.confidence
        lastTimestampMs = 0L
        
        android.util.Log.d("RawImuDR", "Initialized at lat=$latitude, lon=$longitude, speed=${lastState.speedKmh} km/h, heading=$heading°")
    }
    
    override fun estimatePosition(
        sensorData: SensorData,
        previousState: NavigationState,
        deltaTimeMs: Long
    ): InferenceResult {
        val deltaTimeSec = deltaTimeMs / 1000.0
        
        if (deltaTimeSec <= 0 || deltaTimeSec > 1.0) {
            // Skip invalid time deltas
            return createResult()
        }
        
        // 1. GRAVITY COMPENSATION
        // Remove gravity from raw accelerometer to get motion acceleration
        val motionAccelX = sensorData.accelerationX - sensorData.gravityX
        val motionAccelY = sensorData.accelerationY - sensorData.gravityY
        val motionAccelZ = sensorData.accelerationZ - sensorData.gravityZ
        
        // 2. PHONE FRAME TO NAVIGATION FRAME TRANSFORMATION
        // Use device orientation to transform accelerations to North-East-Down frame
        val (accelNorth, accelEast) = transformToNavigationFrame(
            motionAccelX, motionAccelY, motionAccelZ,
            sensorData.orientationYaw, sensorData.orientationPitch, sensorData.orientationRoll
        )
        
        // 3. VELOCITY INTEGRATION
        // Integrate acceleration to update velocity
        velocityNorth += accelNorth * deltaTimeSec
        velocityEast += accelEast * deltaTimeSec
        
        // 4. HEADING INTEGRATION  
        // Use gyroscope yaw rate to update heading
        val headingChangeRad = sensorData.gyroYaw * deltaTimeSec
        heading += Math.toDegrees(headingChangeRad)
        heading = normalizeAngle(heading)
        
        // 5. POSITION INTEGRATION
        // Integrate velocity to update position
        val deltaLatitude = (velocityNorth * deltaTimeSec) / METERS_PER_DEGREE_LAT
        val metersPerDegreeLon = METERS_PER_DEGREE_LAT * cos(Math.toRadians(latitude))
        val deltaLongitude = (velocityEast * deltaTimeSec) / metersPerDegreeLon
        
        latitude += deltaLatitude
        longitude += deltaLongitude
        
        // 6. CONFIDENCE DECAY
        // Confidence decreases over time due to IMU drift
        confidence *= CONFIDENCE_DECAY_RATE
        confidence = confidence.coerceIn(0.1, 1.0)
        
        lastTimestampMs = sensorData.timestampMs
        
        // Log periodic state for debugging
        if ((sensorData.timestampMs / 1000) % 5 == 0L) {
            val speed = sqrt(velocityNorth * velocityNorth + velocityEast * velocityEast) * 3.6
            android.util.Log.d("RawImuDR", "t=${sensorData.timestampMs/1000}s: pos=(${latitude.format(6)},${longitude.format(6)}), vel=(${"%.2f".format(velocityNorth)},${"%.2f".format(velocityEast)}) m/s, speed=${"%.1f".format(speed)} km/h, heading=${"%.1f".format(heading)}°")
        }
        
        return createResult()
    }
    
    override fun reset() {
        latitude = 0.0
        longitude = 0.0
        velocityNorth = 0.0
        velocityEast = 0.0
        heading = 0.0
        confidence = 1.0
        lastTimestampMs = 0L
    }
    
    /**
     * Transform accelerations from phone body frame to navigation frame (North-East-Down)
     */
    private fun transformToNavigationFrame(
        accelX: Double, accelY: Double, accelZ: Double,
        yawDeg: Double, pitchDeg: Double, rollDeg: Double
    ): Pair<Double, Double> {
        // Simplified transformation using device orientation
        // In a full implementation, this would use rotation matrices or quaternions
        
        // Convert angles to radians
        val yawRad = Math.toRadians(yawDeg)
        val pitchRad = Math.toRadians(pitchDeg)
        val rollRad = Math.toRadians(rollDeg)
        
        // Assume phone is held relatively flat (pitch/roll corrections minimal)
        // Primary transformation is rotation around Z-axis (yaw) to align with North
        
        // Rotate X,Y acceleration to North-East frame
        val accelNorth = accelX * cos(yawRad) - accelY * sin(yawRad)
        val accelEast = accelX * sin(yawRad) + accelY * cos(yawRad)
        
        // Apply simple pitch compensation to North component
        val accelNorthCompensated = accelNorth * cos(pitchRad) - accelZ * sin(pitchRad)
        
        return Pair(accelNorthCompensated, accelEast)
    }
    
    /**
     * Normalize angle to [0, 360) degrees
     */
    private fun normalizeAngle(angle: Double): Double {
        var normalized = angle % 360.0
        if (normalized < 0) normalized += 360.0
        return normalized
    }
    
    /**
     * Create inference result from current state
     */
    private fun createResult(): InferenceResult {
        val speed = sqrt(velocityNorth * velocityNorth + velocityEast * velocityEast) * 3.6 // km/h
        
        return InferenceResult(
            latitude = latitude,
            longitude = longitude,
            speedKmh = speed,
            headingDegrees = heading,
            confidence = confidence
        )
    }
    
    /**
     * Format double to specified decimal places
     */
    private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)
}