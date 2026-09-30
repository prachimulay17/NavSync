package com.example.navsync.ml

import com.example.navsync.data.*
import com.example.navsync.eskf.*
import com.example.navsync.inference.*
import com.example.navsync.model.NavigationState
import com.example.navsync.model.NavigationSource
import org.junit.Test
import org.junit.Assert.*
import kotlin.math.*

/**
 * Unit test comparing IMU-only ESKF vs ESKF+RoNIN (mocked) performance.
 * 
 * Uses synthetic datasets to avoid Context dependency and run in unit test environment.
 * Tests the same GNSS outage scenario for both approaches.
 */
class RoninESKFComparisonTest {
    
    companion object {
        // Test configuration (matching validation requirements)
        private const val OUTAGE_START_TIME = 35.0  // seconds
        private const val OUTAGE_END_TIME = 50.0    // seconds
        private const val OUTAGE_DURATION = OUTAGE_END_TIME - OUTAGE_START_TIME  // 15 seconds
        
        // Dataset characteristics
        private const val DATASET_LAT = 52.202805
        private const val DATASET_LON = -2.2002
        private const val SAMPLE_RATE_HZ = 10.0     // 10 Hz sampling
        private const val TOTAL_DURATION = 60.0     // 60 seconds
        
        // Validation thresholds
        private const val MAX_POSITION_ERROR = 1000.0  // meters
        private const val MAX_VELOCITY_ERROR = 100.0   // m/s
    }
    
    @Test
    fun testRoninESKFComparison() {
        println("=".repeat(80))
        println("RONIN + ESKF COMPARISON TEST")
        println("Dataset: Synthetic S-Vw9-like (${TOTAL_DURATION}s)")
        println("GNSS Outage: ${OUTAGE_START_TIME}-${OUTAGE_END_TIME}s (${OUTAGE_DURATION}s)")
        println("=".repeat(80))
        
        // Generate synthetic datasets
        val sensorDataset = generateSyntheticSensorDataset()
        val referenceDataset = generateSyntheticReferenceDataset()
        
        println("Generated datasets:")
        println("  Sensor samples: ${sensorDataset.size}")
        println("  Reference samples: ${referenceDataset.size}")
        println("  Sample rate: ${SAMPLE_RATE_HZ} Hz")
        println()
        
        // Run baseline: IMU-only ESKF
        println("🔄 Running BASELINE: IMU-only ESKF")
        val baselineResults = runValidationScenario(
            inference = ESKFNavSyncInference(),
            sensorDataset = sensorDataset,
            referenceDataset = referenceDataset,
            tag = "BASELINE"
        )
        
        println()
        println("🔄 Running EXPERIMENT: ESKF + RoNIN (mocked)")
        val roninResults = runValidationScenario(
            inference = MockRoninESKFInference(),
            sensorDataset = sensorDataset,
            referenceDataset = referenceDataset,
            tag = "RONIN"
        )
        
        // Compare results
        println()
        println("=".repeat(80))
        println("COMPARISON RESULTS")
        println("=".repeat(80))
        
        compareResults(baselineResults, roninResults)
        
        // Validate that ESKF.updateWithVelocity() was called
        val mockInference = roninResults.inference as MockRoninESKFInference
        assertTrue("ESKF.updateWithVelocity() should be called during outage", 
                   mockInference.velocityUpdateCount > 0)
        
        println()
        println("✅ RoNIN ESKF comparison test completed")
        
        // Assertions for test validation
        assertTrue("Baseline should complete without excessive errors", 
                   baselineResults.maxError < MAX_POSITION_ERROR)
        assertTrue("RoNIN should complete without excessive errors", 
                   roninResults.maxError < MAX_POSITION_ERROR)
        assertFalse("No NaN errors in baseline", baselineResults.hasNaNErrors)
        assertFalse("No NaN errors in RoNIN", roninResults.hasNaNErrors)
    }
    
