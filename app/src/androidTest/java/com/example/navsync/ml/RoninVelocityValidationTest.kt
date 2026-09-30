package com.example.navsync.ml

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.navsync.data.CsvDataSource
import com.example.navsync.eskf.ESKFResult
import com.example.navsync.model.NavigationState
import com.example.navsync.model.NavigationSource
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import kotlin.math.*

/**
 * Definitive RoNIN velocity validation against actual V-Vw9 reference data.
 * 
 * This test:
 * 1. Runs the current 6-DOF GRV RoNIN pipeline on S-Vw9
 * 2. Extracts RoNIN vx/vy predictions for entire sequence
 * 3. Extracts reference vx/vy from V-Vw9 ground truth
 * 4. Transforms both to same coordinate frame (NED)
 * 5. Calculates comprehensive velocity metrics
 * 6. Reports metrics for full sequence and 35-50s GNSS outage
 * 7. Determines if current RoNIN predictions are accurate
 */
@RunWith(AndroidJUnit4::class)
class RoninVelocityValidationTest {
    
    companion object {
        private const val TAG = "RoninVelocityVal"
        
        // Validation configuration
        private const val DATASET_NAME = "V-Vw9"
        private const val OUTAGE_START_S = 35.0
        private const val OUTAGE_END_S = 50.0
        
        // Earth radius for coordinate conversions (meters)
        private const val EARTH_RADIUS_M = 6371000.0
    }
    
    @Test
    fun testRoninVelocityAccuracy() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        
        println("=".repeat(80))
        println("RoNIN VELOCITY VALIDATION TEST")
        println("Dataset: S-Vw9 (IMU) + V-Vw9 (reference)")
        println("GNSS Outage Focus: ${OUTAGE_START_S}-${OUTAGE_END_S} seconds")
        println("=".repeat(80))
        
        // Load datasets
        val dataSource = CsvDataSource(context)
        val dataset = dataSource.loadDataset(DATASET_NAME)
        
        println("✅ Loaded $DATASET_NAME: ${dataset.dataPoints.size} synchronized points")
        println()
        
        // Run RoNIN pipeline and extract velocity predictions
        println("🔄 Running 6-DOF GRV RoNIN pipeline...")
        val roninInference = RoninESKFInference(context)
        val velocityPairs = mutableListOf<VelocityPair>()
        
        // Initialize from first point
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
        
        var roninInferenceCount = 0
        var previousLat = firstPoint.gnssData.latitude
        var previousLon = firstPoint.gnssData.longitude
        var previousTimestamp = firstPoint.sensorData.timestampMs
        
        for ((index, point) in dataset.dataPoints.withIndex()) {
            val timeS = point.sensorData.timestampMs / 1000.0
            val deltaTimeMs = point.sensorData.timestampMs - previousTimestamp
            
            if (deltaTimeMs <= 0 || deltaTimeMs > 5000) {
                continue // Skip invalid deltas
            }
            
            // Determine GNSS availability
            val gnssAvailable = timeS < OUTAGE_START_S || timeS > OUTAGE_END_S
            
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
                val result = roninInference.estimatePosition(
                    sensorData = point.sensorData,
                    previousState = previousState,
                    deltaTimeMs = deltaTimeMs
                )
                
                // CRITICAL FIX: Get ESKF's internal velocity state directly
                val eskfState = roninInference.getESKFState()
                val vN_est = eskfState.velocityNorth  // m/s in NED frame
                val vE_est = eskfState.velocityEast   // m/s in NED frame
                
                // Extract reference velocity from ground truth
                val (vN_ref, vE_ref) = computeReferenceVelocityNED(
                    point.groundTruth.speedKmh / 3.6,  // km/h -> m/s
                    point.groundTruth.headingDegrees
                )
                
                // Calculate position in NED frame for position error metrics
                val (posN_est, posE_est) = computePositionNED(
                    firstPoint.gnssData.latitude, firstPoint.gnssData.longitude,
                    result.latitude, result.longitude
                )
                val (posN_ref, posE_ref) = computePositionNED(
                    firstPoint.gnssData.latitude, firstPoint.gnssData.longitude,
                    point.gnssData.latitude, point.gnssData.longitude
                )
                
                velocityPairs.add(VelocityPair(
                    timestampMs = point.sensorData.timestampMs,
                    vN_estimated = vN_est,
                    vE_estimated = vE_est,
                    vN_reference = vN_ref,
                    vE_reference = vE_ref,
                    posN_estimated = posN_est,
                    posE_estimated = posE_est,
                    posN_reference = posN_ref,
                    posE_reference = posE_ref,
                    inOutage = timeS >= OUTAGE_START_S && timeS <= OUTAGE_END_S
                ))
                
                previousLat = result.latitude
                previousLon = result.longitude
                
                // Track RoNIN inference activity during outage
                if (!gnssAvailable && index % 50 == 0) {
                    roninInferenceCount++
                }
                
                if (index % 100 == 0) {
                    Log.d(TAG, "Processed $index/${dataset.dataPoints.size} points, t=${timeS.format(1)}s")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Inference failed at index $index: ${e.message}", e)
            }
            
            previousTimestamp = point.sensorData.timestampMs
        }
        
