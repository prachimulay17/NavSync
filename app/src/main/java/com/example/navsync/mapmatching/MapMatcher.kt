package com.example.navsync.mapmatching

import com.example.navsync.model.NavigationState
import kotlin.math.*

/**
 * Lightweight road map-matching for NavSync prototype.
 * 
 * Projects ESKF position estimates onto nearby road segments while maintaining
 * temporal continuity and handling fallback to raw positions when needed.
 */
class MapMatcher(
    private val config: MapMatchingConfig = MapMatchingConfig.default()
) {
    
    // Road network (in production, would load from OSM or other source)
    private val roadNetwork = generateSyntheticRoadNetwork()
    
    // Continuity tracking
    private var lastMatchedSegment: RoadSegment? = null
    private var lastRawPosition: Pair<Double, Double>? = null
    private var lastMatchedPosition: Pair<Double, Double>? = null
    
    /**
     * Perform map-matching on navigation state.
     * 
     * @param navigationState Raw ESKF/RoNIN navigation state
     * @return Map-matched state with smoothed position, or original if no match
     */
    fun matchToRoad(navigationState: NavigationState): NavigationState {
        
        // Find candidate road segments near the estimated position
        val candidates = findCandidateSegments(
            navigationState.latitude, 
            navigationState.longitude,
            config.searchRadiusMeters
        )
        
        if (candidates.isEmpty()) {
            // No roads nearby - use raw ESKF position
            lastMatchedSegment = null
            updatePositionHistory(navigationState.latitude, navigationState.longitude, null)
            return navigationState
        }
        
        // Score and select best segment
        val bestMatch = selectBestSegment(
            candidates,
            navigationState.latitude,
            navigationState.longitude, 
            navigationState.headingDegrees,
            navigationState.speedKmh
        )
        
        if (bestMatch == null) {
            // No suitable match - use raw ESKF position
            lastMatchedSegment = null
            updatePositionHistory(navigationState.latitude, navigationState.longitude, null)
            return navigationState
        }
        
        // Project position onto selected road segment
        val (projectedLat, projectedLon, distance) = bestMatch.segment.projectPoint(
            navigationState.latitude, navigationState.longitude
        )
        
        // Apply temporal smoothing
        val (smoothedLat, smoothedLon) = applyTemporalSmoothing(
            projectedLat, projectedLon,
            navigationState.latitude, navigationState.longitude
        )
        
        // Update continuity tracking
        lastMatchedSegment = bestMatch.segment
        updatePositionHistory(navigationState.latitude, navigationState.longitude, Pair(smoothedLat, smoothedLon))
        
        // Return enhanced navigation state with map-matched position
        return navigationState.copy(
            latitude = smoothedLat,
            longitude = smoothedLon
            // Keep all other fields (speed, heading, confidence) unchanged
        )
    }
    
    /**
     * Find road segments within search radius of given position.
     */
    private fun findCandidateSegments(
        latitude: Double, 
        longitude: Double, 
        radiusMeters: Double
    ): List<RoadSegment> {
        return roadNetwork.filter { segment ->
            // Check if segment bounding box intersects search area
            val minLat = min(segment.startLatitude, segment.endLatitude)
            val maxLat = max(segment.startLatitude, segment.endLatitude)
            val minLon = min(segment.startLongitude, segment.endLongitude)
            val maxLon = max(segment.startLongitude, segment.endLongitude)
            
            // Quick bounding box check first
            val latRange = radiusMeters / 111000.0  // Approximate degrees
            val lonRange = radiusMeters / (111000.0 * cos(Math.toRadians(latitude)))
            
            if (latitude < minLat - latRange || latitude > maxLat + latRange ||
                longitude < minLon - lonRange || longitude > maxLon + lonRange) {
                return@filter false
            }
            
            // More precise distance check
            val (_, _, distance) = segment.projectPoint(latitude, longitude)
            distance <= radiusMeters
        }
    }
    
    /**
     * Select best road segment from candidates based on multiple criteria.
     */
    private fun selectBestSegment(
        candidates: List<RoadSegment>,
        latitude: Double,
        longitude: Double,
        vehicleHeading: Double,
        vehicleSpeed: Double
    ): SegmentMatch? {
        
        var bestMatch: SegmentMatch? = null
        var bestScore = Double.NEGATIVE_INFINITY
        
        for (segment in candidates) {
            val score = scoreSegment(segment, latitude, longitude, vehicleHeading, vehicleSpeed)
            
            if (score > bestScore) {
                bestScore = score
                val (projLat, projLon, distance) = segment.projectPoint(latitude, longitude)
                bestMatch = SegmentMatch(segment, projLat, projLon, distance, score)
            }
        }
        
        // Only return match if score is above threshold
        return if (bestMatch != null && bestMatch.score > config.minimumMatchScore) {
            bestMatch
        } else {
            null
        }
    }
    
    /**
     * Score a road segment for map-matching suitability.
     */
    private fun scoreSegment(
        segment: RoadSegment,
        latitude: Double,
        longitude: Double,
        vehicleHeading: Double,
        vehicleSpeed: Double
    ): Double {
        val (_, _, distance) = segment.projectPoint(latitude, longitude)
        
        var score = 0.0
        
        // Distance penalty (closer is better)
        score -= distance / config.searchRadiusMeters * 50.0
        
        // Direction compatibility (prefer segments aligned with vehicle heading)
        if (segment.isDirectionCompatible(vehicleHeading, config.headingToleranceDegrees)) {
            score += 30.0
            
            // Bonus for close alignment
            val angleDiff = abs(normalizeAngleDiff(segment.bearing - vehicleHeading))
            val backwardAngleDiff = abs(normalizeAngleDiff(segment.bearing - vehicleHeading + 180.0))
            val minAngleDiff = min(angleDiff, if (!segment.oneway) backwardAngleDiff else 180.0)
            
            score += (45.0 - minAngleDiff) / 45.0 * 20.0
        } else {
            score -= 40.0  // Heavy penalty for wrong direction
        }
        
        // Continuity bonus (prefer to stay on same segment)
        if (lastMatchedSegment?.id == segment.id) {
            score += 25.0
        }
        
        // Road type preference (prefer major roads)
        when (segment.roadType) {
            RoadType.MOTORWAY -> score += 15.0
            RoadType.PRIMARY -> score += 10.0
            RoadType.SECONDARY -> score += 5.0
            RoadType.TERTIARY -> score += 2.0
            RoadType.RESIDENTIAL -> score += 0.0
            RoadType.SERVICE -> score -= 5.0
            RoadType.UNKNOWN -> score -= 2.0
        }
        
        return score
    }
    
    /**
     * Apply temporal smoothing to avoid position jitter.
     */
    private fun applyTemporalSmoothing(
        matchedLat: Double,
        matchedLon: Double,
        rawLat: Double,
        rawLon: Double
    ): Pair<Double, Double> {
        
        val lastMatched = lastMatchedPosition
        if (lastMatched == null) {
            // First match - no smoothing
            return Pair(matchedLat, matchedLon)
        }
        
        // Calculate movement from last matched position
        val matchedMovement = RoadSegment.calculateDistance(
            lastMatched.first, lastMatched.second, matchedLat, matchedLon
        )
        
        val lastRaw = lastRawPosition
        val rawMovement = if (lastRaw != null) {
            RoadSegment.calculateDistance(lastRaw.first, lastRaw.second, rawLat, rawLon)
        } else {
            matchedMovement
        }
        
        // If matched movement is much larger than raw movement, apply smoothing
        if (matchedMovement > rawMovement * 2.0 && rawMovement < config.maxJumpDistanceMeters) {
            // Interpolate between last matched position and new matched position
            val alpha = config.temporalSmoothingFactor
            return Pair(
                lastMatched.first * (1 - alpha) + matchedLat * alpha,
                lastMatched.second * (1 - alpha) + matchedLon * alpha
            )
        }
        
        return Pair(matchedLat, matchedLon)
    }
    
    /**
     * Update position history for continuity tracking.
     */
    private fun updatePositionHistory(
        rawLat: Double, 
        rawLon: Double, 
        matched: Pair<Double, Double>?
    ) {
        lastRawPosition = Pair(rawLat, rawLon)
        lastMatchedPosition = matched
    }
    
    /**
     * Normalize angle difference to [-180, 180] degrees.
     */
    private fun normalizeAngleDiff(angleDiff: Double): Double {
        var normalized = angleDiff
        while (normalized > 180.0) normalized -= 360.0
        while (normalized < -180.0) normalized += 360.0
        return normalized
    }
    
    /**
     * Generate synthetic road network for prototype.
     * In production, this would load from OpenStreetMap or other road data source.
     */
    private fun generateSyntheticRoadNetwork(): List<RoadSegment> {
        val segments = mutableListOf<RoadSegment>()
        
        // Create a basic road network around the S-Vw9/V-Vw9 area (Coventry, UK)
        val baseLatitude = 52.2028
        val baseLongitude = -2.2002
        
        // Main road running roughly east-west
        segments.add(RoadSegment(
            id = "main_ew_1",
            startLatitude = baseLatitude,
            startLongitude = baseLongitude - 0.01,
            endLatitude = baseLatitude,
            endLongitude = baseLongitude + 0.01,
            roadType = RoadType.PRIMARY
        ))
        
        // Main road running roughly north-south
        segments.add(RoadSegment(
            id = "main_ns_1", 
            startLatitude = baseLatitude - 0.01,
            startLongitude = baseLongitude,
            endLatitude = baseLatitude + 0.01,
            endLongitude = baseLongitude,
            roadType = RoadType.PRIMARY
        ))
        
        // Secondary roads in a grid pattern
        for (i in -2..2) {
            for (j in -2..2) {
                val offsetLat = i * 0.003
                val offsetLon = j * 0.003
                
                // Horizontal segments
                segments.add(RoadSegment(
                    id = "grid_h_${i}_$j",
                    startLatitude = baseLatitude + offsetLat,
                    startLongitude = baseLongitude + offsetLon - 0.002,
                    endLatitude = baseLatitude + offsetLat,
                    endLongitude = baseLongitude + offsetLon + 0.002,
                    roadType = RoadType.SECONDARY
                ))
                
                // Vertical segments
                segments.add(RoadSegment(
                    id = "grid_v_${i}_$j",
                    startLatitude = baseLatitude + offsetLat - 0.002,
                    startLongitude = baseLongitude + offsetLon,
                    endLatitude = baseLatitude + offsetLat + 0.002,
                    endLongitude = baseLongitude + offsetLon,
                    roadType = RoadType.SECONDARY
                ))
            }
        }
        
        return segments
    }
    
    /**
     * Reset map-matching state (for new navigation sessions).
     */
    fun reset() {
        lastMatchedSegment = null
        lastRawPosition = null
        lastMatchedPosition = null
    }
}

