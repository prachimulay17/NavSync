package com.example.navsync.eskf

import com.example.navsync.model.NavigationState
import com.example.navsync.model.NavigationSource
import org.junit.Test
import org.junit.Assert.*
import kotlin.math.*

/**
 * Validation test for ESKF Phase 2 using realistic IMU data patterns.
 * Tests nominal-state prediction against synthetic but realistic data based on S-Vw9 characteristics.
 */
class ESKFValidationTest {
    
    companion object {
        private const val TOLERANCE = 1e-6
        private const val GRAVITY = 9.80665
        
        // S-Vw9 dataset location (Birmingham, UK area)
        private const val DATASET_LAT = 52.202805
        private const val DATASET_LON = -2.2002
        private const val DATASET_ALT = 117.75
    }
    
    /**
     * Validate ESKF prediction using realistic S-Vw9 data patterns.
     * Tests prediction stability and behavior with actual dataset characteristics.
     */
    @Test
    fun testESKFPredictionWithRealisticSVw9Data() {
        val config = ESKFConfiguration.navSync()
        val eskf = ESKFImpl(config)
        
        // Initial state from S-Vw9 dataset start
        val initialState = NavigationState(
            latitude = DATASET_LAT,
            longitude = DATASET_LON,
            speedKmh = 6.19,  // From dataset GPS speed
            headingDegrees = 262.19,  // From dataset GPS orientation
            confidence = 1.0,
            gnssAvailable = true,
            source = NavigationSource.GNSS
        )
        
        // Initialize ESKF
        eskf.initialize(initialState, config, null)
        
        val results = mutableListOf<ValidationResult>()
        var lastTimestamp = 0L
        var sampleCount = 0
        val maxSamples = 100  // ~10 seconds at 10Hz
        
        try {
            // Load realistic S-Vw9 sample data
            val sampleData = generateRealisticS_Vw9Data(maxSamples)
            
            for (sample in sampleData) {
                if (lastTimestamp == 0L) {
                    lastTimestamp = sample.timestampMs
                    continue
                }
                
                val deltaTime = (sample.timestampMs - lastTimestamp) / 1000.0
                if (deltaTime <= 0 || deltaTime > 1.0) {
                    continue // Skip invalid time deltas
                }
                
                // Create IMU measurement from sample data 
                val imuMeasurement = ImuMeasurement(
                    timestampMs = sample.timestampMs,
                    accelerationX = sample.accelerationX - sample.gravityX,  // Gravity compensated
                    accelerationY = sample.accelerationY - sample.gravityY,
                    accelerationZ = sample.accelerationZ - sample.gravityZ,
                    angularVelocityX = sample.gyroRoll,   // Roll rate -> X
                    angularVelocityY = sample.gyroPitch,  // Pitch rate -> Y  
                    angularVelocityZ = sample.gyroYaw     // Yaw rate -> Z
                )
                
                // Perform ESKF prediction
                val result = eskf.predict(imuMeasurement, deltaTime)
                
                // Store validation result
                results.add(ValidationResult(
                    timestampMs = sample.timestampMs,
                    referencePosition = GeoPosition(sample.gpsLatitude ?: DATASET_LAT, sample.gpsLongitude ?: DATASET_LON),
                    eskfResult = result,
                    deltaTime = deltaTime,
                    imuMagnitudes = ImuMagnitudes(
                        accelMagnitude = sqrt(sample.accelerationX*sample.accelerationX + 
                                            sample.accelerationY*sample.accelerationY + 
                                            sample.accelerationZ*sample.accelerationZ),
                        gyroMagnitude = sqrt(sample.gyroYaw*sample.gyroYaw + 
                                           sample.gyroPitch*sample.gyroPitch + 
                                           sample.gyroRoll*sample.gyroRoll),
                        gravityMagnitude = sqrt(sample.gravityX*sample.gravityX + 
                                              sample.gravityY*sample.gravityY + 
                                              sample.gravityZ*sample.gravityZ)
                    )
                ))
                
                lastTimestamp = sample.timestampMs
                sampleCount++
                
                // Verify basic stability
                assertTrue("Result should be valid at sample $sampleCount", result.isValid)
                assertTrue("Latitude should be finite", result.latitude.isFinite())
                assertTrue("Longitude should be finite", result.longitude.isFinite())
                assertTrue("All velocities should be finite", 
                          result.velocityNorth.isFinite() && 
                          result.velocityEast.isFinite() && 
                          result.velocityDown.isFinite())
            }
            
            // Analyze results
            analyzeValidationResults(results)
        } catch (e: Exception) {
            println("Validation failed with exception: ${e.message}")
            e.printStackTrace()
            fail("Validation should complete without exceptions")
        }
    }
    
