package com.example.navsync.data

/**
 * ═══════════════════════════════════════════════════════════════════
 * REPLAY DATA CONTRACT
 * ═══════════════════════════════════════════════════════════════════
 * 
 * This file defines the data contract for NavSync replay sources.
 * All data sources (synthetic, CSV, database, remote) must provide
 * data in this format.
 * 
 * The contract ensures:
 * - Clean separation between sensor/GNSS/ground-truth data
 * - Consistent data format across all sources
 * - Replaceable data sources without changing pipeline
 */

/**
 * Type alias for existing DatasetPoint (for compatibility).
 * ReplayDataPoint is the preferred name going forward.
 */
typealias ReplayDataPoint = DatasetPoint

/**
 * Metadata about a replay dataset.
 */
data class ReplayMetadata(
    val name: String,
    val description: String,
    val durationSeconds: Double,
    val totalPoints: Int,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val source: String  // e.g., "synthetic", "csv", "recorded"
)

/**
 * Complete replay dataset with metadata and data points.
 * Wraps NavigationDataset with additional metadata.
 */
data class ReplayDataset(
    val metadata: ReplayMetadata,
    val dataPoints: List<ReplayDataPoint>
) {
    /**
     * Get data point at specific index.
     */
    fun getPoint(index: Int): ReplayDataPoint? {
        return dataPoints.getOrNull(index)
    }
    
    /**
     * Get total number of data points.
     */
    fun size(): Int = dataPoints.size
    
    /**
     * Check if dataset is empty.
     */
    fun isEmpty(): Boolean = dataPoints.isEmpty()
    
    /**
     * Convert to legacy NavigationDataset format (for backward compatibility).
     */
    fun toNavigationDataset(): NavigationDataset {
        return NavigationDataset(
            name = metadata.name,
            description = metadata.description,
            points = dataPoints
        )
    }
    
    companion object {
        /**
         * Create from legacy NavigationDataset format.
         */
        fun fromNavigationDataset(dataset: NavigationDataset, source: String = "unknown"): ReplayDataset {
            val startTime = dataset.points.firstOrNull()?.sensorData?.timestampMs ?: 0L
            val endTime = dataset.points.lastOrNull()?.sensorData?.timestampMs ?: 0L
            val duration = (endTime - startTime) / 1000.0
            
            return ReplayDataset(
                metadata = ReplayMetadata(
                    name = dataset.name,
                    description = dataset.description,
                    durationSeconds = duration,
                    totalPoints = dataset.points.size,
                    startTimestampMs = startTime,
                    endTimestampMs = endTime,
                    source = source
                ),
                dataPoints = dataset.points
            )
        }
    }
}

/**
 * ═══════════════════════════════════════════════════════════════════
 * REPLAY DATA SOURCE INTERFACE
 * ═══════════════════════════════════════════════════════════════════
 * 
 * All data sources must implement this interface.
 * Provides a clean abstraction for different data sources.
 * 
 * Implementations:
 * - SyntheticDataSource: Current synthetic dataset generator
 * - CsvDataSource: Future CSV file parser
 * - RecordedDataSource: Future real sensor recording
 * - RemoteDataSource: Future remote dataset loading
 */
interface ReplayDataSource {
    
    /**
     * Get list of available datasets from this source.
     * @return List of dataset names
     */
    suspend fun getAvailableDatasets(): List<String>
    
    /**
     * Load a dataset by name.
     * @param name Dataset name
     * @return Complete replay dataset
     * @throws DataSourceException if dataset cannot be loaded
     */
    suspend fun loadDataset(name: String): ReplayDataset
    
    /**
     * Get source type identifier.
     * @return Source type (e.g., "synthetic", "csv", "recorded")
     */
    fun getSourceType(): String
    
    /**
     * Check if this source is available/ready.
     * @return true if source can provide data
     */
    fun isAvailable(): Boolean = true
}

/**
 * Exception thrown when data source operations fail.
 */
class DataSourceException(message: String, cause: Throwable? = null) : Exception(message, cause)
