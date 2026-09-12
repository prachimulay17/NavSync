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
import com.example.navsync.model.NavigationSource
import com.example.navsync.model.NavigationState
import com.example.navsync.ui.theme.StatusGreen

@Composable
fun StatusBar(
    navigationState: NavigationState,
    hasArrived: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Color(0xFF1A2F42).copy(alpha = 0.92f),
                RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // GNSS Status or Arrived indicator - left side
        if (hasArrived) {
            ArrivedIndicator()
        } else {
            GnssStatusIndicator(
                gnssAvailable = navigationState.gnssAvailable,
                confidence = navigationState.confidence
            )
        }
        
        // Speed display - center (dominant)
        SpeedDisplay(speed = navigationState.speedKmh)
        
        // Compass heading - right side
        CompassHeading(heading = navigationState.headingDegrees)
    }
}

@Composable
private fun ArrivedIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(Color(0xFF4CAF50))
        )
        
        Text(
            text = "ARRIVED",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun GnssStatusIndicator(
    gnssAvailable: Boolean,
    confidence: Double
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(if (gnssAvailable) StatusGreen else Color(0xFFFFC107))
        )
        
        Text(
            text = "GNSS",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        
        Text(
            text = "${(confidence * 100).toInt()}%",
            color = Color(0xFFB0BEC5),
            fontSize = 14.sp
        )
    }
}

@Composable
private fun SpeedDisplay(speed: Double) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = speed.toInt().toString(),
                color = Color.White,
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "km/h",
                color = Color(0xFFB0BEC5),
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
            )
        }
    }
}

@Composable
private fun CompassHeading(heading: Double) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Compass circle
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(0xFF1A2F42))
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "N",
                color = Color(0xFF00BFFF),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        
        // Heading text
        Row(
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = "${heading.toInt()}°",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "SE",
                color = Color(0xFFB0BEC5),
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 2.dp, start = 2.dp)
            )
        }
    }
}