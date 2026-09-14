package com.example.navsync.eskf

import com.example.navsync.data.SensorData
import com.example.navsync.inference.InferenceResult
import com.example.navsync.inference.NavSyncInference
import com.example.navsync.model.NavigationState
import kotlin.math.*

/**
 * Error-State Kalman Filter (ESKF) interface for NavSync integration.
 * 
 * This interface provides the main ESKF functionality while integrating cleanly
 * with the existing NavSync architecture. The ESKF maintains:
 * 
 * - 15-dimensional error state: δx = [δp, δv, δθ, δba, δbg]
 * - Nominal state propagation using IMU measurements
 * - Error covariance propagation and updates
 * - Coordinate frame transformations (body ↔ NED ↔ geodetic)
 * 
 * The interface is designed to be modular and extensible for future
 * ML velocity measurements or other sensor integrations.
 */
interface ESKF {
    
    /**
     * Initialize ESKF with initial navigation state and configuration.
     * 
     * @param initialState Initial navigation state from GNSS
     * @param configuration ESKF tuning parameters
     * @param deviceOrientation Initial device orientation for attitude initialization
     */
    fun initialize(
        initialState: NavigationState,
        configuration: ESKFConfiguration,
        deviceOrientation: DeviceOrientation? = null
    )
    
    /**
     * Predict navigation state forward using IMU measurements.
     * 
     * Performs:
     * 1. Nominal state propagation using IMU data
     * 2. Error covariance propagation (linearized dynamics)
     * 3. Process noise injection
     * 
     * @param imuMeasurement IMU data in body frame
     * @param deltaTimeSeconds Time step for prediction
     * @return Predicted navigation state with uncertainty
     */
    fun predict(imuMeasurement: ImuMeasurement, deltaTimeSeconds: Double): ESKFResult
    
    /**
     * Update ESKF with GNSS position measurement (for future use).
     * 
     * @param gnssLatitude GNSS latitude (degrees, WGS84)
     * @param gnssLongitude GNSS longitude (degrees, WGS84) 
     * @param gnssUncertainty Position measurement uncertainty (m, 1-sigma)
     * @return Updated navigation state after GNSS correction
     */
    fun updateWithGNSSPosition(
        gnssLatitude: Double,
        gnssLongitude: Double, 
        gnssUncertainty: Double
    ): ESKFResult
    
    /**
     * Update ESKF with velocity measurement (future ML integration point).
     * 
     * This provides an integration point for ML-derived velocity estimates
     * that can be fused with IMU-based predictions.
     * 
     * @param velocityNorth Velocity in North direction (m/s)
     * @param velocityEast Velocity in East direction (m/s)
     * @param velocityUncertainty Velocity measurement uncertainty (m/s, 1-sigma)
     * @return Updated navigation state after velocity correction
     */
    fun updateWithVelocity(
        velocityNorth: Double,
        velocityEast: Double,
        velocityUncertainty: Double
    ): ESKFResult
    
    /**
     * Update ESKF with heading measurement (future magnetometer integration).
     * 
     * @param headingDegrees Heading measurement (degrees, 0=North, clockwise)
     * @param headingUncertainty Heading measurement uncertainty (degrees, 1-sigma)
     * @return Updated navigation state after heading correction
     */
    fun updateWithHeading(
        headingDegrees: Double,
        headingUncertainty: Double
    ): ESKFResult
    
    /**
     * Get current navigation state estimate.
     * 
     * @return Current best estimate with uncertainty
     */
    fun getCurrentState(): ESKFResult
    
    /**
     * Get current nominal state (for debugging/analysis).
     */
    fun getNominalState(): NominalState
    
    /**
     * Get current error state (for debugging/analysis).
     */
    fun getErrorState(): ErrorState
    
    /**
     * Get current error covariance matrix diagonal (for confidence estimation).
     * 
     * @return 15-element array with diagonal elements of covariance matrix
     */
    fun getCovarianceDiagonal(): DoubleArray
    
    /**
     * Get full covariance matrix for advanced analysis and testing.
     * 
     * @return 15×15 covariance matrix (copy, not reference)
     */
    fun getCovarianceMatrix(): Array<DoubleArray>
    
    /**
     * Reset ESKF to uninitialized state.
     */
    fun reset()
    
    /**
     * Check if ESKF is properly initialized.
     */
    fun isInitialized(): Boolean
    
    /**
     * Update ESKF configuration (allows runtime tuning).
     * 
     * @param newConfiguration Updated configuration parameters
     */
    fun updateConfiguration(newConfiguration: ESKFConfiguration)
    
    /**
     * Enable/disable adaptive noise based on motion profile.
     * 
     * @param enabled Whether to enable adaptive noise
     * @param profile Motion profile for noise adaptation
     */
    fun setAdaptiveNoise(enabled: Boolean, profile: NoiseProfile = NoiseProfile.URBAN_DRIVING)
}

/**
 * ESKF result containing navigation estimate and uncertainty information.
 */
data class ESKFResult(
    // Navigation state estimate
    val latitude: Double,        // degrees, WGS84
    val longitude: Double,       // degrees, WGS84
    val altitude: Double,        // meters above WGS84 ellipsoid
    val velocityNorth: Double,   // m/s
    val velocityEast: Double,    // m/s 
    val velocityDown: Double,    // m/s
    val rollDegrees: Double,     // degrees
    val pitchDegrees: Double,    // degrees
    val yawDegrees: Double,      // degrees, 0=North, clockwise
    
    // Uncertainty estimates (1-sigma)
    val positionUncertainty: Double,  // m
    val velocityUncertainty: Double,  // m/s
    val attitudeUncertainty: Double,  // degrees
    
    // Sensor bias estimates
    val accelBiasX: Double,      // m/s²
    val accelBiasY: Double,      // m/s²
    val accelBiasZ: Double,      // m/s²
    val gyroBiasX: Double,       // rad/s
    val gyroBiasY: Double,       // rad/s
    val gyroBiasZ: Double,       // rad/s
    
    // Quality indicators
    val confidence: Double,      // 0.0 to 1.0
    val isValid: Boolean,        // Whether estimate is valid
    val timestampMs: Long        // Timestamp of this estimate
) {
    
    /**
     * Convert ESKF result to NavSync InferenceResult.
     * This provides compatibility with the existing NavSync interface.
     */
    fun toInferenceResult(): InferenceResult {
        val speedMps = kotlin.math.sqrt(velocityNorth * velocityNorth + velocityEast * velocityEast)
        val speedKmh = speedMps * 3.6
        
        return InferenceResult(
            latitude = latitude,
            longitude = longitude,
            speedKmh = speedKmh,
            headingDegrees = yawDegrees,
            confidence = confidence
        )
    }
    
    /**
     * Convert ESKF result to NavSync NavigationState.
     */
    fun toNavigationState(): NavigationState {
        val speedMps = kotlin.math.sqrt(velocityNorth * velocityNorth + velocityEast * velocityEast)
        val speedKmh = speedMps * 3.6
        
        return NavigationState(
            latitude = latitude,
            longitude = longitude,
            speedKmh = speedKmh,
            headingDegrees = yawDegrees,
            confidence = confidence,
            gnssAvailable = false,  // ESKF estimates are not GNSS
            source = com.example.navsync.model.NavigationSource.AI_ESTIMATION
        )
    }
    
    companion object {
        
        /**
         * Create ESKF instance with default configuration.
         */
        fun create(): ESKF {
            return ESKFImpl(ESKFConfiguration.navSync())
        }
        
        /**
         * Create ESKF instance with custom configuration.
         */
        fun create(configuration: ESKFConfiguration): ESKF {
            return ESKFImpl(configuration)
        }
        
        /**
         * Create invalid/error result.
         */
        fun invalid(timestampMs: Long = 0L): ESKFResult {
            return ESKFResult(
                latitude = 0.0, longitude = 0.0, altitude = 0.0,
                velocityNorth = 0.0, velocityEast = 0.0, velocityDown = 0.0,
                rollDegrees = 0.0, pitchDegrees = 0.0, yawDegrees = 0.0,
                positionUncertainty = Double.MAX_VALUE,
                velocityUncertainty = Double.MAX_VALUE,
                attitudeUncertainty = Double.MAX_VALUE,
                accelBiasX = 0.0, accelBiasY = 0.0, accelBiasZ = 0.0,
                gyroBiasX = 0.0, gyroBiasY = 0.0, gyroBiasZ = 0.0,
                confidence = 0.0,
                isValid = false,
                timestampMs = timestampMs
            )
        }
    }
}

/**
 * ESKF implementation that integrates with NavSync via the NavSyncInference interface.
 * 
 * This class provides the bridge between the ESKF interface and the existing
 * NavSync architecture, handling coordinate conversions and data format translations.
 */