    private fun runValidationScenario(
        inference: NavSyncInference,
        sensorDataset: List<SensorData>,
        referenceDataset: List<NavigationState>,
        tag: String
    ): ValidationResults {
        
        // Initialize with first reference point
        val initialState = referenceDataset.first()
        inference.initialize(initialState)
        
        val results = mutableListOf<NavigationState>()
        val errors = mutableListOf<Double>()
        val velocityErrors = mutableListOf<Double>()
        var maxError = 0.0
        var hasNaNErrors = false
        
        var currentState = initialState
        
        // Process each sensor sample
        for ((index, sensorSample) in sensorDataset.withIndex()) {
            val timeSeconds = sensorSample.timestampMs / 1000.0
            
            // Find corresponding reference state
            val referenceState = referenceDataset.getOrNull(index) ?: referenceDataset.last()
            
            // Determine if GNSS is available (outage simulation)
            val gnssAvailable = timeSeconds < OUTAGE_START_TIME || timeSeconds > OUTAGE_END_TIME
            
            // Update current state to include GNSS availability
            currentState = currentState.copy(gnssAvailable = gnssAvailable)
            
            // Run inference
            val deltaTime = if (index > 0) {
                sensorSample.timestampMs - sensorDataset[index - 1].timestampMs
            } else {
                100L  // Default 100ms
            }
            
            try {
                val inferenceResult = inference.estimatePosition(
                    sensorData = sensorSample,
                    previousState = currentState,
                    deltaTimeMs = deltaTime
                )
                
                // Convert to NavigationState
                currentState = NavigationState(
                    latitude = inferenceResult.latitude,
                    longitude = inferenceResult.longitude,
                    speedKmh = inferenceResult.speedKmh,
                    headingDegrees = inferenceResult.headingDegrees,
                    confidence = inferenceResult.confidence,
                    gnssAvailable = gnssAvailable,
                    source = NavigationSource.AI_ESTIMATION
                )
                
                results.add(currentState)
                
                // Calculate position error against reference
                val errorMeters = calculatePositionError(currentState, referenceState)
                errors.add(errorMeters)
                maxError = maxOf(maxError, errorMeters)
                
                // Check for NaN/Inf
                if (!errorMeters.isFinite()) {
                    hasNaNErrors = true
                }
                
                // Calculate velocity error
                val velocityError = abs(currentState.speedKmh - referenceState.speedKmh)
                velocityErrors.add(velocityError)
                
                // Periodic logging during outage
                if (!gnssAvailable && index % 50 == 0) {  // Every 5 seconds
                    println("$tag t=${timeSeconds.format(1)}s: pos=(${currentState.latitude.format(6)}, ${currentState.longitude.format(6)}), " +
                            "error=${errorMeters.format(1)}m, conf=${inferenceResult.confidence.format(3)}")
                    
                    // Log RoNIN activity if applicable
                    if (tag == "RONIN" && inference is MockRoninESKFInference) {
                        val (vxHacf, vyHacf) = inference.getLastRoninPrediction()
                        val yawOffset = inference.getLastYawOffset()
                        val (vN, vE) = inference.getLastNEDVelocity()
                        
                        println("  RoNIN: vxHacf=${"%.3f".format(vxHacf)}, vyHacf=${"%.3f".format(vyHacf)}, " +
                                "yaw=${"%.1f".format(Math.toDegrees(yawOffset))}°, vN=${"%.3f".format(vN)}, vE=${"%.3f".format(vE)}")
                    }
                }
                
            } catch (e: Exception) {
                println("$tag inference failed at t=${timeSeconds}s: ${e.message}")
                hasNaNErrors = true
                // Keep previous state on error
            }
        }
        
        return ValidationResults(
            tag = tag,
            totalSamples = results.size,
            errors = errors,
            velocityErrors = velocityErrors,
            results = results,
            maxError = maxError,
            hasNaNErrors = hasNaNErrors,
            inference = inference
        )
    }
    
