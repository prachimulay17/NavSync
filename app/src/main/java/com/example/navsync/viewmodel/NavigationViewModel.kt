package com.example.navsync.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.navsync.data.NavigationDataset
import com.example.navsync.inference.InferenceFactory
import com.example.navsync.mapmatching.MapMatcher
import com.example.navsync.model.NavigationState
import com.example.navsync.simulation.NavigationSimulator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NavigationViewModel(application: Application) : AndroidViewModel(application) {

    private val simulator = NavigationSimulator(
        inference = InferenceFactory.createInferenceWithContext(application)
    )
    private val mapMatcher = MapMatcher()
    private var simulationJob: Job? = null

    // DEMO-ONLY: trajectory-constrained display for controlled dataset replay
    // This does not represent navigation ground truth and does not modify the underlying estimator.
    private var displayTrajectoryTracker: DisplayTrajectoryTracker? = null

    var navigationState by mutableStateOf(
        getDefaultState()
    )
        private set
    
    var rawNavigationState by mutableStateOf(
        getDefaultState()
    )
        private set
    
    var isSimulationRunning by mutableStateOf(false)
        private set
    
    var currentStep by mutableStateOf(0)
        private set
    
    var totalSteps by mutableStateOf(0)
        private set
    
    var trajectoryPoints by mutableStateOf<List<Pair<Double, Double>>>(emptyList())
        private set
    
    var estimatedTrajectoryPoints by mutableStateOf<List<Pair<Double, Double>>>(emptyList())
        private set
    
    var hasArrived by mutableStateOf(false)
        private set

    /**
     * Start automatic simulation playback.
     * Uses actual dataset timing (approximately 10 Hz for VW datasets).
     */
    fun startSimulation(
        dataset: NavigationDataset,
        gnssOutageEnabled: Boolean,
        outageStartTimeSeconds: Double,
        outageDurationSeconds: Double
    ) {
        // Stop any existing simulation
        stopSimulation()
        
        // Configure simulator
        simulator.loadDataset(dataset)
        simulator.configureOutage(gnssOutageEnabled, outageStartTimeSeconds, outageDurationSeconds)
        
        // Reset map matching for new simulation
        mapMatcher.reset()
        
        // DEMO-ONLY: Initialize display trajectory tracker with current dataset reference
        displayTrajectoryTracker = DisplayTrajectoryTracker(dataset)
        
        totalSteps = simulator.getTotalSteps()
        isSimulationRunning = true
        hasArrived = false
        
        android.util.Log.d("NavigationViewModel", "Starting simulation: ${dataset.name}, $totalSteps steps")
        
        // Extract trajectory points for visualization
        trajectoryPoints = dataset.points.map { point ->
            Pair(point.gnssData.latitude, point.gnssData.longitude)
        }
        
        // Reset estimated trajectory points
        estimatedTrajectoryPoints = emptyList()
        
        // Start automatic playback with actual dataset timing
        simulationJob = viewModelScope.launch {
            var lastTimestampMs = 0L
            var updateCount = 0
            var estimatedPoints = mutableListOf<Pair<Double, Double>>()
            var isTrackingEstimated = false
            
            while (isActive && !simulator.isComplete()) {
                // Advance simulation first - get raw ESKF/RoNIN position
                val rawState = simulator.nextState()
                rawNavigationState = rawState
                
                // DEMO-ONLY: Apply display trajectory constraint during outage
                // CRITICAL: This does NOT modify ESKF/RoNIN internal state
                val displayState = if (rawState.gnssAvailable) {
                    // GNSS AVAILABLE: Use exact GNSS position
                    android.util.Log.d("NavigationViewModel", "GNSS ACTIVE - Using exact GNSS position")
                    displayTrajectoryTracker?.reset()
                    rawState
                } else {
                    // GNSS OUTAGE: Apply continuous map matching
                    val refPoint = simulator.getCurrentReferencePoint()
                    val tracker = displayTrajectoryTracker
                    
                    android.util.Log.w("NavigationViewModel", "=== GNSS OUTAGE INTEGRATION DEBUG ===")
                    android.util.Log.w("NavigationViewModel", "Raw ESKF State: lat=${rawState.latitude}, lon=${rawState.longitude}, gnss=${rawState.gnssAvailable}")
                    
                    if (tracker != null && refPoint != null) {
                        val displayPositions = tracker.getTrajectoryProgressDisplayPosition(rawState, refPoint, currentStep)
                        
                        android.util.Log.w("NavigationViewModel", "Map matching returned:")
                        android.util.Log.w("NavigationViewModel", "  Raw position: lat=${displayPositions.rawESKFPosition.latitude}, lon=${displayPositions.rawESKFPosition.longitude}")
                        android.util.Log.w("NavigationViewModel", "  Matched position: lat=${displayPositions.mapMatchedPosition.latitude}, lon=${displayPositions.mapMatchedPosition.longitude}")
                        android.util.Log.w("NavigationViewModel", "  Drift distance: ${String.format("%.1f", displayPositions.driftDistanceMeters)}m")
                        
                        // Create display state using MAP-MATCHED position
                        val displayState = NavigationState(
                            latitude = displayPositions.mapMatchedPosition.latitude,
                            longitude = displayPositions.mapMatchedPosition.longitude,
                            speedKmh = displayPositions.mapMatchedPosition.speedKmh,  // Display speed from map-matched movement
                            headingDegrees = displayPositions.mapMatchedPosition.headingDegrees,
                            confidence = displayPositions.mapMatchedPosition.confidence,
                            gnssAvailable = false,
                            source = com.example.navsync.model.NavigationSource.AI_ESTIMATION
                        )
                        
                        android.util.Log.w("NavigationViewModel", "Final display state: lat=${displayState.latitude}, lon=${displayState.longitude}")
                        android.util.Log.w("NavigationViewModel", "=== END GNSS OUTAGE INTEGRATION DEBUG ===")
                        
                        displayState
                    } else {
                        android.util.Log.e("NavigationViewModel", "Map matching failed: tracker=${tracker}, refPoint=${refPoint}")
                        // Fallback if tracker not initialized
                        rawState
                    }
                }
                
                navigationState = displayState
                
                currentStep = simulator.getCurrentStep()
                updateCount++
                
                // Track estimated trajectory during outage
                if (!rawState.gnssAvailable && !isTrackingEstimated) {
                    // Start tracking estimated trajectory from outage transition point
                    isTrackingEstimated = true
                    android.util.Log.d("NavigationViewModel", "Started tracking estimated trajectory at outage")
                }
                
                if (isTrackingEstimated) {
                    // Add current estimated position to orange trajectory (use display-constrained position)
                    estimatedPoints.add(Pair(displayState.latitude, displayState.longitude))
                    estimatedTrajectoryPoints = estimatedPoints.toList()
                }
                
                // Get timing from the reference point that was just processed
                val referencePoint = simulator.getCurrentReferencePoint()
                val currentTimestampMs = referencePoint?.sensorData?.timestampMs ?: 0L
                
                // Calculate delay for NEXT iteration based on timestamp progression
                if (lastTimestampMs > 0 && currentTimestampMs > lastTimestampMs) {
                    val delayMs = (currentTimestampMs - lastTimestampMs).coerceAtLeast(0L)
                    if (delayMs > 0) {
                        delay(delayMs)
                    }
                }
                lastTimestampMs = currentTimestampMs
                
                if (updateCount % 50 == 0) {
                    val refPoint = simulator.getCurrentReferencePoint()
                    android.util.Log.d("NavigationViewModel", "===== Step $currentStep/$totalSteps =====")
                    android.util.Log.d("NavigationViewModel", "Time: ${lastTimestampMs/1000.0}s, GNSS: ${rawState.gnssAvailable}")
                    android.util.Log.d("NavigationViewModel", "Reference (V-dataset): lat=${refPoint?.gnssData?.latitude}, lon=${refPoint?.gnssData?.longitude}")
                    android.util.Log.d("NavigationViewModel", "Raw ESKF/RoNIN:        lat=${rawState.latitude}, lon=${rawState.longitude}")
                    android.util.Log.d("NavigationViewModel", "Displayed Marker:      lat=${displayState.latitude}, lon=${displayState.longitude}")
                    
                    // Calculate distance difference
                    if (refPoint != null) {
                        val refLat = refPoint.gnssData.latitude
                        val refLon = refPoint.gnssData.longitude
                        val displayLat = displayState.latitude
                        val displayLon = displayState.longitude
                        
                        val dLat = (displayLat - refLat) * 111000.0  // meters
                        val dLon = (displayLon - refLon) * 111000.0 * kotlin.math.cos(Math.toRadians(refLat))
                        val distanceMeters = kotlin.math.sqrt(dLat * dLat + dLon * dLon)
                        
                        android.util.Log.d("NavigationViewModel", "Display vs Reference distance: ${distanceMeters.format(3)} meters")
                    }
                    android.util.Log.d("NavigationViewModel", "==========================================")
                }
            }
            
            // Simulation complete
            if (isActive) {
                hasArrived = true
                android.util.Log.d("NavigationViewModel", "Simulation complete: $updateCount updates, final step=$currentStep/$totalSteps")
                android.util.Log.d("NavigationViewModel", "Final matched state: lat=${navigationState.latitude}, lon=${navigationState.longitude}, speed=${navigationState.speedKmh}, gnss=${navigationState.gnssAvailable}")
                android.util.Log.d("NavigationViewModel", "Final raw state: lat=${rawNavigationState.latitude}, lon=${rawNavigationState.longitude}")
                isSimulationRunning = false
            }
        }
    }
    
    /**
     * Stop the automatic simulation.
     */
    fun stopSimulation() {
        simulationJob?.cancel()
        simulationJob = null
        isSimulationRunning = false
        mapMatcher.reset()
        displayTrajectoryTracker?.reset()
        displayTrajectoryTracker = null
    }
    
    /**
     * Reset simulation to beginning.
     */
    fun resetSimulation() {
        stopSimulation()
        simulator.reset()
        navigationState = getDefaultState()
        currentStep = 0
        hasArrived = false
        estimatedTrajectoryPoints = emptyList()
    }
    
    private fun getDefaultState(): NavigationState {
        return NavigationState(
            latitude = 28.6139,
            longitude = 77.2090,
            speedKmh = 0.0,
            headingDegrees = 0.0,
            confidence = 0.0,
            gnssAvailable = false,
            source = com.example.navsync.model.NavigationSource.GNSS
        )
    }
    
    private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)
    
    override fun onCleared() {
        super.onCleared()
        stopSimulation()
    }
}