    /**
     * Test ESKF with actual S-Vw9 data pattern reproduction.
     */
    @Test
    fun testESKFWithActualS_Vw9Patterns() {
        val config = ESKFConfiguration.navSync()
        val eskf = ESKFImpl(config)
        
        // Use actual S-Vw9 starting conditions  
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
        
        // Test with actual S-Vw9 sample characteristics
        val actualSamples = listOf(
            // Sample 1 from S-Vw9 (first valid sample)
            ActualS_Vw9Sample(
                timestampMs = 13716809L,
                accelXYZ = Triple(-0.0646, -1.2997, 9.5197),
                gravityXYZ = Triple(-0.0138, -0.0114, 9.8066),
                gyroYPR = Triple(-0.051, 0.1903, -0.1151)
            ),
            // Sample 2
            ActualS_Vw9Sample(
                timestampMs = 13716910L,
                accelXYZ = Triple(-0.1217, -0.3621, 10.5772),
                gravityXYZ = Triple(0.0072, 0.0055, 9.8066), 
                gyroYPR = Triple(0.078, 0.0334, -0.0201)
            ),
            // Sample 3
            ActualS_Vw9Sample(
                timestampMs = 13717010L,
                accelXYZ = Triple(-0.3755, -2.1702, 9.9273),
                gravityXYZ = Triple(0.0105, -0.0079, 9.8065),
                gyroYPR = Triple(-0.0849, 0.3813, -0.2611)
            ),
            // Sample 4 - more dynamic motion
            ActualS_Vw9Sample(
                timestampMs = 13717110L,
                accelXYZ = Triple(1.3594, -3.9958, 8.6715),
                gravityXYZ = Triple(0.0409, -0.0152, 9.8065),
                gyroYPR = Triple(-0.0735, 0.1976, 0.0121)
            ),
            // Sample 5
            ActualS_Vw9Sample(
                timestampMs = 13717210L,
                accelXYZ = Triple(0.5774, -2.2799, 9.7731),
                gravityXYZ = Triple(0.0105, 0.0284, 9.8065),
                gyroYPR = Triple(-0.0837, 0.2093, -0.0393)
            )
        )
        
        val results = mutableListOf<ValidationResult>()
        
        for (i in 1 until actualSamples.size) {
            val prevSample = actualSamples[i-1]
            val currSample = actualSamples[i]
            
            val deltaTime = (currSample.timestampMs - prevSample.timestampMs) / 1000.0
            
            // Create IMU measurement with gravity compensation
            val imuMeasurement = ImuMeasurement(
                timestampMs = currSample.timestampMs,
                accelerationX = currSample.accelXYZ.first - currSample.gravityXYZ.first,
                accelerationY = currSample.accelXYZ.second - currSample.gravityXYZ.second,
                accelerationZ = currSample.accelXYZ.third - currSample.gravityXYZ.third,
                angularVelocityX = currSample.gyroYPR.third,   // Roll -> X
                angularVelocityY = currSample.gyroYPR.second,  // Pitch -> Y
                angularVelocityZ = currSample.gyroYPR.first    // Yaw -> Z
            )
            
            val result = eskf.predict(imuMeasurement, deltaTime)
            
            results.add(ValidationResult(
                timestampMs = currSample.timestampMs,
                referencePosition = GeoPosition(DATASET_LAT, DATASET_LON),
                eskfResult = result,
                deltaTime = deltaTime,
                imuMagnitudes = ImuMagnitudes(
                    accelMagnitude = sqrt(currSample.accelXYZ.let { it.first*it.first + it.second*it.second + it.third*it.third }),
                    gyroMagnitude = sqrt(currSample.gyroYPR.let { it.first*it.first + it.second*it.second + it.third*it.third }),
                    gravityMagnitude = sqrt(currSample.gravityXYZ.let { it.first*it.first + it.second*it.second + it.third*it.third })
                )
            ))
            
            // Verify each prediction
            assertTrue("Sample $i should produce valid result", result.isValid)
            assertTrue("Latitude should be finite", result.latitude.isFinite())
            assertTrue("Longitude should be finite", result.longitude.isFinite())
        }
        
        analyzeActualS_Vw9Results(results, actualSamples)
    }
    