        println("✅ Collected ${velocityPairs.size} velocity pairs")
        println("   RoNIN inferences during outage: ~$roninInferenceCount")
        println()
        
        // Calculate metrics
        println("📊 CALCULATING VELOCITY AND POSITION METRICS")
        println("=".repeat(80))
        
        val fullMetrics = calculateVelocityMetrics(velocityPairs, "Full Sequence")
        val outageMetrics = calculateVelocityMetrics(
            velocityPairs.filter { it.inOutage },
            "GNSS Outage (${OUTAGE_START_S}-${OUTAGE_END_S}s)"
        )
        
        // Print results
        printMetrics(fullMetrics)
        println()
        printMetrics(outageMetrics)
        
        // Print sample snapshots
        println()
        println("=".repeat(80))
        println("SAMPLE SNAPSHOTS")
        println("=".repeat(80))
        printSnapshot(velocityPairs, 35.0, "Outage Start (35s)")
        printSnapshot(velocityPairs, 40.0, "Mid-Outage (40s)")
        printSnapshot(velocityPairs, 45.0, "Mid-Outage (45s)")
        printSnapshot(velocityPairs, 50.0, "Outage End (50s)")
        
        println()
        println("=".repeat(80))
        println("CONCLUSION")
        println("=".repeat(80))
        
        // Determine accuracy
        val accuracyAssessment = assessAccuracy(outageMetrics)
        println(accuracyAssessment)
        