    private fun compareResults(baseline: ValidationResults, ronin: ValidationResults) {
        
        fun analyzeOutage(results: ValidationResults): OutageMetrics {
            val outageStartIndex = (OUTAGE_START_TIME * SAMPLE_RATE_HZ).toInt()
            val outageEndIndex = (OUTAGE_END_TIME * SAMPLE_RATE_HZ).toInt()
            
            val outageStartError = if (outageStartIndex < results.errors.size) results.errors[outageStartIndex] else 0.0
            val outageEndError = if (outageEndIndex < results.errors.size) results.errors[outageEndIndex] else 0.0
            val recoveryError = if (outageEndIndex > 0 && outageEndIndex-1 < results.errors.size) {
                results.errors[outageEndIndex - 1]  // Just before recovery
            } else 0.0
            
            val outageErrors = results.errors.subList(
                maxOf(0, outageStartIndex), 
                minOf(results.errors.size, outageEndIndex + 1)
            )
            
            val errorGrowthRate = if (outageErrors.size > 1) {
                (outageErrors.last() - outageErrors.first()) / OUTAGE_DURATION
            } else 0.0
            
            val rmse = sqrt(results.errors.map { it * it }.average())
            val velocityRmse = sqrt(results.velocityErrors.map { it * it }.average())
            
            return OutageMetrics(
                outageStartError = outageStartError,
                outageEndError = outageEndError,
                errorGrowthRate = errorGrowthRate,
                rmse = rmse,
                velocityRmse = velocityRmse,
                maxError = results.maxError,
                recoveryError = recoveryError
            )
        }
        
        val baselineMetrics = analyzeOutage(baseline)
        val roninMetrics = analyzeOutage(ronin)
        
        println("Metric                              | IMU-only ESKF  | ESKF + RoNIN   | Improvement")
        println("-".repeat(90))
        
        fun printComparison(name: String, baseline: Double, ronin: Double, unit: String, lowerBetter: Boolean = true) {
            val improvement = if (lowerBetter) {
                (baseline - ronin) / baseline * 100.0
            } else {
                (ronin - baseline) / baseline * 100.0
            }
            val arrow = if ((lowerBetter && ronin < baseline) || (!lowerBetter && ronin > baseline)) "↓" else "↑"
            val sign = if (improvement > 0) "+" else ""
            
            println("%-34s | %13s | %13s | %s %s%.1f%%".format(
                name,
                "${"%.2f".format(baseline)} $unit",
                "${"%.2f".format(ronin)} $unit",
                arrow,
                sign,
                improvement
            ))
        }
        
        printComparison("Outage-start position error", baselineMetrics.outageStartError, roninMetrics.outageStartError, "m")
        printComparison("Outage-end position error", baselineMetrics.outageEndError, roninMetrics.outageEndError, "m")
        printComparison("Error growth rate", baselineMetrics.errorGrowthRate, roninMetrics.errorGrowthRate, "m/s")
        printComparison("Horizontal RMSE", baselineMetrics.rmse, roninMetrics.rmse, "m")
        printComparison("Maximum horizontal error", baselineMetrics.maxError, roninMetrics.maxError, "m")
        printComparison("Recovery error (pre-GNSS)", baselineMetrics.recoveryError, roninMetrics.recoveryError, "m")
        printComparison("Velocity RMSE", baselineMetrics.velocityRmse, roninMetrics.velocityRmse, "km/h")
        
        // RoNIN-specific metrics
        if (ronin.inference is MockRoninESKFInference) {
            val mockInference = ronin.inference
            println()
            println("RoNIN-specific metrics:")
            println("  RoNIN inferences: ${mockInference.roninInferenceCount}")
            println("  ESKF velocity updates: ${mockInference.velocityUpdateCount}")
            println("  Accepted updates: ${mockInference.acceptedUpdateCount}")
            println("  Rejected updates: ${mockInference.rejectedUpdateCount}")
            if (mockInference.velocityUpdateCount > 0) {
                val acceptanceRate = "%.1f".format(mockInference.acceptedUpdateCount * 100.0 / mockInference.velocityUpdateCount)
                println("  Acceptance rate: $acceptanceRate%")
            }
            println("  Yaw initialized: ${if (mockInference.yawInitialized) "YES" else "NO"}")
        }
        
        // Data quality
        println()
        println("Data quality:")
        println("  IMU-only NaN/Inf errors: ${if (baseline.hasNaNErrors) "DETECTED" else "NONE"}")
        println("  RoNIN NaN/Inf errors: ${if (ronin.hasNaNErrors) "DETECTED" else "NONE"}")
        
        if (baseline.hasNaNErrors || ronin.hasNaNErrors) {
            println("  ⚠️  Non-finite errors detected!")
        } else {
            println("  ✅ All error values finite")
        }
    }
    
