package com.example.navsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.navsync.viewmodel.NavigationViewModel

@Composable
fun NavigationScreen(
    navigationViewModel: NavigationViewModel = viewModel()
) {

    val navigationState = navigationViewModel.navigationState

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        Text(
            text = "NavSync"
        )

        Text(
            text = "Intelligent Navigation"
        )

        Text(
            text = "Latitude: ${navigationState.latitude}"
        )

        Text(
            text = "Longitude: ${navigationState.longitude}"
        )

        Text(
            text = "Speed: ${navigationState.speedKmh} km/h"
        )

        Text(
            text = "Heading: ${navigationState.headingDegrees}°"
        )

        Text(
            text = "Confidence: ${navigationState.confidence * 100}%"
        )

        Text(
            text = "GNSS: ${
                if (navigationState.gnssAvailable) {
                    "Available"
                } else {
                    "Unavailable"
                }
            }"
        )

        Text(
            text = "Source: ${navigationState.source}"
        )

        Button(
            onClick = {
                navigationViewModel.advanceSimulation()
            },
            modifier = Modifier.padding(top = 24.dp)
        ) {
            Text("Next Simulation Step")
        }

        Button(
            onClick = {
                navigationViewModel.resetSimulation()
            }
        ) {
            Text("Reset Simulation")
        }
    }
}