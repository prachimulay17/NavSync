package com.example.navsync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.navsync.model.NavigationState
import com.example.navsync.ui.theme.StatusGreen

@Composable
fun NavigationHUD(
    navigationState: NavigationState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Color(0xFF1A2F42).copy(alpha = 0.95f),
                RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            )
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Main navigation metrics row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Speed
            NavigationMetric(
                label = "Speed",
                value = "${navigationState.speedKmh.toInt()}",
                unit = "km/h",
                icon = "⏱"
            )
            
            // Heading  
            NavigationMetric(
                label = "Heading",
                value = "${navigationState.headingDegrees.toInt()}°",
                unit = "SE",
                icon = "🧭"
            )
            
            // Navigation source
            NavigationSource(
                gnssAvailable = navigationState.gnssAvailable,
                confidence = navigationState.confidence
            )
        }
        
        // Secondary info row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Position coordinates
            PositionDisplay(
                latitude = navigationState.latitude,
                longitude = navigationState.longitude
            )
            
            // NavSync system status
            SystemStatus(
                gnssAvailable = navigationState.gnssAvailable,
                source = navigationState.source.name
            )
        }
    }
}

@Composable
private fun NavigationMetric(
    label: String,
    value: String,
    unit: String,
    icon: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            color = Color(0xFFB0BEC5),
            fontSize = 12.sp
        )
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = value,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = unit,
                color = Color(0xFFB0BEC5),
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
    }
}

@Composable
private fun NavigationSource(
    gnssAvailable: Boolean,
    confidence: Double
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Navigation",
            color = Color(0xFFB0BEC5),
            fontSize = 12.sp
        )
        
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (gnssAvailable) StatusGreen else Color(0xFFFFC107))
            )
            Text(
                text = if (gnssAvailable) "GNSS" else "GNSS DENIED",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
        
        // Show "NavSync AI" label during outage
        if (!gnssAvailable) {
            Text(
                text = "NavSync AI",
                color = Color(0xFFFFC107),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ConfidenceDisplay(confidence: Double) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Confidence",
            color = Color(0xFFB0BEC5),
            fontSize = 12.sp
        )
        
        // Circular progress indicator
        Box(
            modifier = Modifier.size(44.dp),
            contentAlignment = Alignment.Center
        ) {
            // Background circle
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2D4F73))
            )
            
            // Confidence percentage
            Text(
                text = "${(confidence * 100).toInt()}%",
                color = StatusGreen,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun PositionDisplay(
    latitude: Double,
    longitude: Double
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "📍",
            fontSize = 16.sp
        )
        
        Column {
            Text(
                text = "Position",
                color = Color(0xFFB0BEC5),
                fontSize = 12.sp
            )
            Text(
                text = "${String.format("%.2f", latitude)}, ${String.format("%.4f", longitude)}",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun SystemStatus(
    gnssAvailable: Boolean,
    source: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "📡",
            fontSize = 16.sp
        )
        
        Column {
            Text(
                text = "System",
                color = Color(0xFF00BFFF),
                fontSize = 12.sp
            )
            Text(
                text = source,
                color = Color(0xFF00BFFF),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}