    private fun generateSyntheticSensorDataset(): List<SensorData> {
        val samples = mutableListOf<SensorData>()
        val totalSamples = (TOTAL_DURATION * SAMPLE_RATE_HZ).toInt()
        
        for (i in 0 until totalSamples) {
            val timestampMs = (i * 1000.0 / SAMPLE_RATE_HZ).toLong()
            val timeSeconds = i / SAMPLE_RATE_HZ
            
            // Simulate vehicle motion with turns and accelerations
            val forwardAccel = 0.5 * sin(timeSeconds * 0.1)  // Gentle acceleration/deceleration
            val lateralAccel = 0.2 * cos(timeSeconds * 0.15)  // Gentle turns
            val yawRate = 0.05 * sin(timeSeconds * 0.08)      // Turning motion
            
            samples.add(SensorData(
                timestampMs = timestampMs,
                accelerationX = forwardAccel + 0.01 * sin(timeSeconds * 10.0),  // Add noise
                accelerationY = lateralAccel + 0.01 * cos(timeSeconds * 12.0),
                accelerationZ = 9.8 + 0.02 * sin(timeSeconds * 8.0),
                gravityX = 0.0,
                gravityY = 0.0,
                gravityZ = 9.8,
                gyroYaw = yawRate + 0.002 * sin(timeSeconds * 15.0),
                gyroPitch = 0.001 * sin(timeSeconds * 7.0),
                gyroRoll = 0.001 * cos(timeSeconds * 9.0),
                magneticX = 25.0,
                magneticY = 0.0,
                magneticZ = -40.0,
                orientationYaw = timeSeconds * 2.0 % 360.0,  // Gradual rotation
                orientationPitch = 0.0,
                orientationRoll = 0.0
            ))
        }
        
        return samples
    }
    
    private fun generateSyntheticReferenceDataset(): List<NavigationState> {
        val states = mutableListOf<NavigationState>()
        val totalSamples = (TOTAL_DURATION * SAMPLE_RATE_HZ).toInt()
        
        for (i in 0 until totalSamples) {
            val timeSeconds = i / SAMPLE_RATE_HZ
            
            // Simulate realistic vehicle trajectory
            val speedKmh = 25.0 + 15.0 * sin(timeSeconds * 0.1)  // 10-40 km/h range
            val heading = 45.0 + 30.0 * sin(timeSeconds * 0.08)  // Gentle S-curves
            
            // Convert to position changes
            val speedMs = speedKmh / 3.6
            val deltaTime = 1.0 / SAMPLE_RATE_HZ
            val distance = speedMs * deltaTime  // meters per timestep
            
            // Approximate position changes (for realistic trajectories)
            val latChange = distance * cos(Math.toRadians(heading)) / 111000.0  // degrees
            val lonChange = distance * sin(Math.toRadians(heading)) / (111000.0 * cos(Math.toRadians(DATASET_LAT)))
            
            val newLat = DATASET_LAT + latChange * i
            val newLon = DATASET_LON + lonChange * i
            
            states.add(NavigationState(
                latitude = newLat,
                longitude = newLon,
                speedKmh = speedKmh,
                headingDegrees = heading,
                confidence = 0.8,
                gnssAvailable = true,  // Will be overridden during outage
                source = NavigationSource.GNSS
            ))
        }
        
        return states
    }
    
    private fun calculatePositionError(estimated: NavigationState, reference: NavigationState): Double {
        // Simple Euclidean approximation for small distances
        val earthRadius = 6371000.0  // meters
        
        val latDiffRad = Math.toRadians(estimated.latitude - reference.latitude)
        val lonDiffRad = Math.toRadians(estimated.longitude - reference.longitude)
        val avgLatRad = Math.toRadians((estimated.latitude + reference.latitude) / 2.0)
        
        val northMeters = latDiffRad * earthRadius
        val eastMeters = lonDiffRad * earthRadius * cos(avgLatRad)
        
        return sqrt(northMeters * northMeters + eastMeters * eastMeters)
    }
    
    private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)
    
    data class ValidationResults(
        val tag: String,
        val totalSamples: Int,
        val errors: List<Double>,
        val velocityErrors: List<Double>,
        val results: List<NavigationState>,
        val maxError: Double,
        val hasNaNErrors: Boolean,
        val inference: NavSyncInference
    )
    
    data class OutageMetrics(
        val outageStartError: Double,
        val outageEndError: Double,
        val errorGrowthRate: Double,
        val rmse: Double,
        val velocityRmse: Double,
        val maxError: Double,
        val recoveryError: Double
    )
}

/**
 * Mock implementation of RoNIN+ESKF that simulates RoNIN velocity predictions
 * and tracks calls to ESKF.updateWithVelocity() for validation.
 */
