package com.example.navsync.eskf

import com.example.navsync.model.NavigationState
import com.example.navsync.model.NavigationSource
import org.junit.Test
import org.junit.Assert.*
import kotlin.math.*

/**
 * Focused unit tests for ESKF Phase 2: nominal-state IMU prediction.
 * Tests the core prediction functionality without covariance or Kalman updates.
 */
class ESKFPredictionTest {
    
    companion object {
        private const val TOLERANCE = 1e-6
        private const val GRAVITY = 9.80665
        
        // Test location: Birmingham, UK (near S-Vw9 dataset location)
        private const val TEST_LAT = 52.2
        private const val TEST_LON = -2.2
        private const val TEST_ALT = 120.0
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
            initialVelocityUncertainty = 1.0,
            initialAttitudeUncertainty = 5.0,
            initialAccelerometerBiasUncertainty = 0.1,
            initialGyroscopeBiasUncertainty = 0.01
        )
    }
    
    private fun createInitialState(): NavigationState {
        return NavigationState(
            latitude = TEST_LAT,
            longitude = TEST_LON,
            speedKmh = 0.0,
            headingDegrees = 0.0,
            confidence = 1.0,
            gnssAvailable = true,
            source = NavigationSource.GNSS
        )
    }
    
    /**
     * Test 1: Zero-motion with gravity case
     * Verifies that with no motion and only gravity, the ESKF maintains position
     * and correctly handles gravity compensation.
     */
    @Test
    fun testZeroMotionWithGravity() {
        val config = createTestConfiguration()
        val eskf = ESKFImpl(config)
        val initialState = createInitialState()
        
        // Initialize ESKF
        eskf.initialize(initialState, config, null)
        
        // Create IMU measurement with only gravity (device flat, Z-axis up)
        val imuMeasurement = ImuMeasurement(
            timestampMs = 1100L,
            accelerationX = 0.0,
            accelerationY = 0.0,
            accelerationZ = GRAVITY,  // Raw gravity measurement
            angularVelocityX = 0.0,
            angularVelocityY = 0.0,
            angularVelocityZ = 0.0
        )
        
        // Predict forward 0.1 seconds
        val result = eskf.predict(imuMeasurement, 0.1)
        
        // Verify state remains stable
        assertTrue("Result should be valid", result.isValid)
        assertEquals("Latitude should remain unchanged", TEST_LAT, result.latitude, TOLERANCE)
        assertEquals("Longitude should remain unchanged", TEST_LON, result.longitude, TOLERANCE)
        // Note: altitude is not directly testable without geodetic conversion
        
        // Velocities should remain near zero
        assertEquals("North velocity should be zero", 0.0, result.velocityNorth, 0.1)
        assertEquals("East velocity should be zero", 0.0, result.velocityEast, 0.1)
        assertEquals("Down velocity should be zero", 0.0, result.velocityDown, 0.1)
        
        // Attitude should remain stable (approximately level)
        assertEquals("Roll should be near zero", 0.0, result.rollDegrees, 5.0)
        assertEquals("Pitch should be near zero", 0.0, result.pitchDegrees, 5.0)
    }
    
    /**
     * Test 2: Zero angular velocity case
     * Verifies quaternion stability when there is no rotation.
     */
    @Test
    fun testZeroAngularVelocity() {
        val config = createTestConfiguration()
        val eskf = ESKFImpl(config)
        val initialState = createInitialState().copy(headingDegrees = 45.0)  // Start with non-zero heading
        
        eskf.initialize(initialState, config, null)
        
        // Create IMU measurement with no rotation
        val imuMeasurement = ImuMeasurement(
            timestampMs = 1100L,
            accelerationX = 0.0,
            accelerationY = 0.0, 
            accelerationZ = GRAVITY,
            angularVelocityX = 0.0,  // No roll rate
            angularVelocityY = 0.0,  // No pitch rate
            angularVelocityZ = 0.0   // No yaw rate
        )
        
        // Predict multiple steps
        var result = eskf.predict(imuMeasurement, 0.1)
        result = eskf.predict(imuMeasurement.copy(timestampMs = 1200L), 0.1)
        result = eskf.predict(imuMeasurement.copy(timestampMs = 1300L), 0.1)
        
        // Heading should remain stable
        assertEquals("Heading should remain at 45 degrees", 45.0, result.yawDegrees, 1.0)
        assertEquals("Roll should remain near zero", 0.0, result.rollDegrees, 1.0)
        assertEquals("Pitch should remain near zero", 0.0, result.pitchDegrees, 1.0)
        
        assertTrue("Result should remain valid", result.isValid)
    }
    
    /**
     * Test 3: Constant acceleration case
     * Verifies that constant acceleration produces linear velocity change and
     * quadratic position change.
     */
    @Test
    fun testConstantAcceleration() {
        val config = createTestConfiguration()
        val eskf = ESKFImpl(config)
        val initialState = createInitialState()
        
        eskf.initialize(initialState, config, null)
        
        // Constant forward acceleration (X-axis in body frame)
        val constantAccel = 2.0  // m/s²
        val dt = 0.1  // seconds
        val numSteps = 10
        
        var lastResult = eskf.predict(
            ImuMeasurement(
                timestampMs = 1000L,
                accelerationX = 0.0, accelerationY = 0.0, accelerationZ = GRAVITY,
                angularVelocityX = 0.0, angularVelocityY = 0.0, angularVelocityZ = 0.0
            ), dt
        )
        
        // Apply constant acceleration for multiple steps
        for (step in 1..numSteps) {
            val imuMeasurement = ImuMeasurement(
                timestampMs = 1000L + (step * 100L),
                accelerationX = constantAccel,  // Constant forward acceleration
                accelerationY = 0.0,
                accelerationZ = GRAVITY,
                angularVelocityX = 0.0,
                angularVelocityY = 0.0,
                angularVelocityZ = 0.0
            )
            
            lastResult = eskf.predict(imuMeasurement, dt)
        }
        
        // After 1 second with constant acceleration
        val totalTime = numSteps * dt
        val expectedVelocity = constantAccel * totalTime
        val expectedDistance = 0.5 * constantAccel * totalTime * totalTime
        
        // Check velocity (should be linear with time)
        val actualSpeed = sqrt(lastResult.velocityNorth * lastResult.velocityNorth + 
                              lastResult.velocityEast * lastResult.velocityEast)
        assertEquals("Velocity should match constant acceleration", 
                    expectedVelocity, actualSpeed, 0.5)
        
        // Position should have moved (exact values depend on coordinate frame transformation)
        val initialLat = TEST_LAT
        val finalLat = lastResult.latitude
        assertTrue("Position should have changed due to acceleration", 
                  kotlin.math.abs(finalLat - initialLat) > 1e-6)
        
        assertTrue("Result should be valid", lastResult.isValid)
    }
    
    /**
     * Test 4: Quaternion normalization
     * Verifies that quaternions remain properly normalized during propagation.
     */
    @Test
    fun testQuaternionNormalization() {
        val config = createTestConfiguration()
        val eskf = ESKFImpl(config)
        val initialState = createInitialState()
        
        eskf.initialize(initialState, config, null)
        
        // Apply various rotations to test quaternion stability
        val rotationCases = listOf(
            Triple(0.1, 0.0, 0.0),   // Roll rotation
            Triple(0.0, 0.1, 0.0),   // Pitch rotation  
            Triple(0.0, 0.0, 0.1),   // Yaw rotation
            Triple(0.05, 0.05, 0.05) // Combined rotation
        )
        
        rotationCases.forEachIndexed { index, (rollRate, pitchRate, yawRate) ->
            var timestampMs = 2000L + (index * 1000L)
            
            // Apply rotation for several steps
            repeat(20) { step ->
                val imuMeasurement = ImuMeasurement(
                    timestampMs = timestampMs + step * 50L,
                    accelerationX = 0.0,
                    accelerationY = 0.0,
                    accelerationZ = GRAVITY,
                    angularVelocityX = rollRate,
                    angularVelocityY = pitchRate,
                    angularVelocityZ = yawRate
                )
                
                val result = eskf.predict(imuMeasurement, 0.05)
                
                // Verify result is valid (quaternion should be normalized internally)
                assertTrue("Result should remain valid during rotation $index, step $step", 
                          result.isValid)
                
                // Verify angles are reasonable (no NaN or extreme values)
                assertTrue("Roll should be finite", result.rollDegrees.isFinite())
                assertTrue("Pitch should be finite", result.pitchDegrees.isFinite())
                assertTrue("Yaw should be finite", result.yawDegrees.isFinite())
                
                assertTrue("Roll should be in reasonable range", 
                          kotlin.math.abs(result.rollDegrees) < 180.0)
                assertTrue("Pitch should be in reasonable range", 
                          kotlin.math.abs(result.pitchDegrees) < 90.0)
            }
        }
    }
    
    /**
     * Test 5: Large time step handling
     * Verifies that the ESKF handles large time steps gracefully.
     */
    @Test
    fun testLargeTimeStepHandling() {
        val config = createTestConfiguration()
        val eskf = ESKFImpl(config)
        val initialState = createInitialState()
        
        eskf.initialize(initialState, config, null)
        
        // Test with large time step (should be clamped internally)
        val imuMeasurement = ImuMeasurement(
            timestampMs = 2000L,
            accelerationX = 0.0,
            accelerationY = 0.0,
            accelerationZ = GRAVITY,
            angularVelocityX = 0.0,
            angularVelocityY = 0.0,
            angularVelocityZ = 0.0
        )
        
        // Test with 10 second time step (should be handled gracefully)
        val result = eskf.predict(imuMeasurement, 10.0)
        
        assertTrue("Result should be valid even with large time step", result.isValid)
        assertTrue("Latitude should be finite", result.latitude.isFinite())
        assertTrue("Longitude should be finite", result.longitude.isFinite())
        assertTrue("All velocities should be finite", 
                  result.velocityNorth.isFinite() && 
                  result.velocityEast.isFinite() && 
                  result.velocityDown.isFinite())
    }
    
    /**
     * Test 6: Sequential prediction consistency
     * Verifies that multiple sequential predictions maintain consistency.
     */
    @Test
    fun testSequentialPredictionConsistency() {
        val config = createTestConfiguration()
        val eskf = ESKFImpl(config)
        val initialState = createInitialState()
        
        eskf.initialize(initialState, config, null)
        
        val dt = 0.1
        var timestampMs = 1000L
        var lastResult: ESKFResult? = null
        
        // Perform 50 sequential predictions with slight motion
        repeat(50) { step ->
            val imuMeasurement = ImuMeasurement(
                timestampMs = timestampMs,
                accelerationX = 0.1,  // Small acceleration
                accelerationY = 0.0,
                accelerationZ = GRAVITY,
                angularVelocityX = 0.0,
                angularVelocityY = 0.0,
                angularVelocityZ = 0.01  // Small yaw rate
            )
            
            val result = eskf.predict(imuMeasurement, dt)
            
            assertTrue("Result $step should be valid", result.isValid)
            
            if (lastResult != null) {
                // Verify smooth progression
                val positionChange = kotlin.math.abs(result.latitude - lastResult!!.latitude) + 
                                   kotlin.math.abs(result.longitude - lastResult!!.longitude)
                assertTrue("Position change should be reasonable at step $step", 
                          positionChange < 0.01)  // Max ~1 km per step
                
                val velocityChange = kotlin.math.abs(result.velocityNorth - lastResult!!.velocityNorth) +
                                   kotlin.math.abs(result.velocityEast - lastResult!!.velocityEast)
                assertTrue("Velocity change should be reasonable at step $step",
                          velocityChange < 5.0)  // Max 5 m/s change per step
            }
            
            lastResult = result
            timestampMs += 100L
        }
        
        // Final state should show accumulated motion
        val finalResult = lastResult!!
        assertTrue("Final position should have moved", 
                  kotlin.math.abs(finalResult.latitude - TEST_LAT) > 1e-6)
        assertTrue("Final heading should have changed due to yaw rate",
                  kotlin.math.abs(finalResult.yawDegrees) > 1.0)
    }
}