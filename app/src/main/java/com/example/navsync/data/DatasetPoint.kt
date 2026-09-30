package com.example.navsync.data

/**
 * Sensor data available to the inference engine.
 * Contains smartphone IMU sensors available during GNSS outage.
 */
data class SensorData(
    val timestampMs: Long,
    // Raw accelerometer (m/s²) - includes gravity + motion
    val accelerationX: Double,
    val accelerationY: Double,
    val accelerationZ: Double,
    // Gravity vector (m/s²) - for gravity compensation
    val gravityX: Double,
    val gravityY: Double,
    val gravityZ: Double,
    // Gyroscope (rad/s)
    val gyroYaw: Double,    // Yaw rate (Z-axis rotation)
    val gyroPitch: Double,  // Pitch rate (Y-axis rotation) 
    val gyroRoll: Double,   // Roll rate (X-axis rotation)
    // Magnetometer (μT)
    val magneticX: Double,
    val magneticY: Double,
    val magneticZ: Double,
    // Device orientation (degrees) - for frame transformation
    val orientationYaw: Double,   // Azimuth/heading
    val orientationPitch: Double, // Pitch
    val orientationRoll: Double,  // Roll
    // Game Rotation Vector (quaternion components, no magnetic declination)
    val gameRotationVector: FloatArray? = null  // [x, y, z, w] or [x, y, z] format
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
