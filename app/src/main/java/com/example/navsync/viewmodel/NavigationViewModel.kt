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

    /**
     * Start automatic simulation playback.
     * Uses actual dataset timing (approximately 10 Hz for VW datasets).
     */
    fun startSimulation(
        dataset: NavigationDataset,
        gnssOutageEnabled: Boolean,
        outageStartStep: Int,
        outageDurationSeconds: Int
    ) {
        // Stop any existing simulation
        stopSimulation()
        
        // Configure simulator
        simulator.loadDataset(dataset)
        simulator.configureOutage(gnssOutageEnabled, outageStartStep, outageDurationSeconds)
        
        totalSteps = simulator.getTotalSteps()
        isSimulationRunning = true
        
        android.util.Log.d("NavigationViewModel", "Starting simulation: ${dataset.name}, $totalSteps steps")
        
        // Extract trajectory points for visualization
        trajectoryPoints = dataset.points.map { point ->
            Pair(point.gnssData.latitude, point.gnssData.longitude)
        }
        
        // Start automatic playback with actual dataset timing
        simulationJob = viewModelScope.launch {
            var lastTimestampMs = 0L
            var updateCount = 0
            
            while (isActive && !simulator.isComplete()) {
                // Get current timestamp BEFORE advancing
                val currentPoint = dataset.points.getOrNull(simulator.getCurrentStep())
                val currentTimestampMs = currentPoint?.sensorData?.timestampMs ?: 0L
                
                // Calculate delay based on actual timestamp delta
                if (lastTimestampMs > 0) {
                    val delayMs = (currentTimestampMs - lastTimestampMs).coerceAtLeast(0L)
                    if (delayMs > 0) {
                        delay(delayMs)
                    }
                }
                
                // Advance simulation
                val state = simulator.nextState()
                navigationState = state
                currentStep = simulator.getCurrentStep()
                lastTimestampMs = currentTimestampMs
                
                updateCount++
                if (updateCount % 50 == 0) {
                    android.util.Log.d("NavigationViewModel", "Step $currentStep/$totalSteps, elapsed: ${lastTimestampMs/1000.0}s")
                }
            }
            
            // Simulation complete
            if (isActive) {
                android.util.Log.d("NavigationViewModel", "Simulation complete: $updateCount updates")
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