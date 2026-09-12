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
        
        val vFileName = "$name.csv"
        val sFileName = "S-${name.substring(2)}.csv"  // V-Vw9 -> S-Vw9
        
        try {
            // Load V-file (reference trajectory)
            val vPoints = loadVFile(vFileName)
            
            // Load S-file (sensor data)
            val sensorDataMap = loadSFile(sFileName)
            
            // Synchronize V and S data by timestamp
            val points = synchronizeVSData(vPoints, sensorDataMap, name)
            
            if (points.isEmpty()) {
                throw DataSourceException("No synchronized data points found for $name")
            }
            
            android.util.Log.d("CsvDataSource", "Loaded $name: V=${vPoints.size} points, S=${sensorDataMap.size} sensor samples, synchronized=${points.size} points, duration: ${points.last().sensorData.timestampMs / 1000.0}s")
            
            // Create NavigationDataset
            val navigationDataset = NavigationDataset(
                name = name,
                description = "VW vehicle dataset $name with IMU",
                points = points
            )
            
            return@withContext ReplayDataset.fromNavigationDataset(navigationDataset, "csv+imu")
            
        } catch (e: DataSourceException) {
            throw e
        } catch (e: Exception) {
            throw DataSourceException("Failed to load dataset $name: ${e.message}", e)
        }
    }
    
    override fun getSourceType(): String = "csv+imu"
    
    override fun isAvailable(): Boolean = true
    
    /**
     * Load V-file (reference trajectory)
     */
    private fun loadVFile(fileName: String): List<Triple<Long, GnssData, GroundTruth>> {
        val inputStream = context.assets.open(fileName)
        val reader = BufferedReader(InputStreamReader(inputStream))
        
        val points = mutableListOf<Triple<Long, GnssData, GroundTruth>>()
        var lineNumber = 0
        var firstTimestamp: Double? = null
        
        reader.useLines { lines ->
            lines.forEach { line ->
                lineNumber++
                
                // Skip header
                if (lineNumber == 1) {
                    if (!line.contains("Latitude") || !line.contains("Longitude")) {
                        throw DataSourceException("Invalid V-file header in $fileName")
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
                        throw DataSourceException("Invalid V-file line $lineNumber: expected at least 9 columns, got ${parts.size}")
                    }
                    
                    // Parse V-file format
                    val timeSeconds = parts[1].toDoubleOrNull()
                        ?: throw DataSourceException("Invalid V timestamp at line $lineNumber")
                    val latitude = parts[2].toDoubleOrNull()
                        ?: throw DataSourceException("Invalid V latitude at line $lineNumber")
                    val longitude = parts[3].toDoubleOrNull()
                        ?: throw DataSourceException("Invalid V longitude at line $lineNumber")
                    val velocityKmh = parts[4].toDoubleOrNull()
                        ?: throw DataSourceException("Invalid V velocity at line $lineNumber")
                    val headingDegrees = parts[5].toDoubleOrNull()
                        ?: throw DataSourceException("Invalid V heading at line $lineNumber")
                    
                    // Store first timestamp as reference
                    if (firstTimestamp == null) {
                        firstTimestamp = timeSeconds
                    }
                    
                    // Calculate milliseconds from start
                    val timestampMs = ((timeSeconds - firstTimestamp!!) * 1000).toLong()
                    
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
                    
                    points.add(Triple(timestampMs, gnssData, groundTruth))
                    
                } catch (e: Exception) {
                    throw DataSourceException("Error parsing V-file line $lineNumber: ${e.message}", e)
                }
            }
        }
        
        return points
    }
    
    /**
     * Load S-file (sensor data) and return map of timestamp -> SensorData
     */
    private fun loadSFile(fileName: String): Map<Long, SensorData> {
        val inputStream = context.assets.open(fileName)
        val reader = BufferedReader(InputStreamReader(inputStream))
        
        val sensorMap = mutableMapOf<Long, SensorData>()
        var lineNumber = 0
        var firstTimestamp: Long? = null
        
        reader.useLines { lines ->
            lines.forEach { line ->
                lineNumber++
                
                // Skip header
                if (lineNumber == 1) {
                    if (!line.contains("ACCELEROMETER") || !line.contains("GYROSCOPE")) {
                        throw DataSourceException("Invalid S-file header in $fileName")
                    }
                    return@forEach
                }
                
                // Skip empty lines
                if (line.trim().isEmpty()) {
                    return@forEach
                }
                
                try {
                    val parts = line.split(",").map { it.trim() }
                    if (parts.size < 24) {
                        throw DataSourceException("Invalid S-file line $lineNumber: expected at least 24 columns, got ${parts.size}")
                    }
                    
                    // Parse S-file format
                    val timeMs = parts[7].toLongOrNull()
                        ?: throw DataSourceException("Invalid S timestamp at line $lineNumber")
                    
                    // Store first timestamp as reference
                    if (firstTimestamp == null) {
                        firstTimestamp = timeMs
                    }
                    
                    // Calculate relative timestamp
                    val relativeTimestampMs = timeMs - firstTimestamp!!
                    
                    val sensorData = SensorData(
                        timestampMs = relativeTimestampMs,
                        // Raw accelerometer (m/s²)
                        accelerationX = parts[9].toDoubleOrNull() ?: 0.0,
                        accelerationY = parts[10].toDoubleOrNull() ?: 0.0,
                        accelerationZ = parts[11].toDoubleOrNull() ?: 0.0,
                        // Gravity vector (m/s²)
                        gravityX = parts[12].toDoubleOrNull() ?: 0.0,
                        gravityY = parts[13].toDoubleOrNull() ?: 0.0,
                        gravityZ = parts[14].toDoubleOrNull() ?: 0.0,
                        // Gyroscope (rad/s)
                        gyroYaw = parts[15].toDoubleOrNull() ?: 0.0,
                        gyroPitch = parts[16].toDoubleOrNull() ?: 0.0,
                        gyroRoll = parts[17].toDoubleOrNull() ?: 0.0,
                        // Magnetometer (μT)
                        magneticX = parts[18].toDoubleOrNull() ?: 0.0,
                        magneticY = parts[19].toDoubleOrNull() ?: 0.0,
                        magneticZ = parts[20].toDoubleOrNull() ?: 0.0,
                        // Orientation (degrees)
                        orientationYaw = parts[21].toDoubleOrNull() ?: 0.0,
                        orientationPitch = parts[22].toDoubleOrNull() ?: 0.0,
                        orientationRoll = parts[23].toDoubleOrNull() ?: 0.0
                    )
                    
                    sensorMap[relativeTimestampMs] = sensorData
                    
                } catch (e: Exception) {
                    throw DataSourceException("Error parsing S-file line $lineNumber: ${e.message}", e)
                }
            }
        }
        
        return sensorMap
    }
    
    /**
     * Synchronize V and S data by matching timestamps
     */
    private fun synchronizeVSData(
        vPoints: List<Triple<Long, GnssData, GroundTruth>>, 
        sensorMap: Map<Long, SensorData>,
        datasetName: String
    ): List<DatasetPoint> {
        val points = mutableListOf<DatasetPoint>()
        
        for ((timestampMs, gnssData, groundTruth) in vPoints) {
            // Find closest S-file sensor data (within 50ms tolerance)
            val sensorData = findClosestSensorData(timestampMs, sensorMap, 50L)
            
            if (sensorData != null) {
                points.add(DatasetPoint(
                    sensorData = sensorData.copy(timestampMs = timestampMs), // Use V timestamp for sync
                    gnssData = gnssData,
                    groundTruth = groundTruth
                ))
            } else {
                android.util.Log.w("CsvDataSource", "No S-file data found for V timestamp ${timestampMs}ms in $datasetName")
            }
        }
        
        return points
    }
    
    /**
     * Find sensor data closest to target timestamp within tolerance
     */
    private fun findClosestSensorData(targetMs: Long, sensorMap: Map<Long, SensorData>, toleranceMs: Long): SensorData? {
        var closestData: SensorData? = null
        var closestDiff = Long.MAX_VALUE
        
        for ((sensorTimestamp, sensorData) in sensorMap) {
            val diff = kotlin.math.abs(sensorTimestamp - targetMs)
            if (diff <= toleranceMs && diff < closestDiff) {
                closestDiff = diff
                closestData = sensorData
            }
        }
        
        return closestData
    }
}