        println()
        println("✅ RoNIN velocity validation completed")
    }
    
    /**
     * Compute position in NED frame from lat/lon relative to reference point
     */
    private fun computePositionNED(
        refLat: Double, refLon: Double,
        lat: Double, lon: Double
    ): Pair<Double, Double> {
        // Convert to radians
        val refLatRad = Math.toRadians(refLat)
        val refLonRad = Math.toRadians(refLon)
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)
        
        // NED position (meters from reference)
        val dLat = latRad - refLatRad
        val dLon = lonRad - refLonRad
        
        val posN = dLat * EARTH_RADIUS_M
        val posE = dLon * EARTH_RADIUS_M * kotlin.math.cos(refLatRad)
        
        return Pair(posN, posE)
    }
    
    /**
     * Compute velocity in NED frame from position change
     * NOTE: This method is NO LONGER USED for validation.
     * Kept for reference only.
     */
    private fun computeVelocityFromPositionChange(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double,
        dtS: Double
    ): Pair<Double, Double> {
        if (dtS <= 0) return Pair(0.0, 0.0)
        
        // Convert lat/lon to radians
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val lon1Rad = Math.toRadians(lon1)
        val lon2Rad = Math.toRadians(lon2)
        
        // Average latitude for local frame
        val latAvg = (lat1Rad + lat2Rad) / 2.0
        
        // Displacement in meters (local tangent plane approximation)
        val dN = (lat2Rad - lat1Rad) * EARTH_RADIUS_M  // North displacement
        val dE = (lon2Rad - lon1Rad) * EARTH_RADIUS_M * cos(latAvg)  // East displacement
        
        // Velocity
        val vN = dN / dtS
        val vE = dE / dtS
        
        return Pair(vN, vE)
    }
    
    /**
     * Compute reference velocity in NED frame from speed and heading
     */
    private fun computeReferenceVelocityNED(speedMs: Double, headingDeg: Double): Pair<Double, Double> {
        // Heading: 0° = North, 90° = East (NED convention)
        val headingRad = Math.toRadians(headingDeg)
        
        val vN = speedMs * cos(headingRad)
        val vE = speedMs * sin(headingRad)
        
        return Pair(vN, vE)
    }
    
    /**
     * Calculate comprehensive velocity metrics
     */
    private fun calculateVelocityMetrics(pairs: List<VelocityPair>, label: String): VelocityMetrics {
        if (pairs.isEmpty()) {
            return VelocityMetrics(label, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        
        // Velocity errors
        val velocityErrors = pairs.map { pair ->
            val dvN = pair.vN_estimated - pair.vN_reference
            val dvE = pair.vE_estimated - pair.vE_reference
            sqrt(dvN * dvN + dvE * dvE)
        }
        
        // Position errors
        val positionErrors = pairs.map { pair ->
            val dpN = pair.posN_estimated - pair.posN_reference
            val dpE = pair.posE_estimated - pair.posE_reference
            sqrt(dpN * dpN + dpE * dpE)
        }
        
        // Speed errors
        val speedEstimated = pairs.map { sqrt(it.vN_estimated * it.vN_estimated + it.vE_estimated * it.vE_estimated) }
        val speedReference = pairs.map { sqrt(it.vN_reference * it.vN_reference + it.vE_reference * it.vE_reference) }
        val speedErrors = speedEstimated.zip(speedReference).map { (est, ref) -> est - ref }
        
        // Direction errors
        val directionErrors = pairs.map { pair ->
            val headingEst = atan2(pair.vE_estimated, pair.vN_estimated)
            val headingRef = atan2(pair.vE_reference, pair.vN_reference)
            val error = abs(wrapAngle(headingEst - headingRef))
            Math.toDegrees(error)
        }
        
        // Correlation
        val correlation = computeCorrelation(speedEstimated, speedReference)
        
        // RMSE and MAE
        val velocityRMSE = sqrt(velocityErrors.map { it * it }.average())
        val velocityMAE = velocityErrors.average()
        val speedRMSE = sqrt(speedErrors.map { it * it }.average())
        val meanSpeedBias = speedErrors.average()
        val meanDirectionError = directionErrors.average()
        
        val positionRMSE = sqrt(positionErrors.map { it * it }.average())
        val maxPositionError = positionErrors.maxOrNull() ?: 0.0
        
        // Outage-specific position errors
        val outageStartError = if (pairs.isNotEmpty()) positionErrors.first() else 0.0
        val outageEndError = if (pairs.isNotEmpty()) positionErrors.last() else 0.0
        
        return VelocityMetrics(
            label = label,
            sampleCount = pairs.size,
            velocityRMSE = velocityRMSE,
            velocityMAE = velocityMAE,
            speedRMSE = speedRMSE,
            meanSpeedBias = meanSpeedBias,
            meanDirectionError = meanDirectionError,
            correlation = correlation,
            maxVelocityError = velocityErrors.maxOrNull() ?: 0.0,
            positionRMSE = positionRMSE,
            maxPositionError = maxPositionError,
            outageStartPositionError = outageStartError,
            outageEndPositionError = outageEndError
        )
    }
    
    /**
     * Compute Pearson correlation coefficient
     */
    private fun computeCorrelation(x: List<Double>, y: List<Double>): Double {
        if (x.size != y.size || x.isEmpty()) return 0.0
        
        val meanX = x.average()
        val meanY = y.average()
        
        val covariance = x.zip(y).map { (xi, yi) -> (xi - meanX) * (yi - meanY) }.average()
        val stdX = sqrt(x.map { (it - meanX) * (it - meanX) }.average())
        val stdY = sqrt(y.map { (it - meanY) * (it - meanY) }.average())
        
        return if (stdX > 0 && stdY > 0) covariance / (stdX * stdY) else 0.0
    }
    
    /**
     * Wrap angle to [-π, π]
     */
    private fun wrapAngle(angle: Double): Double {
        var a = angle % (2 * PI)
        if (a > PI) a -= 2 * PI
        if (a < -PI) a += 2 * PI
        return a
    }
    
    /**
     * Print velocity metrics
     */
    private fun printMetrics(metrics: VelocityMetrics) {
        println(metrics.label)
        println("-".repeat(80))
        println("Sample count:              ${metrics.sampleCount}")
        println()
        println("VELOCITY METRICS:")
        println("  Velocity RMSE:           ${metrics.velocityRMSE.format(3)} m/s")
        println("  Velocity MAE:            ${metrics.velocityMAE.format(3)} m/s")
        println("  Speed RMSE:              ${metrics.speedRMSE.format(3)} m/s")
        println("  Mean speed bias:         ${metrics.meanSpeedBias.format(3)} m/s")
        println("  Mean direction error:    ${metrics.meanDirectionError.format(2)}°")
        println("  Correlation (speed):     ${metrics.correlation.format(4)}")
        println("  Max velocity error:      ${metrics.maxVelocityError.format(3)} m/s")
        println()
        println("POSITION METRICS:")
        println("  Position RMSE:           ${metrics.positionRMSE.format(3)} m")
        println("  Max position error:      ${metrics.maxPositionError.format(3)} m")
        println("  Outage start error:      ${metrics.outageStartPositionError.format(3)} m")
        println("  Outage end error:        ${metrics.outageEndPositionError.format(3)} m")
    }
    
    /**
     * Print snapshot at specific time
     */
    private fun printSnapshot(pairs: List<VelocityPair>, targetTimeS: Double, label: String) {
        // Find closest sample to target time
        val targetTimeMs = (targetTimeS * 1000).toLong()
        val closest = pairs.minByOrNull { abs(it.timestampMs - targetTimeMs) } ?: return
        
        val timeS = closest.timestampMs / 1000.0
        val vEst = sqrt(closest.vN_estimated * closest.vN_estimated + closest.vE_estimated * closest.vE_estimated)
        val vRef = sqrt(closest.vN_reference * closest.vN_reference + closest.vE_reference * closest.vE_reference)
        val posError = sqrt(
            (closest.posN_estimated - closest.posN_reference) * (closest.posN_estimated - closest.posN_reference) +
            (closest.posE_estimated - closest.posE_reference) * (closest.posE_estimated - closest.posE_reference)
        )
        
        println("$label (t=${timeS.format(1)}s)")
        println("  Reference velocity:  vN=${closest.vN_reference.format(3)} m/s, vE=${closest.vE_reference.format(3)} m/s, speed=${vRef.format(3)} m/s")
        println("  ESKF velocity:       vN=${closest.vN_estimated.format(3)} m/s, vE=${closest.vE_estimated.format(3)} m/s, speed=${vEst.format(3)} m/s")
        println("  Velocity error:      ${(vEst - vRef).format(3)} m/s")
        println("  Position error:      ${posError.format(3)} m")
        println()
    }
    
    /**
     * Assess velocity accuracy
     */
    private fun assessAccuracy(metrics: VelocityMetrics): String {
        val assessment = StringBuilder()
        
        assessment.appendLine("Current RoNIN 6-DOF GRV velocity prediction accuracy:")
        assessment.appendLine()
        
        // Velocity RMSE assessment
        when {
            metrics.velocityRMSE < 0.5 -> assessment.appendLine("✅ Velocity RMSE (${metrics.velocityRMSE.format(3)} m/s): EXCELLENT - High accuracy")
            metrics.velocityRMSE < 1.0 -> assessment.appendLine("✅ Velocity RMSE (${metrics.velocityRMSE.format(3)} m/s): GOOD - Acceptable accuracy")
            metrics.velocityRMSE < 2.0 -> assessment.appendLine("⚠️  Velocity RMSE (${metrics.velocityRMSE.format(3)} m/s): MODERATE - Needs improvement")
            else -> assessment.appendLine("❌ Velocity RMSE (${metrics.velocityRMSE.format(3)} m/s): POOR - Significant errors")
        }
        
        // Speed bias assessment
        when {
            abs(metrics.meanSpeedBias) < 0.2 -> assessment.appendLine("✅ Speed bias (${metrics.meanSpeedBias.format(3)} m/s): MINIMAL - Well calibrated")
            abs(metrics.meanSpeedBias) < 0.5 -> assessment.appendLine("⚠️  Speed bias (${metrics.meanSpeedBias.format(3)} m/s): MODERATE - Some systematic error")
            else -> assessment.appendLine("❌ Speed bias (${metrics.meanSpeedBias.format(3)} m/s): HIGH - Systematic error present")
        }
        
        // Direction error assessment
        when {
            metrics.meanDirectionError < 5.0 -> assessment.appendLine("✅ Direction error (${metrics.meanDirectionError.format(2)}°): EXCELLENT - Accurate heading")
            metrics.meanDirectionError < 10.0 -> assessment.appendLine("✅ Direction error (${metrics.meanDirectionError.format(2)}°): GOOD - Acceptable heading")
            metrics.meanDirectionError < 20.0 -> assessment.appendLine("⚠️  Direction error (${metrics.meanDirectionError.format(2)}°): MODERATE - Heading drift")
            else -> assessment.appendLine("❌ Direction error (${metrics.meanDirectionError.format(2)}°): POOR - Significant heading error")
        }
        
        // Correlation assessment
        when {
            metrics.correlation > 0.95 -> assessment.appendLine("✅ Correlation (${metrics.correlation.format(4)}): EXCELLENT - Strong agreement")
            metrics.correlation > 0.85 -> assessment.appendLine("✅ Correlation (${metrics.correlation.format(4)}): GOOD - Good agreement")
            metrics.correlation > 0.70 -> assessment.appendLine("⚠️  Correlation (${metrics.correlation.format(4)}): MODERATE - Weak agreement")
            else -> assessment.appendLine("❌ Correlation (${metrics.correlation.format(4)}): POOR - Low agreement")
        }
        
        assessment.appendLine()
        
        // Overall verdict
        val goodMetrics = listOf(
            metrics.velocityRMSE < 1.0,
            abs(metrics.meanSpeedBias) < 0.5,
            metrics.meanDirectionError < 10.0,
            metrics.correlation > 0.85
        ).count { it }
        
        assessment.appendLine("Overall: $goodMetrics/4 metrics in good range")
        
        when {
            goodMetrics >= 3 -> assessment.appendLine("✅ VERDICT: Current RoNIN velocity predictions are ACCURATE")
            goodMetrics >= 2 -> assessment.appendLine("⚠️  VERDICT: Current RoNIN velocity predictions show MODERATE accuracy")
            else -> assessment.appendLine("❌ VERDICT: Current RoNIN velocity predictions are NOT accurate - requires calibration/debugging")
        }
        
        return assessment.toString()
    }
    
    private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)
    
    /**
     * Data classes
     */
    data class VelocityPair(
        val timestampMs: Long,
        val vN_estimated: Double,
        val vE_estimated: Double,
        val vN_reference: Double,
        val vE_reference: Double,
        val posN_estimated: Double,
        val posE_estimated: Double,
        val posN_reference: Double,
        val posE_reference: Double,
        val inOutage: Boolean
    )
    
    data class VelocityMetrics(
        val label: String,
        val sampleCount: Int,
        val velocityRMSE: Double,
        val velocityMAE: Double,
        val speedRMSE: Double,
        val meanSpeedBias: Double,
        val meanDirectionError: Double,
        val correlation: Double,
        val maxVelocityError: Double,
        val positionRMSE: Double,
        val maxPositionError: Double,
        val outageStartPositionError: Double,
        val outageEndPositionError: Double
    )
}
