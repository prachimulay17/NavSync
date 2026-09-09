package com.example.navsync.inference

import com.example.navsync.data.SensorData
import com.example.navsync.model.NavigationState

/**
 * Result of NavSync ML inference containing estimated position and confidence.
 */
data class InferenceResult(
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double,
    val headingDegrees: Double,
    val confidence: Double
)

/**
 * Interface for NavSync ML-based position estimation.
 * 
 * This is the integration point for the trained ML model.
 * The simulation uses this interface to obtain position estimates
 * during GNSS outages.
 * 
 * CRITICAL: During outage, inference receives ONLY:
 * - Sensor data (IMU: accelerometer, gyroscope)
 * - Previous navigation state (from last known position or last estimate)
 * 
 * It does NOT receive ground truth position - that would be cheating.
 * 
 * Current implementation: Simple dead-reckoning fallback
 * Future implementation: Load and run trained TensorFlow Lite model
 */
interface NavSyncInference {
    
    /**
     * Initialize the inference engine with the last known navigation state.
     * Called when GNSS outage begins.
     * 
     * @param lastState Last navigation state before outage (from GNSS)
     */
    fun initialize(lastState: NavigationState)
    
    /**
     * Estimate current position based on sensor data and previous state.
     * Called repeatedly during GNSS outage.
     * 
     * IMPORTANT: This function receives ONLY sensor data (IMU) and the previous
     * navigation state. It does NOT receive ground truth position.
     * 
     * @param sensorData Current IMU sensor readings (accel, gyro)
     * @param previousState Previous navigation state (last estimate or last GNSS)
     * @param deltaTimeMs Time elapsed since last update
     * @return Estimated position and confidence
     */
    fun estimatePosition(
        sensorData: SensorData,
        previousState: NavigationState,
        deltaTimeMs: Long
    ): InferenceResult
    
    /**
     * Reset the inference state.
     */
    fun reset()
}

/**
 * Default implementation using simple dead-reckoning.
 * This will be replaced with ML model inference later.
 * 
 * Uses only allowed inputs:
 * - Previous position/velocity from navigation state
 * - IMU sensor data for heading changes
 */
class DeadReckoningInference : NavSyncInference {
    
    private var uncertaintyFactor: Double = 1.0
    
    override fun initialize(lastState: NavigationState) {
        // Store nothing - we'll use the state passed to estimatePosition
        uncertaintyFactor = 1.0
    }
    
    override fun estimatePosition(
        sensorData: SensorData,
        previousState: NavigationState,
        deltaTimeMs: Long
    ): InferenceResult {
        // Simple dead-reckoning: use previous velocity and heading
        // Update heading using gyroscope
        // Confidence degrades over time
        
        val deltaTimeSec = deltaTimeMs / 1000.0
        uncertaintyFactor *= 0.98  // Confidence degrades 2% per update
        
        // Estimate distance traveled using previous speed
        val distanceKm = previousState.speedKmh * (deltaTimeSec / 3600.0)
        
        // Update heading using gyroscope Z-axis (yaw rate)
        val headingChange = sensorData.gyroZ * deltaTimeSec * 10.0  // Simple integration
        val newHeading = previousState.headingDegrees + headingChange
        
        // Update position using previous heading (before gyro correction)
        val latChange = distanceKm * Math.cos(Math.toRadians(previousState.headingDegrees)) / 111.0
        val lonChange = distanceKm * Math.sin(Math.toRadians(previousState.headingDegrees)) / 
                       (111.0 * Math.cos(Math.toRadians(previousState.latitude)))
        
        val newLatitude = previousState.latitude + latChange
        val newLongitude = previousState.longitude + lonChange
        
        // Speed gradually decreases in uncertainty (no way to measure it accurately)
        val newSpeed = previousState.speedKmh * 0.99
        
        val confidence = (0.75 * uncertaintyFactor).coerceIn(0.3, 0.85)
        
        return InferenceResult(
            latitude = newLatitude,
            longitude = newLongitude,
            speedKmh = newSpeed,
            headingDegrees = newHeading,
            confidence = confidence
        )
    }
    
    override fun reset() {
        uncertaintyFactor = 1.0
    }
}

/**
 * Factory to create inference engines.
 * Future: Add ML model loading logic here.
 */
object InferenceFactory {
    
    fun createInference(): NavSyncInference {
        // Future: Check if ML model is available and load it
        // For now, return dead-reckoning implementation
        return DeadReckoningInference()
    }
}
