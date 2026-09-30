package com.example.navsync.eskf

import org.junit.Test
import org.junit.Assert.*
import kotlin.math.*

/**
 * Unit tests for Quaternion class used in ESKF.
 */
class QuaternionTest {
    
    companion object {
        private const val TOLERANCE = 1e-6
    }
    
    @Test
    fun testIdentityQuaternion() {
        val identity = Quaternion.identity()
        
        assertEquals("Identity w should be 1", 1.0, identity.w, TOLERANCE)
        assertEquals("Identity x should be 0", 0.0, identity.x, TOLERANCE)
        assertEquals("Identity y should be 0", 0.0, identity.y, TOLERANCE)
        assertEquals("Identity z should be 0", 0.0, identity.z, TOLERANCE)
        assertTrue("Identity should be valid", identity.isValid())
    }
    
    @Test
    fun testQuaternionNormalization() {
        // Test various quaternions
        val testCases = listOf(
            Quaternion(2.0, 0.0, 0.0, 0.0),
            Quaternion(1.0, 1.0, 1.0, 1.0),
            Quaternion(0.5, 0.5, 0.5, 0.5)
        )
        
        testCases.forEach { q ->
            val normalized = q.normalized()
            val magnitude = normalized.magnitude()
            
            assertEquals("Normalized quaternion should have unit magnitude", 
                        1.0, magnitude, TOLERANCE)
            assertTrue("Normalized quaternion should be valid", normalized.isValid())
        }
    }
    
    @Test
    fun testEulerConversion() {
        val testAngles = listOf(
            Triple(0.0, 0.0, 0.0),      // Identity
            Triple(90.0, 0.0, 0.0),     // 90° roll
            Triple(0.0, 90.0, 0.0),     // 90° pitch  
            Triple(0.0, 0.0, 90.0),     // 90° yaw
            Triple(45.0, 30.0, 60.0)    // Combined rotation
        )
        
        testAngles.forEach { (roll, pitch, yaw) ->
            // Convert to quaternion and back
            val q = Quaternion.fromEulerDegrees(roll, pitch, yaw)
            val (rollBack, pitchBack, yawBack) = q.toEulerDegrees()
            
            assertTrue("Quaternion should be valid", q.isValid())
            
            // Note: Euler angle conversion may have multiple representations
            // For simple cases, check approximate equality
            if (abs(pitch) < 85.0) {  // Avoid gimbal lock region
                assertEquals("Roll should round-trip", roll, rollBack, 1.0)
                assertEquals("Pitch should round-trip", pitch, pitchBack, 1.0)
                assertEquals("Yaw should round-trip", yaw, yawBack, 1.0)
            }
        }
    }
    
    @Test
    fun testQuaternionMultiplication() {
        // Test identity multiplication
        val identity = Quaternion.identity()
        val testQ = Quaternion(0.7071, 0.7071, 0.0, 0.0)  // 90° roll
        
        val result1 = identity * testQ
        val result2 = testQ * identity
        
        assertEquals("Identity * q should equal q", testQ.w, result1.w, TOLERANCE)
        assertEquals("q * identity should equal q", testQ.w, result2.w, TOLERANCE)
        
        // Test rotation composition
        val roll90 = Quaternion.fromEulerDegrees(90.0, 0.0, 0.0)
        val pitch90 = Quaternion.fromEulerDegrees(0.0, 90.0, 0.0)
        
        val combined = roll90 * pitch90
        assertTrue("Combined rotation should be valid", combined.isValid())
        assertEquals("Combined rotation should be normalized", 1.0, combined.magnitude(), TOLERANCE)
    }
    
    @Test
    fun testAxisAngleConstruction() {
        // Test axis-angle construction
        val axis = Triple(0.0, 0.0, 1.0)  // Z-axis
        val angle = PI / 2  // 90 degrees
        
        val q = Quaternion.fromAxisAngle(axis.first, axis.second, axis.third, angle)
        
        assertTrue("Axis-angle quaternion should be valid", q.isValid())
        assertEquals("Quaternion should be normalized", 1.0, q.magnitude(), TOLERANCE)
        
        // Convert back to Euler and check
        val (roll, pitch, yaw) = q.toEulerDegrees()
        assertEquals("Should be 90° yaw rotation", 90.0, yaw, 1.0)
        assertEquals("Roll should be near zero", 0.0, roll, 1.0)
        assertEquals("Pitch should be near zero", 0.0, pitch, 1.0)
    }
    
    @Test
    fun testQuaternionConjugate() {
        val q = Quaternion.fromEulerDegrees(30.0, 45.0, 60.0)
        val qConj = q.conjugate()
        
        // q * q_conjugate should be identity (for unit quaternions)
        val result = q * qConj
        val identity = result.normalized()
        
        assertEquals("q * q* should give identity w", 1.0, identity.w, TOLERANCE)
        assertEquals("q * q* should give identity x", 0.0, identity.x, TOLERANCE)
        assertEquals("q * q* should give identity y", 0.0, identity.y, TOLERANCE)
        assertEquals("q * q* should give identity z", 0.0, identity.z, TOLERANCE)
    }
    
    @Test
    fun testZeroQuaternionHandling() {
        val zeroQ = Quaternion(0.0, 0.0, 0.0, 0.0)
        
        assertFalse("Zero quaternion should not be valid", zeroQ.isValid())
        
        val normalized = zeroQ.normalized()
        assertTrue("Normalized zero should become identity", normalized.isValid())
        assertEquals("Normalized zero should be identity", 1.0, normalized.w, TOLERANCE)
    }
}