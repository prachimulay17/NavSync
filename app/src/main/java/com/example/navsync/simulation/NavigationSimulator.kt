package com.example.navsync.simulation

import com.example.navsync.data.NavigationDataset
import com.example.navsync.inference.InferenceFactory
import com.example.navsync.inference.NavSyncInference
import com.example.navsync.model.NavigationSource
import com.example.navsync.model.NavigationState

/**
 * Configuration for GNSS outage simulation.
 */
data class OutageConfig(
    val enabled: Boolean,
    val startTimeSeconds: Double,
    val durationSeconds: Double
)

/**
 * Evaluation metrics comparing estimated vs reference position.
 * Useful for ML team to assess inference performance.
 */
data class EvaluationMetrics(
    val positionErrorMeters: Double,
    val velocityErrorKmh: Double, 
    val headingErrorDegrees: Double,
    val referenceLatitude: Double,
    val referenceLongitude: Double,
    val referenceSpeedKmh: Double,
    val referenceHeadingDegrees: Double
)

/**
 * NavigationSimulator replays recorded dataset data and simulates GNSS outages.
 * 
 * ARCHITECTURE FOR ML TEAM INTEGRATION:
 * ═══════════════════════════════════════════════════════════════════
 * 
 * 1. Reference Trajectory (V Dataset):
 *    - Always advances through V dataset points at recorded timestamps
 *    - Provides timing/clock for the entire simulation
 *    - Determines when simulation is complete
 *    - Independent of GNSS availability or inference performance
 *    - Used ONLY for: timing, completion detection, evaluation metrics
 * 
 * 2. Estimated Navigation (NavSyncInference):
 *    - Pluggable inference implementation via NavSyncInference interface
 *    - During GNSS: estimation matches reference (no inference needed)
 *    - During outage: estimation comes from ML/ESKF implementation
 *    - Input: SensorData (IMU) + previous NavigationState + deltaTime
 *    - Output: InferenceResult (position, speed, heading, confidence)
 *    - NO ACCESS to V dataset position during outage (prevents cheating)
 * 
 * 3. UI Contract (NavigationState):
 *    - Single state object representing current estimated navigation
 *    - Always reflects the "best current estimate" (GNSS or inference)
 *    - UI components (MapLibre, StatusBar, HUD) consume only this state
 *    - Reference trajectory displayed separately via trajectoryPoints
 * 
 * INTEGRATION POINTS:
 * - Replace inference via InferenceFactory.createInference()
 * - All sensor data available in SensorData structure
 * - No UI changes required - NavigationState contract preserved
 * - Reference vs estimated separation maintained automatically
 */
