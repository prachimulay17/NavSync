package com.example.navsync.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.navsync.data.CsvDataSource
import com.example.navsync.data.DatasetRepository
import com.example.navsync.data.NavigationDataset
import kotlinx.coroutines.launch

data class SimulationConfig(
    val dataset: String = "V-Vw9",
    val gnssActive: Boolean = true,
    val outageDuration: Int = 60, // seconds - long enough to test drift through end of Vw9
    val outageStartTime: Int = 35 // seconds into the simulation - Vw9 outage timing
)

class SimulationViewModel(application: Application) : AndroidViewModel(application) {
    
    private val datasetRepository = DatasetRepository(CsvDataSource(application))
    
    var simulationConfig by mutableStateOf(SimulationConfig())
        private set
    
    var availableDatasets by mutableStateOf<List<String>>(emptyList())
        private set
    
    var isLoadingDatasets by mutableStateOf(false)
        private set
    
    init {
        loadAvailableDatasets()
    }
    
    private fun loadAvailableDatasets() {
        viewModelScope.launch {
            isLoadingDatasets = true
            try {
                availableDatasets = datasetRepository.getAvailableDatasets()
            } catch (e: Exception) {
                // Fallback to empty list
                availableDatasets = emptyList()
            } finally {
                isLoadingDatasets = false
            }
        }
    }
    
    fun getAvailableDatasetsSync(): List<String> = availableDatasets
    
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
     * This is a suspend function to support async data sources.
     */
    suspend fun loadConfiguredDataset(): NavigationDataset {
        return datasetRepository.loadNavigationDataset(simulationConfig.dataset)
    }
}