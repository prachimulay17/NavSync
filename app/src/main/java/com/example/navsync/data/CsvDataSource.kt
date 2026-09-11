package com.example.navsync.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs

/**
 * CSV data source for VW vehicle datasets.
 * 
 * Expected CSV format (IOVNB Dataset format):
 * Header row with columns including:
 * - Column 1: No of GPS Satellites Available
 * - Column 2: Time Since Start of Day (seconds)
 * - Column 3: Latitude (degrees)
 * - Column 4: Longitude (degrees)
 * - Column 5: Velocity (km/hr)
 * - Column 6: Heading (degrees)
 * - Column 9: Sample period (seconds)
 * 
 * Parses all valid samples in chronological order using actual timestamps.
 */
class CsvDataSource(private val context: Context) : ReplayDataSource {
    
    private val availableDatasets = listOf("V-Vw9", "V-Vw10", "V-Vw11")
    
    override suspend fun getAvailableDatasets(): List<String> {
        return availableDatasets
    }
    
    override suspend fun loadDataset(name: String): ReplayDataset = withContext(Dispatchers.IO) {
        if (name !in availableDatasets) {
            throw DataSourceException("Dataset not found: $name. Available: $availableDatasets")
        }
        
        val fileName = "$name.csv"
        
        try {
            val inputStream = context.assets.open(fileName)
            val reader = BufferedReader(InputStreamReader(inputStream))
            
            val points = mutableListOf<DatasetPoint>()
            var lineNumber = 0
            var firstTimestamp: Double? = null
            
            reader.useLines { lines ->
                lines.forEach { line ->
                    lineNumber++
                    
                    // Skip header
                    if (lineNumber == 1) {
                        if (!line.contains("Latitude") || !line.contains("Longitude")) {
                            throw DataSourceException("Invalid CSV header in $fileName")
                        }
                        return@forEach
                    }
                    
                    // Skip empty lines
                    if (line.trim().isEmpty()) {
                        return@forEach
                    }
                    
                    try {
                        val parts = line.split(",").map { it.trim() }
                        if (parts.size < 9) {
                            throw DataSourceException("Invalid line $lineNumber in $fileName: expected at least 9 columns, got ${parts.size}")
                        }
                        
                        // Parse IOVNB dataset format
                        // Column 1: Time Since Start of Day (seconds) - index 1
                        // Column 2: Latitude (degrees) - index 2
                        // Column 3: Longitude (degrees) - index 3
                        // Column 4: Velocity (km/hr) - index 4
                        // Column 5: Heading (degrees) - index 5
                        // Column 8: Sample period (seconds) - index 8
                        
                        val timeSeconds = parts[1].toDoubleOrNull()
                            ?: throw DataSourceException("Invalid timestamp at line $lineNumber")
                        val latitude = parts[2].toDoubleOrNull()
                            ?: throw DataSourceException("Invalid latitude at line $lineNumber")
                        val longitude = parts[3].toDoubleOrNull()
                            ?: throw DataSourceException("Invalid longitude at line $lineNumber")
                        val velocityKmh = parts[4].toDoubleOrNull()
                            ?: throw DataSourceException("Invalid velocity at line $lineNumber")
                        val headingDegrees = parts[5].toDoubleOrNull()
                            ?: throw DataSourceException("Invalid heading at line $lineNumber")
                        
                        // Store first timestamp as reference
                        if (firstTimestamp == null) {
                            firstTimestamp = timeSeconds
                        }
                        
                        // Calculate milliseconds from start
                        val timestampMs = ((timeSeconds - firstTimestamp!!) * 1000).toLong()
                        
                        // Create DatasetPoint with actual timestamp
                        val point = createDatasetPoint(
                            timestampMs = timestampMs,
                            latitude = latitude,
                            longitude = longitude,
                            velocityKmh = velocityKmh,
                            headingDegrees = headingDegrees,
                            previousPoint = points.lastOrNull()
                        )
                        
                        points.add(point)
                        
                    } catch (e: Exception) {
                        throw DataSourceException("Error parsing line $lineNumber in $fileName: ${e.message}", e)
                    }
                }
            }
            
            if (points.isEmpty()) {
                throw DataSourceException("No data points found in $fileName")
            }
            
            android.util.Log.d("CsvDataSource", "Loaded $fileName: ${points.size} points, duration: ${points.last().sensorData.timestampMs / 1000.0}s")
            
            // Create NavigationDataset
            val navigationDataset = NavigationDataset(
                name = name,
                description = "VW vehicle dataset $name",
                points = points
            )
            
            return@withContext ReplayDataset.fromNavigationDataset(navigationDataset, "csv")
            
        } catch (e: DataSourceException) {
            throw e
        } catch (e: Exception) {
            throw DataSourceException("Failed to load dataset $fileName: ${e.message}", e)
        }
    }
    
