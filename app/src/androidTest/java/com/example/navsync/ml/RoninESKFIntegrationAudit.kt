package com.example.navsync.ml

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.navsync.data.CsvDataSource
import com.example.navsync.model.NavigationState
import com.example.navsync.model.NavigationSource
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import kotlin.math.*

/**
 * AUDIT SCRIPT: RoNIN → ESKF Integration Debugging
 * 
 * Purpose: Find the exact point where reasonable RoNIN velocities (1.3 m/s RMSE)
 * become catastrophic ESKF outputs (293 m/s RMSE).
 * 
 * Focus areas:
 * 1. Timestamp/dt handling
 * 2. HACF → ENU → NED transformation
 * 3. Velocity update in ESKF
 * 4. Manual trace of single prediction through entire chain
 */
@RunWith(AndroidJUnit4::class)
class RoninESKFIntegrationAudit {
    
    companion object {
        private const val TAG = "RoninESKFAudit"
        
        private const val DATASET_NAME = "V-Vw9"
        private const val AUDIT_START_INDEX = 350  // Around 35s where RoNIN first fires
        private const val AUDIT_END_INDEX = 450    // 10 second window for detailed analysis
    }
    
    @Test
    fun auditRoninESKFIntegration() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        
        println("=" .repeat(80))
        println("RoNIN → ESKF INTEGRATION AUDIT")
        println("=" .repeat(80))
        println()
        
        // Load dataset
        val dataSource = CsvDataSource(context)
        val dataset = dataSource.loadDataset(DATASET_NAME)
        
        println("Dataset: $DATASET_NAME (${dataset.dataPoints.size} points)")
        println("Audit window: indices $AUDIT_START_INDEX to $AUDIT_END_INDEX")
        println()
        
        // Initialize RoNIN-ESKF
        val roninInference = RoninESKFInference(context)
        val firstPoint = dataset.dataPoints.first()
        
        roninInference.initialize(NavigationState(
            latitude = firstPoint.gnssData.latitude,
            longitude = firstPoint.gnssData.longitude,
            speedKmh = firstPoint.gnssData.speedKmh,
            headingDegrees = firstPoint.gnssData.headingDegrees,
            confidence = 1.0,
            gnssAvailable = true,
            source = NavigationSource.GNSS
        ))
        
        println("✅ Initialized RoNIN-ESKF")
        println()
        
        // AUDIT 1: Timestamp and dt handling
        println("═".repeat(80))
        println("AUDIT 1: TIMESTAMP AND DELTA-TIME HANDLING")
        println("═".repeat(80))
        
        var previousTimestamp = dataset.dataPoints.first().sensorData.timestampMs
        val dtValues = mutableListOf<Long>()
        
        for (i in 0 until min(100, dataset.dataPoints.size)) {
            val point = dataset.dataPoints[i]
            val dt = point.sensorData.timestampMs - previousTimestamp
            if (i > 0) {
                dtValues.add(dt)
            }
            previousTimestamp = point.sensorData.timestampMs
        }
        
        println("First 100 samples dt analysis:")
        println("  Min dt: ${dtValues.minOrNull()} ms")
        println("  Max dt: ${dtValues.maxOrNull()} ms")
        println("  Mean dt: ${dtValues.average().format(2)} ms")
        println("  Median dt: ${dtValues.sorted()[dtValues.size/2]} ms")
        println("  Expected: ~100 ms (10 Hz for V-Vw9)")
        println()
        
        if (dtValues.any { it > 200 }) {
            println("⚠️  WARNING: Found dt values > 200ms (potential 200Hz assumption error)")
        }
        
        println()
        
        // AUDIT 2-4: Detailed trace through integration chain
        println("═".repeat(80))
        println("AUDIT 2-4: DETAILED INTEGRATION CHAIN TRACE")
        println("═".repeat(80))
        println()
        
        var previousLat = firstPoint.gnssData.latitude
        var previousLon = firstPoint.gnssData.longitude
        previousTimestamp = firstPoint.sensorData.timestampMs
        
