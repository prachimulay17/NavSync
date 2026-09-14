package com.example.navsync.eskf

import kotlin.math.*

/**
 * ESKF (Error-State Kalman Filter) state representations for NavSync.
 * 
 * Uses 15-dimensional error state: δx = [δp, δv, δθ, δba, δbg]
 * - δp: Position error (3D, NED frame)
 * - δv: Velocity error (3D, NED frame)  
 * - δθ: Attitude error (3D, small angles)
 * - δba: Accelerometer bias error (3D, body frame)
 * - δbg: Gyroscope bias error (3D, body frame)
 * 
 * Coordinate frame conventions match existing NavSync architecture:
 * - Navigation frame: North-East-Down (NED)
 * - Body frame: Smartphone coordinate system
 * - Output: WGS84 geodetic coordinates (lat/lon)
 */

/**
 * Nominal state representation for ESKF.
 * Contains the current best estimate of all navigation parameters.
 * 
 * Coordinate frames:
 * - Position: NED frame (meters relative to initial reference point)
 * - Velocity: NED frame (m/s)
 * - Attitude: Quaternion from body frame to NED frame
 * - Biases: Body frame (sensor frame)
 */
data class NominalState(
    // Position in NED frame (meters from reference origin)
    val positionNorth: Double,
    val positionEast: Double, 
    val positionDown: Double,
    
    // Velocity in NED frame (m/s)
    val velocityNorth: Double,
    val velocityEast: Double,
    val velocityDown: Double,
    
    // Attitude quaternion (body to NED frame transformation)
    // q = [qw, qx, qy, qz] where qw is scalar part
    val quaternionW: Double,
    val quaternionX: Double,
    val quaternionY: Double,
    val quaternionZ: Double,
    
    // Accelerometer bias in body frame (m/s²)
    val accelBiasX: Double,
    val accelBiasY: Double,
    val accelBiasZ: Double,
    
    // Gyroscope bias in body frame (rad/s)
    val gyroBiasX: Double,
    val gyroBiasY: Double,
    val gyroBiasZ: Double,
    
    // Reference position for geodetic coordinate conversion
    val referenceLatitude: Double,  // degrees, WGS84
    val referenceLongitude: Double  // degrees, WGS84
) {
    
    /**
     * Convert NED position to WGS84 geodetic coordinates.
     * Uses local tangent plane approximation around reference point.
     */
    fun toGeodeticPosition(): GeodeticPosition {
        // Convert NED position to lat/lon using Mercator approximation
        val earthRadius = 6378137.0  // WGS84 equatorial radius (m)
        
        // Convert North displacement to latitude change
        val deltaLatRad = positionNorth / earthRadius
        val latitude = referenceLatitude + Math.toDegrees(deltaLatRad)
        
        // Convert East displacement to longitude change (accounting for latitude)
        val cosLat = cos(Math.toRadians(referenceLatitude))
        val deltaLonRad = positionEast / (earthRadius * cosLat)
        val longitude = referenceLongitude + Math.toDegrees(deltaLonRad)
        
        return GeodeticPosition(
            latitude = latitude,
            longitude = longitude,
            altitude = -positionDown  // NED Down is negative altitude
        )
    }
    
    /**
     * Convert NED velocity to ground speed and heading.
     */
    fun toSpeedAndHeading(): SpeedAndHeading {
        val groundSpeed = sqrt(velocityNorth * velocityNorth + velocityEast * velocityEast)
        val speedKmh = groundSpeed * 3.6  // m/s to km/h
        
        // Heading: 0° = North, clockwise positive
        val headingRad = atan2(velocityEast, velocityNorth)
        var headingDeg = Math.toDegrees(headingRad)
        if (headingDeg < 0) headingDeg += 360.0  // Normalize to [0, 360)
        
        return SpeedAndHeading(
            speedKmh = speedKmh,
            headingDegrees = headingDeg
        )
    }
    
    /**
     * Get roll, pitch, yaw angles from quaternion (degrees).
     * ZYX Euler angle convention (yaw-pitch-roll).
     */
    fun toEulerAngles(): EulerAngles {
        val q = Quaternion(quaternionW, quaternionX, quaternionY, quaternionZ).normalized()
        
        // Convert to Euler angles (ZYX convention)
        val sinRoll = 2.0 * (q.w * q.x + q.y * q.z)
        val cosRoll = 1.0 - 2.0 * (q.x * q.x + q.y * q.y)
        val roll = atan2(sinRoll, cosRoll)
        
        val sinPitch = 2.0 * (q.w * q.y - q.z * q.x)
        val pitch = asin(sinPitch.coerceIn(-1.0, 1.0))
        
        val sinYaw = 2.0 * (q.w * q.z + q.x * q.y)
        val cosYaw = 1.0 - 2.0 * (q.y * q.y + q.z * q.z)
        val yaw = atan2(sinYaw, cosYaw)
        
        return EulerAngles(
            rollDegrees = Math.toDegrees(roll),
            pitchDegrees = Math.toDegrees(pitch),
            yawDegrees = Math.toDegrees(yaw)
        )
    }
    
    /**
     * Validate state consistency.
     */
    fun isValid(): Boolean {
        val q = Quaternion(quaternionW, quaternionX, quaternionY, quaternionZ)
        return q.magnitude() > 0.1 && // Quaternion should be non-zero
                positionNorth.isFinite() && positionEast.isFinite() && positionDown.isFinite() &&
                velocityNorth.isFinite() && velocityEast.isFinite() && velocityDown.isFinite() &&
                accelBiasX.isFinite() && accelBiasY.isFinite() && accelBiasZ.isFinite() &&
                gyroBiasX.isFinite() && gyroBiasY.isFinite() && gyroBiasZ.isFinite()
    }
    
    companion object {
        /**
         * Create initial nominal state from NavigationState and device orientation.
         */
        fun fromNavigationState(
            navState: com.example.navsync.model.NavigationState,
            deviceOrientation: DeviceOrientation = DeviceOrientation.flat()
        ): NominalState {
            // Convert speed/heading to NED velocity
            val speedMps = navState.speedKmh / 3.6
            val headingRad = Math.toRadians(navState.headingDegrees)
            val velNorth = speedMps * cos(headingRad)
            val velEast = speedMps * sin(headingRad)
            
            // Create quaternion from device orientation
            val quaternion = deviceOrientation.toQuaternion()
            
            return NominalState(
                positionNorth = 0.0,  // Start at reference origin
                positionEast = 0.0,
                positionDown = 0.0,
                velocityNorth = velNorth,
                velocityEast = velEast, 
                velocityDown = 0.0,    // Assume 2D navigation
                quaternionW = quaternion.w,
                quaternionX = quaternion.x,
                quaternionY = quaternion.y,
                quaternionZ = quaternion.z,
                accelBiasX = 0.0,      // Initialize biases to zero
                accelBiasY = 0.0,
                accelBiasZ = 0.0,
                gyroBiasX = 0.0,
                gyroBiasY = 0.0,
                gyroBiasZ = 0.0,
                referenceLatitude = navState.latitude,
                referenceLongitude = navState.longitude
            )
        }
    }
}