    override fun getSourceType(): String = "csv"
    
    override fun isAvailable(): Boolean = true
    
    /**
     * Create a DatasetPoint from CSV row data.
     * Synthesizes IMU sensor data since CSV doesn't contain it.
     */
    private fun createDatasetPoint(
        timestampMs: Long,
        latitude: Double,
        longitude: Double,
        velocityKmh: Double,
        headingDegrees: Double,
        previousPoint: DatasetPoint?
    ): DatasetPoint {
        
        // Synthesize IMU data
        // In a real system, this would come from actual IMU sensors
        val (accelX, accelY, accelZ, gyroZ) = if (previousPoint != null) {
            val prevVel = previousPoint.gnssData.speedKmh
            val prevHeading = previousPoint.gnssData.headingDegrees
            val prevTime = previousPoint.sensorData.timestampMs
            
            val deltaTime = (timestampMs - prevTime) / 1000.0  // seconds
            
            // Acceleration in m/s^2
            val velocityMps = velocityKmh / 3.6
            val prevVelocityMps = prevVel / 3.6
            val acceleration = if (deltaTime > 0) {
                (velocityMps - prevVelocityMps) / deltaTime
            } else {
                0.0
            }
            
            // Heading change rate (rad/s)
            val headingChange = normalizeAngle(headingDegrees - prevHeading)
            val gyroZ = if (deltaTime > 0) {
                Math.toRadians(headingChange / deltaTime)
            } else {
                0.0
            }
            
            // Simplified: assume acceleration is forward, decompose to body frame
            val headingRad = Math.toRadians(headingDegrees)
            val accelX = acceleration * kotlin.math.cos(headingRad)
            val accelY = acceleration * kotlin.math.sin(headingRad)
            
            Quadruple(accelX, accelY, 9.8, gyroZ)
        } else {
            Quadruple(0.0, 0.0, 9.8, 0.0)
        }
        
        val sensorData = SensorData(
            timestampMs = timestampMs,
            accelerationX = accelX,
            accelerationY = accelY,
            accelerationZ = accelZ,
            gyroX = 0.0,
            gyroY = 0.0,
            gyroZ = gyroZ
        )
        
        val gnssData = GnssData(
            timestampMs = timestampMs,
            latitude = latitude,
            longitude = longitude,
            speedKmh = velocityKmh,
            headingDegrees = headingDegrees
        )
        
        val groundTruth = GroundTruth(
            timestampMs = timestampMs,
            latitude = latitude,
            longitude = longitude,
            speedKmh = velocityKmh,
            headingDegrees = headingDegrees
        )
        
        return DatasetPoint(
            sensorData = sensorData,
            gnssData = gnssData,
            groundTruth = groundTruth
        )
    }
    
    /**
     * Normalize angle difference to [-180, 180] range.
     */
    private fun normalizeAngle(angle: Double): Double {
        var normalized = angle
        while (normalized > 180.0) normalized -= 360.0
        while (normalized < -180.0) normalized += 360.0
        return normalized
    }
    
    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )
}