class ESKFNavSyncInference(
    private val configuration: ESKFConfiguration = ESKFConfiguration.navSync(),
    private val eskf: ESKF = ESKFImpl(configuration)  // Default implementation
) : NavSyncInference {
    
    private var initialized = false
    private var lastTimestampMs = 0L
    
    override fun initialize(lastState: NavigationState) {
        try {
            // Extract device orientation from the lastState or use default
            val deviceOrientation = DeviceOrientation.flat()  // Could be enhanced to use actual orientation
            
            // Initialize ESKF with NavigationState
            eskf.initialize(lastState, configuration, deviceOrientation)
            initialized = true
            lastTimestampMs = 0L
            
            android.util.Log.d("ESKFNavSync", "Initialized ESKF at lat=${lastState.latitude}, lon=${lastState.longitude}, speed=${lastState.speedKmh} km/h")
            
        } catch (e: Exception) {
            android.util.Log.e("ESKFNavSync", "Failed to initialize ESKF: ${e.message}", e)
            initialized = false
        }
    }
    
    override fun estimatePosition(
        sensorData: SensorData,
        previousState: NavigationState,
        deltaTimeMs: Long
    ): InferenceResult {
        if (!initialized) {
            android.util.Log.w("ESKFNavSync", "ESKF not initialized, returning previous state")
            return InferenceResult(
                latitude = previousState.latitude,
                longitude = previousState.longitude,
                speedKmh = previousState.speedKmh,
                headingDegrees = previousState.headingDegrees,
                confidence = 0.1
            )
        }
        
        try {
            // Convert SensorData to ImuMeasurement
            val imuMeasurement = ImuMeasurement.fromSensorData(sensorData)
            
            // Convert deltaTime to seconds
            val deltaTimeSeconds = deltaTimeMs / 1000.0
            
            // Skip if time delta is invalid
            if (deltaTimeSeconds <= 0 || deltaTimeSeconds > 1.0) {
                android.util.Log.w("ESKFNavSync", "Invalid delta time: ${deltaTimeSeconds}s, skipping update")
                return eskf.getCurrentState().toInferenceResult()
            }
            
            // Perform ESKF prediction
            val result = eskf.predict(imuMeasurement, deltaTimeSeconds)
            
            // Update timestamp
            lastTimestampMs = sensorData.timestampMs
            
            // Log periodic state for debugging
            if ((sensorData.timestampMs / 1000) % 5 == 0L) {
                android.util.Log.d("ESKFNavSync", "ESKF t=${sensorData.timestampMs/1000}s: pos=(${result.latitude.format(6)},${result.longitude.format(6)}), speed=${"%.1f".format(result.velocityNorth * result.velocityNorth + result.velocityEast * result.velocityEast)} m/s, conf=${"%.3f".format(result.confidence)}")
            }
            
            return result.toInferenceResult()
            
        } catch (e: Exception) {
            android.util.Log.e("ESKFNavSync", "ESKF prediction failed: ${e.message}", e)
            // Return degraded estimate based on previous state
            return InferenceResult(
                latitude = previousState.latitude,
                longitude = previousState.longitude,
                speedKmh = previousState.speedKmh * 0.98,  // Slight decay
                headingDegrees = previousState.headingDegrees,
                confidence = (previousState.confidence * 0.9).coerceAtLeast(0.05)
            )
        }
    }
    
    override fun reset() {
        eskf.reset()
        initialized = false
        lastTimestampMs = 0L
        android.util.Log.d("ESKFNavSync", "ESKF reset")
    }
    
    /**
     * Get access to underlying ESKF for advanced operations.
     */
    fun getESKF(): ESKF = eskf
    
    /**
     * Enable GNSS updates when available (future use).
     */
    fun enableGnssUpdates(enabled: Boolean) {
        // Future implementation for GNSS corrections
    }
    
    /**
     * Update ESKF configuration at runtime.
     */
    fun updateConfiguration(newConfiguration: ESKFConfiguration) {
        eskf.updateConfiguration(newConfiguration)
    }
    
    /**
     * Format double for logging.
     */
    private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)
}

/**
 * ESKF implementation with nominal-state IMU prediction and error-state covariance propagation.
 * Phase 3: Implements IMU-based prediction WITH covariance propagation for uncertainty estimation.
 */
internal class ESKFImpl(private var configuration: ESKFConfiguration) : ESKF {
    
    private var nominalState: NominalState? = null
    private var errorState: ErrorState = ErrorState.zero()
    private var covarianceMatrix: Array<DoubleArray> = Array(15) { DoubleArray(15) }
    private var initialized = false
    
    // Constants for integration
    private companion object {
        const val GRAVITY_MAGNITUDE = 9.80665  // m/s² (standard gravity)
        const val MIN_QUATERNION_NORM = 1e-6   // Minimum quaternion norm for stability
        const val MAX_DELTA_TIME = 0.5         // Maximum delta time (seconds) for stability
    }
    
    override fun initialize(
        initialState: NavigationState,
        configuration: ESKFConfiguration,
        deviceOrientation: DeviceOrientation?
    ) {
        this.configuration = configuration
        val orientation = deviceOrientation ?: DeviceOrientation.fromSensorData(
            // Create dummy SensorData for orientation extraction - in real use would get from actual sensor data
            com.example.navsync.data.SensorData(
                timestampMs = 0L,
                accelerationX = 0.0, accelerationY = 0.0, accelerationZ = 0.0,
                gravityX = 0.0, gravityY = 0.0, gravityZ = GRAVITY_MAGNITUDE,
                gyroYaw = 0.0, gyroPitch = 0.0, gyroRoll = 0.0,
                magneticX = 0.0, magneticY = 0.0, magneticZ = 0.0,
                orientationYaw = initialState.headingDegrees,
                orientationPitch = 0.0, orientationRoll = 0.0
            )
        )
        
        nominalState = NominalState.fromNavigationState(initialState, orientation)
        errorState = ErrorState.zero()
        
        // Initialize covariance matrix
        initializeCovarianceMatrix(configuration)
        
        initialized = true
    }
    
    override fun predict(imuMeasurement: ImuMeasurement, deltaTimeSeconds: Double): ESKFResult {
        if (!initialized) throw IllegalStateException("ESKF not initialized")
        
        val nominal = nominalState ?: throw IllegalStateException("Nominal state is null")
        
        // Validate delta time
        val dt = deltaTimeSeconds.coerceIn(0.0, MAX_DELTA_TIME)
        if (dt <= 0.0) {
            return createResult(nominal, imuMeasurement.timestampMs)
        }
        
        try {
            // Perform nominal state prediction
            val predictedNominal = predictNominalState(nominal, imuMeasurement, dt)
            
            // Perform error-state covariance propagation
            propagateCovariance(nominal, imuMeasurement, dt)
            
            // Update stored state
            nominalState = predictedNominal
            
            return createResult(predictedNominal, imuMeasurement.timestampMs)
            
        } catch (e: Exception) {
            // Return current state on error
            return createResult(nominal, imuMeasurement.timestampMs)
        }
    }
    
    /**
     * Predict nominal state forward using IMU measurements.
     * Implements the full IMU prediction model with proper coordinate transformations.
     */
    private fun predictNominalState(
        currentState: NominalState,
        imu: ImuMeasurement,
        dt: Double
    ): NominalState {
        
        // 1. BIAS-CORRECTED IMU MEASUREMENTS
        // Remove estimated biases from IMU measurements
        val correctedAccelX = imu.accelerationX - currentState.accelBiasX
        val correctedAccelY = imu.accelerationY - currentState.accelBiasY
        val correctedAccelZ = imu.accelerationZ - currentState.accelBiasZ
        
        val correctedGyroX = imu.angularVelocityX - currentState.gyroBiasX
        val correctedGyroY = imu.angularVelocityY - currentState.gyroBiasY
        val correctedGyroZ = imu.angularVelocityZ - currentState.gyroBiasZ
        
        // 2. QUATERNION PROPAGATION
        // Propagate attitude using bias-corrected gyroscope measurements
        val currentQuaternion = Quaternion(
            currentState.quaternionW, currentState.quaternionX,
            currentState.quaternionY, currentState.quaternionZ
        ).normalized()
        
        val newQuaternion = propagateQuaternion(
            currentQuaternion, correctedGyroX, correctedGyroY, correctedGyroZ, dt
        )
        
        // 3. ACCELERATION TRANSFORMATION (Body → NED)
        // Transform bias-corrected accelerations from body frame to NED frame
        val accelNED = transformAccelerationToNED(
            newQuaternion, correctedAccelX, correctedAccelY, correctedAccelZ
        )
        
        // 4. GRAVITY COMPENSATION  
        // Remove gravity from NED acceleration (gravity is positive Down in NED)
        val accelNorthNoGrav = accelNED.first
        val accelEastNoGrav = accelNED.second
        val accelDownNoGrav = accelNED.third - GRAVITY_MAGNITUDE  // Remove +Down gravity
        
        // 5. VELOCITY PROPAGATION
        // Integrate acceleration to update velocity
        val newVelNorth = currentState.velocityNorth + accelNorthNoGrav * dt
        val newVelEast = currentState.velocityEast + accelEastNoGrav * dt
        val newVelDown = currentState.velocityDown + accelDownNoGrav * dt
        
        // 6. POSITION PROPAGATION
        // Integrate velocity to update position (using trapezoidal rule for better accuracy)
        val avgVelNorth = (currentState.velocityNorth + newVelNorth) * 0.5
        val avgVelEast = (currentState.velocityEast + newVelEast) * 0.5
        val avgVelDown = (currentState.velocityDown + newVelDown) * 0.5
        
        val newPosNorth = currentState.positionNorth + avgVelNorth * dt
        val newPosEast = currentState.positionEast + avgVelEast * dt
        val newPosDown = currentState.positionDown + avgVelDown * dt
        
        // 7. BIAS PROPAGATION (random walk - no change in nominal prediction)
        // Biases remain unchanged in nominal state prediction
        
        return NominalState(
            positionNorth = newPosNorth,
            positionEast = newPosEast,
            positionDown = newPosDown,
            velocityNorth = newVelNorth,
            velocityEast = newVelEast,
            velocityDown = newVelDown,
            quaternionW = newQuaternion.w,
            quaternionX = newQuaternion.x,
            quaternionY = newQuaternion.y,
            quaternionZ = newQuaternion.z,
            accelBiasX = currentState.accelBiasX,  // Unchanged in prediction
            accelBiasY = currentState.accelBiasY,
            accelBiasZ = currentState.accelBiasZ,
            gyroBiasX = currentState.gyroBiasX,    // Unchanged in prediction
            gyroBiasY = currentState.gyroBiasY,
            gyroBiasZ = currentState.gyroBiasZ,
            referenceLatitude = currentState.referenceLatitude,
            referenceLongitude = currentState.referenceLongitude
        )
    }
    
