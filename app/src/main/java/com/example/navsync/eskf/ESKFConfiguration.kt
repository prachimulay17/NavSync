package com.example.navsync.eskf

/**
 * ESKF configuration parameters for NavSync.
 * 
 * Contains all tunable parameters for the Error-State Kalman Filter including:
 * - Process noise parameters (Q matrix elements)
 * - Measurement noise parameters (R matrix elements)  
 * - Initial uncertainty parameters (P0 matrix elements)
 * - Algorithm-specific settings
 * 
 * Default values are based on typical smartphone IMU characteristics and
 * automotive navigation requirements.
 */
data class ESKFConfiguration(
    
    // ═══════════════════════════════════════════════════════════════════
    // PROCESS NOISE PARAMETERS (Q matrix diagonal elements)
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Position process noise standard deviation (m/s^1.5).
     * Models uncertainty in position prediction due to unmodeled dynamics.
     */
    val positionProcessNoise: Double = 0.1,
    
    /**
     * Velocity process noise standard deviation (m/s^2).  
     * Models uncertainty in velocity prediction from accelerometer integration.
     */
    val velocityProcessNoise: Double = 0.5,
    
    /**
     * Attitude process noise standard deviation (rad/s^1.5).
     * Models uncertainty in attitude prediction from gyroscope integration.
     */
    val attitudeProcessNoise: Double = 0.01,
    
    /**
     * Accelerometer bias random walk standard deviation (m/s^2.5).
     * Models how accelerometer bias changes over time.
     */
    val accelerometerBiasProcessNoise: Double = 0.01,
    
    /**
     * Gyroscope bias random walk standard deviation (rad/s^1.5).
     * Models how gyroscope bias changes over time.
     */
    val gyroscopeBiasProcessNoise: Double = 0.001,
    
    // ═══════════════════════════════════════════════════════════════════
    // MEASUREMENT NOISE PARAMETERS (R matrix diagonal elements)
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Accelerometer measurement noise standard deviation (m/s²).
     * Models uncertainty in accelerometer readings.
     */
    val accelerometerMeasurementNoise: Double = 0.2,
    
    /**
     * Gyroscope measurement noise standard deviation (rad/s).
     * Models uncertainty in gyroscope readings.
     */  
    val gyroscopeMeasurementNoise: Double = 0.01,
    
    /**
     * GNSS position measurement noise standard deviation (m).
     * Used for future GNSS update implementation.
     */
    val gnssPositionMeasurementNoise: Double = 3.0,
    
    /**
     * GNSS velocity measurement noise standard deviation (m/s).
     * Used for future GNSS velocity update implementation.
     */
    val gnssVelocityMeasurementNoise: Double = 0.5,
    
    // ═══════════════════════════════════════════════════════════════════
    // INITIAL UNCERTAINTY PARAMETERS (P0 matrix diagonal elements)
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Initial position uncertainty standard deviation (m).
     * Uncertainty in initial position from GNSS.
     */
    val initialPositionUncertainty: Double = 5.0,
    
    /**
     * Initial velocity uncertainty standard deviation (m/s).
     * Uncertainty in initial velocity from GNSS speed/heading.
     */
    val initialVelocityUncertainty: Double = 2.0,
    
    /**
     * Initial attitude uncertainty standard deviation (rad).
     * Uncertainty in initial attitude from device orientation.
     */
    val initialAttitudeUncertainty: Double = 0.1,  // ~5.7 degrees
    
    /**
     * Initial accelerometer bias uncertainty standard deviation (m/s²).
     * Uncertainty in initial accelerometer bias estimate.
     */
    val initialAccelerometerBiasUncertainty: Double = 0.5,
    
    /**
     * Initial gyroscope bias uncertainty standard deviation (rad/s).
     * Uncertainty in initial gyroscope bias estimate.
     */
    val initialGyroscopeBiasUncertainty: Double = 0.05,  // ~2.9 degrees/s
    
    // ═══════════════════════════════════════════════════════════════════
    // ALGORITHM PARAMETERS
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Maximum time step for prediction (seconds).
     * If deltaTime exceeds this, prediction will be subdivided.
     */
    val maxPredictionTimeStep: Double = 0.2,  // 200ms
    
    /**
     * Minimum confidence threshold for filter validity.
     * Filter is considered invalid if trace(P) implies confidence below this.
     */
    val minimumConfidence: Double = 0.01,
    
    /**
     * Maximum confidence value for output.
     * Confidence is clamped to this upper bound.
     */
    val maximumConfidence: Double = 0.95,
    
    /**
     * Gravity magnitude for consistency checks (m/s²).
     * Used to validate accelerometer measurements.
     */
    val gravityMagnitude: Double = 9.80665,
    
    /**
     * Maximum gravity deviation tolerance (m/s²).
     * Accelerometer measurements with gravity magnitude outside
     * [gravityMagnitude ± gravityTolerance] may be rejected.
     */
    val gravityTolerance: Double = 2.0,
    
    /**
     * Earth rotation rate (rad/s).
     * Used for Coriolis effect compensation (typically negligible for automotive).
     */
    val earthRotationRate: Double = 7.292115e-5,
    
    /**
     * Enable/disable Coriolis effect compensation.
     * Usually disabled for automotive navigation due to negligible effect.
     */
    val enableCoriolisCompensation: Boolean = false,
    
    /**
     * Enable/disable Earth rotation compensation.
     * Usually disabled for automotive navigation due to negligible effect.
     */
    val enableEarthRotationCompensation: Boolean = false
) {
    
    /**
     * Validate configuration parameters for consistency.
     * @return List of validation errors, empty if valid
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        
        // Process noise parameters must be positive
        if (positionProcessNoise <= 0) errors.add("positionProcessNoise must be positive")
        if (velocityProcessNoise <= 0) errors.add("velocityProcessNoise must be positive")
        if (attitudeProcessNoise <= 0) errors.add("attitudeProcessNoise must be positive")
        if (accelerometerBiasProcessNoise <= 0) errors.add("accelerometerBiasProcessNoise must be positive")
        if (gyroscopeBiasProcessNoise <= 0) errors.add("gyroscopeBiasProcessNoise must be positive")
        
        // Measurement noise parameters must be positive
        if (accelerometerMeasurementNoise <= 0) errors.add("accelerometerMeasurementNoise must be positive")
        if (gyroscopeMeasurementNoise <= 0) errors.add("gyroscopeMeasurementNoise must be positive")
        if (gnssPositionMeasurementNoise <= 0) errors.add("gnssPositionMeasurementNoise must be positive")
        if (gnssVelocityMeasurementNoise <= 0) errors.add("gnssVelocityMeasurementNoise must be positive")
        
        // Initial uncertainty parameters must be positive
        if (initialPositionUncertainty <= 0) errors.add("initialPositionUncertainty must be positive")
        if (initialVelocityUncertainty <= 0) errors.add("initialVelocityUncertainty must be positive")
        if (initialAttitudeUncertainty <= 0) errors.add("initialAttitudeUncertainty must be positive")
        if (initialAccelerometerBiasUncertainty <= 0) errors.add("initialAccelerometerBiasUncertainty must be positive")
        if (initialGyroscopeBiasUncertainty <= 0) errors.add("initialGyroscopeBiasUncertainty must be positive")
        
        // Algorithm parameters must be reasonable
        if (maxPredictionTimeStep <= 0 || maxPredictionTimeStep > 1.0) {
            errors.add("maxPredictionTimeStep must be in (0, 1.0] seconds")
        }
        if (minimumConfidence < 0 || minimumConfidence > maximumConfidence) {
            errors.add("minimumConfidence must be in [0, maximumConfidence]")
        }
        if (maximumConfidence <= 0 || maximumConfidence > 1.0) {
            errors.add("maximumConfidence must be in (0, 1.0]")
        }
        if (gravityMagnitude <= 0 || gravityMagnitude > 15.0) {
            errors.add("gravityMagnitude must be reasonable (0, 15] m/s²")
        }
        if (gravityTolerance <= 0 || gravityTolerance > gravityMagnitude) {
            errors.add("gravityTolerance must be in (0, gravityMagnitude]")
        }
        
        return errors
    }
    
    /**
     * Check if configuration is valid (no validation errors).
     */
    fun isValid(): Boolean = validate().isEmpty()
    
    companion object {
        
        /**
         * Create conservative configuration with higher noise values.
         * Suitable for initial tuning or when sensor quality is uncertain.
         */
        fun conservative(): ESKFConfiguration = ESKFConfiguration(
            positionProcessNoise = 0.2,
            velocityProcessNoise = 1.0,
            attitudeProcessNoise = 0.02,
            accelerometerBiasProcessNoise = 0.02,
            gyroscopeBiasProcessNoise = 0.002,
            accelerometerMeasurementNoise = 0.5,
            gyroscopeMeasurementNoise = 0.02,
            initialPositionUncertainty = 10.0,
            initialVelocityUncertainty = 5.0,
            initialAttitudeUncertainty = 0.2,
            initialAccelerometerBiasUncertainty = 1.0,
            initialGyroscopeBiasUncertainty = 0.1
        )
        
        /**
         * Create aggressive configuration with lower noise values.
         * Suitable for high-quality sensors and well-calibrated systems.
         */
        fun aggressive(): ESKFConfiguration = ESKFConfiguration(
            positionProcessNoise = 0.05,
            velocityProcessNoise = 0.2,
            attitudeProcessNoise = 0.005,
            accelerometerBiasProcessNoise = 0.005,
            gyroscopeBiasProcessNoise = 0.0005,
            accelerometerMeasurementNoise = 0.1,
            gyroscopeMeasurementNoise = 0.005,
            initialPositionUncertainty = 2.0,
            initialVelocityUncertainty = 1.0,
            initialAttitudeUncertainty = 0.05,
            initialAccelerometerBiasUncertainty = 0.2,
            initialGyroscopeBiasUncertainty = 0.02
        )
        
        /**
         * Create automotive-optimized configuration.
         * Tuned for typical car dynamics and smartphone sensor characteristics.
         */
        fun automotive(): ESKFConfiguration = ESKFConfiguration(
            positionProcessNoise = 0.15,
            velocityProcessNoise = 0.8,
            attitudeProcessNoise = 0.008,
            accelerometerBiasProcessNoise = 0.015,
            gyroscopeBiasProcessNoise = 0.0008,
            accelerometerMeasurementNoise = 0.3,
            gyroscopeMeasurementNoise = 0.008,
            initialPositionUncertainty = 5.0,
            initialVelocityUncertainty = 2.0,
            initialAttitudeUncertainty = 0.1,
            initialAccelerometerBiasUncertainty = 0.5,
            initialGyroscopeBiasUncertainty = 0.05,
            maxPredictionTimeStep = 0.15,  // Slightly longer for automotive
            gravityTolerance = 3.0  // Account for vehicle accelerations
        )
        
        /**
         * Create configuration optimized for NavSync datasets.
         * Based on characteristics of the VW test vehicle data.
         */
        fun navSync(): ESKFConfiguration = ESKFConfiguration(
            // Process noise tuned for VW vehicle dynamics
            positionProcessNoise = 0.12,
            velocityProcessNoise = 0.6,
            attitudeProcessNoise = 0.01,
            accelerometerBiasProcessNoise = 0.01,
            gyroscopeBiasProcessNoise = 0.001,
            // Measurement noise based on smartphone sensor characteristics
            accelerometerMeasurementNoise = 0.25,
            gyroscopeMeasurementNoise = 0.01,
            // GNSS noise typical for automotive navigation
            gnssPositionMeasurementNoise = 3.0,
            gnssVelocityMeasurementNoise = 0.5,
            // Initial uncertainties for GNSS initialization
            initialPositionUncertainty = 5.0,
            initialVelocityUncertainty = 2.0,
            initialAttitudeUncertainty = 0.1,
            initialAccelerometerBiasUncertainty = 0.5,
            initialGyroscopeBiasUncertainty = 0.05,
            // NavSync-specific algorithm settings
            maxPredictionTimeStep = 0.12,  // Slightly larger than 100ms dataset rate
            minimumConfidence = 0.05,
            maximumConfidence = 0.95,
            gravityTolerance = 4.0,  // Allow for vehicle accelerations/decelerations
            enableCoriolisCompensation = false,  // Negligible for automotive navigation
            enableEarthRotationCompensation = false
        )
    }
}