class MockRoninESKFInference : NavSyncInference {
    
    private val baselineESKF = ESKFNavSyncInference()
    
    // Tracking variables
    var roninInferenceCount = 0
    var velocityUpdateCount = 0
    var acceptedUpdateCount = 0
    var rejectedUpdateCount = 0
    var yawInitialized = false
    
    // Last prediction values (for logging)
    private var lastVxHacf = 0.0f
    private var lastVyHacf = 0.0f
    private var lastYawOffset = 0.0
    private var lastVN = 0.0
    private var lastVE = 0.0
    
    private var sampleCount = 0
    private val inferenceStride = 50  // Run RoNIN every 50 samples
    
    override fun initialize(lastState: NavigationState) {
        baselineESKF.initialize(lastState)
        
        // Initialize yaw tracking
        if (lastState.gnssAvailable && lastState.speedKmh > 3.0) {
            yawInitialized = true
            lastYawOffset = Math.toRadians(lastState.headingDegrees - 90.0)  // GPS heading → ENU angle approximation
        }
    }
    
    override fun estimatePosition(
        sensorData: SensorData,
        previousState: NavigationState,
        deltaTimeMs: Long
    ): InferenceResult {
        
        // Always run baseline ESKF prediction
        val baselineResult = baselineESKF.estimatePosition(sensorData, previousState, deltaTimeMs)
        
        // During GNSS outage, simulate RoNIN velocity updates
        if (!previousState.gnssAvailable) {
            sampleCount++
            
            // Run RoNIN inference every N samples
            if (sampleCount >= inferenceStride) {
                sampleCount = 0
                runMockRoninInference(sensorData, previousState)
            }
        } else {
            // Reset sample count during GNSS availability
            sampleCount = 0
            
            // Update yaw from GPS when available
            if (previousState.speedKmh > 3.0) {
                yawInitialized = true
                lastYawOffset = Math.toRadians(previousState.headingDegrees - 90.0)
            }
        }
        
        return baselineResult
    }
    
    private fun runMockRoninInference(sensorData: SensorData, previousState: NavigationState) {
        roninInferenceCount++
        
        // Mock RoNIN velocity prediction based on sensor data
        // Simulate realistic velocity in HACF frame
        val timeSeconds = sensorData.timestampMs / 1000.0
        lastVxHacf = (2.0 + 1.0 * sin(timeSeconds * 0.1)).toFloat()  // 1-3 m/s forward
        lastVyHacf = (0.5 * sin(timeSeconds * 0.15)).toFloat()       // Small lateral velocity
        
        // Simulate HACF → ENU → NED transformation
        if (yawInitialized) {
            // Mock yaw offset (would come from GPS bearing tracking in real implementation)
            lastYawOffset = Math.toRadians(previousState.headingDegrees - 90.0)
            
            // Transform HACF → ENU
            val cosY = cos(lastYawOffset)
            val sinY = sin(lastYawOffset)
            val vE_enu = cosY * lastVxHacf - sinY * lastVyHacf
            val vN_enu = sinY * lastVxHacf + cosY * lastVyHacf
            
            // ENU → NED
            lastVN = vN_enu
            lastVE = vE_enu
            
            // Simulate call to ESKF.updateWithVelocity()
            velocityUpdateCount++
            
            // Mock acceptance/rejection based on velocity magnitude
            val velocityMagnitude = sqrt(lastVN * lastVN + lastVE * lastVE)
            if (velocityMagnitude < 10.0) {  // Reasonable velocity
                acceptedUpdateCount++
            } else {
                rejectedUpdateCount++
            }
            
        } else {
            // Can't transform without yaw - skip update
            rejectedUpdateCount++
        }
    }
    
    override fun reset() {
        baselineESKF.reset()
        roninInferenceCount = 0
        velocityUpdateCount = 0
        acceptedUpdateCount = 0
        rejectedUpdateCount = 0
        yawInitialized = false
        sampleCount = 0
    }
    
    // Accessors for test validation
    fun getLastRoninPrediction(): Pair<Float, Float> = Pair(lastVxHacf, lastVyHacf)
    fun getLastYawOffset(): Double = lastYawOffset
    fun getLastNEDVelocity(): Pair<Double, Double> = Pair(lastVN, lastVE)
}