package com.example.navsync.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.example.navsync.model.NavigationState
import com.example.navsync.simulation.NavigationSimulator

class NavigationViewModel : ViewModel() {

    private val simulator = NavigationSimulator()

    var navigationState by mutableStateOf(
        simulator.nextState()
    )
        private set

    fun advanceSimulation() {
        navigationState = simulator.nextState()
    }

    fun resetSimulation() {
        simulator.reset()
        navigationState = simulator.nextState()
    }
}