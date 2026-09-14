package com.example.navsync.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.navsync.ui.components.FloatingButtons
import com.example.navsync.ui.components.MapLibreMapArea
import com.example.navsync.ui.components.NavigationHUD
import com.example.navsync.ui.components.StatusBar
import com.example.navsync.viewmodel.NavigationViewModel

@Composable
fun NavigationScreen(
    navigationViewModel: NavigationViewModel = viewModel()
) {
    val navigationState = navigationViewModel.navigationState
    val trajectoryPoints = navigationViewModel.trajectoryPoints
    val estimatedTrajectoryPoints = navigationViewModel.estimatedTrajectoryPoints
    val hasArrived = navigationViewModel.hasArrived

    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        // MapLibre map area (full screen background)
        MapLibreMapArea(
            navigationState = navigationState,
            trajectoryPoints = trajectoryPoints,
            estimatedTrajectoryPoints = estimatedTrajectoryPoints,
            rawNavigationState = navigationViewModel.rawNavigationState,
            modifier = Modifier.fillMaxSize()
        )
        
        // Status bar overlay (top)
        StatusBar(
            navigationState = navigationState,
            hasArrived = hasArrived,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        )
        
        // Floating buttons (right side)
        FloatingButtons(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 20.dp)
        )
        
        // Navigation HUD overlay (bottom)
        NavigationHUD(
            navigationState = navigationState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
        )
    }
}