    /**
     * Test ESKF numerical stability under challenging conditions.
     */
    @Test 
    fun testESKFNumericalStability() {
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
        
        val results = mutableListOf<ValidationResult>()
        val dt = 0.1  // 10Hz sampling
        var timestamp = 1000L
        
        // Simulate 30 seconds of vehicle motion
        for (step in 0..299) {
            val timeSeconds = step * dt
            
            // Generate realistic vehicle motion profile
            val motion = generateVehicleMotionProfile(timeSeconds)
            
            val imuMeasurement = ImuMeasurement(
                timestampMs = timestamp,
                accelerationX = motion.accelX,
                accelerationY = motion.accelY, 
                accelerationZ = motion.accelZ + GRAVITY,  // Add gravity
                angularVelocityX = motion.gyroX,
                angularVelocityY = motion.gyroY,
                angularVelocityZ = motion.gyroZ
            )
            
            val result = eskf.predict(imuMeasurement, dt)
            
            results.add(ValidationResult(
                timestampMs = timestamp,
                referencePosition = GeoPosition(DATASET_LAT, DATASET_LON), // Static reference
                eskfResult = result,
                deltaTime = dt,
                imuMagnitudes = ImuMagnitudes(
                    accelMagnitude = sqrt(motion.accelX*motion.accelX + motion.accelY*motion.accelY + motion.accelZ*motion.accelZ),
                    gyroMagnitude = sqrt(motion.gyroX*motion.gyroX + motion.gyroY*motion.gyroY + motion.gyroZ*motion.gyroZ),
                    gravityMagnitude = GRAVITY
                )
            ))
            
            timestamp += 100L
            
            // Basic stability checks
            assertTrue("Result should be valid at step $step", result.isValid)
            assertTrue("Position should not drift excessively", 
                      abs(result.latitude - DATASET_LAT) < 0.01)  // Max ~1km drift
            assertTrue("Velocity should be reasonable", 
                      sqrt(result.velocityNorth*result.velocityNorth + result.velocityEast*result.velocityEast) < 50.0)  // Max 50 m/s
        }
        
        analyzeSyntheticValidationResults(results)
    }
    
    /**
     * Generate realistic data based on actual S-Vw9 samples from dataset verification.
     */
    private fun generateRealisticS_Vw9Data(maxSamples: Int): List<MockSensorData> {
        val samples = mutableListOf<MockSensorData>()
        var timestamp = 13716809L  // Actual S-Vw9 start timestamp
        
        for (i in 0 until maxSamples) {
            // Use actual S-Vw9 sample characteristics with some variation
            val baseAccelX = -0.0646 + (Math.random() - 0.5) * 0.1
            val baseAccelY = -1.2997 + (Math.random() - 0.5) * 0.2  
            val baseAccelZ = 9.5197 + (Math.random() - 0.5) * 0.1
            
            val baseGyroYaw = -0.051 + (Math.random() - 0.5) * 0.02
            val baseGyroPitch = 0.1903 + (Math.random() - 0.5) * 0.05
            val baseGyroRoll = -0.1151 + (Math.random() - 0.5) * 0.02
            
            samples.add(MockSensorData(
                timestampMs = timestamp + i * 100L,  // 10Hz
                accelerationX = baseAccelX,
                accelerationY = baseAccelY,
                accelerationZ = baseAccelZ,
                gravityX = -0.0138,  // Actual S-Vw9 gravity
                gravityY = -0.0114,
                gravityZ = 9.8066,
                gyroYaw = baseGyroYaw,
                gyroPitch = baseGyroPitch, 
                gyroRoll = baseGyroRoll,
                gpsLatitude = DATASET_LAT + (Math.random() - 0.5) * 0.0001,  // Small GPS variation
                gpsLongitude = DATASET_LON + (Math.random() - 0.5) * 0.0001
            ))
        }
        
        return samples
    }
    
