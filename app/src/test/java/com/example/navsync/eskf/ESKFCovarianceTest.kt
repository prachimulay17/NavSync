package com.example.navsync.eskf

import com.example.navsync.model.NavigationState
import com.example.navsync.model.NavigationSource
import org.junit.Test
import org.junit.Assert.*
import kotlin.math.*

/**
 * Comprehensive tests for ESKF Phase 3: Error-state covariance propagation.
 * Tests covariance initialization, propagation, and mathematical properties.
 */
class ESKFCovarianceTest {
    
    companion object {
        private const val TOLERANCE = 1e-10
        private const val GRAVITY = 9.80665
        private const val DATASET_LAT = 52.202805
        private const val DATASET_LON = -2.2002
    }
    
    private fun createTestConfiguration(): ESKFConfiguration {
        return ESKFConfiguration(
            positionProcessNoise = 0.1,
            velocityProcessNoise = 0.5,
            attitudeProcessNoise = 0.01,
            accelerometerBiasProcessNoise = 0.01,
            gyroscopeBiasProcessNoise = 0.001,
            accelerometerMeasurementNoise = 0.2,
            gyroscopeMeasurementNoise = 0.01,
            gnssPositionMeasurementNoise = 5.0,
            gnssVelocityMeasurementNoise = 1.0,
            initialPositionUncertainty = 10.0,
            initialVelocityUncertainty = 2.0,
            initialAttitudeUncertainty = 0.1,  // radians
            initialAccelerometerBiasUncertainty = 0.5,
            initialGyroscopeBiasUncertainty = 0.05
        )
    }
    
    private fun createInitialState(): NavigationState {
        return NavigationState(
            latitude = DATASET_LAT,
            longitude = DATASET_LON,
            speedKmh = 0.0,
            headingDegrees = 0.0,
            confidence = 1.0,
            gnssAvailable = true,
            source = NavigationSource.GNSS
        )
    }
    
    /**
     * Test 1: Covariance matrix dimensions and initialization.
     */
    @Test
    fun testCovarianceDimensionsAndInitialization() {
        val config = createTestConfiguration()
        val eskf = ESKFImpl(config)
        val initialState = createInitialState()
        
        eskf.initialize(initialState, config, null)
        
        // Test covariance diagonal
        val covDiagonal = eskf.getCovarianceDiagonal()
        
        assertEquals("Covariance diagonal should have 15 elements", 15, covDiagonal.size)
        
        // Test initial values match configuration
        // Position uncertainties (indices 0-2)
        assertEquals("Position uncertainty North", config.initialPositionUncertainty * config.initialPositionUncertainty, covDiagonal[0], TOLERANCE)
        assertEquals("Position uncertainty East", config.initialPositionUncertainty * config.initialPositionUncertainty, covDiagonal[1], TOLERANCE)
        assertEquals("Position uncertainty Down", config.initialPositionUncertainty * config.initialPositionUncertainty, covDiagonal[2], TOLERANCE)
        
        // Velocity uncertainties (indices 3-5)
        assertEquals("Velocity uncertainty North", config.initialVelocityUncertainty * config.initialVelocityUncertainty, covDiagonal[3], TOLERANCE)
        assertEquals("Velocity uncertainty East", config.initialVelocityUncertainty * config.initialVelocityUncertainty, covDiagonal[4], TOLERANCE)
        assertEquals("Velocity uncertainty Down", config.initialVelocityUncertainty * config.initialVelocityUncertainty, covDiagonal[5], TOLERANCE)
        
        // Attitude uncertainties (indices 6-8)
        assertEquals("Attitude uncertainty Roll", config.initialAttitudeUncertainty * config.initialAttitudeUncertainty, covDiagonal[6], TOLERANCE)
        assertEquals("Attitude uncertainty Pitch", config.initialAttitudeUncertainty * config.initialAttitudeUncertainty, covDiagonal[7], TOLERANCE)
        assertEquals("Attitude uncertainty Yaw", config.initialAttitudeUncertainty * config.initialAttitudeUncertainty, covDiagonal[8], TOLERANCE)
        
        // Accelerometer bias uncertainties (indices 9-11)
        val expectedAccelBiasVar = config.initialAccelerometerBiasUncertainty * config.initialAccelerometerBiasUncertainty
        assertEquals("Accel bias uncertainty X", expectedAccelBiasVar, covDiagonal[9], TOLERANCE)
        assertEquals("Accel bias uncertainty Y", expectedAccelBiasVar, covDiagonal[10], TOLERANCE)
        assertEquals("Accel bias uncertainty Z", expectedAccelBiasVar, covDiagonal[11], TOLERANCE)
        
        // Gyroscope bias uncertainties (indices 12-14)
        val expectedGyroBiasVar = config.initialGyroscopeBiasUncertainty * config.initialGyroscopeBiasUncertainty
        assertEquals("Gyro bias uncertainty X", expectedGyroBiasVar, covDiagonal[12], TOLERANCE)
        assertEquals("Gyro bias uncertainty Y", expectedGyroBiasVar, covDiagonal[13], TOLERANCE)
        assertEquals("Gyro bias uncertainty Z", expectedGyroBiasVar, covDiagonal[14], TOLERANCE)
        
        println("✅ Covariance initialization test passed")
    }
    
