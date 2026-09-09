package com.example.navsync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FloatingButtons(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Layers button
        FloatingButton(
            icon = "◈", // Diamond/layers icon
            onClick = { /* TODO: Handle layers */ }
        )
        
        // Navigation button
        FloatingButton(
            icon = "➤", // Navigation arrow
            onClick = { /* TODO: Handle navigation */ }
        )
        
        // Center/Location button
        FloatingButton(
            icon = "⊕", // Target/crosshair
            onClick = { /* TODO: Handle center */ }
        )
    }
}

@Composable
private fun FloatingButton(
    icon: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color(0xFF1A2F42).copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = icon,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium
        )
    }
}