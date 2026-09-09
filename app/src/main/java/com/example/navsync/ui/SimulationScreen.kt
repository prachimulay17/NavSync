package com.example.navsync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.navsync.ui.theme.ElectricBlue
import com.example.navsync.ui.theme.NavyDark
import com.example.navsync.ui.theme.NavyMedium
import com.example.navsync.ui.theme.StatusGreen
import com.example.navsync.ui.theme.StatusRed
import com.example.navsync.ui.theme.StatusYellow
import com.example.navsync.ui.theme.TextPrimary
import com.example.navsync.ui.theme.TextSecondary
import com.example.navsync.viewmodel.NavigationViewModel
import com.example.navsync.viewmodel.SimulationViewModel

@Composable
fun SimulationScreen(
    simulationViewModel: SimulationViewModel = viewModel(),
    navigationViewModel: NavigationViewModel = viewModel()
) {
    val simulationConfig = simulationViewModel.simulationConfig
    val isRunning = navigationViewModel.isSimulationRunning
    val currentStep = navigationViewModel.currentStep
    val totalSteps = navigationViewModel.totalSteps

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NavyDark)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Header
        SimulationHeader()
        
        // Dataset Selection
        DatasetSelection(
            selectedDataset = simulationConfig.dataset,
            availableDatasets = simulationViewModel.getAvailableDatasets(),
            onDatasetSelected = simulationViewModel::selectDataset,
            enabled = !isRunning
        )
        
        // GNSS Configuration
        GnssConfiguration(
            gnssActive = simulationConfig.gnssActive,
            onGnssActiveChanged = simulationViewModel::setGnssActive,
            outageStartTime = simulationConfig.outageStartTime,
            onOutageStartTimeChanged = simulationViewModel::setOutageStartTime,
            outageDuration = simulationConfig.outageDuration,
            onOutageDurationChanged = simulationViewModel::setOutageDuration,
            enabled = !isRunning
        )
        
        // Simulation Status
        SimulationStatus(
            isRunning = isRunning,
            currentStep = currentStep,
            totalSteps = totalSteps
        )
        
        // Control Buttons
        SimulationControls(
            isRunning = isRunning,
            onStart = {
                val dataset = simulationViewModel.loadConfiguredDataset()
                navigationViewModel.startSimulation(
                    dataset = dataset,
                    gnssOutageEnabled = !simulationConfig.gnssActive,
                    outageStartStep = simulationConfig.outageStartTime,
                    outageDurationSeconds = simulationConfig.outageDuration
                )
            },
            onStop = navigationViewModel::stopSimulation,
            onReset = navigationViewModel::resetSimulation
        )
    }
}

@Composable
private fun SimulationHeader() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "NavSync Simulation",
            color = TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Configure GNSS outage experiment",
            color = TextSecondary,
            fontSize = 16.sp
        )
    }
}

@Composable
private fun DatasetSelection(
    selectedDataset: String,
    availableDatasets: List<String>,
    onDatasetSelected: (String) -> Unit,
    enabled: Boolean
) {
    var expanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NavyMedium),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Dataset",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
            
            Text(
                text = "Select recorded navigation data to replay",
                color = TextSecondary,
                fontSize = 13.sp
            )
            
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(NavyDark)
                        .clickable(enabled = enabled) { expanded = !expanded }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedDataset,
                        color = if (enabled) TextPrimary else TextSecondary,
                        fontSize = 16.sp
                    )
                    Text(
                        text = if (expanded) "▲" else "▼",
                        color = if (enabled) ElectricBlue else TextSecondary,
                        fontSize = 14.sp
                    )
                }
                
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(NavyMedium)
                ) {
                    availableDatasets.forEach { dataset ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = dataset,
                                    color = TextPrimary
                                )
                            },
                            onClick = {
                                onDatasetSelected(dataset)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GnssConfiguration(
    gnssActive: Boolean,
    onGnssActiveChanged: (Boolean) -> Unit,
    outageStartTime: Int,
    onOutageStartTimeChanged: (Int) -> Unit,
    outageDuration: Int,
    onOutageDurationChanged: (Int) -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NavyMedium),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "GNSS Configuration",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
            
            // GNSS Mode Selection
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "GNSS Mode",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = gnssActive,
                            onClick = { if (enabled) onGnssActiveChanged(true) }
                        )
                        .padding(8.dp)
                ) {
                    RadioButton(
                        selected = gnssActive,
                        onClick = { if (enabled) onGnssActiveChanged(true) },
                        enabled = enabled,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = StatusGreen,
                            unselectedColor = TextSecondary
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "GNSS Active",
                            color = if (enabled) TextPrimary else TextSecondary,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Normal GPS operation throughout",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = !gnssActive,
                            onClick = { if (enabled) onGnssActiveChanged(false) }
                        )
                        .padding(8.dp)
                ) {
                    RadioButton(
                        selected = !gnssActive,
                        onClick = { if (enabled) onGnssActiveChanged(false) },
                        enabled = enabled,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = StatusYellow,
                            unselectedColor = TextSecondary
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "GNSS Outage",
                            color = if (enabled) TextPrimary else TextSecondary,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Simulate GPS loss and ML estimation",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
            
            // Outage Configuration (only shown when GNSS Outage is selected)
            if (!gnssActive) {
                // Outage Start Time
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Outage Start",
                            color = TextSecondary,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "${outageStartTime}s",
                            color = ElectricBlue,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    
                    Slider(
                        value = outageStartTime.toFloat(),
                        onValueChange = { if (enabled) onOutageStartTimeChanged(it.toInt()) },
                        valueRange = 5f..60f,
                        steps = 10,
                        enabled = enabled,
                        colors = SliderDefaults.colors(
                            thumbColor = ElectricBlue,
                            activeTrackColor = ElectricBlue,
                            inactiveTrackColor = NavyDark,
                            disabledThumbColor = TextSecondary,
                            disabledActiveTrackColor = TextSecondary
                        )
                    )
                }
                
                // Outage Duration
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Outage Duration",
                            color = TextSecondary,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "${outageDuration}s",
                            color = ElectricBlue,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    
                    Slider(
                        value = outageDuration.toFloat(),
                        onValueChange = { if (enabled) onOutageDurationChanged(it.toInt()) },
                        valueRange = 10f..60f,
                        steps = 9,
                        enabled = enabled,
                        colors = SliderDefaults.colors(
                            thumbColor = ElectricBlue,
                            activeTrackColor = ElectricBlue,
                            inactiveTrackColor = NavyDark,
                            disabledThumbColor = TextSecondary,
                            disabledActiveTrackColor = TextSecondary
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun SimulationStatus(
    isRunning: Boolean,
    currentStep: Int,
    totalSteps: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NavyMedium),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Status",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(if (isRunning) StatusGreen else StatusRed)
                        )
                        Text(
                            text = if (isRunning) "Running" else "Stopped",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Progress",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                    Text(
                        text = if (totalSteps > 0) "$currentStep / $totalSteps" else "—",
                        color = ElectricBlue,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun SimulationControls(
    isRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit
) {
    // Primary controls only - Start/Stop and Reset
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = if (isRunning) onStop else onStart,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) StatusRed else StatusGreen
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = if (isRunning) "Stop" else "Start",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        
        Button(
            onClick = onReset,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = NavyMedium,
                contentColor = TextPrimary
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = "Reset",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }
}