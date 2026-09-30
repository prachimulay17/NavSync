package com.example.navsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.navsync.ui.NavigationScreen
import com.example.navsync.ui.SimulationScreen
import com.example.navsync.ui.theme.NavSyncTheme
import com.example.navsync.ui.theme.ElectricBlue
import com.example.navsync.viewmodel.NavigationViewModel
import com.example.navsync.viewmodel.SimulationViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {
            NavSyncTheme {
                NavSyncApp()
            }
        }
    }
}

@Composable
fun NavSyncApp() {
    val navController = rememberNavController()
    
    // Shared ViewModel instances across both screens
    val navigationViewModel: NavigationViewModel = viewModel()
    val simulationViewModel: SimulationViewModel = viewModel()
    
    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = "navigation"
        ) {
            composable("navigation") {
                NavigationScreen(navigationViewModel = navigationViewModel)
            }
            composable("simulation") {
                SimulationScreen(
                    simulationViewModel = simulationViewModel,
                    navigationViewModel = navigationViewModel
                )
            }
        }
        
        // Navigation FAB
        FloatingActionButton(
            onClick = {
                val currentRoute = navController.currentBackStackEntry?.destination?.route
                if (currentRoute == "navigation") {
                    navController.navigate("simulation")
                } else {
                    navController.navigate("navigation")
                }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .systemBarsPadding()
                .padding(20.dp)
                .size(56.dp),
            containerColor = ElectricBlue.copy(alpha = 0.9f),
            contentColor = Color.White
        ) {
            val currentRoute = navController.currentBackStackEntry?.destination?.route
            Text(
                text = if (currentRoute == "navigation") "⚙" else "🗺",
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}