/**
 * Error state vector for ESKF (15 dimensions).
 * Small-angle approximation for attitude errors.
 */
data class ErrorState(
    // Position errors in NED frame (m)
    val deltaPositionNorth: Double,
    val deltaPositionEast: Double,
    val deltaPositionDown: Double,
    
    // Velocity errors in NED frame (m/s)
    val deltaVelocityNorth: Double,
    val deltaVelocityEast: Double,
    val deltaVelocityDown: Double,
    
    // Attitude errors as small angles (rad)
    val deltaAttitudeX: Double,  // Roll error
    val deltaAttitudeY: Double,  // Pitch error  
    val deltaAttitudeZ: Double,  // Yaw error
    
    // Accelerometer bias errors in body frame (m/s²)
    val deltaAccelBiasX: Double,
    val deltaAccelBiasY: Double,
    val deltaAccelBiasZ: Double,
    
    // Gyroscope bias errors in body frame (rad/s)
    val deltaGyroBiasX: Double,
    val deltaGyroBiasY: Double,
    val deltaGyroBiasZ: Double
) {
    
    /**
     * Convert to array for matrix operations.
     */
    fun toArray(): DoubleArray = doubleArrayOf(
        deltaPositionNorth, deltaPositionEast, deltaPositionDown,
        deltaVelocityNorth, deltaVelocityEast, deltaVelocityDown,
        deltaAttitudeX, deltaAttitudeY, deltaAttitudeZ,
        deltaAccelBiasX, deltaAccelBiasY, deltaAccelBiasZ,
        deltaGyroBiasX, deltaGyroBiasY, deltaGyroBiasZ
    )
    
    companion object {
        const val DIMENSION = 15
        
        /**
         * Create error state from array.
         */
        fun fromArray(array: DoubleArray): ErrorState {
            require(array.size == DIMENSION) { "Error state array must have $DIMENSION elements" }
            return ErrorState(
                deltaPositionNorth = array[0], deltaPositionEast = array[1], deltaPositionDown = array[2],
                deltaVelocityNorth = array[3], deltaVelocityEast = array[4], deltaVelocityDown = array[5],
                deltaAttitudeX = array[6], deltaAttitudeY = array[7], deltaAttitudeZ = array[8],
                deltaAccelBiasX = array[9], deltaAccelBiasY = array[10], deltaAccelBiasZ = array[11],
                deltaGyroBiasX = array[12], deltaGyroBiasY = array[13], deltaGyroBiasZ = array[14]
            )
        }
        
        /**
         * Zero error state.
         */
        fun zero(): ErrorState = ErrorState(
            0.0, 0.0, 0.0,  // Position errors
            0.0, 0.0, 0.0,  // Velocity errors
            0.0, 0.0, 0.0,  // Attitude errors
            0.0, 0.0, 0.0,  // Accel bias errors
            0.0, 0.0, 0.0   // Gyro bias errors
        )
    }
}

