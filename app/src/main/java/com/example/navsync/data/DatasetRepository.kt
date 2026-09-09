package com.example.navsync.data

import kotlin.math.cos
import kotlin.math.sin

/**
 * Repository for navigation datasets.
 * Currently provides synthetic datasets for prototyping.
 * Future: Load real recorded datasets from files or remote sources.
 */
class DatasetRepository {
    
    fun getAvailableDatasets(): List<String> = listOf(
        "Delhi Urban Route",
        "Mumbai Highway",
        "Bangalore Tech Corridor",
        "Chennai Coastal Drive",
        "Pune Hills Route"
    )
    
    fun loadDataset(name: String): NavigationDataset {
        // Generate synthetic dataset for prototyping
        // Future: Load from JSON, CSV, or database
        return generateSyntheticDataset(name)
    }
    
    private fun generateSyntheticDataset(name: String): NavigationDataset {
        val basePoints = when (name) {
            "Delhi Urban Route" -> Triple(28.6139, 77.2090, 42.0)
            "Mumbai Highway" -> Triple(19.0760, 72.8777, 65.0)
            "Bangalore Tech Corridor" -> Triple(12.9716, 77.5946, 38.0)
            "Chennai Coastal Drive" -> Triple(13.0827, 80.2707, 52.0)
            "Pune Hills Route" -> Triple(18.5204, 73.8567, 45.0)
            else -> Triple(28.6139, 77.2090, 42.0)
        }
        
        val (startLat, startLon, avgSpeed) = basePoints
        val points = mutableListOf<DatasetPoint>()
        
        // Generate 100 points representing ~100 seconds of driving
        var currentLat = startLat
        var currentLon = startLon
        var currentHeading = 137.0
        
        for (i in 0 until 100) {
            // Add slight variations to simulate realistic movement
            val speedVariation = avgSpeed + (Math.random() * 6 - 3)
            val headingVariation = currentHeading + (Math.random() * 4 - 2)
            
            // Calculate position change (approximate)
            val distanceKm = speedVariation / 3600.0  // distance in 1 second
            val latChange = distanceKm * cos(Math.toRadians(headingVariation)) / 111.0
            val lonChange = distanceKm * sin(Math.toRadians(headingVariation)) / (111.0 * cos(Math.toRadians(currentLat)))
            
            currentLat += latChange
            currentLon += lonChange
            currentHeading = headingVariation
            
            // Sensor data (IMU) - always available
            val sensorData = SensorData(
                timestampMs = i * 1000L,
                accelerationX = Math.random() * 0.5 - 0.25,
                accelerationY = Math.random() * 0.5 - 0.25,
                accelerationZ = 9.8 + Math.random() * 0.2 - 0.1,
                gyroX = Math.random() * 0.02 - 0.01,
                gyroY = Math.random() * 0.02 - 0.01,
                gyroZ = (headingVariation - currentHeading) * 0.1
            )
            
            // GNSS data - available only when signal present
            val gnssData = GnssData(
                timestampMs = i * 1000L,
                latitude = currentLat,
                longitude = currentLon,
                speedKmh = speedVariation,
                headingDegrees = currentHeading
            )
            
            // Ground truth - for evaluation only
            val groundTruth = GroundTruth(
                timestampMs = i * 1000L,
                latitude = currentLat,
                longitude = currentLon,
                speedKmh = speedVariation,
                headingDegrees = currentHeading
            )
            
            points.add(
                DatasetPoint(
                    sensorData = sensorData,
                    gnssData = gnssData,
                    groundTruth = groundTruth
                )
            )
        }
        
        return NavigationDataset(
            name = name,
            description = "Synthetic dataset for $name",
            points = points
        )
    }
}
