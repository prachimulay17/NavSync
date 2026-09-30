package com.example.navsync.eskf

import com.example.navsync.model.NavigationState
import com.example.navsync.model.NavigationSource
import org.junit.Test
import kotlin.math.*

/**
 * Diagnostic test for ESKF Phase 2 to identify specific behavior characteristics.
 */
class ESKFDiagnosticTest {
    
    companion object {
        private const val GRAVITY = 9.80665
        private const val DATASET_LAT = 52.202805
        private const val DATASET_LON = -2.2002
    }
    
    /**
     * Test bias estimation impact - what happens with non-zero initial biases.
     */
    @Test
    fun testBiasEstimationImpact() {
        println("=== BIAS ESTIMATION IMPACT DIAGNOSTIC ===")
        
        val config = ESKFConfiguration.navSync()
        val initialState = NavigationState(
            latitude = DATASET_LAT,
            longitude = DATASET_LON,
            speedKmh = 0.0,
            headingDegrees = 0.0,
            confidence = 1.0,
            gnssAvailable = true,
            source = NavigationSource.GNSS
        )
        
        // Test 1: Zero bias (ideal case)
        val eskfZeroBias = ESKFImpl(config)
        eskfZeroBias.initialize(initialState, config, null)
        
        // Test 2: With bias (we can't directly set bias, but we can observe behavior)
        val eskfWithBias = ESKFImpl(config)
        eskfWithBias.initialize(initialState, config, null)
        
        // Apply constant acceleration for 10 steps
        val constantAccel = ImuMeasurement(
            timestampMs = 1000L,
            accelerationX = 1.0,  // 1 m/s² forward
            accelerationY = 0.0,
            accelerationZ = GRAVITY,
            angularVelocityX = 0.0,
            angularVelocityY = 0.0, 
            angularVelocityZ = 0.0
        )
        
        val dt = 0.1
        var result1: ESKFResult? = null
        var result2: ESKFResult? = null
        
        repeat(10) { step ->
            result1 = eskfZeroBias.predict(constantAccel.copy(timestampMs = 1000L + step * 100L), dt)
            result2 = eskfWithBias.predict(constantAccel.copy(timestampMs = 1000L + step * 100L), dt)
        }
        
        println("After 10 steps of 1.0 m/s² forward acceleration:")
        println("  Expected velocity: ${1.0 * 10 * dt} m/s")
        println("  ESKF velocity: ${"%.3f".format(sqrt(result1!!.velocityNorth*result1!!.velocityNorth + result1!!.velocityEast*result1!!.velocityEast))} m/s")
        println("  Expected position change: ${0.5 * 1.0 * (10*dt)*(10*dt)} m")
        
        val actualPosChange = (result1!!.latitude - DATASET_LAT) * 111320.0
        println("  ESKF position change: ${"%.3f".format(actualPosChange)} m")
        
        val velocityError = abs(sqrt(result1!!.velocityNorth*result1!!.velocityNorth + result1!!.velocityEast*result1!!.velocityEast) - 1.0)
        val positionError = abs(actualPosChange - 0.5)
        
        println("  Velocity error: ${"%.3f".format(velocityError)} m/s")
        println("  Position error: ${"%.3f".format(positionError)} m")
        
        if (velocityError < 0.1 && positionError < 0.1) {
            println("  ✅ GOOD: Accurate integration")
        } else {
            println("  ⚠️  WARNING: Integration errors - check coordinate transformations or bias handling")
        }
    }
    