/**
 * Map-matching configuration parameters.
 */
data class MapMatchingConfig(
    val searchRadiusMeters: Double = 50.0,           // Maximum distance to search for roads
    val headingToleranceDegrees: Double = 45.0,      // Angular tolerance for direction matching
    val minimumMatchScore: Double = 10.0,            // Minimum score required for road match
    val temporalSmoothingFactor: Double = 0.3,       // Smoothing factor (0=no smooth, 1=full smooth)
    val maxJumpDistanceMeters: Double = 20.0         // Maximum position jump before applying smoothing
) {
    companion object {
        fun default() = MapMatchingConfig()
        
        fun conservative() = MapMatchingConfig(
            searchRadiusMeters = 30.0,
            headingToleranceDegrees = 30.0,
            minimumMatchScore = 20.0,
            temporalSmoothingFactor = 0.5,
            maxJumpDistanceMeters = 10.0
        )
        
        fun aggressive() = MapMatchingConfig(
            searchRadiusMeters = 100.0,
            headingToleranceDegrees = 60.0,
            minimumMatchScore = 5.0,
            temporalSmoothingFactor = 0.1,
            maxJumpDistanceMeters = 50.0
        )
    }
}

/**
 * Result of segment matching.
 */
data class SegmentMatch(
    val segment: RoadSegment,
    val projectedLatitude: Double,
    val projectedLongitude: Double,
    val distanceMeters: Double,
    val score: Double
)