/**
 * IMU measurement in body frame with proper units and timestamp.
 * Matches existing SensorData structure from NavSync.
 */
data class ImuMeasurement(
    val timestampMs: Long,
    
    // Accelerometer measurement in body frame (m/s²)
    // Already gravity-compensated in NavSync data pipeline
    val accelerationX: Double,
    val accelerationY: Double, 
    val accelerationZ: Double,
    
    // Gyroscope measurement in body frame (rad/s)
    val angularVelocityX: Double,  // Roll rate
    val angularVelocityY: Double,  // Pitch rate
    val angularVelocityZ: Double   // Yaw rate
) {
    companion object {
        /**
         * Create IMU measurement from NavSync SensorData.
         * Applies gravity compensation and coordinate frame mapping.
         */
        fun fromSensorData(sensorData: com.example.navsync.data.SensorData): ImuMeasurement {
            return ImuMeasurement(
                timestampMs = sensorData.timestampMs,
                // Gravity-compensated acceleration
                accelerationX = sensorData.accelerationX - sensorData.gravityX,
                accelerationY = sensorData.accelerationY - sensorData.gravityY,
                accelerationZ = sensorData.accelerationZ - sensorData.gravityZ,
                // Gyroscope rates (convert from Yaw/Pitch/Roll to X/Y/Z)
                angularVelocityX = sensorData.gyroRoll,   // Roll rate -> X-axis rotation
                angularVelocityY = sensorData.gyroPitch,  // Pitch rate -> Y-axis rotation
                angularVelocityZ = sensorData.gyroYaw     // Yaw rate -> Z-axis rotation
            )
        }
    }
}

/**
 * Supporting data classes for coordinate conversions.
 */
data class GeodeticPosition(
    val latitude: Double,   // degrees, WGS84
    val longitude: Double,  // degrees, WGS84
    val altitude: Double    // meters above WGS84 ellipsoid
)

data class SpeedAndHeading(
    val speedKmh: Double,      // km/h
    val headingDegrees: Double // degrees, 0=North, clockwise positive
)

data class EulerAngles(
    val rollDegrees: Double,   // degrees
    val pitchDegrees: Double,  // degrees  
    val yawDegrees: Double     // degrees, 0=North, clockwise positive
)

data class DeviceOrientation(
    val yawDegrees: Double,    // degrees
    val pitchDegrees: Double,  // degrees
    val rollDegrees: Double    // degrees
) {
    /**
     * Convert device orientation to quaternion.
     * Uses ZYX Euler angle convention.
     */
    fun toQuaternion(): Quaternion {
        val yaw = Math.toRadians(yawDegrees)
        val pitch = Math.toRadians(pitchDegrees)
        val roll = Math.toRadians(rollDegrees)
        
        val cy = cos(yaw * 0.5)
        val sy = sin(yaw * 0.5)
        val cp = cos(pitch * 0.5)
        val sp = sin(pitch * 0.5)
        val cr = cos(roll * 0.5)
        val sr = sin(roll * 0.5)
        
        return Quaternion(
            w = cr * cp * cy + sr * sp * sy,
            x = sr * cp * cy - cr * sp * sy,
            y = cr * sp * cy + sr * cp * sy,
            z = cr * cp * sy - sr * sp * cy
        )
    }
    
    companion object {
        /**
         * Create flat device orientation (horizontal, facing North).
         */
        fun flat(): DeviceOrientation = DeviceOrientation(0.0, 0.0, 0.0)
        
        /**
         * Create from NavSync sensor orientation.
         */
        fun fromSensorData(sensorData: com.example.navsync.data.SensorData): DeviceOrientation {
            return DeviceOrientation(
                yawDegrees = sensorData.orientationYaw,
                pitchDegrees = sensorData.orientationPitch,
                rollDegrees = sensorData.orientationRoll
            )
        }
    }
}