    /**
     * Test coordinate transformation accuracy.
     */
    @Test
    fun testCoordinateTransformationAccuracy() {
        println("=== COORDINATE TRANSFORMATION DIAGNOSTIC ===")
        
        val config = ESKFConfiguration.navSync()
        val eskf = ESKFImpl(config)
        
        val initialState = NavigationState(
            latitude = DATASET_LAT,
            longitude = DATASET_LON, 
            speedKmh = 0.0,
            headingDegrees = 0.0,  // Facing North
            confidence = 1.0,
            gnssAvailable = true,
            source = NavigationSource.GNSS
        )
        
        eskf.initialize(initialState, config, null)
        
        // Test pure X-axis (forward in body frame) acceleration
        val forwardAccel = ImuMeasurement(
            timestampMs = 1000L,
            accelerationX = 2.0,  // Pure forward
            accelerationY = 0.0,
            accelerationZ = GRAVITY, 
            angularVelocityX = 0.0,
            angularVelocityY = 0.0,
            angularVelocityZ = 0.0
        )
        
        val result = eskf.predict(forwardAccel, 0.1)
        
        println("Pure forward acceleration (2.0 m/s² in body X-axis):")
        println("  Velocity North: ${"%.3f".format(result.velocityNorth)} m/s")
        println("  Velocity East: ${"%.3f".format(result.velocityEast)} m/s")
        println("  Velocity Down: ${"%.3f".format(result.velocityDown)} m/s")
        
        val expectedVelNorth = 2.0 * 0.1  // Should be North since heading=0
        val velNorthError = abs(result.velocityNorth - expectedVelNorth)
        val velEastError = abs(result.velocityEast)
        
        println("  Expected: North=${"%.3f".format(expectedVelNorth)}, East=0.000")
        println("  Error North: ${"%.3f".format(velNorthError)} m/s")
        println("  Error East: ${"%.3f".format(velEastError)} m/s")
        
        if (velNorthError < 0.01 && velEastError < 0.01) {
            println("  ✅ GOOD: Accurate body→NED transformation")
        } else {
            println("  ❌ ISSUE: Body→NED transformation error")
        }
    }
    
    /**
     * Test quaternion stability during rotations.
     */
    @Test
    fun testQuaternionStabilityDuringRotation() {
        println("=== QUATERNION STABILITY DIAGNOSTIC ===")
        
        val config = ESKFConfiguration.navSync()
        val eskf = ESKFImpl(config)
        
        val initialState = NavigationState(
            latitude = DATASET_LAT,
            longitude = DATASET_LON,
            speedKmh = 0.0,
            headingDegrees = 0.0,
            confidence = 1.0,
            gnssAvailable = true,
            source = NavigationSource.GNSS
        )
        
        eskf.initialize(initialState, config, null)
        
        // Apply constant yaw rate (turning)
        val turnRate = 0.1  // rad/s (about 5.7°/s)
        val dt = 0.1
        var heading = 0.0
        
        repeat(20) { step ->
            val turnMeasurement = ImuMeasurement(
                timestampMs = 1000L + step * 100L,
                accelerationX = 0.0,
                accelerationY = 0.0,
                accelerationZ = GRAVITY,
                angularVelocityX = 0.0,
                angularVelocityY = 0.0,
                angularVelocityZ = turnRate
            )
            
            val result = eskf.predict(turnMeasurement, dt)
            heading = result.yawDegrees
            
            // Check for quaternion normalization issues  
            if (step % 5 == 0) {
                println("  Step $step: Heading = ${"%.1f".format(heading)}°")
            }
        }
        
        val expectedHeading = Math.toDegrees(turnRate * 20 * dt)  // 20 steps
        val headingError = abs(heading - expectedHeading)
        
        println("After 20 steps of constant yaw rate:")
        println("  Expected heading change: ${"%.1f".format(expectedHeading)}°") 
        println("  Actual heading: ${"%.1f".format(heading)}°")
        println("  Error: ${"%.1f".format(headingError)}°")
        
        if (headingError < 1.0) {
            println("  ✅ GOOD: Accurate quaternion integration")
        } else {
            println("  ⚠️  WARNING: Quaternion integration drift")
        }
    }
    
    /**
     * Test gravity handling accuracy.
     */
    @Test 
    fun testGravityHandlingAccuracy() {
        println("=== GRAVITY HANDLING DIAGNOSTIC ===")
        
        val config = ESKFConfiguration.navSync()
        val eskf = ESKFImpl(config)
        
        val initialState = NavigationState(
            latitude = DATASET_LAT,
            longitude = DATASET_LON,
            speedKmh = 0.0,
            headingDegrees = 0.0,
            confidence = 1.0,
            gnssAvailable = true,
            source = NavigationSource.GNSS
        )
        
        eskf.initialize(initialState, config, null)
        
        // Test with only gravity (no motion acceleration)
        val gravityOnly = ImuMeasurement(
            timestampMs = 1000L,
            accelerationX = 0.0,  // No motion
            accelerationY = 0.0,
            accelerationZ = GRAVITY,  // Pure gravity
            angularVelocityX = 0.0,
            angularVelocityY = 0.0,
            angularVelocityZ = 0.0
        )
        
        // Run for multiple steps
        var lastResult: ESKFResult? = null
        repeat(10) { step ->
            lastResult = eskf.predict(gravityOnly.copy(timestampMs = 1000L + step * 100L), 0.1)
        }
        
        println("After 10 steps with gravity-only measurements:")
        println("  Final velocity North: ${"%.6f".format(lastResult!!.velocityNorth)} m/s")
        println("  Final velocity East: ${"%.6f".format(lastResult!!.velocityEast)} m/s") 
        println("  Final velocity Down: ${"%.6f".format(lastResult!!.velocityDown)} m/s")
        
        val totalSpeed = sqrt(lastResult!!.velocityNorth*lastResult!!.velocityNorth + 
                             lastResult!!.velocityEast*lastResult!!.velocityEast +
                             lastResult!!.velocityDown*lastResult!!.velocityDown)
        
        println("  Total speed magnitude: ${"%.6f".format(totalSpeed)} m/s")
        
        if (totalSpeed < 0.01) {
            println("  ✅ GOOD: Gravity properly compensated")
        } else {
            println("  ❌ ISSUE: Gravity compensation error - check coordinate frames or bias")
        }
    }
    
