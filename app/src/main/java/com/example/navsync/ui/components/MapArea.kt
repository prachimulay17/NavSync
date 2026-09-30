package com.example.navsync.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.navsync.model.NavigationState
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun MapArea(
    navigationState: NavigationState,
    modifier: Modifier = Modifier
) {
    // PLACEHOLDER MAP CONTAINER
    // This component is designed for easy replacement with real map SDK
    // (Google Maps, MapBox, OpenStreetMap, etc.)
    // 
    // Current implementation provides:
    // - Clean dark background suitable for navigation
    // - Route overlay structure (easily replaceable with real route geometry)
    // - Vehicle marker system (maintains position/heading/confidence visualization)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F1419)) // Dark map background
            .clipToBounds()
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            // Simple route overlay structure
            drawRouteOverlay()
            
            // Vehicle/navigation marker at center
            drawVehicleMarker(
                center = center,
                heading = navigationState.headingDegrees.toFloat(),
                confidence = navigationState.confidence.toFloat(),
                gnssAvailable = navigationState.gnssAvailable
            )
        }
    }
}

private fun DrawScope.drawRouteOverlay() {
    // ROUTE OVERLAY PLACEHOLDER
    // Simple route path structure - designed for easy replacement with:
    // - Real route geometry from navigation APIs
    // - Turn-by-turn directions
    // - Traffic-aware routing
    val routeWidth = 8.dp.toPx()
    val routeColor = Color(0xFF00BFFF)
    
    // Simple vertical route (placeholder for real route data)
    val centerX = size.width / 2f
    val path = Path()
    
    path.moveTo(centerX, 0f)
    path.quadraticBezierTo(
        centerX - 20f, size.height * 0.3f,
        centerX, size.height * 0.5f
    )
    path.quadraticBezierTo(
        centerX + 15f, size.height * 0.7f,
        centerX, size.height
    )
    
    // Draw route with subtle glow
    drawPath(
        path = path,
        color = routeColor.copy(alpha = 0.3f),
        style = Stroke(width = routeWidth * 2f)
    )
    drawPath(
        path = path,
        color = routeColor,
        style = Stroke(width = routeWidth)
    )
}

private fun DrawScope.drawVehicleMarker(
    center: Offset,
    heading: Float,
    confidence: Float,
    gnssAvailable: Boolean
) {
    // VEHICLE MARKER - Professional Navigation Style
    // Designed to work with any map SDK - position/heading/confidence visualization
    val vehicleRadius = 16.dp.toPx()
    
    // Confidence ring (subtle indicator)
    val confidenceAlpha = if (gnssAvailable) 0.6f else 0.4f
    val confidenceColor = if (gnssAvailable) Color(0xFF00E676) else Color(0xFFFFC107)
    
    drawCircle(
        color = confidenceColor.copy(alpha = confidenceAlpha * 0.3f),
        radius = vehicleRadius * 1.8f,
        center = center
    )
    drawCircle(
        color = confidenceColor.copy(alpha = confidenceAlpha),
        radius = vehicleRadius * 1.8f,
        center = center,
        style = Stroke(width = 2.dp.toPx())
    )
    
    // Vehicle marker - professional navigation style
    drawCircle(
        color = Color.White,
        radius = vehicleRadius,
        center = center
    )
    drawCircle(
        color = Color(0xFF00BFFF),
        radius = vehicleRadius * 0.7f,
        center = center
    )
    
    // Directional indicator (heading arrow)
    val arrowSize = vehicleRadius * 0.6f
    val headingRadians = Math.toRadians((heading - 90).toDouble())
    
    val arrowTip = Offset(
        center.x + (arrowSize * cos(headingRadians)).toFloat(),
        center.y + (arrowSize * sin(headingRadians)).toFloat()
    )
    
    // Arrow shaft
    drawLine(
        color = Color.White,
        start = center,
        end = arrowTip,
        strokeWidth = 3.dp.toPx()
    )
    
    // Arrow head (small triangle)
    val arrowHeadSize = 4.dp.toPx()
    val leftAngle = headingRadians - 0.5
    val rightAngle = headingRadians + 0.5
    
    val leftPoint = Offset(
        arrowTip.x - (arrowHeadSize * cos(leftAngle)).toFloat(),
        arrowTip.y - (arrowHeadSize * sin(leftAngle)).toFloat()
    )
    val rightPoint = Offset(
        arrowTip.x - (arrowHeadSize * cos(rightAngle)).toFloat(),
        arrowTip.y - (arrowHeadSize * sin(rightAngle)).toFloat()
    )
    
    drawLine(color = Color.White, start = arrowTip, end = leftPoint, strokeWidth = 2.dp.toPx())
    drawLine(color = Color.White, start = arrowTip, end = rightPoint, strokeWidth = 2.dp.toPx())
}