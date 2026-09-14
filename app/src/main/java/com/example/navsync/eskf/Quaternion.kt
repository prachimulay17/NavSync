package com.example.navsync.eskf

import kotlin.math.*

/**
 * Quaternion class for ESKF attitude representation.
 * Uses Hamilton convention: q = [w, x, y, z] = [scalar, vector]
 * Represents rotation from body frame to navigation frame.
 */
data class Quaternion(
    val w: Double,  // Scalar part
    val x: Double,  // i component
    val y: Double,  // j component  
    val z: Double   // k component
) {
    
    /**
     * Quaternion magnitude (norm).
     */
    fun magnitude(): Double = sqrt(w * w + x * x + y * y + z * z)
    
    /**
     * Normalized quaternion (unit quaternion).
     */
    fun normalized(): Quaternion {
        val mag = magnitude()
        if (mag < 1e-12) {
            // Return identity quaternion for degenerate case
            return identity()
        }
        return Quaternion(w / mag, x / mag, y / mag, z / mag)
    }
    
    /**
     * Quaternion multiplication (Hamilton product).
     * Represents composition of rotations: this * other
     */
    operator fun times(other: Quaternion): Quaternion {
        return Quaternion(
            w = this.w * other.w - this.x * other.x - this.y * other.y - this.z * other.z,
            x = this.w * other.x + this.x * other.w + this.y * other.z - this.z * other.y,
            y = this.w * other.y - this.x * other.z + this.y * other.w + this.z * other.x,
            z = this.w * other.z + this.x * other.y - this.y * other.x + this.z * other.w
        )
    }
    
    /**
     * Quaternion conjugate (inverse rotation for unit quaternions).
     */
    fun conjugate(): Quaternion = Quaternion(w, -x, -y, -z)
    
    /**
     * Convert to Euler angles (Roll, Pitch, Yaw) in degrees.
     * Returns angles in aerospace convention: Roll(X), Pitch(Y), Yaw(Z).
     */
    fun toEulerDegrees(): Triple<Double, Double, Double> {
        val q = this.normalized()
        
        // Roll (rotation about x-axis)
        val sinRoll = 2 * (q.w * q.x + q.y * q.z)
        val cosRoll = 1 - 2 * (q.x * q.x + q.y * q.y)
        val rollRad = atan2(sinRoll, cosRoll)
        
        // Pitch (rotation about y-axis)
        val sinPitch = 2 * (q.w * q.y - q.z * q.x)
        val pitchRad = if (abs(sinPitch) >= 1.0) {
            sign(sinPitch) * PI / 2  // Handle gimbal lock
        } else {
            asin(sinPitch)
        }
        
        // Yaw (rotation about z-axis)
        val sinYaw = 2 * (q.w * q.z + q.x * q.y)
        val cosYaw = 1 - 2 * (q.y * q.y + q.z * q.z)
        val yawRad = atan2(sinYaw, cosYaw)
        
        return Triple(
            rollRad * 180.0 / PI,
            pitchRad * 180.0 / PI,
            yawRad * 180.0 / PI
        )
    }
    
    /**
     * Check if quaternion is valid (finite and non-zero norm).
     */
    fun isValid(): Boolean {
        return w.isFinite() && x.isFinite() && y.isFinite() && z.isFinite() && 
               magnitude() > 1e-12
    }
    
    companion object {
        
        /**
         * Identity quaternion (no rotation).
         */
        fun identity(): Quaternion = Quaternion(1.0, 0.0, 0.0, 0.0)
        
        /**
         * Create quaternion from Euler angles in degrees.
         * Input: Roll(X), Pitch(Y), Yaw(Z) in degrees.
         */
        fun fromEulerDegrees(rollDeg: Double, pitchDeg: Double, yawDeg: Double): Quaternion {
            val rollRad = rollDeg * PI / 180.0
            val pitchRad = pitchDeg * PI / 180.0
            val yawRad = yawDeg * PI / 180.0
            
            val cr = cos(rollRad * 0.5)
            val sr = sin(rollRad * 0.5)
            val cp = cos(pitchRad * 0.5)
            val sp = sin(pitchRad * 0.5)
            val cy = cos(yawRad * 0.5)
            val sy = sin(yawRad * 0.5)
            
            return Quaternion(
                w = cr * cp * cy + sr * sp * sy,
                x = sr * cp * cy - cr * sp * sy,
                y = cr * sp * cy + sr * cp * sy,
                z = cr * cp * sy - sr * sp * cy
            )
        }
        
        /**
         * Create quaternion from axis-angle representation.
         * @param axisX, axisY, axisZ: rotation axis (will be normalized)
         * @param angleRad: rotation angle in radians
         */
        fun fromAxisAngle(axisX: Double, axisY: Double, axisZ: Double, angleRad: Double): Quaternion {
            val axisMag = sqrt(axisX * axisX + axisY * axisY + axisZ * axisZ)
            if (axisMag < 1e-12) {
                return identity()
            }
            
            val normalizedX = axisX / axisMag
            val normalizedY = axisY / axisMag
            val normalizedZ = axisZ / axisMag
            
            val halfAngle = angleRad * 0.5
            val sinHalf = sin(halfAngle)
            val cosHalf = cos(halfAngle)
            
            return Quaternion(
                w = cosHalf,
                x = normalizedX * sinHalf,
                y = normalizedY * sinHalf,
                z = normalizedZ * sinHalf
            )
        }
    }
}