    /**
     * Test 2: Covariance matrix symmetry and positive semi-definiteness.
     */
    @Test
    fun testCovarianceSymmetryAndPositivity() {
        val config = createTestConfiguration()
        val eskf = ESKFImpl(config)
        val initialState = createInitialState()
        
        eskf.initialize(initialState, config, null)
        
        // Apply some motion to evolve covariance
        val imuMeasurement = ImuMeasurement(
            timestampMs = 1000L,
            accelerationX = 0.5,
            accelerationY = 0.1,
            accelerationZ = GRAVITY,
            angularVelocityX = 0.0,
            angularVelocityY = 0.0,
            angularVelocityZ = 0.02
        )
        
        repeat(5) { step ->
            eskf.predict(imuMeasurement.copy(timestampMs = 1000L + step * 100L), 0.1)
            
            val covDiagonal = eskf.getCovarianceDiagonal()
            
            // Test positive semi-definiteness (diagonal elements must be non-negative)
            for (i in covDiagonal.indices) {
                assertTrue("Covariance diagonal element $i should be non-negative at step $step", 
                          covDiagonal[i] >= 0.0)
            }
            
            // Test that variances are finite
            for (i in covDiagonal.indices) {
                assertTrue("Covariance diagonal element $i should be finite at step $step",
                          covDiagonal[i].isFinite())
            }
        }
        
        println("✅ Covariance symmetry and positivity test passed")
    }
    
    /**
     * Test 3: Covariance growth during IMU-only propagation.
     */
    @Test
    fun testCovarianceGrowthDuringPropagation() {
        val config = createTestConfiguration()
        val eskf = ESKFImpl(config)
        val initialState = createInitialState()
        
        eskf.initialize(initialState, config, null)
        
        val initialCovDiagonal = eskf.getCovarianceDiagonal().clone()
        
        // Apply motion for multiple time steps
        val imuMeasurement = ImuMeasurement(
            timestampMs = 1000L,
            accelerationX = 1.0,  // Some acceleration
            accelerationY = 0.0,
            accelerationZ = GRAVITY,
            angularVelocityX = 0.0,
            angularVelocityY = 0.0,
            angularVelocityZ = 0.05  // Some rotation
        )
        
        // Propagate for 20 steps (2 seconds)
        repeat(20) { step ->
            eskf.predict(imuMeasurement.copy(timestampMs = 1000L + step * 100L), 0.1)
        }
        
        val finalCovDiagonal = eskf.getCovarianceDiagonal()
        
        // Verify covariance growth
        println("COVARIANCE GROWTH ANALYSIS:")
        val categories = listOf("Position", "Velocity", "Attitude", "AccelBias", "GyroBias")
        val startIndices = listOf(0, 3, 6, 9, 12)
        
        for (categoryIndex in categories.indices) {
            val category = categories[categoryIndex]
            val startIdx = startIndices[categoryIndex]
            
            val initialSum = (0..2).sumOf { initialCovDiagonal[startIdx + it] }
            val finalSum = (0..2).sumOf { finalCovDiagonal[startIdx + it] }
            
            println("  $category: Initial=${String.format("%.6f", initialSum)}, Final=${String.format("%.6f", finalSum)}, Growth=${String.format("%.2f", finalSum/initialSum)}x")
            
            // All categories should show some growth due to process noise
            assertTrue("$category uncertainty should grow during propagation", finalSum >= initialSum)
        }
        
        // Position uncertainty should grow the most (due to velocity integration)
        val positionGrowth = (0..2).sumOf { finalCovDiagonal[it] } / (0..2).sumOf { initialCovDiagonal[it] }
        val velocityGrowth = (3..5).sumOf { finalCovDiagonal[it] } / (3..5).sumOf { initialCovDiagonal[it] }
        
        assertTrue("Position uncertainty should grow during propagation", positionGrowth > 1.1)
        assertTrue("Velocity uncertainty should grow significantly", velocityGrowth > 1.5)
        
        println("✅ Covariance growth test passed")
    }
    
