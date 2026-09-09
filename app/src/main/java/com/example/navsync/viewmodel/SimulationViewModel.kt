package com.example.navsync.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.example.navsync.data.DatasetRepository

data class SimulationConfig(
    val dataset: String = "Delhi Urban Route",
    val gnssActive: Boolean = true,
    val outageDuration: Int = 30, // seconds
    val outageStartTime: Int = 20 // seconds into the simulation
)

class SimulationViewModel : ViewModel() {
    
    private val datasetRepository = DatasetRepository()
    
    var simulationConfig by mutableStateOf(SimulationConfig())
        private set
    
    fun getAvailableDatasets(): List<String> = datasetRepository.getAvailableDatasets()
    
    fun selectDataset(dataset: String) {
        simulationConfig = simulationConfig.copy(dataset = dataset)
    }
    
    fun setGnssActive(active: Boolean) {
        simulationConfig = simulationConfig.copy(gnssActive = active)
    }
    
    fun setOutageDuration(duration: Int) {
        simulationConfig = simulationConfig.copy(outageDuration = duration)
    }
    
    fun setOutageStartTime(startTime: Int) {
        simulationConfig = simulationConfig.copy(outageStartTime = startTime)
    }
    
    /**
     * Load the configured dataset.
     */
    fun loadConfiguredDataset() = datasetRepository.loadDataset(simulationConfig.dataset)
}