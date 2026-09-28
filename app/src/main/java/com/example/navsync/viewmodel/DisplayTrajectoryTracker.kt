package com.example.navsync.viewmodel

import com.example.navsync.data.DatasetPoint
import com.example.navsync.data.NavigationDataset
import com.example.navsync.model.NavigationState
import kotlin.math.*

/**
 * DEMO-ONLY: Progressive trajectory tracking map matching for controlled dataset replay.
 * 
 * This class demonstrates trajectory-following map matching by maintaining progress
 * along the BLUE reference trajectory and applying corridor corrections based on
 * cross-track distance to the current trajectory segment.
 * 
 * CRITICAL NOTES:
 * - This is DEMO-ONLY controlled replay visualization
 * - This does NOT represent navigation ground truth or modify the estimator
 * - This does NOT feed reference data back into ESKF/RoNIN
 * - Real-world navigation would use OpenStreetMap or other road data
 * 
 * TRAJECTORY PROGRESS LOGIC:
 * - Initialize progress from last valid GNSS position
 * - Progress ONLY FORWARD along BLUE reference trajectory
 * - Calculate cross-track distance to current reference segment
 * - Apply corridor corrections based on cross-track thresholds
 */
class DisplayTrajectoryTracker(
    private val referenceDataset: NavigationDataset
) {
    
    // Corridor configuration
    companion object {
        const val INNER_THRESHOLD = 5.0    // meters - no correction zone
        const val OUTER_THRESHOLD = 10.0   // meters - gentle correction starts
        const val STRONG_THRESHOLD = 20.0  // meters - strong correction zone
        const val HARD_THRESHOLD = 30.0    // meters - maximum correction
    }
    
    // Reference trajectory points
    private val referencePoints: List<DatasetPoint> = referenceDataset.points
    
    // Trajectory progress tracking state
    private var isInOutage = false
    private var referenceProgressIndex: Int = 0                    // Current index along BLUE trajectory
    private var referenceProgressMeters: Double = 0.0              // Arc length progress along trajectory
    private var lastDisplayPosition: Pair<Double, Double>? = null
    private var lastUpdateTime: Long = 0L
    
    /**
     * Get trajectory-following display position during GNSS outage.
     * 
     * Maintains forward progress along BLUE reference trajectory and applies
     * corridor corrections based on cross-track distance to current segment.
     * 
     * @param rawESKFState Current ESKF/RoNIN estimated state (unmodified)
     * @param referencePoint Current reference point from dataset (for timing)
     * @param currentReplayIndex Current step index (for progress advancement)
     * @return Dual display state with trajectory progress tracking
     */
    fun getTrajectoryProgressDisplayPosition(
        rawESKFState: NavigationState,
        referencePoint: DatasetPoint,
        currentReplayIndex: Int
    ): DisplayPositions {
        
        val currentTime = System.currentTimeMillis()
        
        // Initialize trajectory progress on first call
        if (!isInOutage) {
            isInOutage = true
            
            // Find initial reference progress from last valid GNSS position
            initializeTrajectoryProgress(rawESKFState.latitude, rawESKFState.longitude)
            
            lastDisplayPosition = Pair(rawESKFState.latitude, rawESKFState.longitude)
            lastUpdateTime = currentTime
            
            android.util.Log.i("DisplayTrajectory", "DEMO-ONLY: trajectory progress tracking for controlled dataset replay")
            android.util.Log.i("DisplayTrajectory", "This is DEMO-ONLY controlled replay visualization and does NOT feed reference data back into ESKF/RoNIN")
            android.util.Log.i("DisplayTrajectory", "Initial progress: index=${referenceProgressIndex}, meters=${String.format("%.1f", referenceProgressMeters)}")
        }
        
        // Advance trajectory progress based on replay movement
        advanceTrajectoryProgress(currentReplayIndex)
        
        // Get current reference trajectory segment
        val (currentRefLat, currentRefLon, nextRefLat, nextRefLon) = getCurrentReferenceSegment()
        
        // Calculate cross-track distance to current BLUE reference segment
        val (projectedLat, projectedLon, crossTrackDistance) = calculateCrossTrackDistance(
            rawESKFState.latitude, rawESKFState.longitude,
            currentRefLat, currentRefLon, nextRefLat, nextRefLon
        )
        
        // Apply corridor-based correction to current reference segment
        val (displayLat, displayLon, correctionApplied, thresholdZone) = applyTrajectoryCorrection(
            rawESKFState.latitude, rawESKFState.longitude,
            projectedLat, projectedLon,
            crossTrackDistance
        )
        
        // Calculate display speed from consecutive display positions
        val displaySpeed = calculateDisplaySpeed(displayLat, displayLon)
        
        // Update tracking
        lastDisplayPosition = Pair(displayLat, displayLon)
        lastUpdateTime = currentTime
        
        // Calculate final cross-track distance for logging
        val displayCrossTrack = calculatePointToSegmentDistance(
            displayLat, displayLon,
            currentRefLat, currentRefLon, nextRefLat, nextRefLon
        )
        
        // TRAJECTORY PROGRESS LOGGING
        android.util.Log.e("DisplayTrajectory", 
            "TRAJECTORY: t=${currentTime/1000.0}s " +
            "refIndex=${referenceProgressIndex} " +
            "progress=${String.format("%.1f", referenceProgressMeters)}m " +
            "rawCrossTrack=${String.format("%.1f", crossTrackDistance)}m " +
            "zone=${thresholdZone} " +
            "correction=${String.format("%.1f", correctionApplied)}m " +
            "displayCrossTrack=${String.format("%.1f", displayCrossTrack)}m " +
            "speed=${String.format("%.1f", displaySpeed)}km/h"
        )
        
        android.util.Log.d("DisplayTrajectory", 
            "BLUE ref: (${String.format("%.6f", projectedLat)}, ${String.format("%.6f", projectedLon)}) " +
            "RED raw: (${String.format("%.6f", rawESKFState.latitude)}, ${String.format("%.6f", rawESKFState.longitude)}) " +
            "ORANGE display: (${String.format("%.6f", displayLat)}, ${String.format("%.6f", displayLon)})"
        )
        
        return DisplayPositions(
            rawESKFPosition = PositionState(
                latitude = rawESKFState.latitude,
                longitude = rawESKFState.longitude,
                speedKmh = rawESKFState.speedKmh,  // Keep raw ESKF speed for diagnostics
                headingDegrees = rawESKFState.headingDegrees,
                confidence = rawESKFState.confidence
            ),
            mapMatchedPosition = PositionState(
                latitude = displayLat,
                longitude = displayLon,
                speedKmh = displaySpeed,  // Use display speed from trajectory-constrained movement
                headingDegrees = rawESKFState.headingDegrees,  // Keep heading from ESKF
                confidence = rawESKFState.confidence
            ),
            driftDistanceMeters = correctionApplied
        )
    }
    
    /**
     * Reset tracker (called when GNSS is restored).
     */
    fun reset() {
        isInOutage = false
        referenceProgressIndex = 0
        referenceProgressMeters = 0.0
        lastDisplayPosition = null
        lastUpdateTime = 0L
        android.util.Log.i("DisplayTrajectory", "Trajectory progress tracking reset (GNSS restored)")
    }
    
    /**
     * Initialize trajectory progress from last valid GNSS position.
     * 
     * Finds the reference trajectory point closest to the GNSS outage start position
     * and sets that as the initial progress along the BLUE reference trajectory.
     */
    private fun initializeTrajectoryProgress(gnssLat: Double, gnssLon: Double) {
        if (referencePoints.isEmpty()) {
            referenceProgressIndex = 0
            referenceProgressMeters = 0.0
            return
        }
        
        var nearestIndex = 0
        var minDistance = Double.MAX_VALUE
        
        // Find reference point closest to last valid GNSS position
        for (i in referencePoints.indices) {
            val refPoint = referencePoints[i]
            val distance = calculateDistance(
                gnssLat, gnssLon,
                refPoint.gnssData.latitude, refPoint.gnssData.longitude
            )
            
            if (distance < minDistance) {
                minDistance = distance
                nearestIndex = i
            }
        }
        
        referenceProgressIndex = nearestIndex
        referenceProgressMeters = calculateArcLengthToIndex(nearestIndex)
        
        android.util.Log.i("DisplayTrajectory", 
            "Initialized trajectory progress: index=${nearestIndex}, distance=${String.format("%.1f", minDistance)}m, arcLength=${String.format("%.1f", referenceProgressMeters)}m"
        )
    }
    
    /**
     * Advance trajectory progress based on replay movement.
     * 
     * Progresses ONLY FORWARD along the BLUE reference trajectory based on
     * replay timing, not based on raw ESKF position.
     */
    private fun advanceTrajectoryProgress(currentReplayIndex: Int) {
        if (referencePoints.isEmpty()) return
        
        // Progress forward along trajectory based on replay index advancement
        val targetProgressIndex = currentReplayIndex.coerceIn(referenceProgressIndex, referencePoints.size - 1)
        
        // Only move forward along trajectory (never backward)
        if (targetProgressIndex > referenceProgressIndex) {
            referenceProgressIndex = targetProgressIndex
            referenceProgressMeters = calculateArcLengthToIndex(referenceProgressIndex)
        }
        
        // Ensure we don't exceed trajectory bounds
        referenceProgressIndex = referenceProgressIndex.coerceIn(0, referencePoints.size - 2)
    }
    
    /**
     * Get current reference trajectory segment being tracked.
     * 
     * Returns the segment of BLUE reference trajectory at current progress position.
     */
    private fun getCurrentReferenceSegment(): Tuple4<Double, Double, Double, Double> {
        if (referencePoints.isEmpty() || referenceProgressIndex >= referencePoints.size - 1) {
            // Fallback to last available segment
            val lastIndex = (referencePoints.size - 2).coerceAtLeast(0)
            val current = referencePoints[lastIndex]
            val next = referencePoints[lastIndex + 1]
            return Tuple4(
                current.gnssData.latitude, current.gnssData.longitude,
                next.gnssData.latitude, next.gnssData.longitude
            )
        }
        
        val currentPoint = referencePoints[referenceProgressIndex]
        val nextPoint = referencePoints[referenceProgressIndex + 1]
        
        return Tuple4(
            currentPoint.gnssData.latitude, currentPoint.gnssData.longitude,
            nextPoint.gnssData.latitude, nextPoint.gnssData.longitude
        )
    }
    
    /**
     * Calculate cross-track distance from raw position to current reference segment.
     * 
     * Projects raw ESKF position onto current BLUE reference segment and
     * calculates perpendicular (cross-track) distance.
     */
    private fun calculateCrossTrackDistance(
        rawLat: Double, rawLon: Double,
        segLat1: Double, segLon1: Double,
        segLat2: Double, segLon2: Double
    ): Triple<Double, Double, Double> {
        
        // Convert to approximate meters for geometry calculations
        val toMetersLat = 111000.0
        val toMetersLon = 111000.0 * cos(Math.toRadians(rawLat))
        
        val rawX = rawLon * toMetersLon
        val rawY = rawLat * toMetersLat
        val seg1X = segLon1 * toMetersLon
        val seg1Y = segLat1 * toMetersLat
        val seg2X = segLon2 * toMetersLon
        val seg2Y = segLat2 * toMetersLat
        
        // Vector from segment start to end
        val segVecX = seg2X - seg1X
        val segVecY = seg2Y - seg1Y
        val segLengthSq = segVecX * segVecX + segVecY * segVecY
        
        if (segLengthSq < 1e-6) {
            // Segment is essentially a point
            val distance = calculateDistance(rawLat, rawLon, segLat1, segLon1)
            return Triple(segLat1, segLon1, distance)
        }
        
        // Vector from segment start to raw position
        val rawVecX = rawX - seg1X
        val rawVecY = rawY - seg1Y
        
        // Project raw position onto segment line (parameter t)
        val t = (rawVecX * segVecX + rawVecY * segVecY) / segLengthSq
        
        // Clamp t to [0, 1] to stay on segment
        val clampedT = t.coerceIn(0.0, 1.0)
        
        // Calculate projected point in meters
        val projectedX = seg1X + clampedT * segVecX
        val projectedY = seg1Y + clampedT * segVecY
        
        // Convert back to lat/lon
        val projectedLat = projectedY / toMetersLat
        val projectedLon = projectedX / toMetersLon
        
        // Calculate cross-track (perpendicular) distance
        val crossTrackDistance = sqrt((rawX - projectedX) * (rawX - projectedX) + 
                                      (rawY - projectedY) * (rawY - projectedY))
        
        return Triple(projectedLat, projectedLon, crossTrackDistance)
    }
    
    /**
     * Apply trajectory-following correction based on cross-track distance.
     * 
     * Applies corridor corrections toward current BLUE reference segment
     * based on cross-track distance thresholds.
     * 
     * CRITICAL: Verifies correction reduces distance to blue reference.
     */
    private fun applyTrajectoryCorrection(
        rawLat: Double, rawLon: Double,
        referenceProjectedLat: Double, referenceProjectedLon: Double,
        crossTrackDistance: Double
    ): Tuple4<Double, Double, Double, String> {
        
        // Determine correction factor based on cross-track distance from BLUE trajectory
        val (correctionFactor, thresholdZone) = when {
            crossTrackDistance <= INNER_THRESHOLD -> {
                // Within inner corridor - no correction needed
                Pair(0.0, "INNER")
            }
            crossTrackDistance <= OUTER_THRESHOLD -> {
                // Gentle correction zone - smooth ramp from 0% to 50%
                val ratio = (crossTrackDistance - INNER_THRESHOLD) / (OUTER_THRESHOLD - INNER_THRESHOLD)
                Pair(ratio * 0.5, "OUTER")  // 0% to 50% correction
            }
            crossTrackDistance <= STRONG_THRESHOLD -> {
                // Strong correction zone - ramp from 50% to 70%
                val ratio = (crossTrackDistance - OUTER_THRESHOLD) / (STRONG_THRESHOLD - OUTER_THRESHOLD)
                Pair(0.5 + ratio * 0.2, "STRONG")  // 50% to 70% correction
            }
            crossTrackDistance <= HARD_THRESHOLD -> {
                // Hard correction zone - ramp from 70% to 80%
                val ratio = (crossTrackDistance - STRONG_THRESHOLD) / (HARD_THRESHOLD - STRONG_THRESHOLD)
                Pair(0.7 + ratio * 0.1, "HARD")  // 70% to 80% correction
            }
            else -> {
                // Outside hard threshold - maximum correction toward BLUE trajectory
                Pair(0.8, "MAX")  // 80% correction
            }
        }
        
        // Apply correction toward current BLUE reference segment projection
        val correctedLat = rawLat + (referenceProjectedLat - rawLat) * correctionFactor
        val correctedLon = rawLon + (referenceProjectedLon - rawLon) * correctionFactor
        
        // CRITICAL BUG FIX: Verify correction moves orange TOWARD blue reference
        val beforeDistance = calculateDistance(rawLat, rawLon, referenceProjectedLat, referenceProjectedLon)
        val afterDistance = calculateDistance(correctedLat, correctedLon, referenceProjectedLat, referenceProjectedLon)
        
        // If correction would increase distance to blue reference, reject it
        if (afterDistance > beforeDistance) {
            android.util.Log.w("DisplayTrajectory", 
                "CORRECTION REJECTED: would move away from blue reference " +
                "before=${String.format("%.1f", beforeDistance)}m after=${String.format("%.1f", afterDistance)}m"
            )
            // Return raw position unchanged
            return Tuple4(rawLat, rawLon, 0.0, "${thresholdZone}_REJECTED")
        }
        
        // Calculate actual correction distance applied (should always be positive now)
        val correctionDistance = calculateDistance(rawLat, rawLon, correctedLat, correctedLon)
        
        return Tuple4(correctedLat, correctedLon, correctionDistance, thresholdZone)
    }
    
    /**
     * Calculate arc length distance along trajectory to given index.
     */
    private fun calculateArcLengthToIndex(targetIndex: Int): Double {
        if (referencePoints.isEmpty() || targetIndex <= 0) return 0.0
        
        var arcLength = 0.0
        val maxIndex = (targetIndex).coerceAtMost(referencePoints.size - 1)
        
        for (i in 0 until maxIndex) {
            val current = referencePoints[i]
            val next = referencePoints[i + 1]
            
            val segmentLength = calculateDistance(
                current.gnssData.latitude, current.gnssData.longitude,
                next.gnssData.latitude, next.gnssData.longitude
            )
            
            arcLength += segmentLength
        }
        
        return arcLength
    }
    
    /**
     * Calculate point-to-segment distance for logging purposes.
     */
    private fun calculatePointToSegmentDistance(
        pointLat: Double, pointLon: Double,
        segLat1: Double, segLon1: Double,
        segLat2: Double, segLon2: Double
    ): Double {
        val (_, _, distance) = calculateCrossTrackDistance(pointLat, pointLon, segLat1, segLon1, segLat2, segLon2)
        return distance
    }
    
    /**
     * Calculate display speed from consecutive display positions.
     * 
     * This represents the speed of the corridor-constrained vehicle,
     * not the raw ESKF velocity which may be inaccurate during outages.
     */
    private fun calculateDisplaySpeed(currentLat: Double, currentLon: Double): Double {
        val lastPos = lastDisplayPosition
        val currentTime = System.currentTimeMillis()
        
        if (lastPos == null || lastUpdateTime == 0L) {
            return 0.0  // No previous position for speed calculation
        }
        
        val deltaTimeMs = currentTime - lastUpdateTime
        if (deltaTimeMs <= 0) {
            return 0.0  // Avoid division by zero
        }
        
        val deltaTimeSeconds = deltaTimeMs / 1000.0
        val distanceMeters = calculateDistance(
            lastPos.first, lastPos.second,
            currentLat, currentLon
        )
        
        // Convert m/s to km/h
        val speedMs = distanceMeters / deltaTimeSeconds
        val speedKmh = speedMs * 3.6
        
        // Apply reasonable bounds (0-120 km/h)
        return speedKmh.coerceIn(0.0, 120.0)
    }
    
    /**
     * Calculate great-circle distance between two points (Haversine formula).
     * 
     * @return Distance in meters
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0  // Earth radius in meters
        
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1Rad) * cos(lat2Rad) *
                sin(dLon / 2) * sin(dLon / 2)
        
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        
        return R * c
    }
}

/**
 * Dual position state for outage display.
 * Shows both raw ESKF drift and corridor-constrained corrected positions.
 */
data class DisplayPositions(
    val rawESKFPosition: PositionState,
    val mapMatchedPosition: PositionState,
    val driftDistanceMeters: Double
)

/**
 * Individual position state.
 */
data class PositionState(
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double,
    val headingDegrees: Double,
    val confidence: Double
)

/**
 * Helper data class for 4-tuple return values.
 */
data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)