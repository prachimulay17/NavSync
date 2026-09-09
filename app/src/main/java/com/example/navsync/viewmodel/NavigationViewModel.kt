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

    /**
     * Start automatic simulation playback.
     * Updates navigation state every second until simulation completes.
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
        
        // Start automatic playback
        simulationJob = viewModelScope.launch {
            while (isActive && !simulator.isComplete()) {
                navigationState = simulator.nextState()
                currentStep = simulator.getCurrentStep()
                delay(1000L)  // 1 second per step
            }
            
            // Simulation complete
            if (isActive) {
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