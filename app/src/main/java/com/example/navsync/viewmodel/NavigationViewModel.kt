package com.example.navsync.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.navsync.data.NavigationDataset
import com.example.navsync.model.NavigationState
import com.example.navsync.simulation.NavigationSimulator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NavigationViewModel : ViewModel() {

    private val simulator = NavigationSimulator()
    private var simulationJob: Job? = null

    var navigationState by mutableStateOf(
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
                // Advance simulation first
                val state = simulator.nextState()
                navigationState = state
                currentStep = simulator.getCurrentStep()
                updateCount++
                
                // Track estimated trajectory during outage
                if (!state.gnssAvailable && !isTrackingEstimated) {
                    // Start tracking estimated trajectory from outage transition point
                    isTrackingEstimated = true
                    android.util.Log.d("NavigationViewModel", "Started tracking estimated trajectory at outage")
                }
                
                if (isTrackingEstimated) {
                    // Add current estimated position to orange trajectory
                    estimatedPoints.add(Pair(state.latitude, state.longitude))
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
                    android.util.Log.d("NavigationViewModel", "Step $currentStep/$totalSteps, elapsed: ${lastTimestampMs/1000.0}s, speed: ${navigationState.speedKmh}")
                }
            }
            
            // Simulation complete
            if (isActive) {
                hasArrived = true
                android.util.Log.d("NavigationViewModel", "Simulation complete: $updateCount updates, final step=$currentStep/$totalSteps")
                android.util.Log.d("NavigationViewModel", "Final state: lat=${navigationState.latitude}, lon=${navigationState.longitude}, speed=${navigationState.speedKmh}, gnss=${navigationState.gnssAvailable}")
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
    
    override fun onCleared() {
        super.onCleared()
        stopSimulation()
    }
}