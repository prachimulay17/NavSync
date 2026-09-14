package com.example.navsync.eskf

import com.example.navsync.model.NavigationState
import com.example.navsync.model.NavigationSource
import org.junit.Test
import kotlin.math.*

/**
 * Long-term validation test for ESKF Phase 2 to assess drift characteristics.
 */
class ESKFLongTermTest {
    
    companion object {
        private const val GRAVITY = 9.80665
        private const val DATASET_LAT = 52.202805
        private const val DATASET_LON = -2.2002
    }
    
    /**
     * Test ESKF drift over extended period with realistic driving scenario.
     */
    @Test
    fun testLongTermDriftCharacteristics() {
        println("=== LONG-TERM DRIFT ANALYSIS ===")
        
        val config = ESKFConfiguration.navSync()
        val eskf = ESKFImpl(config)
        
        val initialState = NavigationState(
            latitude = DATASET_LAT,
            longitude = DATASET_LON,
            speedKmh = 50.0,  // 50 km/h initial speed
            headingDegrees = 45.0,  // NE direction
            confidence = 1.0,
            gnssAvailable = true,
            source = NavigationSource.GNSS
        )
        
        eskf.initialize(initialState, config, null)
        
        val dt = 0.1  // 10Hz
        val durationSeconds = 60.0  // 1 minute
        val steps = (durationSeconds / dt).toInt()
        
        val results = mutableListOf<Pair<Double, ESKFResult>>()
        
        // Simulate realistic highway driving with some maneuvers
        for (step in 0 until steps) {
            val timeSeconds = step * dt
            
            // Generate driving scenario:
            // 0-20s: steady driving
            // 20-40s: gentle turn
            // 40-60s: slight acceleration/deceleration
            val (accelX, accelY, gyroZ) = when {
                timeSeconds < 20.0 -> {
                    // Steady driving with small variations
                    Triple(
                        0.1 * sin(timeSeconds * 0.5),  // Small longitudinal variations
                        0.05 * sin(timeSeconds * 1.2),  // Small lateral variations
                        0.01 * sin(timeSeconds * 0.8)   // Small yaw variations
                    )
                }
                timeSeconds < 40.0 -> {
                    // Gentle turn (highway curve)
                    val turnPhase = (timeSeconds - 20.0) / 20.0
                    Triple(
                        0.0,
                        1.0 * sin(turnPhase * PI),  // Centripetal acceleration
                        0.1 * sin(turnPhase * PI)   // Yaw rate for turn
                    )
                }
                else -> {
                    // Acceleration/deceleration phase
                    val phase = (timeSeconds - 40.0) / 20.0
                    Triple(
                        0.5 * sin(phase * PI * 2),  // Longitudinal acceleration
                        0.1 * sin(phase * PI * 4),  // Lane changes
                        0.02 * sin(phase * PI * 3)  // Heading adjustments
                    )
                }
            }
            
            val measurement = ImuMeasurement(
                timestampMs = 1000L + (step * 100L),
                accelerationX = accelX,
                accelerationY = accelY,
                accelerationZ = GRAVITY,
                angularVelocityX = 0.0,
                angularVelocityY = 0.0,
                angularVelocityZ = gyroZ
            )
            
            val result = eskf.predict(measurement, dt)
            
            // Store result every 5 seconds for analysis
            if (step % 50 == 0) {
                results.add(Pair(timeSeconds, result))
            }
        }
        
        // Analyze drift characteristics
        analyzeLongTermDrift(results, durationSeconds)
    }
    
    /**
     * Analyze long-term drift patterns.
     */
    private fun analyzeLongTermDrift(results: List<Pair<Double, ESKFResult>>, duration: Double) {
        if (results.isEmpty()) return
        
        println("DRIFT ANALYSIS over ${"%.0f".format(duration)} seconds:")
        
        val initialResult = results.first().second
        val finalResult = results.last().second
        
        // Position drift analysis
        val latDrift_m = (finalResult.latitude - initialResult.latitude) * 111320.0
        val lonDrift_m = (finalResult.longitude - initialResult.longitude) * 111320.0 * cos(Math.toRadians(DATASET_LAT))
        val totalDrift_m = sqrt(latDrift_m * latDrift_m + lonDrift_m * lonDrift_m)
        
        println("  Initial position: (${"%.6f".format(initialResult.latitude)}, ${"%.6f".format(initialResult.longitude)})")
        println("  Final position: (${"%.6f".format(finalResult.latitude)}, ${"%.6f".format(finalResult.longitude)})")
        println("  Position drift: North=${"%.1f".format(latDrift_m)}m, East=${"%.1f".format(lonDrift_m)}m")
        println("  Total drift: ${"%.1f".format(totalDrift_m)} m")
        println("  Drift rate: ${"%.2f".format(totalDrift_m / duration)} m/s")
        
        // Velocity analysis
        val initialSpeed = sqrt(initialResult.velocityNorth*initialResult.velocityNorth + initialResult.velocityEast*initialResult.velocityEast)
        val finalSpeed = sqrt(finalResult.velocityNorth*finalResult.velocityNorth + finalResult.velocityEast*finalResult.velocityEast)
        
        println("  Initial speed: ${"%.2f".format(initialSpeed)} m/s")
        println("  Final speed: ${"%.2f".format(finalSpeed)} m/s")
        println("  Speed change: ${"%.2f".format(finalSpeed - initialSpeed)} m/s")
        
        // Heading analysis
        val headingChange = finalResult.yawDegrees - initialResult.yawDegrees
        val normalizedHeadingChange = if (headingChange > 180) headingChange - 360 
                                    else if (headingChange < -180) headingChange + 360 
                                    else headingChange
        
        println("  Initial heading: ${"%.1f".format(initialResult.yawDegrees)}°")
        println("  Final heading: ${"%.1f".format(finalResult.yawDegrees)}°")
        println("  Heading change: ${"%.1f".format(normalizedHeadingChange)}°")
        
        // Stability analysis
        val maxSpeed = results.map { (_, result) ->
            sqrt(result.velocityNorth*result.velocityNorth + result.velocityEast*result.velocityEast)
        }.maxOrNull() ?: 0.0
        
        val validResults = results.count { (_, result) -> result.isValid }
        
        println("  Max speed reached: ${"%.2f".format(maxSpeed)} m/s")
        println("  Valid results: $validResults / ${results.size}")
        
        // Assessment
        println("LONG-TERM ASSESSMENT:")
        
        val driftPerHour = (totalDrift_m / duration) * 3600.0  // m/hour
        println("  Projected drift: ${"%.0f".format(driftPerHour)} m/hour")
        
        when {
            driftPerHour < 100.0 -> println("  ✅ EXCELLENT: Very low drift for dead reckoning")
            driftPerHour < 500.0 -> println("  ✅ GOOD: Acceptable drift for automotive navigation")
            driftPerHour < 1000.0 -> println("  ⚠️  MODERATE: Usable for short-term GNSS outages")
            else -> println("  ❌ HIGH: Excessive drift - requires bias calibration or sensor fusion")
        }
        
        if (validResults == results.size) {
            println("  ✅ STABILITY: All predictions numerically stable")
        } else {
            println("  ❌ STABILITY: Some numerical issues detected")
        }
        
        if (maxSpeed < 100.0) {  // ~360 km/h max reasonable
            println("  ✅ REALISM: Speed values within reasonable bounds")
        } else {
            println("  ❌ REALISM: Unrealistic speed values - check integration")
        }
    }
}