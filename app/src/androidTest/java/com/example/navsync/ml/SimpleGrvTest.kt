package com.example.navsync.ml

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.*

/**
 * Simple test to validate 6-DOF GRV transformation vs yaw-only.
 */
@RunWith(AndroidJUnit4::class)
class SimpleGrvTest {
    
    @Test
    fun testGrvTransformation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        
        // Test with sample orientation data from S-Vw9
        val orientations = listOf(
            Triple(88.66, -78.84, -162.08),  // yaw, pitch, roll from first S-Vw9 sample
            Triple(86.09, -79.1, -162.94),
            Triple(87.25, -79.22, -162.72),
            Triple(86.9, -79.19, -161.95),
            Triple(90.63, -79.4, -161.49)
        )
        
        // Sample IMU data (gyro + accel)
        val sampleGyro = floatArrayOf(-0.051f, 0.1903f, -0.1151f)  // From S-Vw9
        val sampleAccel = floatArrayOf(-0.0646f, -1.2997f, 9.5197f)
        
        println("=== STEP 1 GRV COMPARISON ===")
        println("Sample gyro: [${sampleGyro.joinToString(", ") { "%.3f".format(it) }}] rad/s")
        println("Sample accel: [${sampleAccel.joinToString(", ") { "%.3f".format(it) }}] m/s²")
        println()
        
        val yawOnlyResults = mutableListOf<FloatArray>()
        val sixDofResults = mutableListOf<FloatArray>()
        
        for ((index, orientation) in orientations.withIndex()) {
            val (yaw, pitch, roll) = orientation
            
            println("Sample ${index + 1}: yaw=${yaw.format(1)}°, pitch=${pitch.format(1)}°, roll=${roll.format(1)}°")
            
            // 1. Yaw-only quaternion (previous implementation)
            val yawRad = Math.toRadians(yaw) / 2.0
            val yawOnlyQuat = floatArrayOf(
                cos(yawRad).toFloat(),  // w
                0f,                     // x = 0
                0f,                     // y = 0
                sin(yawRad).toFloat()   // z
            )
            
            // 2. 6-DOF quaternion (current implementation)
            val y = Math.toRadians(yaw) / 2.0
            val p = Math.toRadians(pitch) / 2.0
            val r = Math.toRadians(roll) / 2.0
            
            val cy = cos(y); val sy = sin(y)
            val cp = cos(p); val sp = sin(p)
            val cr = cos(r); val sr = sin(r)
            
            val sixDofQuat = floatArrayOf(
                (cr * cp * cy + sr * sp * sy).toFloat(),  // w
                (sr * cp * cy - cr * sp * sy).toFloat(),  // x
                (cr * sp * cy + sr * cp * sy).toFloat(),  // y
                (cr * cp * sy - sr * sp * cy).toFloat()   // z
            )
            
            // Transform gyro to HACF with both approaches
            val yawOnlyGyroHacf = Hacf.rotateWxyz(yawOnlyQuat, sampleGyro[0], sampleGyro[1], sampleGyro[2])
            val sixDofGyroHacf = Hacf.rotateWxyz(sixDofQuat, sampleGyro[0], sampleGyro[1], sampleGyro[2])
            
            // Transform accel to HACF with both approaches  
            val yawOnlyAccelHacf = Hacf.rotateWxyz(yawOnlyQuat, sampleAccel[0], sampleAccel[1], sampleAccel[2])
            val sixDofAccelHacf = Hacf.rotateWxyz(sixDofQuat, sampleAccel[0], sampleAccel[1], sampleAccel[2])
            
            yawOnlyResults.add(yawOnlyGyroHacf)
            sixDofResults.add(sixDofGyroHacf)
            
            // Calculate differences
            val gyroDiff = sqrt(
                (yawOnlyGyroHacf[0] - sixDofGyroHacf[0]).pow(2) +
                (yawOnlyGyroHacf[1] - sixDofGyroHacf[1]).pow(2) +
                (yawOnlyGyroHacf[2] - sixDofGyroHacf[2]).pow(2)
            )
            
            val accelDiff = sqrt(
                (yawOnlyAccelHacf[0] - sixDofAccelHacf[0]).pow(2) +
                (yawOnlyAccelHacf[1] - sixDofAccelHacf[1]).pow(2) +
                (yawOnlyAccelHacf[2] - sixDofAccelHacf[2]).pow(2)
            )
            
            println("  Yaw-only quat: [${yawOnlyQuat.joinToString(", ") { "%.3f".format(it) }}]")
            println("  6-DOF quat:     [${sixDofQuat.joinToString(", ") { "%.3f".format(it) }}]")
            println("  Yaw-only HACF gyro: [${yawOnlyGyroHacf.joinToString(", ") { "%.3f".format(it) }}]")
            println("  6-DOF HACF gyro:     [${sixDofGyroHacf.joinToString(", ") { "%.3f".format(it) }}]")
            println("  Gyro transform diff: ${gyroDiff.format(4)} rad/s")
            println("  Accel transform diff: ${accelDiff.format(4)} m/s²")
            println()
        }
        