        var roninInferenceCount = 0
        
        for (i in 0 until AUDIT_END_INDEX) {
            val point = dataset.dataPoints[i]
            val timeS = point.sensorData.timestampMs / 1000.0
            val deltaTimeMs = point.sensorData.timestampMs - previousTimestamp
            
            if (deltaTimeMs <= 0 || deltaTimeMs > 5000) {
                continue
            }
            
            val gnssAvailable = timeS < 35.0 || timeS > 50.0
            
            val previousState = NavigationState(
                latitude = previousLat,
                longitude = previousLon,
                speedKmh = point.gnssData.speedKmh,
                headingDegrees = point.gnssData.headingDegrees,
                confidence = if (gnssAvailable) 1.0 else 0.5,
                gnssAvailable = gnssAvailable,
                source = if (gnssAvailable) NavigationSource.GNSS else NavigationSource.AI_ESTIMATION
            )
            
            try {
                // Trigger RoNIN inference
                val result = roninInference.estimatePosition(
                    sensorData = point.sensorData,
                    previousState = previousState,
                    deltaTimeMs = deltaTimeMs
                )
                
                // Track when RoNIN actually fires (every 50 samples during outage)
                if (!gnssAvailable && i % 50 == 0 && i >= AUDIT_START_INDEX) {
                    roninInferenceCount++
                    
                    println("─".repeat(80))
                    println("RoNIN INFERENCE #$roninInferenceCount at t=${timeS.format(1)}s (index=$i)")
                    println("─".repeat(80))
                    
                    // AUDIT 2A: Input parameters
                    println()
                    println("INPUT PARAMETERS:")
                    println("  deltaTimeMs: $deltaTimeMs ms (${(deltaTimeMs/1000.0).format(3)}s)")
                    println("  Expected dt: ~5.0s (50 samples × 0.1s)")
                    
                    if (abs(deltaTimeMs/1000.0 - 5.0) > 0.5) {
                        println("  ⚠️  WARNING: dt mismatch! Expected ~5.0s, got ${(deltaTimeMs/1000.0).format(3)}s")
                    }
                    
                    // AUDIT 2B: Sensor data
                    println()
                    println("SENSOR DATA (device frame):")
                    println("  Accel: [${point.sensorData.accelerationX.format(3)}, " +
                            "${point.sensorData.accelerationY.format(3)}, " +
                            "${point.sensorData.accelerationZ.format(3)}] m/s²")
                    println("  Gyro:  [${point.sensorData.gyroRoll.format(3)}, " +
                            "${point.sensorData.gyroPitch.format(3)}, " +
                            "${point.sensorData.gyroYaw.format(3)}] rad/s")
                    
                    val grv = point.sensorData.gameRotationVector
                    if (grv != null) {
                        println("  GRV:   [${grv[0].format(3)}, ${grv[1].format(3)}, " +
                                "${grv[2].format(3)}, ${grv[3].format(3)}] (x,y,z,w)")
                    }
                    
                    // AUDIT 2C: Expected transformation
                    println()
                    println("EXPECTED TRANSFORMATION:")
                    println("  1. GRV quaternion rotates IMU to HACF frame")
                    println("  2. RoNIN LSTM predicts (vx_hacf, vy_hacf) from 400-sample window")
                    println("  3. HACF → ENU via yaw rotation")
                    println("  4. ENU → NED (axis swap only)")
                    println("  5. ESKF.updateWithVelocity(vN_ned, vE_ned, σ)")
                    
                    // AUDIT 2D: Output analysis
                    println()
                    println("OUTPUT ANALYSIS:")
                    println("  Result position: (${result.latitude.format(6)}, ${result.longitude.format(6)})")
                    println("  Result speed: ${result.speedKmh.format(2)} km/h (${(result.speedKmh/3.6).format(2)} m/s)")
                    println("  Result heading: ${result.headingDegrees.format(1)}°")
                    println("  Result confidence: ${result.confidence.format(3)}")
                    
                    // AUDIT 2E: Reference comparison
                    println()
                    println("REFERENCE COMPARISON:")
                    println("  Ground truth speed: ${point.groundTruth.speedKmh.format(2)} km/h " +
                            "(${(point.groundTruth.speedKmh/3.6).format(2)} m/s)")
                    println("  Ground truth heading: ${point.groundTruth.headingDegrees.format(1)}°")
                    
                    val speedError = abs(result.speedKmh - point.groundTruth.speedKmh)
                    val headingError = abs(wrapAngleDeg(result.headingDegrees - point.groundTruth.headingDegrees))
                    
                    println("  Speed error: ${speedError.format(2)} km/h (${(speedError/3.6).format(2)} m/s)")
                    println("  Heading error: ${headingError.format(1)}°")
                    
                    if (speedError > 10.0) {
                        println("  ❌ CATASTROPHIC SPEED ERROR DETECTED!")
                    } else if (speedError > 3.0) {
                        println("  ⚠️  Large speed error")
                    } else {
                        println("  ✅ Speed error acceptable")
                    }
                    
                    // AUDIT 2F: Position change analysis
                    val dtSeconds = deltaTimeMs / 1000.0
                    val (dN, dE) = computePositionChange(
                        previousLat, previousLon,
                        result.latitude, result.longitude
                    )
                    val actualVN = dN / dtSeconds
                    val actualVE = dE / dtSeconds
                    val actualSpeed = sqrt(actualVN * actualVN + actualVE * actualVE)
                    
                    println()
                    println("POSITION CHANGE ANALYSIS:")
                    println("  dt: ${dtSeconds.format(3)}s")
                    println("  dN: ${dN.format(2)} m, dE: ${dE.format(2)} m")
                    println("  Implied vN: ${actualVN.format(2)} m/s, vE: ${actualVE.format(2)} m/s")
                    println("  Implied speed: ${actualSpeed.format(2)} m/s (${(actualSpeed*3.6).format(2)} km/h)")
                    
                    if (actualSpeed > 10.0) {
                        println("  ❌ UNREALISTIC VELOCITY: ${actualSpeed.format(2)} m/s!")
                        println("     Expected: ~1-2 m/s for walking")
                    } else if (actualSpeed > 5.0) {
                        println("  ⚠️  High velocity: ${actualSpeed.format(2)} m/s")
                    }
                    
                    println()
                }
                
                previousLat = result.latitude
                previousLon = result.longitude
                
            } catch (e: Exception) {
                Log.e(TAG, "Inference failed at index $i: ${e.message}", e)
            }
            
            previousTimestamp = point.sensorData.timestampMs
        }
        
