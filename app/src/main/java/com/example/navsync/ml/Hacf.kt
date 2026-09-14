package com.example.navsync.ml

import kotlin.math.abs

/**
 * HACF (Horizontal Accelerometer Coordinate Frame) transformation.
 * 
 * Rotates device-frame vectors by Game Rotation Vector (GRV) quaternion
 * to obtain gravity-aligned, heading-relative coordinates.
 * 
 * Adapted from RealAzimuth for NavSync ML integration.
 */
object Hacf {
    
    /**
     * Rotate a 3D vector by quaternion.
     * 
     * @param qx, qy, qz, qw Quaternion components (scalar-last convention)
     * @param vx, vy, vz Vector components to rotate
     * @return Rotated vector [vx', vy', vz']
     */
    fun rotate(qx: Float, qy: Float, qz: Float, qw: Float, vx: Float, vy: Float, vz: Float): FloatArray {
        val tx = 2f * (qy * vz - qz * vy)
        val ty = 2f * (qz * vx - qx * vz)
        val tz = 2f * (qx * vy - qy * vx)
        return floatArrayOf(
            vx + qw * tx + (qy * tz - qz * ty),
            vy + qw * ty + (qz * tx - qx * tz),
            vz + qw * tz + (qx * ty - qy * tx),
        )
    }

    /**
     * Rotate vector using quaternion in Android [w, x, y, z] format.
     * 
     * Android SensorManager.getQuaternionFromVector returns [w, x, y, z].
     * 
     * @param q Quaternion array [w, x, y, z]
     * @param vx, vy, vz Vector components
     * @return Rotated vector in HACF frame
     */
    fun rotateWxyz(q: FloatArray, vx: Float, vy: Float, vz: Float): FloatArray {
        return rotate(q[1], q[2], q[3], q[0], vx, vy, vz)
    }

    /**
     * Check if two float arrays are nearly equal (for testing).
     */
    fun nearlyEqual(a: FloatArray, b: FloatArray, eps: Float = 1e-4f): Boolean {
        if (a.size != b.size) return false
        return a.indices.all { abs(a[it] - b[it]) <= eps }
    }
}
