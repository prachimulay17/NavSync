package com.example.navsync.eskf

import com.example.navsync.model.NavigationState
import com.example.navsync.model.NavigationSource
import org.junit.Test
import org.junit.Assert.*
import kotlin.math.*

/**
 * Comprehensive tests for ESKF measurement fusion functionality.
 * Tests GNSS position updates, innovation calculation, outlier rejection,
 * error state injection, and covariance reduction.
 */
class ESKFMeasurementFusionTest {
    
    companion object {
        private const val TOLERANCE = 1e-6
        private const val GRAVITY = 9.80665
        
        // Test location: Birmingham, UK (S-Vw9 dataset area)
        private const val TEST_LAT = 52.202805
        private const val TEST_LON = -2.200200
        private const val TEST_ALT = 117.75
        
        // Typical GNSS uncertainty
        private const val GNSS_UNCERTAINTY = 5.0  // meters
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
            gnssPositionMeasurementNoise = GNSS_UNCERTAINTY,
            gnssVelocityMeasurementNoise = 1.0,
            initialPositionUncertainty = 10.0,
            initialVelocityUncertainty = 2.0,
            initialAttitudeUncertainty = 0.1,
            initialAccelerometerBiasUncertainty = 0.5,
            initialGyroscopeBiasUncertainty = 0.05
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
     * Test 1: Basic GNSS position update functionality.
     */
    @Test
    fun testBasicGNSSPositionUpdate() {
        val config = createTestConfiguration()
        val eskf = ESKFImpl(config)
        val initialState = createInitialState()
        
        eskf.initialize(initialState, config, null)
        
        // Get initial covariance for comparison
        val initialCovDiagonal = eskf.getCovarianceDiagonal().clone()
        
        // Apply some IMU predictions to build up uncertainty
        val imuMeasurement = ImuMeasurement(
            timestampMs = 1000L,
            accelerationX = 0.5,
            accelerationY = 0.1,
            accelerationZ = GRAVITY,
            angularVelocityX = 0.0,
            angularVelocityY = 0.0,
            angularVelocityZ = 0.02
        )
        
        repeat(10) { step ->
            eskf.predict(imuMeasurement.copy(timestampMs = 1000L + step * 100L), 0.1)
        }
        
        val preUpdateCovDiagonal = eskf.getCovarianceDiagonal()
        val preUpdateResult = eskf.getCurrentState()
        
        // Apply GNSS measurement update (simulate accurate GNSS fix)
        val gnssResult = eskf.updateWithGNSSPosition(
            TEST_LAT + 0.0001,  // Slightly north (about 11m)
            TEST_LON + 0.0001,  // Slightly east (about 7m)
            GNSS_UNCERTAINTY
        )
        
        val postUpdateCovDiagonal = eskf.getCovarianceDiagonal()
        
        println("BASIC GNSS UPDATE TEST:")
        println("  Pre-update position: (${preUpdateResult.latitude}, ${preUpdateResult.longitude})")
        println("  GNSS measurement: (${TEST_LAT + 0.0001}, ${TEST_LON + 0.0001})")
        println("  Post-update position: (${gnssResult.latitude}, ${gnssResult.longitude})")
        
        // Verify update was successful
        assertTrue("GNSS update result should be valid", gnssResult.isValid)
        
        // Position should be corrected towards GNSS measurement
        val latDiff = abs(gnssResult.latitude - (TEST_LAT + 0.0001))
        val lonDiff = abs(gnssResult.longitude - (TEST_LON + 0.0001))
        assertTrue("Latitude should be closer to GNSS measurement", latDiff < 0.0001)
        assertTrue("Longitude should be closer to GNSS measurement", lonDiff < 0.0001)
        
        // Position uncertainty should be reduced
        val positionUncertaintyBefore = sqrt(preUpdateCovDiagonal[0] + preUpdateCovDiagonal[1])
        val positionUncertaintyAfter = sqrt(postUpdateCovDiagonal[0] + postUpdateCovDiagonal[1])
        
        println("  Position uncertainty: Before=${String.format("%.2f", positionUncertaintyBefore)}m, After=${String.format("%.2f", positionUncertaintyAfter)}m")
        
        assertTrue("Position uncertainty should be reduced by GNSS update", 
                  positionUncertaintyAfter < positionUncertaintyBefore)
        
        println("✅ Basic GNSS position update test passed")
    }
    
    /**
     * Test 2: GNSS outlier rejection functionality.
     */
    @Test
    fun testGNSSOutlierRejection() {
        val config = createTestConfiguration()
        val eskf = ESKFImpl(config)
        val initialState = createInitialState()
        
        eskf.initialize(initialState, config, null)
        
        // Get initial state
        val preOutlierResult = eskf.getCurrentState()
        val preOutlierCov = eskf.getCovarianceDiagonal().clone()
        
        // Apply obvious outlier measurement (1000m away = ~0.009 degrees)
        val outlierResult = eskf.updateWithGNSSPosition(
            TEST_LAT + 0.009,  // About 1000m north (should be rejected)
            TEST_LON + 0.009,  // About 1000m east (should be rejected)
            GNSS_UNCERTAINTY
        )
        
        val postOutlierCov = eskf.getCovarianceDiagonal()
        
        println("GNSS OUTLIER REJECTION TEST:")
        println("  Original position: (${preOutlierResult.latitude}, ${preOutlierResult.longitude})")
        println("  Outlier measurement: (${TEST_LAT + 0.009}, ${TEST_LON + 0.009}) - ~1400m away")
        println("  Result position: (${outlierResult.latitude}, ${outlierResult.longitude})")
        
        // Position should remain unchanged (outlier rejected)
        val latChange = abs(outlierResult.latitude - preOutlierResult.latitude)
        val lonChange = abs(outlierResult.longitude - preOutlierResult.longitude)
        
        assertTrue("Outlier should not significantly change latitude", latChange < 1e-6)
        assertTrue("Outlier should not significantly change longitude", lonChange < 1e-6)
        
        // Covariance should remain unchanged (no update applied)
        for (i in 0..14) {
            assertEquals("Covariance element $i should remain unchanged after outlier rejection",
                        preOutlierCov[i], postOutlierCov[i], TOLERANCE)
        }
        
        println("✅ GNSS outlier rejection test passed")
    }
    
    /**
     * Test 3: Covariance reduction verification.
     */
    @Test
    fun testCovarianceReduction() {
        val config = createTestConfiguration()
        val eskf = ESKFImpl(config)
        val initialState = createInitialState()
        
        eskf.initialize(initialState, config, null)
        
        // Build up uncertainty with IMU predictions
        val imuMeasurement = ImuMeasurement(
            timestampMs = 1000L,
            accelerationX = 1.0,
            accelerationY = 0.5,
            accelerationZ = GRAVITY,
            angularVelocityX = 0.0,
            angularVelocityY = 0.0,
            angularVelocityZ = 0.05
        )
        
        // Propagate for 20 steps to build up significant uncertainty
        repeat(20) { step ->
            eskf.predict(imuMeasurement.copy(timestampMs = 1000L + step * 100L), 0.1)
        }
        
        val preUpdateCov = eskf.getCovarianceDiagonal().clone()
        
        // Apply GNSS update
        eskf.updateWithGNSSPosition(TEST_LAT, TEST_LON, GNSS_UNCERTAINTY)
        
        val postUpdateCov = eskf.getCovarianceDiagonal()
        
        println("COVARIANCE REDUCTION TEST:")
        
        // Check each error state category
        val categories = listOf("Position", "Velocity", "Attitude", "AccelBias", "GyroBias")
        val startIndices = listOf(0, 3, 6, 9, 12)
        
        for (categoryIndex in categories.indices) {
            val category = categories[categoryIndex]
            val startIdx = startIndices[categoryIndex]
            
            val preSum = (0..2).sumOf { preUpdateCov[startIdx + it] }
            val postSum = (0..2).sumOf { postUpdateCov[startIdx + it] }
            val reduction = (1.0 - postSum / preSum) * 100.0
            
            println("  $category uncertainty: Before=${String.format("%.6f", preSum)}, After=${String.format("%.6f", postSum)}, Reduction=${String.format("%.1f", reduction)}%")
            
            if (category == "Position") {
                // Position uncertainty should be significantly reduced
                assertTrue("Position uncertainty should be reduced by at least 50%", reduction > 50.0)
            }
        }
        
        // Position uncertainties should be reduced the most
        val positionReduction = (preUpdateCov[0] + preUpdateCov[1]) - (postUpdateCov[0] + postUpdateCov[1])
        assertTrue("Position uncertainty should be significantly reduced", positionReduction > 0.0)
        
        println("✅ Covariance reduction test passed")
    }
    
    /**
     * Test 4: Error state injection and nominal state correction.
     */
    @Test
    fun testErrorStateInjection() {
        val config = createTestConfiguration()
        val eskf = ESKFImpl(config)
        val initialState = createInitialState()
        
        eskf.initialize(initialState, config, null)
        
        // Apply predictions to create error buildup
        val imuMeasurement = ImuMeasurement(
            timestampMs = 1000L,
            accelerationX = 2.0,  // Significant acceleration
            accelerationY = 1.0,
            accelerationZ = GRAVITY,
            angularVelocityX = 0.0,
            angularVelocityY = 0.0,
            angularVelocityZ = 0.1  // Some rotation
        )
        
        repeat(15) { step ->
            eskf.predict(imuMeasurement.copy(timestampMs = 1000L + step * 100L), 0.1)
        }
        
        val preUpdateResult = eskf.getCurrentState()
        
        // Apply GNSS update with known offset
        val knownGnssLat = TEST_LAT + 0.0002  // About 22m north
        val knownGnssLon = TEST_LON - 0.0001  // About 7m west
        
        val postUpdateResult = eskf.updateWithGNSSPosition(knownGnssLat, knownGnssLon, GNSS_UNCERTAINTY)
        
        println("ERROR STATE INJECTION TEST:")
        println("  Pre-update position: (${preUpdateResult.latitude}, ${preUpdateResult.longitude})")
        println("  GNSS measurement: ($knownGnssLat, $knownGnssLon)")
        println("  Post-update position: (${postUpdateResult.latitude}, ${postUpdateResult.longitude})")
        
        // Position should move towards GNSS measurement
        val latMovement = postUpdateResult.latitude - preUpdateResult.latitude
        val lonMovement = postUpdateResult.longitude - preUpdateResult.longitude
        val expectedLatMovement = knownGnssLat - preUpdateResult.latitude
        val expectedLonMovement = knownGnssLon - preUpdateResult.longitude
        
        // Movement should be in the correct direction (same sign as expected)
        assertTrue("Latitude correction should move towards GNSS measurement", 
                  latMovement * expectedLatMovement >= 0.0)
        assertTrue("Longitude correction should move towards GNSS measurement",
                  lonMovement * expectedLonMovement >= 0.0)
        
        // Movement should be reasonable (not excessive)
        assertTrue("Latitude correction should be reasonable", abs(latMovement) < abs(expectedLatMovement) + 0.0001)
        assertTrue("Longitude correction should be reasonable", abs(lonMovement) < abs(expectedLonMovement) + 0.0001)
        
        // Verify state remains valid after injection
        assertTrue("State should remain valid after error injection", postUpdateResult.isValid)
        assertTrue("Velocities should remain finite", 
                  postUpdateResult.velocityNorth.isFinite() && 
                  postUpdateResult.velocityEast.isFinite() &&
                  postUpdateResult.velocityDown.isFinite())
        
        println("✅ Error state injection test passed")
    }
    
    /**
     * Test 5: Repeated GNSS updates behavior.
     */
    @Test
    fun testRepeatedGNSSUpdates() {
        val config = createTestConfiguration()
        val eskf = ESKFImpl(config)
        val initialState = createInitialState()
        
        eskf.initialize(initialState, config, null)
        
        val gnssReadings = listOf(
            Pair(TEST_LAT + 0.0001, TEST_LON + 0.0001),  // NE
            Pair(TEST_LAT + 0.0002, TEST_LON + 0.0000),  // N
            Pair(TEST_LAT + 0.0001, TEST_LON - 0.0001),  // NW
            Pair(TEST_LAT + 0.0000, TEST_LON - 0.0001),  // W
            Pair(TEST_LAT - 0.0001, TEST_LON + 0.0000)   // S
        )
        
        val results = mutableListOf<ESKFResult>()
        val uncertainties = mutableListOf<Double>()
        
        for (i in gnssReadings.indices) {
            // Apply some IMU prediction between GNSS updates
            if (i > 0) {
                val imuMeasurement = ImuMeasurement(
                    timestampMs = 1000L + i * 1000L,
                    accelerationX = 0.2,
                    accelerationY = 0.1,
                    accelerationZ = GRAVITY,
                    angularVelocityX = 0.0,
                    angularVelocityY = 0.0,
                    angularVelocityZ = 0.01
                )
                
                repeat(5) { step ->
                    eskf.predict(imuMeasurement.copy(timestampMs = 1000L + i * 1000L + step * 100L), 0.1)
                }
            }
            
            // Apply GNSS update
            val (gnssLat, gnssLon) = gnssReadings[i]
            val result = eskf.updateWithGNSSPosition(gnssLat, gnssLon, GNSS_UNCERTAINTY)
            
            val covDiagonal = eskf.getCovarianceDiagonal()
            val positionUncertainty = sqrt(covDiagonal[0] + covDiagonal[1])
            
            results.add(result)
            uncertainties.add(positionUncertainty)
            
            println("  Update $i: GNSS=(${"%.6f".format(gnssLat)}, ${"%.6f".format(gnssLon)}) → Result=(${"%.6f".format(result.latitude)}, ${"%.6f".format(result.longitude)}) Unc=${"%.2f".format(positionUncertainty)}m")
        }
        
        println("REPEATED GNSS UPDATES TEST:")
        
        // All results should be valid
        assertTrue("All GNSS update results should be valid", results.all { it.isValid })
        
        // Position uncertainty should remain bounded
        val maxUncertainty = uncertainties.maxOrNull() ?: 0.0
        assertTrue("Maximum position uncertainty should remain reasonable", maxUncertainty < 50.0)
        
        // Final uncertainty should be lower than what we'd expect from IMU-only propagation
        val finalUncertainty = uncertainties.last()
        assertTrue("Final uncertainty should be well-bounded by GNSS updates", finalUncertainty < 20.0)
        
        println("✅ Repeated GNSS updates test passed")
    }
    
    /**
     * Test 6: Measurement Jacobian H matrix verification.
     */
    @Test
    fun testMeasurementJacobianStructure() {
        val config = createTestConfiguration()
        val eskf = ESKFImpl(config)
        val initialState = createInitialState()
        
        eskf.initialize(initialState, config, null)
        
        // We can't directly access the H matrix, but we can test its effects
        // by applying small perturbations and observing the response
        
        val baseResult = eskf.getCurrentState()
        
        // Test that position measurements affect position states
        val smallOffset = 0.00001  // ~1m
        val result1 = eskf.updateWithGNSSPosition(
            TEST_LAT + smallOffset, TEST_LON, GNSS_UNCERTAINTY
        )
        
        // Position should change in the expected direction
        assertTrue("GNSS latitude update should affect result latitude",
                  result1.latitude > baseResult.latitude)
        
        // Reset and test longitude
        eskf.initialize(initialState, config, null)
        val result2 = eskf.updateWithGNSSPosition(
            TEST_LAT, TEST_LON + smallOffset, GNSS_UNCERTAINTY
        )
        
        assertTrue("GNSS longitude update should affect result longitude",
                  result2.longitude > baseResult.longitude)
        
        println("MEASUREMENT JACOBIAN TEST:")
        println("  H matrix structure verified through measurement response")
        println("  Position measurements correctly affect position states")
        
        println("✅ Measurement Jacobian test passed")
    }
    
    /**
     * Test 7: Numerical stability under various GNSS conditions.
     */
    @Test
    fun testNumericalStabilityWithGNSS() {
        val config = createTestConfiguration()
        val eskf = ESKFImpl(config)
        val initialState = createInitialState()
        
        eskf.initialize(initialState, config, null)
        
        val testScenarios = listOf(
            Triple("High Accuracy GNSS", 1.0, 0.0001),      // 1m accuracy, small offset
            Triple("Low Accuracy GNSS", 20.0, 0.0005),     // 20m accuracy, larger offset
            Triple("Very High Accuracy", 0.1, 0.00005),    // 10cm accuracy, tiny offset
            Triple("Poor GNSS", 50.0, 0.001)               // 50m accuracy, large offset
        )
        
        for ((scenarioName, gnssAccuracy, positionOffset) in testScenarios) {
            // Reset for each scenario
            eskf.initialize(initialState, config, null)
            
            // Build up some uncertainty
            val imuMeasurement = ImuMeasurement(
                timestampMs = 1000L,
                accelerationX = 1.0,
                accelerationY = 0.5,
                accelerationZ = GRAVITY,
                angularVelocityX = 0.0,
                angularVelocityY = 0.0,
                angularVelocityZ = 0.02
            )
            
            repeat(10) { step ->
                eskf.predict(imuMeasurement.copy(timestampMs = 1000L + step * 100L), 0.1)
            }
            
            // Apply GNSS update
            val result = eskf.updateWithGNSSPosition(
                TEST_LAT + positionOffset,
                TEST_LON + positionOffset,
                gnssAccuracy
            )
            
            val covDiagonal = eskf.getCovarianceDiagonal()
            
            // Verify numerical stability
            assertTrue("$scenarioName result should be valid", result.isValid)
            assertTrue("$scenarioName covariance should be finite", 
                      covDiagonal.all { it.isFinite() && it >= 0.0 })
            
            val maxVariance = covDiagonal.maxOrNull() ?: 0.0
            assertTrue("$scenarioName maximum variance should be reasonable", maxVariance < 1e6)
            
            println("  $scenarioName: Max variance=${"%.3e".format(maxVariance)}, Valid=${result.isValid}")
        }
        
        println("NUMERICAL STABILITY WITH GNSS TEST:")
        println("✅ Numerical stability test passed")
    }
}