        println()
        println("═".repeat(80))
        println("AUDIT SUMMARY")
        println("═".repeat(80))
        println()
        println("RoNIN inferences analyzed: $roninInferenceCount")
        println()
        println("Key findings will be visible in logcat output above.")
        println("Search for:")
        println("  - ❌ CATASTROPHIC indicators")
        println("  - ⚠️  WARNING messages")
        println("  - dt mismatch issues")
        println("  - Unrealistic velocity values")
        println()
        println("✅ Integration audit complete")
    }
    
    /**
     * Compute position change in NED frame
     */
    private fun computePositionChange(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Pair<Double, Double> {
        val EARTH_RADIUS_M = 6371000.0
        
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val lon1Rad = Math.toRadians(lon1)
        val lon2Rad = Math.toRadians(lon2)
        
        val latAvg = (lat1Rad + lat2Rad) / 2.0
        
        val dN = (lat2Rad - lat1Rad) * EARTH_RADIUS_M
        val dE = (lon2Rad - lon1Rad) * EARTH_RADIUS_M * cos(latAvg)
        
        return Pair(dN, dE)
    }
    
    /**
     * Wrap angle to [-180, 180]
     */
    private fun wrapAngleDeg(angle: Double): Double {
        var a = angle % 360.0
        if (a > 180.0) a -= 360.0
        if (a < -180.0) a += 360.0
        return a
    }
    
    private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)
    private fun Float.format(decimals: Int): String = "%.${decimals}f".format(this)
}
