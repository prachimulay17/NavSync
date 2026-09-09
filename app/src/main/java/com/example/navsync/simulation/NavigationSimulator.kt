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
    val startStep: Int,
    val durationSeconds: Int
)

/**
 * NavigationSimulator replays recorded dataset data and simulates GNSS outages.
 * 
 * Data Flow Architecture:
 * 
 * 1. GNSS Available:
 *    - Dataset GNSS data → NavigationState
 *    - Ground truth stored for evaluation
 * 
 * 2. GNSS Outage:
 *    - Dataset sensor data (IMU) → NavSyncInference
 *    - Previous NavigationState → NavSyncInference
 *    - NO GNSS data provided to inference
 *    - Inference result → NavigationState
 *    - Ground truth stored separately for evaluation
 * 
 * 3. GNSS Recovery:
 *    - Return to using GNSS data
 *    - Compare outage estimates vs ground truth
 */
class NavigationSimulator(
    private val inference: NavSyncInference = InferenceFactory.createInference()
) {
    private var dataset: NavigationDataset? = null
    private var currentStep = 0
    private var outageConfig: OutageConfig = OutageConfig(
        enabled = false,
        startStep = 0,
        durationSeconds = 0
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
     */
    fun configureOutage(enabled: Boolean, startStep: Int, durationSeconds: Int) {
        outageConfig = OutageConfig(
            enabled = enabled,
            startStep = startStep.coerceAtLeast(1),
            durationSeconds = durationSeconds
        )
    }
    
    /**
     * Get the next navigation state.
     * This is called repeatedly to drive the simulation forward.
     */
    fun nextState(): NavigationState {
        val currentDataset = dataset ?: return getDefaultState()
        
        if (currentStep >= currentDataset.points.size) {
            // Dataset complete, stay at last position
            return getLastState()
        }
        
        val dataPoint = currentDataset.points[currentStep]
        val outageEndStep = outageConfig.startStep + outageConfig.durationSeconds
        
        // Determine if we're in a GNSS outage
        val shouldBeInOutage = outageConfig.enabled && 
                               currentStep >= outageConfig.startStep && 
                               currentStep < outageEndStep
        
        // Handle outage state transitions
        when {
            shouldBeInOutage && !inOutage -> {
                // Entering outage - initialize inference with last GNSS state
                inOutage = true
                val lastState = lastNavigationState ?: getDefaultState()
                inference.initialize(lastState)
                lastUpdateTimeMs = dataPoint.sensorData.timestampMs
            }
            !shouldBeInOutage && inOutage -> {
                // Exiting outage (GNSS recovery)
                inOutage = false
                inference.reset()
            }
        }
        
        val state = if (inOutage) {
            // GNSS OUTAGE MODE
            // Inference receives ONLY:
            // 1. Sensor data (IMU)
            // 2. Previous navigation state
            // NO ground truth position is provided
            
            val deltaTime = dataPoint.sensorData.timestampMs - lastUpdateTimeMs
            val previousState = lastNavigationState ?: getDefaultState()
            
            val inferenceResult = inference.estimatePosition(
                sensorData = dataPoint.sensorData,
                previousState = previousState,
                deltaTimeMs = deltaTime
            )
            
            lastUpdateTimeMs = dataPoint.sensorData.timestampMs
            
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
            // Use GNSS data from dataset (ground truth during normal operation)
            NavigationState(
                latitude = dataPoint.gnssData.latitude,
                longitude = dataPoint.gnssData.longitude,
                speedKmh = dataPoint.gnssData.speedKmh,
                headingDegrees = dataPoint.gnssData.headingDegrees,
                confidence = 0.95,
                gnssAvailable = true,
                source = NavigationSource.GNSS
            )
        }
        
        // Store current state for next iteration
        lastNavigationState = state
        currentStep++
        
        // Note: dataPoint.groundTruth is available here for evaluation/metrics
        // but is never passed to the inference engine
        
        return state
    }
    
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
     * This is used ONLY for displaying accuracy metrics, never for inference.
     */
    fun getCurrentGroundTruth() = dataset?.points?.getOrNull(currentStep - 1)?.groundTruth
    
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
}