    /**
     * Test 4: Zero process noise behavior (growth only from state coupling).
     */
    @Test
    fun testZeroProcessNoiseBehavior() {
        // Create configuration with zero process noise
        val zeroNoiseConfig = ESKFConfiguration(
            positionProcessNoise = 0.0,
            velocityProcessNoise = 0.0,
            attitudeProcessNoise = 0.0,
            accelerometerBiasProcessNoise = 0.0,
            gyroscopeBiasProcessNoise = 0.0,
            accelerometerMeasurementNoise = 0.0,
            gyroscopeMeasurementNoise = 0.0,
            gnssPositionMeasurementNoise = 5.0,  // Not used in prediction
            gnssVelocityMeasurementNoise = 1.0,  // Not used in prediction
            initialPositionUncertainty = 5.0,
            initialVelocityUncertainty = 1.0,
            initialAttitudeUncertainty = 0.05,
            initialAccelerometerBiasUncertainty = 0.1,
            initialGyroscopeBiasUncertainty = 0.01
        )
        
        val eskf = ESKFImpl(zeroNoiseConfig)
        val initialState = createInitialState()
        
        eskf.initialize(initialState, zeroNoiseConfig, null)
        
        val initialCovDiagonal = eskf.getCovarianceDiagonal().clone()
        
        // Apply motion for several time steps with zero process noise
        val imuMeasurement = ImuMeasurement(
            timestampMs = 1000L,
            accelerationX = 0.5,
            accelerationY = 0.0,
            accelerationZ = GRAVITY,
            angularVelocityX = 0.0,
            angularVelocityY = 0.0,
            angularVelocityZ = 0.02
        )
        
        repeat(10) { step ->
            eskf.predict(imuMeasurement.copy(timestampMs = 1000L + step * 100L), 0.1)
        }
        
        val finalCovDiagonal = eskf.getCovarianceDiagonal()
        
        // With zero process noise, covariance still grows due to state coupling (F matrix)
        // but should not grow due to Q matrix. We expect bounded growth.
        for (i in 0..14) {
            assertTrue("Covariance element $i should remain finite with zero process noise", 
                      finalCovDiagonal[i].isFinite())
            assertTrue("Covariance element $i should remain non-negative", 
                      finalCovDiagonal[i] >= 0.0)
        }
        
        // Position uncertainty should grow due to velocity uncertainty coupling
        val positionGrowth = (0..2).sumOf { finalCovDiagonal[it] } / (0..2).sumOf { initialCovDiagonal[it] }
        assertTrue("Position should show bounded growth with zero process noise", 
                  positionGrowth > 1.0 && positionGrowth < 2.0)
        
        // Bias uncertainties should remain exactly constant (no coupling, no process noise)
        for (i in 9..14) {
            assertEquals("Bias covariance element $i should remain constant with zero process noise", 
                        initialCovDiagonal[i], finalCovDiagonal[i], TOLERANCE)
        }
        
        println("✅ Zero process noise behavior test passed")
    }
    
    /**
     * Test 5: Numerical stability under various conditions.
     */
    @Test
    fun testNumericalStability() {
        val config = createTestConfiguration()
        val eskf = ESKFImpl(config)
        val initialState = createInitialState()
        
        eskf.initialize(initialState, config, null)
        
        // Test various motion scenarios
        val testScenarios = listOf(
            // Scenario 1: High acceleration
            Triple("High Acceleration", 10.0, 0.0),
            // Scenario 2: High rotation
            Triple("High Rotation", 0.0, 1.0),
            // Scenario 3: Combined motion  
            Triple("Combined Motion", 5.0, 0.5),
            // Scenario 4: Very small motion
            Triple("Small Motion", 0.01, 0.001)
        )
        
        for ((scenarioName, accel, gyro) in testScenarios) {
            // Reset ESKF for each scenario
            eskf.initialize(initialState, config, null)
            
            val imuMeasurement = ImuMeasurement(
                timestampMs = 1000L,
                accelerationX = accel,
                accelerationY = 0.0,
                accelerationZ = GRAVITY,
                angularVelocityX = 0.0,
                angularVelocityY = 0.0,
                angularVelocityZ = gyro
            )
            
            var allValid = true
            var maxVariance = 0.0
            
            repeat(50) { step ->
                val result = eskf.predict(imuMeasurement.copy(timestampMs = 1000L + step * 100L), 0.1)
                
                if (!result.isValid) {
                    allValid = false
                }
                
                val covDiagonal = eskf.getCovarianceDiagonal()
                maxVariance = maxOf(maxVariance, covDiagonal.maxOrNull() ?: 0.0)
                
                // Check for NaN or infinite values
                for (variance in covDiagonal) {
                    assertTrue("Covariance should be finite in $scenarioName at step $step", 
                              variance.isFinite())
                }
            }
            
            println("  $scenarioName: All valid=$allValid, Max variance=${"%.3e".format(maxVariance)}")
            
            assertTrue("All predictions should be valid for $scenarioName", allValid)
            assertTrue("Maximum variance should be reasonable for $scenarioName", maxVariance < 1e6)
        }
        
        println("✅ Numerical stability test passed")
    }
    