    /**
     * Propagate quaternion using gyroscope measurements.
     * Uses first-order integration with proper normalization.
     */
    private fun propagateQuaternion(
        currentQ: Quaternion,
        gyroX: Double, gyroY: Double, gyroZ: Double,
        dt: Double
    ): Quaternion {
        // Compute angular velocity magnitude
        val omegaMag = kotlin.math.sqrt(gyroX * gyroX + gyroY * gyroY + gyroZ * gyroZ)
        
        if (omegaMag < 1e-8) {
            // No rotation - return current quaternion
            return currentQ.normalized()
        }
        
        // Rotation axis (normalized)
        val axisX = gyroX / omegaMag
        val axisY = gyroY / omegaMag
        val axisZ = gyroZ / omegaMag
        
        // Rotation angle
        val angle = omegaMag * dt
        val halfAngle = angle * 0.5
        val sinHalfAngle = kotlin.math.sin(halfAngle)
        val cosHalfAngle = kotlin.math.cos(halfAngle)
        
        // Rotation quaternion
        val rotQ = Quaternion(
            w = cosHalfAngle,
            x = axisX * sinHalfAngle,
            y = axisY * sinHalfAngle,
            z = axisZ * sinHalfAngle
        )
        
        // Apply rotation: q_new = q_current * q_rotation
        val result = currentQ * rotQ
        return result.normalized()
    }
    
    /**
     * Transform acceleration from body frame to NED frame using quaternion.
     * Returns (North, East, Down) acceleration components.
     */
    private fun transformAccelerationToNED(
        quaternion: Quaternion,
        accelX: Double, accelY: Double, accelZ: Double
    ): Triple<Double, Double, Double> {
        val q = quaternion.normalized()
        
        // Rotation matrix elements (body to NED)
        val q0 = q.w; val q1 = q.x; val q2 = q.y; val q3 = q.z
        
        // First row of rotation matrix (North direction)
        val r11 = 1 - 2 * (q2*q2 + q3*q3)
        val r12 = 2 * (q1*q2 - q0*q3)
        val r13 = 2 * (q1*q3 + q0*q2)
        
        // Second row of rotation matrix (East direction)
        val r21 = 2 * (q1*q2 + q0*q3)
        val r22 = 1 - 2 * (q1*q1 + q3*q3)
        val r23 = 2 * (q2*q3 - q0*q1)
        
        // Third row of rotation matrix (Down direction)
        val r31 = 2 * (q1*q3 - q0*q2)
        val r32 = 2 * (q2*q3 + q0*q1)
        val r33 = 1 - 2 * (q1*q1 + q2*q2)
        
        // Transform acceleration vector
        val accelNorth = r11 * accelX + r12 * accelY + r13 * accelZ
        val accelEast  = r21 * accelX + r22 * accelY + r23 * accelZ
        val accelDown  = r31 * accelX + r32 * accelY + r33 * accelZ
        
        return Triple(accelNorth, accelEast, accelDown)
    }
    
    /**
     * Create ESKF result from nominal state.
     */
    private fun createResult(nominal: NominalState, timestampMs: Long): ESKFResult {
        val geodetic = nominal.toGeodeticPosition()
        val speedHeading = nominal.toSpeedAndHeading()
        val euler = nominal.toEulerAngles()
        
        // Simple confidence based on time since initialization (placeholder)
        val confidence = 0.9  // Placeholder - in full implementation would use covariance trace
        
        return ESKFResult(
            latitude = geodetic.latitude,
            longitude = geodetic.longitude,
            altitude = geodetic.altitude,
            velocityNorth = nominal.velocityNorth,
            velocityEast = nominal.velocityEast,
            velocityDown = nominal.velocityDown,
            rollDegrees = euler.rollDegrees,
            pitchDegrees = euler.pitchDegrees,
            yawDegrees = euler.yawDegrees,
            positionUncertainty = 10.0,  // Placeholder
            velocityUncertainty = 2.0,   // Placeholder
            attitudeUncertainty = 5.0,   // Placeholder
            accelBiasX = nominal.accelBiasX,
            accelBiasY = nominal.accelBiasY,
            accelBiasZ = nominal.accelBiasZ,
            gyroBiasX = nominal.gyroBiasX,
            gyroBiasY = nominal.gyroBiasY,
            gyroBiasZ = nominal.gyroBiasZ,
            confidence = confidence,
            isValid = nominal.isValid(),
            timestampMs = timestampMs
        )
    }
    