    /**
     * Generate vehicle motion profile for synthetic testing.
     */
    private fun generateVehicleMotionProfile(timeSeconds: Double): VehicleMotion {
        // Simulate various driving maneuvers
        return when {
            timeSeconds < 5.0 -> {
                // Acceleration phase
                VehicleMotion(
                    accelX = 2.0 * sin(timeSeconds * 0.5),  // Forward acceleration
                    accelY = 0.1 * sin(timeSeconds * 2.0),  // Slight lateral
                    accelZ = 0.0,
                    gyroX = 0.0,
                    gyroY = 0.0, 
                    gyroZ = 0.02 * sin(timeSeconds)  // Slight yaw
                )
            }
            timeSeconds < 15.0 -> {
                // Steady driving with turn
                VehicleMotion(
                    accelX = 0.5 * sin(timeSeconds * 0.2),
                    accelY = 1.5 * sin((timeSeconds - 5.0) * 0.3),  // Cornering
                    accelZ = 0.0,
                    gyroX = 0.0,
                    gyroY = 0.0,
                    gyroZ = 0.3 * sin((timeSeconds - 5.0) * 0.3)  // Turning
                )
            }
            else -> {
                // Deceleration phase
                VehicleMotion(
                    accelX = -1.5 * exp(-(timeSeconds - 15.0) * 0.5),  // Braking
                    accelY = 0.1 * sin(timeSeconds * 1.5),
                    accelZ = 0.0,
                    gyroX = 0.0,
                    gyroY = 0.0,
                    gyroZ = 0.1 * sin(timeSeconds * 0.8)
                )
            }
        }
    }
    
    /**
     * Analyze results specifically from actual S-Vw9 samples.
     */
    private fun analyzeActualS_Vw9Results(results: List<ValidationResult>, samples: List<ActualS_Vw9Sample>) {
        println("=== ESKF Validation with Actual S-Vw9 Patterns ===")
        
        if (results.isEmpty()) {
            println("No results to analyze")
            return
        }
        
        // 1. COORDINATE FRAME VALIDATION
        val gravityMagnitudes = samples.map { sample ->
            sqrt(sample.gravityXYZ.let { it.first*it.first + it.second*it.second + it.third*it.third })
        }
        println("COORDINATE FRAME VERIFICATION:")
        println("  Gravity magnitude range: ${"%.4f".format(gravityMagnitudes.minOrNull())} - ${"%.4f".format(gravityMagnitudes.maxOrNull())} m/s²")
        println("  Expected ~9.8066: ${gravityMagnitudes.all { abs(it - 9.8066) < 0.001 }}")
        
        // 2. IMU CHARACTERISTICS FROM REAL DATA
        val motionAccels = samples.map { sample ->
            Triple(
                sample.accelXYZ.first - sample.gravityXYZ.first,
                sample.accelXYZ.second - sample.gravityXYZ.second, 
                sample.accelXYZ.third - sample.gravityXYZ.third
            )
        }
        
        println("REAL IMU CHARACTERISTICS:")
        val maxMotionAccel = motionAccels.map { sqrt(it.first*it.first + it.second*it.second + it.third*it.third) }.maxOrNull() ?: 0.0
        val maxGyroRate = samples.map { sample -> 
            sqrt(sample.gyroYPR.let { it.first*it.first + it.second*it.second + it.third*it.third })
        }.maxOrNull() ?: 0.0
        
        println("  Max motion acceleration: ${"%.2f".format(maxMotionAccel)} m/s²")
        println("  Max angular rate: ${"%.4f".format(maxGyroRate)} rad/s (${"%.1f".format(Math.toDegrees(maxGyroRate))} °/s)")
        
        // 3. ESKF PREDICTION BEHAVIOR
        analyzeValidationResults(results)
        
        // 4. SPECIFIC S-VW9 ASSESSMENT
        println("S-VW9 SPECIFIC ASSESSMENT:")
        val timeSpan = (samples.last().timestampMs - samples.first().timestampMs) / 1000.0
        println("  Time span: ${"%.2f".format(timeSpan)} seconds")
        println("  Sample rate: ${"%.1f".format((samples.size - 1) / timeSpan)} Hz")
        
        val finalResult = results.last()
        val positionChange_m = (finalResult.eskfResult.latitude - DATASET_LAT) * 111320.0
        println("  Final position change: ${"%.2f".format(positionChange_m)} m")
        println("  Final velocity magnitude: ${"%.2f".format(sqrt(finalResult.eskfResult.velocityNorth*finalResult.eskfResult.velocityNorth + finalResult.eskfResult.velocityEast*finalResult.eskfResult.velocityEast))} m/s")
        
        // Check if behavior is reasonable for vehicle motion
        val reasonable = positionChange_m < 10.0 && // Less than 10m drift in ~0.5s
                        maxMotionAccel < 20.0 && // Reasonable vehicle acceleration
                        maxGyroRate < 0.5 // Reasonable turn rates
        
        if (reasonable) {
            println("  ✅ ASSESSMENT: ESKF behavior consistent with realistic vehicle motion")
        } else {
            println("  ⚠️  ASSESSMENT: Check for potential issues in coordinate mapping or integration")
        }
    }
    
