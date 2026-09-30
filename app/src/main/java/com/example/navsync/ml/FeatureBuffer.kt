package com.example.navsync.ml

/**
 * Circular buffer for HACF IMU features used by RoNIN model.
 * 
 * Stores the last 400 samples (2 seconds at 200 Hz) of 6-channel IMU data:
 * [gyro_x, gyro_y, gyro_z, accel_x, accel_y, accel_z]
 * 
 * Adapted from RealAzimuth for NavSync ML integration.
 */
class FeatureBuffer(
    val window: Int = WINDOW,
    val channels: Int = CHANNELS,
) {
    private val data = FloatArray(window * channels)
    private var write = 0
    var count: Int = 0
        private set

    val isFull: Boolean get() = count >= window

    /**
     * Add one HACF IMU sample to the buffer.
     * 
     * @param gx, gy, gz Gyroscope in HACF frame (rad/s)
     * @param ax, ay, az Accelerometer in HACF frame (m/s²)
     */
    @Synchronized
    fun add(gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float) {
        val i = write * channels
        data[i] = gx
        data[i + 1] = gy
        data[i + 2] = gz
        data[i + 3] = ax
        data[i + 4] = ay
        data[i + 5] = az
        write = (write + 1) % window
        if (count < window) count++
    }

    /**
     * Reset buffer to empty state.
     */
    @Synchronized
    fun reset() {
        write = 0
        count = 0
    }

    /**
     * Copy buffer contents in chronological order.
     * 
     * @return [window × channels] array in row-major order, or null if not full
     */
    @Synchronized
    fun copyWindow(): FloatArray? {
        if (count < window) return null
        val out = FloatArray(window * channels)
        val oldest = write
        for (t in 0 until window) {
            val src = ((oldest + t) % window) * channels
            System.arraycopy(data, src, out, t * channels, channels)
        }
        return out
    }

    companion object {
        /** RoNIN model window size: 400 samples (2 seconds at 200 Hz) */
        const val WINDOW = 400
        
        /** Number of IMU channels: gyro_xyz + accel_xyz = 6 */
        const val CHANNELS = 6
    }
}