class NavigationSimulator(
    private val inference: NavSyncInference = InferenceFactory.createInference()
) {
    private var dataset: NavigationDataset? = null
    private var currentStep = 0
    private var outageConfig: OutageConfig = OutageConfig(
        enabled = false,
        startTimeSeconds = 0.0,
        durationSeconds = 0.0
    )
    
    private var lastUpdateTimeMs = 0L
    private var inOutage = false
    private var lastNavigationState: NavigationState? = null
    
    /**
     * Load a dataset for replay.
     */
    fun loadDataset(dataset: NavigationDataset) {
        this.dataset = dataset
        reset()
    }
    
    /**
     * Configure GNSS outage parameters.
     * @param enabled Whether outage simulation is enabled
     * @param startTimeSeconds Elapsed time in seconds when outage begins
     * @param durationSeconds How long the outage lasts in seconds
     */
    fun configureOutage(enabled: Boolean, startTimeSeconds: Double, durationSeconds: Double) {
        outageConfig = OutageConfig(
            enabled = enabled,
            startTimeSeconds = startTimeSeconds.coerceAtLeast(0.0),
            durationSeconds = durationSeconds.coerceAtLeast(0.0)
        )
    }
    
    /**
     * Set custom inference implementation for ML team integration.
     * This allows swapping the inference engine without changing the simulator.
     * 
     * @param newInference Custom ML/ESKF implementation
     */
    fun setInference(newInference: NavSyncInference) {
        // Note: In current implementation, inference is set at construction time
        // For dynamic switching, consider factory pattern or dependency injection
        android.util.Log.w("NavigationSimulator", "Dynamic inference switching not supported in current implementation. Use InferenceFactory.createInference() instead.")
    }
    
    /**
     * Get information about the current inference implementation.
     */
    fun getInferenceInfo(): String {
        return inference.javaClass.simpleName
    }
    
    /**
     * Get the next navigation state.
     * This is called repeatedly to drive the simulation forward.
     * 
     * The reference trajectory ALWAYS advances through V dataset points regardless
     * of outage state. During outage, the NavigationState represents the estimated 
     * position which may drift from the reference trajectory.
     */
    fun nextState(): NavigationState {
        val currentDataset = dataset ?: return getDefaultState()
        
        if (currentStep >= currentDataset.points.size) {
            // Dataset complete, stay at last position
            android.util.Log.d("NavigationSimulator", "SIMULATION COMPLETE: currentStep=$currentStep >= totalSteps=${currentDataset.points.size}")
            return getLastState()
        }
        
        // REFERENCE TRAJECTORY: Always advance to current step
        val referencePoint = currentDataset.points[currentStep]
        val elapsedTimeSeconds = referencePoint.sensorData.timestampMs / 1000.0
        
        // Determine if we're in a GNSS outage based on elapsed time
        val outageEndTimeSeconds = outageConfig.startTimeSeconds + outageConfig.durationSeconds
        val shouldBeInOutage = outageConfig.enabled && 
                               elapsedTimeSeconds >= outageConfig.startTimeSeconds && 
                               elapsedTimeSeconds < outageEndTimeSeconds
        
        // Handle outage state transitions
        when {
            shouldBeInOutage && !inOutage -> {
                // Entering outage - initialize inference with last GNSS state
                inOutage = true
                val lastState = lastNavigationState ?: getDefaultState()
                inference.initialize(lastState)
                lastUpdateTimeMs = referencePoint.sensorData.timestampMs
                android.util.Log.d("NavigationSimulator", "GNSS OUTAGE START at ${elapsedTimeSeconds}s (step $currentStep)")
            }
            !shouldBeInOutage && inOutage -> {
                // Exiting outage (GNSS recovery)
                inOutage = false
                inference.reset()
                android.util.Log.d("NavigationSimulator", "GNSS RECOVERY at ${elapsedTimeSeconds}s (step $currentStep)")
            }
        }
        
        // NAVIGATION STATE: Determine estimated position based on outage state
        val state = if (inOutage) {
            // GNSS OUTAGE MODE
            // The reference trajectory continues advancing (for timing), but NavigationState
            // comes from AI estimation which may drift from the reference.
            // 
            // Inference receives ONLY:
            // 1. Sensor data (IMU) from current reference point
            // 2. Previous estimated navigation state
            // NO reference position is provided (that would be cheating)
            
            val deltaTime = referencePoint.sensorData.timestampMs - lastUpdateTimeMs
            val previousEstimatedState = lastNavigationState ?: getDefaultState()
            
            val inferenceResult = inference.estimatePosition(
                sensorData = referencePoint.sensorData,
                previousState = previousEstimatedState,
                deltaTimeMs = deltaTime
            )
            
            lastUpdateTimeMs = referencePoint.sensorData.timestampMs
            
            android.util.Log.d("NavigationSimulator", "OUTAGE MODE: step=$currentStep, elapsed=${elapsedTimeSeconds}s, ref=(${referencePoint.gnssData.latitude.format(6)},${referencePoint.gnssData.longitude.format(6)}), est=(${inferenceResult.latitude.format(6)},${inferenceResult.longitude.format(6)})")
            
            NavigationState(
                latitude = inferenceResult.latitude,
                longitude = inferenceResult.longitude,
                speedKmh = inferenceResult.speedKmh,
                headingDegrees = inferenceResult.headingDegrees,
                confidence = inferenceResult.confidence,
                gnssAvailable = false,
                source = NavigationSource.AI_ESTIMATION
            )
        } else {
            // GNSS AVAILABLE MODE
            // NavigationState matches reference trajectory (V dataset GNSS data)
            NavigationState(
                latitude = referencePoint.gnssData.latitude,
                longitude = referencePoint.gnssData.longitude,
                speedKmh = referencePoint.gnssData.speedKmh,
                headingDegrees = referencePoint.gnssData.headingDegrees,
                confidence = 0.95,
                gnssAvailable = true,
                source = NavigationSource.GNSS
            )
        }
        
        // Store current estimated state for next iteration
        lastNavigationState = state
        
        // ALWAYS advance reference trajectory step (regardless of outage state)
        currentStep++
        
        return state
    }
    
    /**
     * Get current reference point for evaluation/visualization.
     * This is the current V dataset point regardless of outage state.
     * 
     * ML TEAM: Use this for evaluation metrics - compare your inference
     * result against this ground truth position.
     */
    fun getCurrentReferencePoint() = dataset?.points?.getOrNull(currentStep - 1)
    
    /**
     * Get evaluation metrics by comparing current estimate vs reference.
     * Returns null if no reference data available.
     */
    fun getEvaluationMetrics(currentEstimate: NavigationState): EvaluationMetrics? {
        val reference = getCurrentReferencePoint()?.groundTruth ?: return null
        
        val positionErrorKm = calculateDistanceKm(
            currentEstimate.latitude, currentEstimate.longitude,
            reference.latitude, reference.longitude
        )
        
        val velocityErrorKmh = kotlin.math.abs(currentEstimate.speedKmh - reference.speedKmh)
        val headingErrorDegrees = kotlin.math.abs(normalizeAngleDiff(currentEstimate.headingDegrees - reference.headingDegrees))
        
        return EvaluationMetrics(
            positionErrorMeters = positionErrorKm * 1000,
            velocityErrorKmh = velocityErrorKmh,
            headingErrorDegrees = headingErrorDegrees,
            referenceLatitude = reference.latitude,
            referenceLongitude = reference.longitude,
            referenceSpeedKmh = reference.speedKmh,
            referenceHeadingDegrees = reference.headingDegrees
        )
    }
    
    private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)
    
    /**
     * Reset simulation to beginning.
     */
    fun reset() {
        currentStep = 0
        inOutage = false
        lastUpdateTimeMs = 0L
        lastNavigationState = null
        inference.reset()
    }
    
    /**
     * Get current step in the simulation.
     */
    fun getCurrentStep(): Int = currentStep
    
    /**
     * Get total steps in loaded dataset.
     */
    fun getTotalSteps(): Int = dataset?.points?.size ?: 0
    
    /**
     * Check if simulation has finished.
     */
    fun isComplete(): Boolean {
        val totalSteps = getTotalSteps()
        return totalSteps > 0 && currentStep >= totalSteps
    }
    
    /**
     * Get ground truth at current step for evaluation.
     * This returns the V dataset reference point, NOT the estimated position.
     */
    fun getCurrentGroundTruth() = getCurrentReferencePoint()?.groundTruth
    
    private fun getDefaultState(): NavigationState {
        return NavigationState(
            latitude = 28.6139,
            longitude = 77.2090,
            speedKmh = 0.0,
            headingDegrees = 0.0,
            confidence = 0.0,
            gnssAvailable = false,
            source = NavigationSource.GNSS
        )
    }
    
    private fun getLastState(): NavigationState {
        return lastNavigationState ?: getDefaultState()
    }
    
    /**
     * Calculate distance between two lat/lng points in kilometers.
     */
    private fun calculateDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return earthRadiusKm * c
    }
    
    /**
     * Normalize angle difference to [-180, 180] range.
     */
    private fun normalizeAngleDiff(angleDiff: Double): Double {
        var normalized = angleDiff
        while (normalized > 180.0) normalized -= 360.0
        while (normalized < -180.0) normalized += 360.0
        return normalized
    }
}