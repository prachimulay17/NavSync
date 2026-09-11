package com.example.navsync.data

import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ═══════════════════════════════════════════════════════════════════
 * SYNTHETIC DATA SOURCE
 * ═══════════════════════════════════════════════════════════════════
 * 
 * Generates synthetic navigation datasets for prototyping and testing.
 * This is the current default data source.
 * 
 * Future: Will be replaced with real recorded datasets from CSV files,
 * database, or remote sources.
 */
class SyntheticDataSource : ReplayDataSource {
    
    private val availableRoutes = listOf(
        "Delhi Urban Route",
        "Mumbai Highway",
        "Bangalore Tech Corridor",
        "Chennai Coastal Drive",
        "Pune Hills Route"
    )
    
    override suspend fun getAvailableDatasets(): List<String> {
        return availableRoutes
    }
    
    override suspend fun loadDataset(name: String): ReplayDataset = withContext(Dispatchers.Default) {
        if (name !in availableRoutes) {
            throw DataSourceException("Dataset not found: $name")
        }
        
        val navigationDataset = generateSyntheticDataset(name)
        return@withContext ReplayDataset.fromNavigationDataset(navigationDataset, "synthetic")
    }
    
    override fun getSourceType(): String = "synthetic"
    
    override fun isAvailable(): Boolean = true
    
    /**
     * Generate synthetic dataset for prototyping.
     * Creates realistic-looking trajectories with IMU sensor data.
     */
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

/**
 * ═══════════════════════════════════════════════════════════════════
 * DATA SOURCE REPOSITORY
 * ═══════════════════════════════════════════════════════════════════
 * 
 * Central repository for managing replay data sources.
 * Provides unified access to different data sources.
 * 
 * Current: Uses CsvDataSource for VW datasets by default
 * Can also use SyntheticDataSource for testing
 */
class DatasetRepository(
    private val dataSource: ReplayDataSource
) {
    
    /**
     * Get list of available datasets from current source.
     */
    suspend fun getAvailableDatasets(): List<String> {
        return try {
            dataSource.getAvailableDatasets()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Load dataset by name from current source.
     */
    suspend fun loadDataset(name: String): ReplayDataset {
        return dataSource.loadDataset(name)
    }
    
    /**
     * Load dataset and return in legacy NavigationDataset format.
     * For backward compatibility with existing code.
     */
    suspend fun loadNavigationDataset(name: String): NavigationDataset {
        val replayDataset = loadDataset(name)
        return replayDataset.toNavigationDataset()
    }
    
    /**
     * Get current data source type.
     */
    fun getSourceType(): String {
        return dataSource.getSourceType()
    }
    
    /**
     * Check if current data source is available.
     */
    fun isSourceAvailable(): Boolean {
        return dataSource.isAvailable()
    }
}

/**
 * ═══════════════════════════════════════════════════════════════════
 * FUTURE DATA SOURCE EXAMPLES
 * ═══════════════════════════════════════════════════════════════════
 * 
 * Templates for future data source implementations:
 * 
 * class CsvDataSource(private val csvDirectory: File) : ReplayDataSource {
 *     override suspend fun getAvailableDatasets(): List<String> {
 *         return csvDirectory.listFiles { file -> file.extension == "csv" }
 *             ?.map { it.nameWithoutExtension }
 *             ?: emptyList()
 *     }
 *     
 *     override suspend fun loadDataset(name: String): ReplayDataset {
 *         val file = File(csvDirectory, "$name.csv")
 *         return parseCsvFile(file)
 *     }
 *     
 *     override fun getSourceType(): String = "csv"
 * }
 * 
 * class RecordedDataSource(private val context: Context) : ReplayDataSource {
 *     override suspend fun getAvailableDatasets(): List<String> {
 *         // Query database or file system for recorded sessions
 *     }
 *     
 *     override suspend fun loadDataset(name: String): ReplayDataset {
 *         // Load from internal storage or database
 *     }
 *     
 *     override fun getSourceType(): String = "recorded"
 * }
 */