/**
 * Noise model specification for different operational scenarios.
 * Allows dynamic adjustment of ESKF parameters based on driving conditions.
 */
enum class NoiseProfile {
    STATIONARY,    // Vehicle stopped - very low process noise
    URBAN_DRIVING, // City driving - moderate dynamics and accelerations  
    HIGHWAY,       // Highway driving - smooth dynamics, high speeds
    AGGRESSIVE,    // Aggressive driving - high accelerations and turns
    PARKING        // Parking maneuvers - very low speeds, tight turns
}

/**
 * ESKF tuning parameters for specific noise profiles.
 * Allows adaptation to different driving scenarios.
 */
data class AdaptiveNoiseParameters(
    val profile: NoiseProfile,
    val processNoiseScale: Double,      // Multiplier for process noise
    val measurementNoiseScale: Double,  // Multiplier for measurement noise
    val confidenceDecayRate: Double     // Rate of confidence decrease
) {
    companion object {
        
        /**
         * Get adaptive parameters for noise profile.
         */
        fun forProfile(profile: NoiseProfile): AdaptiveNoiseParameters {
            return when (profile) {
                NoiseProfile.STATIONARY -> AdaptiveNoiseParameters(
                    profile = profile,
                    processNoiseScale = 0.1,  // Very low dynamics
                    measurementNoiseScale = 1.0,
                    confidenceDecayRate = 0.999  // Slow confidence decay when stationary
                )
                NoiseProfile.URBAN_DRIVING -> AdaptiveNoiseParameters(
                    profile = profile,
                    processNoiseScale = 1.0,  // Baseline dynamics
                    measurementNoiseScale = 1.0,
                    confidenceDecayRate = 0.995
                )
                NoiseProfile.HIGHWAY -> AdaptiveNoiseParameters(
                    profile = profile,
                    processNoiseScale = 0.8,  // Smoother dynamics
                    measurementNoiseScale = 1.2,  // Higher speeds may affect sensors
                    confidenceDecayRate = 0.997  // Slower decay on highway
                )
                NoiseProfile.AGGRESSIVE -> AdaptiveNoiseParameters(
                    profile = profile,
                    processNoiseScale = 2.0,  // High dynamics
                    measurementNoiseScale = 1.5,  // More sensor stress
                    confidenceDecayRate = 0.990  // Faster confidence decay
                )
                NoiseProfile.PARKING -> AdaptiveNoiseParameters(
                    profile = profile,
                    processNoiseScale = 0.3,  // Low speed maneuvers
                    measurementNoiseScale = 0.8,  // Better sensor conditions
                    confidenceDecayRate = 0.998  // Slow decay for parking
                )
            }
        }
    }
}