package com.example.navsync.ml

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RoNIN bilinear LSTM ONNX inference wrapper for NavSync.
 *
 * Model: RoNIN (Robust Neural Inertial Navigation)
 * Input: [1, T, 6] where T ≥ 400 — gyro_xyz + accel_xyz in HACF frame
 * Output: [1, 2] — (vx, vy) velocity in m/s at the last timestep
 * 
 * The model was re-exported with output slicing [:, -1, :] baked in,
 * so only the velocity at the newest sample is returned.
 * 
 * Adapted from RealAzimuth for NavSync ML integration.
 */
class RoninOnnx(context: Context) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val bytes = context.assets.open(ASSET).use { it.readBytes() }
        session = env.createSession(bytes)
    }

    /**
     * Run inference on HACF IMU window.
     * 
     * @param window [WINDOW × CHANNELS] array in row-major order
     * @return Pair of (vx, vy) velocity in m/s in HACF frame
     */
    fun predictVelocity(window: FloatArray): Pair<Float, Float> {
        require(window.size == WINDOW * CHANNELS) {
            "window must be ${WINDOW * CHANNELS} floats, got ${window.size}"
        }
        
        // Create ONNX tensor from window
        val buf = ByteBuffer.allocateDirect(window.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buf.put(window)
        buf.rewind()
        
        val shape = longArrayOf(1L, WINDOW.toLong(), CHANNELS.toLong())
        OnnxTensor.createTensor(env, buf, shape).use { input ->
            session.run(mapOf(INPUT to input)).use { results ->
                return parseVel(results[0].value)
            }
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        /** Asset filename for RoNIN LSTM model */
        const val ASSET = "ronin_lstm.onnx"
        
        /** ONNX input tensor name */
        const val INPUT = "imu"
        
        /** Window size: 400 samples (2 seconds at 200 Hz) */
        const val WINDOW = 400
        
        /** Number of channels: gyro_xyz + accel_xyz = 6 */
        const val CHANNELS = 6

        /**
         * Parse ONNX Runtime output for shape [1, 2].
         * 
         * ORT returns nested Java array: Array<FloatArray> where [0] = [vx, vy].
         */
        fun parseVel(raw: Any?): Pair<Float, Float> {
            // Shape [1, 2] → Array<FloatArray>
            if (raw is Array<*>) {
                val row = raw[0]
                if (row is FloatArray && row.size >= 2) return row[0] to row[1]
                // Scalar-wrapped: Array<Array<FloatArray>> (unlikely but safe)
                if (row is Array<*> && row.isNotEmpty()) {
                    val inner = row[0]
                    if (inner is FloatArray && inner.size >= 2) return inner[0] to inner[1]
                }
            }
            if (raw is FloatArray && raw.size >= 2) return raw[0] to raw[1]
            error("Unexpected ONNX output type ${raw?.javaClass} — expected [1,2] FloatArray")
        }
    }
}