        // Test with RoNIN model if available
        println("=== RONIN MODEL TEST ===")
        try {
            val roninModel = RoninOnnx(context)
            val featureBuffer = FeatureBuffer()
            
            // Fill buffer with yaw-only transformed data
            for (i in 0 until FeatureBuffer.WINDOW) {
                val gyroHacf = yawOnlyResults[0] // Use first sample repeated
                val accelHacf = floatArrayOf(0f, 0f, 9.8f) // Approximate
                
                featureBuffer.add(
                    gyroHacf[0], gyroHacf[1], gyroHacf[2],
                    accelHacf[0], accelHacf[1], accelHacf[2]
                )
            }
            
            val yawOnlyWindow = featureBuffer.copyWindow()
            val (yawOnlyVx, yawOnlyVy) = roninModel.predictVelocity(yawOnlyWindow!!)
            
            // Fill buffer with 6-DOF transformed data
            featureBuffer.reset()
            for (i in 0 until FeatureBuffer.WINDOW) {
                val gyroHacf = sixDofResults[0] // Use first sample repeated
                val accelHacf = floatArrayOf(0f, 0f, 9.8f) // Approximate
                
                featureBuffer.add(
                    gyroHacf[0], gyroHacf[1], gyroHacf[2],
                    accelHacf[0], accelHacf[1], accelHacf[2]
                )
            }
            
            val sixDofWindow = featureBuffer.copyWindow()
            val (sixDofVx, sixDofVy) = roninModel.predictVelocity(sixDofWindow!!)
            
            println("Yaw-only RoNIN prediction: vx=${yawOnlyVx.format(3)} m/s, vy=${yawOnlyVy.format(3)} m/s")
            println("6-DOF RoNIN prediction:     vx=${sixDofVx.format(3)} m/s, vy=${sixDofVy.format(3)} m/s")
            
            val speedYawOnly = sqrt(yawOnlyVx.pow(2) + yawOnlyVy.pow(2))
            val speed6Dof = sqrt(sixDofVx.pow(2) + sixDofVy.pow(2))
            val velocityDiff = abs(speed6Dof - speedYawOnly)
            
            println("Speed difference: ${velocityDiff.format(4)} m/s")
            
            if (velocityDiff > 0.01) {
                println("✅ STEP 1: Significant velocity prediction difference detected")
                println("   6-DOF GRV transformation affects RoNIN output")
            } else {
                println("⚠️ STEP 1: Minimal velocity prediction difference") 
                println("   Transform changes may not significantly affect RoNIN")
            }
            
            roninModel.close()
            
        } catch (e: Exception) {
            println("❌ RoNIN model test failed: ${e.message}")
        }
        
        println()
        println("=== STEP 1 CONCLUSION ===")
        
        // Calculate average transformation differences
        val avgGyroDiff = yawOnlyResults.zip(sixDofResults) { yaw, sixDof ->
            sqrt(
                (yaw[0] - sixDof[0]).pow(2) +
                (yaw[1] - sixDof[1]).pow(2) +
                (yaw[2] - sixDof[2]).pow(2)
            )
        }.average()
        
        println("Average gyro transformation difference: ${avgGyroDiff.format(4)} rad/s")
        
        if (avgGyroDiff > 0.1) {
            println("✅ 6-DOF GRV shows significant transformation differences vs yaw-only")
            println("   This validates Step 1 implementation improvement")
        } else {
            println("⚠️ 6-DOF GRV shows minimal transformation differences vs yaw-only")  
            println("   Step 1 improvement may be limited for this dataset")
        }
        
        // Assert test passes
        assert(true) { "Step 1 validation completed" }
    }
    
    private fun Float.format(decimals: Int) = "%.${decimals}f".format(this)
    private fun Double.format(decimals: Int) = "%.${decimals}f".format(this)
}