    /**
     * Format double for logging.
     */
    private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)
    
    override fun updateWithGNSSPosition(gnssLatitude: Double, gnssLongitude: Double, gnssUncertainty: Double): ESKFResult {
        if (!initialized) throw IllegalStateException("ESKF not initialized")
        
        val nominal = nominalState ?: throw IllegalStateException("Nominal state is null")
        
        try {
            // Perform GNSS measurement update
            val updated = performGNSSPositionUpdate(nominal, gnssLatitude, gnssLongitude, gnssUncertainty)
            
            // Update stored state
            nominalState = updated
            
            return createResult(updated, System.currentTimeMillis())
            
        } catch (e: Exception) {
            // Return current state on error
            return createResult(nominal, System.currentTimeMillis())
        }
    }
    
    override fun updateWithVelocity(velocityNorth: Double, velocityEast: Double, velocityUncertainty: Double): ESKFResult {
        if (!initialized) throw IllegalStateException("ESKF not initialized")
        
        val nominal = nominalState ?: throw IllegalStateException("Nominal state is null")
        
        try {
            // Perform velocity measurement update
            val updated = performVelocityUpdate(nominal, velocityNorth, velocityEast, velocityUncertainty)
            
            // Update stored state
            nominalState = updated
            
            return createResult(updated, System.currentTimeMillis())
            
        } catch (e: Exception) {
            android.util.Log.e("ESKFImpl", "Velocity update failed: ${e.message}", e)
            // Return current state on error
            return createResult(nominal, System.currentTimeMillis())
        }
    }
    
    override fun updateWithHeading(headingDegrees: Double, headingUncertainty: Double): ESKFResult {
        // Placeholder - return current state
        return getCurrentState()
    }
    
    override fun getCurrentState(): ESKFResult {
        return predict(ImuMeasurement(0L, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), 0.0)
    }
    
    override fun getNominalState(): NominalState {
        return nominalState ?: throw IllegalStateException("ESKF not initialized")
    }
    
    override fun getErrorState(): ErrorState = errorState
    
    override fun getCovarianceDiagonal(): DoubleArray {
        return DoubleArray(15) { i -> covarianceMatrix[i][i] }
    }
    
    override fun getCovarianceMatrix(): Array<DoubleArray> {
        // Return a copy to prevent external modification
        return Array(15) { i -> 
            DoubleArray(15) { j -> 
                covarianceMatrix[i][j] 
            }
        }
    }
    
    override fun reset() {
        nominalState = null
        errorState = ErrorState.zero()
        // Reset covariance matrix
        covarianceMatrix = Array(15) { DoubleArray(15) }
        initialized = false
    }
    
    override fun isInitialized(): Boolean = initialized
    
    override fun updateConfiguration(newConfiguration: ESKFConfiguration) {
        // Placeholder - configuration update logic will be implemented later
    }
    
    override fun setAdaptiveNoise(enabled: Boolean, profile: NoiseProfile) {
        // Placeholder - adaptive noise logic will be implemented later
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // PHASE 3: ERROR-STATE COVARIANCE PROPAGATION
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Initialize covariance matrix P0 with initial uncertainties.
     * Error state ordering: [δp, δv, δθ, δba, δbg] = 15 dimensions
     */
    private fun initializeCovarianceMatrix(config: ESKFConfiguration) {
        // Clear matrix
        for (i in 0..14) {
            for (j in 0..14) {
                covarianceMatrix[i][j] = 0.0
            }
        }
        
        // Set diagonal elements (initial uncertainties)
        // Position uncertainty (indices 0-2): North, East, Down
        covarianceMatrix[0][0] = config.initialPositionUncertainty * config.initialPositionUncertainty
        covarianceMatrix[1][1] = config.initialPositionUncertainty * config.initialPositionUncertainty
        covarianceMatrix[2][2] = config.initialPositionUncertainty * config.initialPositionUncertainty
        
        // Velocity uncertainty (indices 3-5): North, East, Down
        covarianceMatrix[3][3] = config.initialVelocityUncertainty * config.initialVelocityUncertainty
        covarianceMatrix[4][4] = config.initialVelocityUncertainty * config.initialVelocityUncertainty
        covarianceMatrix[5][5] = config.initialVelocityUncertainty * config.initialVelocityUncertainty
        
        // Attitude uncertainty (indices 6-8): Roll, Pitch, Yaw error angles
        covarianceMatrix[6][6] = config.initialAttitudeUncertainty * config.initialAttitudeUncertainty
        covarianceMatrix[7][7] = config.initialAttitudeUncertainty * config.initialAttitudeUncertainty
        covarianceMatrix[8][8] = config.initialAttitudeUncertainty * config.initialAttitudeUncertainty
        
        // Accelerometer bias uncertainty (indices 9-11): X, Y, Z
        val accelBiasVar = config.initialAccelerometerBiasUncertainty * config.initialAccelerometerBiasUncertainty
        covarianceMatrix[9][9] = accelBiasVar
        covarianceMatrix[10][10] = accelBiasVar
        covarianceMatrix[11][11] = accelBiasVar
        
        // Gyroscope bias uncertainty (indices 12-14): X, Y, Z
        val gyroBiasVar = config.initialGyroscopeBiasUncertainty * config.initialGyroscopeBiasUncertainty
        covarianceMatrix[12][12] = gyroBiasVar
        covarianceMatrix[13][13] = gyroBiasVar
        covarianceMatrix[14][14] = gyroBiasVar
    }
    
    /**
     * Propagate error-state covariance matrix using:
     * P_k+1 = Φ P_k Φᵀ + Q
     * 
     * Where:
     * - Φ is the discrete state transition matrix
     * - Q is the discrete process noise covariance
     */
    private fun propagateCovariance(
        nominalState: NominalState,
        imu: ImuMeasurement,
        dt: Double
    ) {
        // 1. Compute continuous-time error-state Jacobian F
        val F = computeErrorStateJacobian(nominalState, imu)
        
        // 2. Compute discrete state transition matrix Φ = I + F*dt (first-order approximation)
        val Phi = computeDiscreteStateTransition(F, dt)
        
        // 3. Compute discrete process noise covariance Q
        val Q = computeProcessNoiseCovariance(nominalState, dt)
        
        // 4. Propagate covariance: P = Φ P Φᵀ + Q
        propagateCovarianceMatrix(Phi, Q)
    }
    
    /**
     * Compute continuous-time error-state Jacobian matrix F.
     * 15x15 matrix representing linearized error dynamics.
     */
    private fun computeErrorStateJacobian(
        nominalState: NominalState,
        imu: ImuMeasurement
    ): Array<DoubleArray> {
        val F = Array(15) { DoubleArray(15) }
        
        // Get bias-corrected IMU measurements
        val correctedAccelX = imu.accelerationX - nominalState.accelBiasX
        val correctedAccelY = imu.accelerationY - nominalState.accelBiasY
        val correctedAccelZ = imu.accelerationZ - nominalState.accelBiasZ
        
        // Get current quaternion for rotation matrices
        val q = Quaternion(nominalState.quaternionW, nominalState.quaternionX, 
                          nominalState.quaternionY, nominalState.quaternionZ).normalized()
        
        // Rotation matrix from body to NED frame
        val R_bn = quaternionToRotationMatrix(q)
        
        // Skew-symmetric matrix of body-frame acceleration
        val accelSkew = skewSymmetricMatrix(correctedAccelX, correctedAccelY, correctedAccelZ)
        
        // Block structure of F matrix:
        // F = [  0    I    0   0   0 ]  Position
        //     [  0    0   F_vθ F_va 0 ]  Velocity  
        //     [  0    0   F_θθ  0  F_θg] Attitude
        //     [  0    0    0   F_ba 0 ]  Accel Bias
        //     [  0    0    0    0  F_bg] Gyro Bias
        
        // Position-Velocity coupling: F[0:2, 3:5] = I (positions derivative = velocities)
        F[0][3] = 1.0  // δpN / δvN
        F[1][4] = 1.0  // δpE / δvE 
        F[2][5] = 1.0  // δpD / δvD
        
        // Velocity-Attitude coupling: F[3:5, 6:8] = -R_bn * [f_b]×
        for (i in 0..2) {
            for (j in 0..2) {
                F[3 + i][6 + j] = -dotProduct(R_bn[i], accelSkew[j])
            }
        }
        
        // Velocity-AccelBias coupling: F[3:5, 9:11] = -R_bn
        for (i in 0..2) {
            for (j in 0..2) {
                F[3 + i][9 + j] = -R_bn[i][j]
            }
        }
        
        // Attitude-GyroBias coupling: F[6:8, 12:14] = -I
        F[6][12] = -1.0   // δθx / δbgx
        F[7][13] = -1.0   // δθy / δbgy
        F[8][14] = -1.0   // δθz / δbgz
        
        // Bias dynamics: F[9:11, 9:11] = 0 (bias is modeled as random walk)
        // Bias dynamics: F[12:14, 12:14] = 0 (bias is modeled as random walk)
        // (These are already zero from initialization)
        
        return F
    }
    
    /**
     * Compute discrete state transition matrix using first-order approximation.
     * Φ = I + F*dt
     */
    private fun computeDiscreteStateTransition(F: Array<DoubleArray>, dt: Double): Array<DoubleArray> {
        val Phi = Array(15) { DoubleArray(15) }
        
        // Initialize as identity matrix
        for (i in 0..14) {
            Phi[i][i] = 1.0
        }
        
        // Add F*dt
        for (i in 0..14) {
            for (j in 0..14) {
                Phi[i][j] += F[i][j] * dt
            }
        }
        
        return Phi
    }
    
    /**
     * Compute discrete process noise covariance matrix Q.
     * Models uncertainty introduced by IMU noise and bias random walk.
     */
    private fun computeProcessNoiseCovariance(
        nominalState: NominalState,
        dt: Double
    ): Array<DoubleArray> {
        val Q = Array(15) { DoubleArray(15) }
        
        // Get rotation matrix for transforming IMU noise to NED frame
        val q = Quaternion(nominalState.quaternionW, nominalState.quaternionX,
                          nominalState.quaternionY, nominalState.quaternionZ).normalized()
        val R_bn = quaternionToRotationMatrix(q)
        
        // IMU noise parameters
        val accelNoiseVar = configuration.accelerometerMeasurementNoise * configuration.accelerometerMeasurementNoise * dt
        val gyroNoiseVar = configuration.gyroscopeMeasurementNoise * configuration.gyroscopeMeasurementNoise * dt
        
        // Bias random walk parameters  
        val accelBiasVar = configuration.accelerometerBiasProcessNoise * configuration.accelerometerBiasProcessNoise * dt
        val gyroBiasVar = configuration.gyroscopeBiasProcessNoise * configuration.gyroscopeBiasProcessNoise * dt
        
        // Position process noise (from velocity integration): Q[0:2, 0:2] = (σ_v * dt)²/3 * I
        val posNoiseVar = configuration.velocityProcessNoise * configuration.velocityProcessNoise * dt * dt * dt / 3.0
        Q[0][0] = posNoiseVar  // North position
        Q[1][1] = posNoiseVar  // East position
        Q[2][2] = posNoiseVar  // Down position
        
        // Velocity process noise (from acceleration): Q[3:5, 3:5] = R_bn * σ_a² * dt * R_bn^T
        for (i in 0..2) {
            for (j in 0..2) {
                var sum = 0.0
                for (k in 0..2) {
                    sum += R_bn[i][k] * (if (k == j) accelNoiseVar else 0.0) * R_bn[j][k]
                }
                Q[3 + i][3 + j] = sum
            }
        }
        
        // Attitude process noise (from gyroscope): Q[6:8, 6:8] = σ_g² * dt * I
        Q[6][6] = gyroNoiseVar   // Roll error
        Q[7][7] = gyroNoiseVar   // Pitch error
        Q[8][8] = gyroNoiseVar   // Yaw error
        
        // Accelerometer bias random walk: Q[9:11, 9:11] = σ_ba² * dt * I
        Q[9][9] = accelBiasVar    // X bias
        Q[10][10] = accelBiasVar  // Y bias
        Q[11][11] = accelBiasVar  // Z bias
        
        // Gyroscope bias random walk: Q[12:14, 12:14] = σ_bg² * dt * I
        Q[12][12] = gyroBiasVar   // X bias
        Q[13][13] = gyroBiasVar   // Y bias
        Q[14][14] = gyroBiasVar   // Z bias
        
        return Q
    }
    
    /**
     * Propagate covariance matrix: P = Φ P Φᵀ + Q
     */
    private fun propagateCovarianceMatrix(Phi: Array<DoubleArray>, Q: Array<DoubleArray>) {
        // Temporary matrix for intermediate results
        val temp = Array(15) { DoubleArray(15) }
        val result = Array(15) { DoubleArray(15) }
        
        // Compute Φ P
        multiplyMatrices(Phi, covarianceMatrix, temp)
        
        // Compute Φ P Φᵀ
        multiplyMatricesTransposeB(temp, Phi, result)
        
        // Add process noise: P = Φ P Φᵀ + Q
        for (i in 0..14) {
            for (j in 0..14) {
                covarianceMatrix[i][j] = result[i][j] + Q[i][j]
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // MATRIX UTILITY FUNCTIONS
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Convert quaternion to 3x3 rotation matrix (body to NED).
     */
    private fun quaternionToRotationMatrix(q: Quaternion): Array<DoubleArray> {
        val R = Array(3) { DoubleArray(3) }
        
        val qw = q.w
        val qx = q.x
        val qy = q.y
        val qz = q.z
        
        // First row
        R[0][0] = 1 - 2*(qy*qy + qz*qz)
        R[0][1] = 2*(qx*qy + qw*qz)
        R[0][2] = 2*(qx*qz - qw*qy)
        
        // Second row  
        R[1][0] = 2*(qx*qy - qw*qz)
        R[1][1] = 1 - 2*(qx*qx + qz*qz)
        R[1][2] = 2*(qy*qz + qw*qx)
        
        // Third row
        R[2][0] = 2*(qx*qz + qw*qy)
        R[2][1] = 2*(qy*qz - qw*qx)
        R[2][2] = 1 - 2*(qx*qx + qy*qy)
        
        return R
    }
    
    /**
     * Create skew-symmetric matrix from vector [x, y, z].
     * Used for cross-product operations.
     */
    private fun skewSymmetricMatrix(x: Double, y: Double, z: Double): Array<DoubleArray> {
        return arrayOf(
            doubleArrayOf( 0.0, -z,   y),
            doubleArrayOf( z,   0.0, -x),
            doubleArrayOf(-y,   x,   0.0)
        )
    }
    
    /**
     * Compute dot product of two vectors (represented as arrays).
     */
    private fun dotProduct(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        for (i in a.indices) {
            sum += a[i] * b[i]
        }
        return sum
    }
    
    /**
     * Multiply two matrices: C = A * B
     */
    private fun multiplyMatrices(A: Array<DoubleArray>, B: Array<DoubleArray>, C: Array<DoubleArray>) {
        val m = A.size
        val n = B[0].size  
        val p = B.size
        
        for (i in 0 until m) {
            for (j in 0 until n) {
                C[i][j] = 0.0
                for (k in 0 until p) {
                    C[i][j] += A[i][k] * B[k][j]
                }
            }
        }
    }
    
    /**
     * Multiply matrix A with transpose of matrix B: C = A * Bᵀ
     */
    private fun multiplyMatricesTransposeB(A: Array<DoubleArray>, B: Array<DoubleArray>, C: Array<DoubleArray>) {
        val m = A.size
        val n = B.size
        val p = A[0].size
        
        for (i in 0 until m) {
            for (j in 0 until n) {
                C[i][j] = 0.0
                for (k in 0 until p) {
                    C[i][j] += A[i][k] * B[j][k]  // Note: B[j][k] instead of B[k][j] for transpose
                }
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // PHASE 4: GNSS MEASUREMENT FUSION
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Perform GNSS position measurement update using Extended Kalman Filter.
     * 
     * Steps:
     * 1. Compute innovation (measurement residual)
     * 2. Build measurement Jacobian H and covariance R
     * 3. Compute innovation covariance S = H P H^T + R  
     * 4. Statistical validation (NIS outlier rejection)
     * 5. Calculate Kalman gain K
     * 6. Update covariance matrix P (Joseph form)
     * 7. Compute error state correction
     * 8. Inject error state into nominal state
     * 9. Apply covariance reset after injection
     */
    private fun performGNSSPositionUpdate(
        nominalState: NominalState,
        gnssLatitude: Double,
        gnssLongitude: Double,
        gnssUncertainty: Double
    ): NominalState {
        
        // 1. Convert nominal position to geodetic coordinates
        val nominalGeodetic = nominalState.toGeodeticPosition()
        
        // 2. Compute innovation (measurement residual) in NED frame
        val innovation = computeGNSSInnovation(
            nominalGeodetic.latitude, nominalGeodetic.longitude,
            gnssLatitude, gnssLongitude,
            nominalState.referenceLatitude, nominalState.referenceLongitude
        )
        
        // 3. Build measurement Jacobian H (3x15 for position measurements)
        val H = computeGNSSMeasurementJacobian()
        
        // 4. Build measurement noise covariance R
        val R = computeGNSSMeasurementNoise(gnssUncertainty)
        
        // 5. Compute innovation covariance S = H P H^T + R
        val S = computeInnovationCovariance(H, R)
        
        // 6. Statistical validation (NIS outlier rejection)
        if (!validateGNSSInnovation(innovation, S)) {
            // Reject outlier measurement - return unchanged state
            return nominalState
        }
        
        // 7. Compute Kalman gain K = P H^T S^-1
        val K = computeKalmanGain(H, S)
        
        // 8. Update covariance matrix P using Joseph form
        updateCovarianceWithMeasurement(K, H, R)
        
        // 9. Compute error state correction δx = K * innovation
        val deltaX = computeErrorStateCorrection(K, innovation)
        
        // 10. Inject error state into nominal state
        val correctedState = injectErrorState(nominalState, deltaX)
        
        // 11. Apply ESKF covariance reset after error state injection
        applyErrorStateReset(deltaX)
        
        return correctedState
    }
    
    /**
     * Perform velocity measurement update (RoNIN ML integration).
     * 
     * Applies EKF measurement update using horizontal velocity measurements.
     * Similar to GNSS position update but observes velocity states instead.
     * 
     * Steps:
     * 1. Compute innovation (measurement - predicted velocity)
     * 2. Build measurement Jacobian H (2×15 for horizontal velocity)
     * 3. Build measurement noise covariance R
     * 4. Compute innovation covariance S = H P H^T + R
     * 5. Statistical validation (NIS outlier rejection)
     * 6. Compute Kalman gain K = P H^T S^-1
     * 7. Update covariance matrix P using Joseph form
     * 8. Compute error state correction δx = K * innovation
     * 9. Inject error state into nominal state
     * 10. Apply covariance reset after injection
     */
    private fun performVelocityUpdate(
        nominalState: NominalState,
        measuredVelocityNorth: Double,
        measuredVelocityEast: Double,
        velocityUncertainty: Double
    ): NominalState {
        
        // 1. Compute innovation (measurement residual)
        val innovation = doubleArrayOf(
            measuredVelocityNorth - nominalState.velocityNorth,  // North innovation
            measuredVelocityEast - nominalState.velocityEast     // East innovation
        )
        
        // 2. Build measurement Jacobian H (2×15 for horizontal velocity)
        val H = computeVelocityMeasurementJacobian()
        
        // 3. Build measurement noise covariance R
        val R = computeVelocityMeasurementNoise(velocityUncertainty)
        
        // 4. Compute innovation covariance S = H P H^T + R
        val S = computeInnovationCovarianceVelocity(H, R)
        
        // 5. Statistical validation (NIS outlier rejection)
        if (!validateVelocityInnovation(innovation, S)) {
            android.util.Log.w("ESKFImpl", "❌ RoNIN velocity REJECTED: NIS gate failed")
            // Reject outlier measurement - return unchanged state
            return nominalState
        }
        
        // 6. Compute Kalman gain K = P H^T S^-1 (15×2)
        val K = computeKalmanGainVelocity(H, S)
        
        // 7. Update covariance matrix P using Joseph form
        updateCovarianceWithVelocityMeasurement(K, H, R)
        
        // 8. Compute error state correction δx = K * innovation
        val deltaX = computeErrorStateCorrectionVelocity(K, innovation)
        
        // 9. Inject error state into nominal state
        val correctedState = injectErrorState(nominalState, deltaX)
        
        // 10. Apply ESKF covariance reset after error state injection
        applyErrorStateReset(deltaX)
        
        android.util.Log.i("ESKFImpl", "✅ RoNIN velocity ACCEPTED: innovation=[%.3f, %.3f] m/s, correction=[%.3f, %.3f] m/s".format(
            innovation[0], innovation[1], deltaX[3], deltaX[4]
        ))
        
        return correctedState
    }
    
    /**
     * Compute velocity measurement Jacobian H matrix.
     * For velocity measurements, H extracts velocity states from error state.
     * 
     * Error state: δx = [δpN, δpE, δpD, δvN, δvE, δvD, δθx, δθy, δθz, δba_xyz, δbg_xyz]
     * Velocity measurement observes: [δvN, δvE]
     */
    private fun computeVelocityMeasurementJacobian(): Array<DoubleArray> {
        val H = Array(2) { DoubleArray(15) }  // 2 measurements × 15 states
        
        // Velocity measurements directly observe horizontal velocity error states
        H[0][3] = 1.0  // North velocity (index 3 in error state)
        H[1][4] = 1.0  // East velocity (index 4 in error state)
        
        // All other elements remain zero (velocity measurements don't directly observe
        // position, down velocity, attitude, or bias errors)
        
        return H
    }
    
    /**
     * Compute velocity measurement noise covariance R matrix.
     */
    private fun computeVelocityMeasurementNoise(velocityUncertainty: Double): Array<DoubleArray> {
        val R = Array(2) { DoubleArray(2) }  // 2×2 for horizontal velocity measurements
        
        // Assume independent North/East velocity noise with equal variance
        val variance = velocityUncertainty * velocityUncertainty
        R[0][0] = variance  // North velocity variance
        R[1][1] = variance  // East velocity variance
        R[0][1] = 0.0       // Assume uncorrelated
        R[1][0] = 0.0
        
        return R
    }
    
    /**
     * Compute innovation covariance S = H P H^T + R for velocity measurements.
     */
    private fun computeInnovationCovarianceVelocity(H: Array<DoubleArray>, R: Array<DoubleArray>): Array<DoubleArray> {
        val S = Array(2) { DoubleArray(2) }  // 2×2 for 2 velocity measurements
        
        // Compute H P H^T
        // H is 2×15, P is 15×15, so H*P is 2×15, and (H*P)*H^T is 2×2
        val HP = Array(2) { DoubleArray(15) }
        
        // HP = H * P
        for (i in 0..1) {
            for (j in 0..14) {
                HP[i][j] = 0.0
                for (k in 0..14) {
                    HP[i][j] += H[i][k] * covarianceMatrix[k][j]
                }
            }
        }
        
        // S = HP * H^T + R
        for (i in 0..1) {
            for (j in 0..1) {
                S[i][j] = R[i][j]  // Start with R
                for (k in 0..14) {
                    S[i][j] += HP[i][k] * H[j][k]  // Add HP * H^T
                }
            }
        }
        
        return S
    }
    
    /**
     * Validate velocity innovation using NIS (Normalized Innovation Squared).
     * 
     * For 2D horizontal velocity measurements, follows chi-square distribution
     * with 2 degrees of freedom.
     */
    private fun validateVelocityInnovation(
        innovation: DoubleArray, 
        innovationCovariance: Array<DoubleArray>
    ): Boolean {
        
        // Compute S^-1 for 2×2 matrix (analytical inversion)
        val det = innovationCovariance[0][0] * innovationCovariance[1][1] - 
                  innovationCovariance[0][1] * innovationCovariance[1][0]
        
        if (abs(det) < 1e-12) {
            // Singular covariance - accept measurement
            return true
        }
        
        val invDet = 1.0 / det
        val S_inv = Array(2) { DoubleArray(2) }
        S_inv[0][0] = innovationCovariance[1][1] * invDet
        S_inv[0][1] = -innovationCovariance[0][1] * invDet  
        S_inv[1][0] = -innovationCovariance[1][0] * invDet
        S_inv[1][1] = innovationCovariance[0][0] * invDet
        
        // Compute NIS = innovation^T * S^-1 * innovation
        val temp = doubleArrayOf(
            S_inv[0][0] * innovation[0] + S_inv[0][1] * innovation[1],
            S_inv[1][0] * innovation[0] + S_inv[1][1] * innovation[1]
        )
        val nis = innovation[0] * temp[0] + innovation[1] * temp[1]
        
        // Chi-square threshold for 2 DOF at 99% confidence level
        val chiSquareThreshold = 9.21
        
        val passed = nis <= chiSquareThreshold
        
        if (!passed) {
            android.util.Log.w("ESKFImpl", "🔴 Velocity NIS check failed: NIS=%.2f > threshold=%.2f".format(nis, chiSquareThreshold))
        } else {
            android.util.Log.d("ESKFImpl", "🟢 Velocity NIS check passed: NIS=%.2f ≤ threshold=%.2f".format(nis, chiSquareThreshold))
        }
        
        return passed
    }
    
    /**
     * Compute Kalman gain for velocity measurements: K = P H^T S^-1
     * Returns 15×2 matrix.
     */
    private fun computeKalmanGainVelocity(H: Array<DoubleArray>, S: Array<DoubleArray>): Array<DoubleArray> {
        val K = Array(15) { DoubleArray(2) }
        
        // Compute S^-1 (2×2 matrix inversion)
        val det = S[0][0] * S[1][1] - S[0][1] * S[1][0]
        
        if (abs(det) < 1e-12) {
            // Singular innovation covariance - return zero gain (no update)
            return K
        }
        
        val invDet = 1.0 / det
        val S_inv = Array(2) { DoubleArray(2) }
        S_inv[0][0] = S[1][1] * invDet
        S_inv[0][1] = -S[0][1] * invDet
        S_inv[1][0] = -S[1][0] * invDet
        S_inv[1][1] = S[0][0] * invDet
        
        // Compute P * H^T (15×2)
        val PH_T = Array(15) { DoubleArray(2) }
        for (i in 0..14) {
            for (j in 0..1) {
                PH_T[i][j] = 0.0
                for (k in 0..14) {
                    PH_T[i][j] += covarianceMatrix[i][k] * H[j][k]  // H[j][k] for transpose
                }
            }
        }
        
        // Compute K = (P * H^T) * S^-1 (15×2)
        for (i in 0..14) {
            for (j in 0..1) {
                K[i][j] = 0.0
                for (k in 0..1) {
                    K[i][j] += PH_T[i][k] * S_inv[k][j]
                }
            }
        }
        
        return K
    }
    
    /**
     * Update covariance matrix using Joseph form for velocity measurements.
     * P = (I - K H) P (I - K H)^T + K R K^T
     */
    private fun updateCovarianceWithVelocityMeasurement(K: Array<DoubleArray>, H: Array<DoubleArray>, R: Array<DoubleArray>) {
        // Compute (I - K H)
        val IKH = Array(15) { DoubleArray(15) }
        
        // Initialize as identity matrix
        for (i in 0..14) {
            IKH[i][i] = 1.0
        }
        
        // Subtract K H (15×15 - (15×2) × (2×15))
        for (i in 0..14) {
            for (j in 0..14) {
                for (k in 0..1) {
                    IKH[i][j] -= K[i][k] * H[k][j]
                }
            }
        }
        
        // Compute first term: (I - K H) P (I - K H)^T
        val temp1 = Array(15) { DoubleArray(15) }
        val firstTerm = Array(15) { DoubleArray(15) }
        
        // temp1 = (I - K H) * P  
        multiplyMatrices(IKH, covarianceMatrix, temp1)
        
        // firstTerm = temp1 * (I - K H)^T
        multiplyMatricesTransposeB(temp1, IKH, firstTerm)
        
        // Compute second term: K R K^T
        val temp2 = Array(15) { DoubleArray(2) }
        val secondTerm = Array(15) { DoubleArray(15) }
        
        // temp2 = K * R (15×2 = (15×2) × (2×2))
        for (i in 0..14) {
            for (j in 0..1) {
                temp2[i][j] = 0.0
                for (k in 0..1) {
                    temp2[i][j] += K[i][k] * R[k][j]
                }
            }
        }
        
        // secondTerm = temp2 * K^T (15×15 = (15×2) × (2×15))
        for (i in 0..14) {
            for (j in 0..14) {
                secondTerm[i][j] = 0.0
                for (k in 0..1) {
                    secondTerm[i][j] += temp2[i][k] * K[j][k]  // K[j][k] for transpose
                }
            }
        }
        
        // Final result: P = (I - K H) P (I - K H)^T + K R K^T
        for (i in 0..14) {
            for (j in 0..14) {
                covarianceMatrix[i][j] = firstTerm[i][j] + secondTerm[i][j]
            }
        }
    }
    
    /**
     * Compute error state correction δx = K * innovation for velocity measurements.
     */
    private fun computeErrorStateCorrectionVelocity(K: Array<DoubleArray>, innovation: DoubleArray): DoubleArray {
        val deltaX = DoubleArray(15)
        
        for (i in 0..14) {
            deltaX[i] = 0.0
            for (j in 0..1) {
                deltaX[i] += K[i][j] * innovation[j]
            }
        }
        
        return deltaX
    }

    
    /**
     * Compute GNSS innovation (measurement residual) in NED frame.
     */
    private fun computeGNSSInnovation(
        nominalLat: Double, nominalLon: Double,
        gnssLat: Double, gnssLon: Double,
        refLat: Double, refLon: Double
    ): DoubleArray {
        // Convert both positions to NED relative to reference point
        val nominalNED = geodeticToNED(nominalLat, nominalLon, 0.0, refLat, refLon, 0.0)
        val gnssNED = geodeticToNED(gnssLat, gnssLon, 0.0, refLat, refLon, 0.0)
        
        // Innovation = measurement - prediction
        return doubleArrayOf(
            gnssNED[0] - nominalNED[0],  // North innovation
            gnssNED[1] - nominalNED[1],  // East innovation
            0.0                          // Down innovation (not available from 2D GNSS)
        )
    }
    
    /**
     * Validate GNSS innovation for outlier rejection using Normalized Innovation Squared (NIS).
     * 
     * Uses proper Mahalanobis distance: NIS = innovation^T * S^-1 * innovation
     * For 2D horizontal position measurements (North, East), follows chi-square distribution
     * with 2 degrees of freedom.
     * 
     * Confidence levels:
     * - 95%: χ²(2, 0.05) ≈ 5.99
     * - 99%: χ²(2, 0.01) ≈ 9.21  
     * - 99.9%: χ²(2, 0.001) ≈ 13.82
     * 
     * @param innovation Measurement residual [north, east, down] in meters
     * @param innovationCovariance S = H P H^T + R matrix (3×3)
     * @return true if measurement passes NIS gate, false if outlier
     */
    private fun validateGNSSInnovation(
        innovation: DoubleArray, 
        innovationCovariance: Array<DoubleArray>
    ): Boolean {
        
        // Use only horizontal components (North, East) for 2D GNSS
        // Down component often unavailable or unreliable for standard GNSS
        val innovation2D = doubleArrayOf(innovation[0], innovation[1])
        val S2D = Array(2) { i -> DoubleArray(2) { j -> innovationCovariance[i][j] } }
        
        // Compute S^-1 for 2×2 matrix (analytical inversion)
        val det = S2D[0][0] * S2D[1][1] - S2D[0][1] * S2D[1][0]
        
        if (abs(det) < 1e-12) {
            // Singular covariance - likely numerical issue, accept measurement
            return true
        }
        
        val invDet = 1.0 / det
        val S2D_inv = Array(2) { DoubleArray(2) }
        S2D_inv[0][0] = S2D[1][1] * invDet
        S2D_inv[0][1] = -S2D[0][1] * invDet  
        S2D_inv[1][0] = -S2D[1][0] * invDet
        S2D_inv[1][1] = S2D[0][0] * invDet
        
        // Compute NIS = innovation^T * S^-1 * innovation
        val temp = doubleArrayOf(
            S2D_inv[0][0] * innovation2D[0] + S2D_inv[0][1] * innovation2D[1],
            S2D_inv[1][0] * innovation2D[0] + S2D_inv[1][1] * innovation2D[1]
        )
        val nis = innovation2D[0] * temp[0] + innovation2D[1] * temp[1]
        
        // Chi-square threshold for 2 DOF at 99% confidence level
        // χ²(2, 0.01) ≈ 9.21 (accepts 99% of valid measurements)
        val chiSquareThreshold = 9.21
        
        return nis <= chiSquareThreshold
    }
    
    /**
     * Compute GNSS measurement Jacobian H matrix.
     * For position measurements, H extracts position states from error state.
     * 
     * Error state: δx = [δpN, δpE, δpD, δvN, δvE, δvD, δθx, δθy, δθz, δba_xyz, δbg_xyz]
     * Position measurement observes: [δpN, δpE, δpD]
     */
    private fun computeGNSSMeasurementJacobian(): Array<DoubleArray> {
        val H = Array(3) { DoubleArray(15) }  // 3 measurements × 15 states
        
        // Position measurements directly observe position error states
        H[0][0] = 1.0  // North position
        H[1][1] = 1.0  // East position
        H[2][2] = 1.0  // Down position (though typically not used for 2D GNSS)
        
        // All other elements remain zero (position measurements don't directly observe
        // velocity, attitude, or bias errors)
        
        return H
    }
    
    /**
     * Compute GNSS measurement noise covariance R matrix.
     */
    private fun computeGNSSMeasurementNoise(gnssUncertainty: Double): Array<DoubleArray> {
        val R = Array(3) { DoubleArray(3) }  // 3×3 for NED position measurements
        
        val variance = gnssUncertainty * gnssUncertainty
        
        R[0][0] = variance  // North position variance
        R[1][1] = variance  // East position variance  
        R[2][2] = variance * 4.0  // Down position variance (less accurate, but often not used)
        
        return R
    }
    
    /**
     * Compute innovation covariance S = H P H^T + R
     */
    private fun computeInnovationCovariance(H: Array<DoubleArray>, R: Array<DoubleArray>): Array<DoubleArray> {
        val S = Array(3) { DoubleArray(3) }
        val PH_t = Array(15) { DoubleArray(3) }
        
        // First compute P H^T
        for (i in 0..14) {
            for (j in 0..2) {
                PH_t[i][j] = 0.0
                for (k in 0..14) {
                    PH_t[i][j] += covarianceMatrix[i][k] * H[j][k]  // H[j][k] for transpose
                }
            }
        }
        
        // Then compute H P H^T
        for (i in 0..2) {
            for (j in 0..2) {
                S[i][j] = 0.0
                for (k in 0..14) {
                    S[i][j] += H[i][k] * PH_t[k][j]
                }
                // Add R
                S[i][j] += R[i][j]
            }
        }
        
        return S
    }
    
    /**
     * Compute Kalman gain K = P H^T S^-1 where S is precomputed innovation covariance
     */
    private fun computeKalmanGain(H: Array<DoubleArray>, S: Array<DoubleArray>): Array<DoubleArray> {
        val PH_t = Array(15) { DoubleArray(3) }
        
        // Compute P H^T
        for (i in 0..14) {
            for (j in 0..2) {
                PH_t[i][j] = 0.0
                for (k in 0..14) {
                    PH_t[i][j] += covarianceMatrix[i][k] * H[j][k]  // H[j][k] for transpose
                }
            }
        }
        
        // Invert S (3×3 innovation covariance matrix)
        val invS = invert3x3Matrix(S)
        
        // Compute K = P H^T S^-1
        val K = Array(15) { DoubleArray(3) }
        for (i in 0..14) {
            for (j in 0..2) {
                K[i][j] = 0.0
                for (k in 0..2) {
                    K[i][j] += PH_t[i][k] * invS[k][j]
                }
            }
        }
        
        return K
    }
    
    /**
     * Update covariance matrix using Joseph form for numerical stability.
     * P = (I - K H) P (I - K H)^T + K R K^T
     * 
     * The Joseph form guarantees:
     * 1. Symmetry preservation under floating-point arithmetic
     * 2. Positive semi-definiteness maintenance  
     * 3. Numerical stability for ill-conditioned systems
     * 
     * Standard form P = (I - KH)P can lose these properties due to rounding errors.
     */
    private fun updateCovarianceWithMeasurement(K: Array<DoubleArray>, H: Array<DoubleArray>, R: Array<DoubleArray>) {
        // Compute (I - K H)
        val IKH = Array(15) { DoubleArray(15) }
        
        // Initialize as identity matrix
        for (i in 0..14) {
            IKH[i][i] = 1.0
        }
        
        // Subtract K H
        for (i in 0..14) {
            for (j in 0..14) {
                for (k in 0..2) {
                    IKH[i][j] -= K[i][k] * H[k][j]
                }
            }
        }
        
        // Compute first term: (I - K H) P (I - K H)^T
        val temp1 = Array(15) { DoubleArray(15) }
        val firstTerm = Array(15) { DoubleArray(15) }
        
        // temp1 = (I - K H) * P  
        multiplyMatrices(IKH, covarianceMatrix, temp1)
        
        // firstTerm = temp1 * (I - K H)^T = (I - K H) P (I - K H)^T
        multiplyMatricesTransposeB(temp1, IKH, firstTerm)
        
        // Compute second term: K R K^T
        val temp2 = Array(15) { DoubleArray(3) }
        val secondTerm = Array(15) { DoubleArray(15) }
        
        // temp2 = K * R
        for (i in 0..14) {
            for (j in 0..2) {
                temp2[i][j] = 0.0
                for (k in 0..2) {
                    temp2[i][j] += K[i][k] * R[k][j]
                }
            }
        }
        
        // secondTerm = temp2 * K^T = K R K^T
        for (i in 0..14) {
            for (j in 0..14) {
                secondTerm[i][j] = 0.0
                for (k in 0..2) {
                    secondTerm[i][j] += temp2[i][k] * K[j][k]  // K[j][k] for transpose
                }
            }
        }
        
        // Final result: P = (I - K H) P (I - K H)^T + K R K^T
        for (i in 0..14) {
            for (j in 0..14) {
                covarianceMatrix[i][j] = firstTerm[i][j] + secondTerm[i][j]
            }
        }
    }
    
    /**
     * Compute error state correction δx = K * innovation
     */
    private fun computeErrorStateCorrection(K: Array<DoubleArray>, innovation: DoubleArray): DoubleArray {
        val deltaX = DoubleArray(15)
        
        for (i in 0..14) {
            deltaX[i] = 0.0
            for (j in 0..2) {
                deltaX[i] += K[i][j] * innovation[j]
            }
        }
        
        return deltaX
    }
    
    /**
     * Inject error state correction into nominal state.
     */
    private fun injectErrorState(nominal: NominalState, deltaX: DoubleArray): NominalState {
        // Position correction (indices 0-2)
        val correctedNorth = nominal.positionNorth + deltaX[0]
        val correctedEast = nominal.positionEast + deltaX[1] 
        val correctedDown = nominal.positionDown + deltaX[2]
        
        // Velocity correction (indices 3-5)
        val correctedVelN = nominal.velocityNorth + deltaX[3]
        val correctedVelE = nominal.velocityEast + deltaX[4]
        val correctedVelD = nominal.velocityDown + deltaX[5]
        
        // Attitude correction (indices 6-8) - apply as small angle rotation to quaternion
        val currentQ = Quaternion(nominal.quaternionW, nominal.quaternionX, 
                                 nominal.quaternionY, nominal.quaternionZ).normalized()
        
        // Create error quaternion from small angle approximation (body frame errors)
        val errorQ = Quaternion.fromAxisAngle(deltaX[6], deltaX[7], deltaX[8], 
                                            sqrt(deltaX[6]*deltaX[6] + deltaX[7]*deltaX[7] + deltaX[8]*deltaX[8]))
        
        // Apply correction with RIGHT multiplication (body frame error composition):
        // q_corrected = q_nominal × q_error  
        // This is consistent with ESKF convention where δθ represents body-frame attitude errors
        val correctedQ = (currentQ * errorQ).normalized()
        
        // Bias corrections (indices 9-14)
        val correctedAccelBiasX = nominal.accelBiasX + deltaX[9]
        val correctedAccelBiasY = nominal.accelBiasY + deltaX[10]
        val correctedAccelBiasZ = nominal.accelBiasZ + deltaX[11]
        
        val correctedGyroBiasX = nominal.gyroBiasX + deltaX[12]
        val correctedGyroBiasY = nominal.gyroBiasY + deltaX[13]
        val correctedGyroBiasZ = nominal.gyroBiasZ + deltaX[14]
        
        return NominalState(
            positionNorth = correctedNorth,
            positionEast = correctedEast,
            positionDown = correctedDown,
            velocityNorth = correctedVelN,
            velocityEast = correctedVelE,
            velocityDown = correctedVelD,
            quaternionW = correctedQ.w,
            quaternionX = correctedQ.x,
            quaternionY = correctedQ.y,
            quaternionZ = correctedQ.z,
            accelBiasX = correctedAccelBiasX,
            accelBiasY = correctedAccelBiasY,
            accelBiasZ = correctedAccelBiasZ,
            gyroBiasX = correctedGyroBiasX,
            gyroBiasY = correctedGyroBiasY,
            gyroBiasZ = correctedGyroBiasZ,
            referenceLatitude = nominal.referenceLatitude,
            referenceLongitude = nominal.referenceLongitude
        )
    }
    
    /**
     * Apply ESKF covariance reset after error state injection.
     * 
     * After injecting the error state into the nominal state, the error state is reset to zero.
     * However, for nonlinear injection (especially quaternion composition), the covariance 
     * matrix must be updated to account for the linearization around the new operating point.
     * 
     * The reset Jacobian G accounts for the relationship between the old error state
     * and the new error state after injection.
     * 
     * For ESKF with quaternion composition q_new = q_nominal × q_error:
     * G = ∂(δx_new)/∂(δx_old)
     * 
     * Updated covariance: P_reset = G * P * G^T
     * 
     * @param deltaX The injected error state vector before reset
     */
    private fun applyErrorStateReset(deltaX: DoubleArray) {
        // Compute reset Jacobian G (15×15)
        val G = Array(15) { i -> DoubleArray(15) { j -> if (i == j) 1.0 else 0.0 } }  // Initialize as identity
        
        // For position, velocity, and bias states: G = I (additive injection, no reset needed)
        // Only attitude block needs reset Jacobian due to quaternion composition nonlinearity
        
        // Attitude reset Jacobian block G[6:8, 6:8] for small-angle approximation:
        // After q_new = q_old × exp(0.5 * δθ), the new error state linearization gives:
        // G_attitude ≈ I - 0.5 * [δθ×] for small δθ
        val deltaTheta = doubleArrayOf(deltaX[6], deltaX[7], deltaX[8])
        val skewDeltaTheta = skewSymmetricMatrix(deltaTheta[0], deltaTheta[1], deltaTheta[2])
        
        // Update attitude block: G[6:8, 6:8] = I - 0.5 * [δθ×]
        for (i in 0..2) {
            for (j in 0..2) {
                G[6 + i][6 + j] = (if (i == j) 1.0 else 0.0) - 0.5 * skewDeltaTheta[i][j]
            }
        }
        
        // Apply covariance reset: P = G * P * G^T
        val temp = Array(15) { DoubleArray(15) }
        val newP = Array(15) { DoubleArray(15) }
        
        // First: temp = G * P
        multiplyMatrices(G, covarianceMatrix, temp)
        
        // Then: newP = temp * G^T = G * P * G^T  
        multiplyMatricesTransposeB(temp, G, newP)
        
        // Update covariance matrix
        for (i in 0..14) {
            for (j in 0..14) {
                covarianceMatrix[i][j] = newP[i][j]
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // COORDINATE TRANSFORMATION UTILITIES  
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Convert geodetic coordinates (WGS84) to NED coordinates relative to reference point.
     * 
     * @param lat Latitude in degrees
     * @param lon Longitude in degrees  
     * @param alt Altitude in meters (MSL)
     * @param refLat Reference latitude in degrees
     * @param refLon Reference longitude in degrees
     * @param refAlt Reference altitude in meters (MSL)
     * @return NED coordinates [North, East, Down] in meters
     */
    private fun geodeticToNED(
        lat: Double, lon: Double, alt: Double,
        refLat: Double, refLon: Double, refAlt: Double
    ): DoubleArray {
        // Simple flat-earth approximation for local navigation
        // Good for distances < 100km from reference point
        
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)
        val refLatRad = Math.toRadians(refLat)
        val refLonRad = Math.toRadians(refLon)
        
        // Earth radius constants
        val R_earth = 6378137.0  // WGS84 equatorial radius (meters)
        val f = 1.0 / 298.257223563  // WGS84 flattening
        val e2 = f * (2.0 - f)  // First eccentricity squared
        
        // Radius of curvature in the meridian
        val M = R_earth * (1.0 - e2) / Math.pow(1.0 - e2 * sin(refLatRad) * sin(refLatRad), 1.5)
        
        // Radius of curvature in the prime vertical  
        val N = R_earth / sqrt(1.0 - e2 * sin(refLatRad) * sin(refLatRad))
        
        // NED coordinates
        val north = (latRad - refLatRad) * M
        val east = (lonRad - refLonRad) * N * cos(refLatRad)
        val down = -(alt - refAlt)  // NED uses negative for up
        
        return doubleArrayOf(north, east, down)
    }
    
    /**
     * Invert a 3×3 matrix using analytical formula.
     */
    private fun invert3x3Matrix(matrix: Array<DoubleArray>): Array<DoubleArray> {
        val A = matrix
        val result = Array(3) { DoubleArray(3) }
        
        // Calculate determinant
        val det = A[0][0] * (A[1][1] * A[2][2] - A[1][2] * A[2][1]) -
                  A[0][1] * (A[1][0] * A[2][2] - A[1][2] * A[2][0]) +
                  A[0][2] * (A[1][0] * A[2][1] - A[1][1] * A[2][0])
        
        if (abs(det) < 1e-12) {
            // Matrix is singular, return identity as fallback
            result[0][0] = 1.0
            result[1][1] = 1.0  
            result[2][2] = 1.0
            return result
        }
        
        val invDet = 1.0 / det
        
        // Calculate inverse using cofactor method
        result[0][0] = (A[1][1] * A[2][2] - A[1][2] * A[2][1]) * invDet
        result[0][1] = (A[0][2] * A[2][1] - A[0][1] * A[2][2]) * invDet
        result[0][2] = (A[0][1] * A[1][2] - A[0][2] * A[1][1]) * invDet
        
        result[1][0] = (A[1][2] * A[2][0] - A[1][0] * A[2][2]) * invDet
        result[1][1] = (A[0][0] * A[2][2] - A[0][2] * A[2][0]) * invDet
        result[1][2] = (A[0][2] * A[1][0] - A[0][0] * A[1][2]) * invDet
        
        result[2][0] = (A[1][0] * A[2][1] - A[1][1] * A[2][0]) * invDet
        result[2][1] = (A[0][1] * A[2][0] - A[0][0] * A[2][1]) * invDet  
        result[2][2] = (A[0][0] * A[1][1] - A[0][1] * A[1][0]) * invDet
        
        return result
    }
}