    /**
     * Analyze validation results from realistic data.
     */
    private fun analyzeValidationResults(results: List<ValidationResult>) {
        if (results.isEmpty()) {
            println("No results to analyze")
            return
        }
        
        println("=== ESKF Validation Analysis (${results.size} samples) ===")
        
        // 1. TRAJECTORY ANALYSIS
        val positionDrifts = results.map { result ->
            val lat_m = (result.eskfResult.latitude - DATASET_LAT) * 111320.0
            val lon_m = (result.eskfResult.longitude - DATASET_LON) * 111320.0 * cos(Math.toRadians(DATASET_LAT))
            sqrt(lat_m * lat_m + lon_m * lon_m)
        }
        
        println("TRAJECTORY BEHAVIOR:")
        println("  Final position drift: ${"%.2f".format(positionDrifts.lastOrNull() ?: 0.0)} m")
        println("  Max position drift: ${"%.2f".format(positionDrifts.maxOrNull() ?: 0.0)} m")
        println("  RMS position drift: ${"%.2f".format(sqrt(positionDrifts.map { it * it }.average()))} m")
        
        // 2. VELOCITY ANALYSIS  
        val speeds = results.map { result ->
            sqrt(result.eskfResult.velocityNorth * result.eskfResult.velocityNorth + 
                 result.eskfResult.velocityEast * result.eskfResult.velocityEast)
        }
        
        println("VELOCITY BEHAVIOR:")
        println("  Final speed: ${"%.2f".format(speeds.lastOrNull() ?: 0.0)} m/s")
        println("  Max speed: ${"%.2f".format(speeds.maxOrNull() ?: 0.0)} m/s")
        println("  Average speed: ${"%.2f".format(speeds.average())} m/s")
        
        // 3. ATTITUDE ANALYSIS
        val headingChanges = results.windowed(2).map { (prev, curr) ->
            val change = curr.eskfResult.yawDegrees - prev.eskfResult.yawDegrees
            if (change > 180) change - 360 else if (change < -180) change + 360 else change
        }
        
        println("ATTITUDE BEHAVIOR:")
        println("  Final heading: ${"%.1f".format(results.last().eskfResult.yawDegrees)}°")
        println("  Max heading change/step: ${"%.1f".format(headingChanges.maxByOrNull { abs(it) } ?: 0.0)}°")
        println("  Total heading change: ${"%.1f".format(headingChanges.sum())}°")
        
        // 4. NUMERICAL STABILITY
        val validResults = results.count { it.eskfResult.isValid }
        val finitePositions = results.count { 
            it.eskfResult.latitude.isFinite() && it.eskfResult.longitude.isFinite() 
        }
        
        println("NUMERICAL STABILITY:")
        println("  Valid results: $validResults / ${results.size} (${"%.1f".format(100.0 * validResults / results.size)}%)")
        println("  Finite positions: $finitePositions / ${results.size}")
        println("  Average confidence: ${"%.3f".format(results.map { it.eskfResult.confidence }.average())}")
        
        // 5. IMU CHARACTERISTICS
        val avgAccelMag = results.map { it.imuMagnitudes.accelMagnitude }.average()
        val avgGyroMag = results.map { it.imuMagnitudes.gyroMagnitude }.average()
        val avgGravityMag = results.map { it.imuMagnitudes.gravityMagnitude }.average()
        
        println("IMU CHARACTERISTICS:")
        println("  Average accel magnitude: ${"%.2f".format(avgAccelMag)} m/s²")
        println("  Average gyro magnitude: ${"%.4f".format(avgGyroMag)} rad/s")  
        println("  Average gravity magnitude: ${"%.2f".format(avgGravityMag)} m/s²")
        
        // 6. ASSESSMENT
        println("ASSESSMENT:")
        val driftRate = (positionDrifts.lastOrNull() ?: 0.0) / (results.size * 0.1)  // m/s
        println("  Position drift rate: ${"%.2f".format(driftRate)} m/s")
        
        if (driftRate < 1.0) {
            println("  ✅ GOOD: Low drift rate")
        } else if (driftRate < 5.0) {
            println("  ⚠️  MODERATE: Acceptable drift for dead reckoning")
        } else {
            println("  ❌ HIGH: Excessive drift - check bias estimation or coordinate frames")
        }
        
        if (validResults == results.size) {
            println("  ✅ GOOD: All predictions numerically stable")
        } else {
            println("  ❌ ISSUE: Some predictions failed - check error handling")
        }
    }
    
