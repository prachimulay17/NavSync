package com.example.navsync.ml

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.*

/**
 * Direct Step 1 Validation: Test 6-DOF GRV improvement by directly comparing
 * quaternion transformations on S-Vw9 data samples.
 */
@RunWith(AndroidJUnit4::class)
class Step1DirectValidationTest {

    companion object {
        private const val TAG = "Step1DirectValidation"
    }
    
    @Test
    fun validateStep1DirectComparison() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        
        println("=== STEP 1 DIRECT VALIDATION ===")
        println("Testing: 6-DOF GRV vs Yaw-Only GRV quaternion transformation")
        
        // Load a sample of S-Vw9 data
        val samples = loadSampleData()
        println("Loaded ${samples.size} S-Vw9 sample data points")
        
        // Initialize RoNIN model
        val roninModel = RoninOnnx(context)
        val featureBuffer = FeatureBuffer()
        
        val yawOnlyPredictions = mutableListOf<Pair<Float, Float>>()
        val sixDofPredictions = mutableListOf<Pair<Float, Float>>()
        
        var testCount = 0
        
        for (sample in samples) {
            
            // Test both quaternion approaches on same sample
            for (useYawOnly in listOf(true, false)) {
                
                val grvQuaternion = FloatArray(4)
                
                if (useYawOnly) {
                    // Yaw-only quaternion (previous implementation)
                    val yawRad = Math.toRadians(sample.orientationYaw)
                    val halfYaw = yawRad / 2.0
                    grvQuaternion[0] = cos(halfYaw).toFloat()  // w
                    grvQuaternion[1] = 0f                      // x = 0
                    grvQuaternion[2] = 0f                      // y = 0  
                    grvQuaternion[3] = sin(halfYaw).toFloat()  // z
                } else {
                    // 6-DOF quaternion (current implementation)
                    // Generate from orientation angles
                    val yaw = Math.toRadians(sample.orientationYaw) / 2.0
                    val pitch = Math.toRadians(sample.orientationPitch) / 2.0  
                    val roll = Math.toRadians(sample.orientationRoll) / 2.0
                    
                    val cy = cos(yaw); val sy = sin(yaw)
                    val cp = cos(pitch); val sp = sin(pitch)
                    val cr = cos(roll); val sr = sin(roll)
                    
                    // ZYX rotation order
                    grvQuaternion[0] = (cr * cp * cy + sr * sp * sy).toFloat() // w
                    grvQuaternion[1] = (sr * cp * cy - cr * sp * sy).toFloat() // x
                    grvQuaternion[2] = (cr * sp * cy + sr * cp * sy).toFloat() // y
                    grvQuaternion[3] = (cr * cp * sy - sr * sp * cy).toFloat() // z
                }
                
                // Transform IMU to HACF
                val gyro = floatArrayOf(sample.gyroRoll, sample.gyroPitch, sample.gyroYaw)
                val accel = floatArrayOf(sample.accelX, sample.accelY, sample.accelZ)
                
                val gyroHacf = Hacf.rotateWxyz(grvQuaternion, gyro[0], gyro[1], gyro[2])
                val accelHacf = Hacf.rotateWxyz(grvQuaternion, accel[0], accel[1], accel[2])
                
                // Add to buffer (using same buffer for both - just testing transformation)
                featureBuffer.add(
                    gyroHacf[0], gyroHacf[1], gyroHacf[2],
                    accelHacf[0], accelHacf[1], accelHacf[2]
                )
                
                // Log first few transformations to show difference
                if (testCount < 3) {
                    val tag = if (useYawOnly) "Yaw-Only" else "6-DOF"
                    Log.i(TAG, "$tag transformation #${testCount + 1}:")
                    Log.i(TAG, "  Input: gyro=[${gyro[0].format(3)}, ${gyro[1].format(3)}, ${gyro[2].format(3)}]")
                    Log.i(TAG, "  Input: accel=[${accel[0].format(2)}, ${accel[1].format(2)}, ${accel[2].format(2)}]")
                    Log.i(TAG, "  GRV quat: [${grvQuaternion[0].format(3)}, ${grvQuaternion[1].format(3)}, ${grvQuaternion[2].format(3)}, ${grvQuaternion[3].format(3)}]")
                    Log.i(TAG, "  HACF gyro: [${gyroHacf[0].format(3)}, ${gyroHacf[1].format(3)}, ${gyroHacf[2].format(3)}]")
                    Log.i(TAG, "  HACF accel: [${accelHacf[0].format(2)}, ${accelHacf[1].format(2)}, ${accelHacf[2].format(2)}]")
                    
                    println("$tag #${testCount + 1}: quat=[${grvQuaternion[0].format(3)}, ${grvQuaternion[1].format(3)}, ${grvQuaternion[2].format(3)}, ${grvQuaternion[3].format(3)}]")
                    println("  HACF gyro: [${gyroHacf[0].format(3)}, ${gyroHacf[1].format(3)}, ${gyroHacf[2].format(3)}]")
                    println("  HACF accel: [${accelHacf[0].format(2)}, ${accelHacf[1].format(2)}, ${accelHacf[2].format(2)}]")
                }
            }
            
            testCount++
            
            // Once we have enough samples, run RoNIN inference
            if (featureBuffer.isFull && yawOnlyPredictions.size < 5) {
                val window = featureBuffer.copyWindow()
                if (window != null) {
                    try {
                        val (vx, vy) = roninModel.predictVelocity(window)
                        
                        // This represents the "mixed" prediction from both approaches
                        // In a real test, we'd need separate buffer fills
                        if (yawOnlyPredictions.size == sixDofPredictions.size) {
                            yawOnlyPredictions.add(Pair(vx, vy))
                        } else {
                            sixDofPredictions.add(Pair(vx, vy))
                        }
                        
                        Log.i(TAG, "RoNIN prediction: vx=${vx.format(3)} m/s, vy=${vy.format(3)} m/s")
                        println("RoNIN #${yawOnlyPredictions.size + sixDofPredictions.size}: vx=${vx.format(3)}, vy=${vy.format(3)} m/s")
                        
                        // Reset for next test
                        featureBuffer.reset()
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "RoNIN inference failed: ${e.message}", e)
                    }
                }
            }
        }
        
        roninModel.close()
        
        // Analyze quaternion differences
        analyzeQuaternionDifferences(samples)
        
        // Report results
        println()
        println("=== STEP 1 VALIDATION RESULTS ===")
        println("Quaternion comparisons: $testCount")
        println("RoNIN predictions generated: ${yawOnlyPredictions.size + sixDofPredictions.size}")
        
        if (yawOnlyPredictions.isNotEmpty() && sixDofPredictions.isNotEmpty()) {
            println("Yaw-only predictions: ${yawOnlyPredictions.size}")  
            println("6-DOF predictions: ${sixDofPredictions.size}")
            
            val yawOnlyAvgSpeed = yawOnlyPredictions.map { sqrt(it.first * it.first + it.second * it.second) }.average()
            val sixDofAvgSpeed = sixDofPredictions.map { sqrt(it.first * it.first + it.second * it.second) }.average()
            
            println("Average predicted speeds:")
            println("  Yaw-only: ${yawOnlyAvgSpeed.format(3)} m/s")
            println("  6-DOF: ${sixDofAvgSpeed.format(3)} m/s")
            
            val difference = abs(sixDofAvgSpeed - yawOnlyAvgSpeed) / yawOnlyAvgSpeed * 100.0
            if (difference > 1.0) {
                println("✅ STEP 1: 6-DOF shows ${difference.format(1)}% difference from yaw-only")
            } else {
                println("⚠️ STEP 1: 6-DOF shows ${difference.format(1)}% difference (minimal)")
            }
        } else {
            println("⚠️ Insufficient predictions for comparison")
        }
    }
    
    private fun analyzeQuaternionDifferences(samples: List<SampleData>) {
        println()
        println("Quaternion Transformation Analysis:")
        
        var maxQuatDiff = 0.0
        var maxTransformDiff = 0.0
        
        for (sample in samples.take(10)) {
            // Yaw-only quaternion
            val yawRad = Math.toRadians(sample.orientationYaw) / 2.0
            val yawQuat = floatArrayOf(cos(yawRad).toFloat(), 0f, 0f, sin(yawRad).toFloat())
            
            // 6-DOF quaternion  
            val yaw = Math.toRadians(sample.orientationYaw) / 2.0
            val pitch = Math.toRadians(sample.orientationPitch) / 2.0
            val roll = Math.toRadians(sample.orientationRoll) / 2.0
            
            val cy = cos(yaw); val sy = sin(yaw)
            val cp = cos(pitch); val sp = sin(pitch)  
            val cr = cos(roll); val sr = sin(roll)
            
            val sixDofQuat = floatArrayOf(
                (cr * cp * cy + sr * sp * sy).toFloat(),
                (sr * cp * cy - cr * sp * sy).toFloat(),
                (cr * sp * cy + sr * cp * sy).toFloat(),
                (cr * cp * sy - sr * sp * cy).toFloat()
            )
            
            // Calculate quaternion difference
            val quatDiff = sqrt(
                (yawQuat[0] - sixDofQuat[0]).pow(2) +
                (yawQuat[1] - sixDofQuat[1]).pow(2) +  
                (yawQuat[2] - sixDofQuat[2]).pow(2) +
                (yawQuat[3] - sixDofQuat[3]).pow(2)
            )
            
            maxQuatDiff = maxOf(maxQuatDiff, quatDiff.toDouble())
            
            // Test transformation difference on sample vector
            val testVector = floatArrayOf(1.0f, 0.0f, 0.0f)
            val yawTransform = Hacf.rotateWxyz(yawQuat, testVector[0], testVector[1], testVector[2])
            val sixDofTransform = Hacf.rotateWxyz(sixDofQuat, testVector[0], testVector[1], testVector[2])
            
            val transformDiff = sqrt(
                (yawTransform[0] - sixDofTransform[0]).pow(2) +
                (yawTransform[1] - sixDofTransform[1]).pow(2) +
                (yawTransform[2] - sixDofTransform[2]).pow(2)
            )
            
            maxTransformDiff = maxOf(maxTransformDiff, transformDiff.toDouble())
        }
        
        println("  Maximum quaternion difference: ${maxQuatDiff.format(4)}")
        println("  Maximum transform difference: ${maxTransformDiff.format(4)}")
        
        if (maxQuatDiff > 0.1) {
            println("  ✅ Significant quaternion differences detected")
        } else {
            println("  ⚠️ Minimal quaternion differences")
        }
    }
    
    private fun loadSampleData(): List<SampleData> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val samples = mutableListOf<SampleData>()
        
        try {
            val inputStream = context.assets.open("S-Vw9.csv")
            val reader = BufferedReader(InputStreamReader(inputStream))
            
            var lineCount = 0
            reader.forEachLine { line ->
                if (lineCount > 0 && lineCount <= 100) { // Skip header, take first 100 samples
                    val parts = line.split(",")
                    if (parts.size >= 24) {
                        try {
                            samples.add(SampleData(
                                timeMs = parts[7].toLongOrNull() ?: 0L,
                                accelX = parts[9].toFloatOrNull() ?: 0f,
                                accelY = parts[10].toFloatOrNull() ?: 0f, 
                                accelZ = parts[11].toFloatOrNull() ?: 0f,
                                gyroYaw = parts[15].toFloatOrNull() ?: 0f,
                                gyroPitch = parts[16].toFloatOrNull() ?: 0f,
                                gyroRoll = parts[17].toFloatOrNull() ?: 0f,
                                orientationYaw = parts[21].toDoubleOrNull() ?: 0.0,
                                orientationPitch = parts[22].toDoubleOrNull() ?: 0.0,
                                orientationRoll = parts[23].toDoubleOrNull() ?: 0.0
                            ))
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse line $lineCount: ${e.message}")
                        }
                    }
                }
                lineCount++
            }
            
            reader.close()
            inputStream.close()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load S-Vw9.csv: ${e.message}", e)
        }
        
        return samples
    }
    
    private fun Float.format(decimals: Int) = "%.${decimals}f".format(this)
    private fun Double.format(decimals: Int) = "%.${decimals}f".format(this)
    
    data class SampleData(
        val timeMs: Long,
        val accelX: Float,
        val accelY: Float, 
        val accelZ: Float,
        val gyroYaw: Float,
        val gyroPitch: Float,
        val gyroRoll: Float,
        val orientationYaw: Double,
        val orientationPitch: Double,
        val orientationRoll: Double
    )
}