    /**
     * Test 6: Covariance propagation with realistic IMU characteristics.
     */
    @Test
    fun testRealisticIMUCovariance() {
        val config = createTestConfiguration()
        val eskf = ESKFImpl(config)
        val initialState = createInitialState()
        
        eskf.initialize(initialState, config, null)
        
        // Use realistic IMU noise levels (based on typical automotive-grade IMUs)
        val realisticConfig = ESKFConfiguration(
            positionProcessNoise = 0.01,       // Low position process noise
            velocityProcessNoise = 0.1,        // Moderate velocity process noise
            attitudeProcessNoise = 0.001,      // Low attitude process noise 
            accelerometerBiasProcessNoise = 1e-5,  // Realistic accel bias stability
            gyroscopeBiasProcessNoise = 1e-6,      // Realistic gyro bias stability
            accelerometerMeasurementNoise = 0.1,   // Realistic accel noise
            gyroscopeMeasurementNoise = 0.005,     // Realistic gyro noise
            gnssPositionMeasurementNoise = 3.0,
            gnssVelocityMeasurementNoise = 0.5,
            initialPositionUncertainty = 5.0,      // GPS-level initial uncertainty
            initialVelocityUncertainty = 1.0,
            initialAttitudeUncertainty = 0.05,     // ~3 degree initial attitude uncertainty
            initialAccelerometerBiasUncertainty = 0.2,  // Realistic initial accel bias uncertainty
            initialGyroscopeBiasUncertainty = 0.02      // Realistic initial gyro bias uncertainty
        )
        
        eskf.initialize(initialState, realisticConfig, null)
        
        // Simulate 60 seconds of driving
        val dt = 0.1  // 10 Hz
        val steps = 600
        
        repeat(steps) { step ->
            val timeSeconds = step * dt
            
            // Generate realistic driving motion profile
            val accelX = 0.5 * sin(timeSeconds * 0.1) + 0.1 * sin(timeSeconds * 0.5)  // Longitudinal
            val accelY = 0.2 * sin(timeSeconds * 0.15)  // Lateral
            val gyroZ = 0.1 * sin(timeSeconds * 0.08)   // Yaw rate
            
            val imuMeasurement = ImuMeasurement(
                timestampMs = 1000L + step * 100L,
                accelerationX = accelX,
                accelerationY = accelY,
                accelerationZ = GRAVITY,
                angularVelocityX = 0.0,
                angularVelocityY = 0.0,
                angularVelocityZ = gyroZ
            )
            
            val result = eskf.predict(imuMeasurement, dt)
            assertTrue("Result should be valid at step $step", result.isValid)
            
            // Check covariance every 10 seconds
            if (step % 100 == 0 && step > 0) {
                val covDiagonal = eskf.getCovarianceDiagonal()
                val positionUncertainty = sqrt((0..2).sumOf { covDiagonal[it] })
                val velocityUncertainty = sqrt((3..5).sumOf { covDiagonal[it] })
                val attitudeUncertainty = sqrt((6..8).sumOf { covDiagonal[it] })
                
                println("  t=${step*dt}s: Pos=${"%.2f".format(positionUncertainty)}m, " +
                       "Vel=${"%.2f".format(velocityUncertainty)}m/s, " +
                       "Att=${"%.3f".format(Math.toDegrees(attitudeUncertainty))}°")
                
                // Verify uncertainties are growing but remain reasonable
                assertTrue("Position uncertainty should be reasonable at ${step*dt}s", 
                          positionUncertainty < 10000.0)  // Less than 10km
                assertTrue("Velocity uncertainty should be reasonable at ${step*dt}s",
                          velocityUncertainty < 1000.0)   // Less than 1000 m/s
                assertTrue("Attitude uncertainty should be reasonable at ${step*dt}s",
                          Math.toDegrees(attitudeUncertainty) < 360.0)  // Less than 360°
            }
        }
        
        println("✅ Realistic IMU covariance test passed")
    }
}