    /**
     * Analyze synthetic validation results.
     */
    private fun analyzeSyntheticValidationResults(results: List<ValidationResult>) {
        println("=== ESKF Synthetic Validation Analysis ===")
        
        val finalResult = results.last()
        val totalTime = results.size * 0.1
        
        println("SYNTHETIC TEST RESULTS:")
        println("  Duration: ${"%.1f".format(totalTime)} seconds")
        println("  Final position drift: ${"%.1f".format((finalResult.eskfResult.latitude - DATASET_LAT) * 111320)} m")
        println("  Final speed: ${"%.1f".format(sqrt(finalResult.eskfResult.velocityNorth*finalResult.eskfResult.velocityNorth + finalResult.eskfResult.velocityEast*finalResult.eskfResult.velocityEast))} m/s")
        println("  All predictions valid: ${results.all { it.eskfResult.isValid }}")
    }
    
    // Data classes for validation
    data class ActualS_Vw9Sample(
        val timestampMs: Long,
        val accelXYZ: Triple<Double, Double, Double>,
        val gravityXYZ: Triple<Double, Double, Double>,
        val gyroYPR: Triple<Double, Double, Double>  // Yaw, Pitch, Roll
    )
    
    data class ValidationResult(
        val timestampMs: Long,
        val referencePosition: GeoPosition,
        val eskfResult: ESKFResult,
        val deltaTime: Double,
        val imuMagnitudes: ImuMagnitudes
    )
    
    data class GeoPosition(val latitude: Double, val longitude: Double)
    
    data class ImuMagnitudes(
        val accelMagnitude: Double,
        val gyroMagnitude: Double, 
        val gravityMagnitude: Double
    )
    
    data class VehicleMotion(
        val accelX: Double, val accelY: Double, val accelZ: Double,
        val gyroX: Double, val gyroY: Double, val gyroZ: Double
    )
    
    data class MockSensorData(
        val timestampMs: Long,
        val accelerationX: Double, val accelerationY: Double, val accelerationZ: Double,
        val gravityX: Double, val gravityY: Double, val gravityZ: Double,
        val gyroYaw: Double, val gyroPitch: Double, val gyroRoll: Double,
        val gpsLatitude: Double?, val gpsLongitude: Double?
    )
}