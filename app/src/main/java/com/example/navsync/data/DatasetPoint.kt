package com.example.navsync.data

/**
 * Sensor data available to the inference engine.
 * This contains ONLY the sensors that would be available during a real GNSS outage:
 * - IMU (accelerometer, gyroscope)
 * - Previous velocity/heading estimates
 * 
 * DOES NOT contain GNSS position - that would be cheating during outage.
 */
data class SensorData(
    val timestampMs: Long,
    val accelerationX: Double,
    val accelerationY: Double,
    val accelerationZ: Double,
    val gyroX: Double,
    val gyroY: Double,
    val gyroZ: Double
)

/**
 * GNSS position data.
 * Available only when GNSS signal is present.
 * NEVER provided to inference during outage.
 */
data class GnssData(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double,
    val headingDegrees: Double
)

/**
 * Ground truth data for evaluation.
 * Contains the true position regardless of GNSS availability.
 * Used ONLY for measuring inference accuracy, never as input to inference.
 */
data class GroundTruth(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double,
    val headingDegrees: Double
)

/**
 * Complete data point from a recorded dataset.
 * Contains all three data types but they are used separately:
 * - sensorData: Always available to inference
 * - gnssData: Available only when GNSS is active
 * - groundTruth: For evaluation only, never for inference
 */
data class DatasetPoint(
    val sensorData: SensorData,
    val gnssData: GnssData,
    val groundTruth: GroundTruth
)

/**
 * A complete navigation dataset containing a sequence of recorded points.
 */
data class NavigationDataset(
    val name: String,
    val description: String,
    val points: List<DatasetPoint>
)