    /**
     * Test ESKF behavior with actual S-Vw9 motion magnitudes.
     */
    @Test
    fun testRealMotionMagnitudes() {
        println("=== REAL MOTION MAGNITUDE DIAGNOSTIC ===")
        
        // These are actual measured values from S-Vw9 analysis
        val realMotionAccels = listOf(
            Triple(-0.051, -1.288, -0.287),  // m/s² (gravity compensated)
            Triple(-0.129, -0.368, 0.771),
            Triple(-0.386, -2.162, 0.121),
            Triple(1.319, -3.981, -1.135),  // Significant lateral acceleration
            Triple(0.567, -2.308, -0.033)
        )
        
        val realGyroRates = listOf(
            Triple(-0.0510, 0.1903, -0.1151),  // rad/s (Yaw, Pitch, Roll)
            Triple(0.0780, 0.0334, -0.0201),
            Triple(-0.0849, 0.3813, -0.2611),
            Triple(-0.0735, 0.1976, 0.0121),
            Triple(-0.0837, 0.2093, -0.0393)
        )
        
        val config = ESKFConfiguration.navSync()
        val eskf = ESKFImpl(config)
        
        val initialState = NavigationState(
            latitude = DATASET_LAT,
            longitude = DATASET_LON,
            speedKmh = 6.19,
            headingDegrees = 262.19,
            confidence = 1.0,
            gnssAvailable = true,
            source = NavigationSource.GNSS
        )
        
        eskf.initialize(initialState, config, null)
        
        var maxVelocity = 0.0
        var maxPositionChange = 0.0
        
        for (i in realMotionAccels.indices) {
            val accel = realMotionAccels[i]
            val gyro = realGyroRates[i]
            
            val measurement = ImuMeasurement(
                timestampMs = 1000L + i * 100L,
                accelerationX = accel.first,
                accelerationY = accel.second,
                accelerationZ = accel.third + GRAVITY,  // Add gravity back
                angularVelocityX = gyro.third,   // Roll -> X
                angularVelocityY = gyro.second,  // Pitch -> Y
                angularVelocityZ = gyro.first    // Yaw -> Z
            )
            
            val result = eskf.predict(measurement, 0.1)
            
            val speed = sqrt(result.velocityNorth*result.velocityNorth + result.velocityEast*result.velocityEast)
            maxVelocity = maxOf(maxVelocity, speed)
            
            val posChange = (result.latitude - DATASET_LAT) * 111320.0
            maxPositionChange = maxOf(maxPositionChange, abs(posChange))
            
            println("  Step $i: Speed=${"%.2f".format(speed)} m/s, PosChange=${"%.2f".format(posChange)} m, Heading=${"%.1f".format(result.yawDegrees)}°")
        }
        
        println("REAL MOTION SUMMARY:")
        println("  Max velocity reached: ${"%.2f".format(maxVelocity)} m/s")
        println("  Max position change: ${"%.2f".format(maxPositionChange)} m")
        
        // Check reasonableness for vehicle motion
        val reasonable = maxVelocity < 20.0 && maxPositionChange < 5.0  // Conservative thresholds
        
        if (reasonable) {
            println("  ✅ ASSESSMENT: Realistic behavior with actual S-Vw9 motion")
        } else {
            println("  ⚠️  ASSESSMENT: Check if motion magnitudes are reasonable for vehicle")
        }
    }
}