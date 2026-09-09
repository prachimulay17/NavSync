package com.example.navsync.simulation

import com.example.navsync.model.NavigationSource
import com.example.navsync.model.NavigationState

class NavigationSimulator {

    private var step = 0

    fun nextState(): NavigationState {

        step++

        return when {

            // Normal GNSS operation
            step <= 10 -> {
                NavigationState(
                    latitude = 28.6139 + step * 0.0001,
                    longitude = 77.2090 + step * 0.0001,
                    speedKmh = 42.0,
                    headingDegrees = 137.0,
                    confidence = 0.95,
                    gnssAvailable = true,
                    source = NavigationSource.GNSS
                )
            }

            // Simulated GNSS outage
            step <= 20 -> {
                NavigationState(
                    latitude = 28.6149 + (step - 10) * 0.0001,
                    longitude = 77.2100 + (step - 10) * 0.0001,
                    speedKmh = 41.5,
                    headingDegrees = 137.5,
                    confidence = 0.80,
                    gnssAvailable = false,
                    source = NavigationSource.DEAD_RECKONING
                )
            }

            // GNSS recovery
            else -> {
                NavigationState(
                    latitude = 28.6159 + (step - 20) * 0.0001,
                    longitude = 77.2110 + (step - 20) * 0.0001,
                    speedKmh = 42.0,
                    headingDegrees = 137.0,
                    confidence = 0.93,
                    gnssAvailable = true,
                    source = NavigationSource.GNSS
                )
            }
        }
    }

    fun reset() {
        step = 0
    }
}