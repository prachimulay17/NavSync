package com.example.navsync.mapmatching

import com.example.navsync.model.NavigationState
import com.example.navsync.model.NavigationSource
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

/**
 * Diagnostics for map-matching issues during AI_ESTIMATION phase.
 * Analyzes the V-Vw9 reference trajectory vs synthetic road network.
 */
class MapMatchingDiagnostics {

    @Test
    fun diagnoseSyntheticRoadNetworkAlignment() {
        val mapMatcher = MapMatcher()
        
        // Extract V-Vw9 reference trajectory points from first ~60 seconds
        val referenceTrajectory = listOf(
            // Starting points from V-Vw9.csv
            Pair(52.2028035, -2.2002006),    // t=57839.9s
            Pair(52.2028023, -2.2002110),    // t=57840.0s
            Pair(52.2028013, -2.2002217),    // t=57840.1s
            Pair(52.2028001, -2.2002324),    // t=57840.2s
            Pair(52.2027993, -2.2002432),    // t=57840.3s
            Pair(52.2027986, -2.2002543),    // t=57840.4s
            Pair(52.2027977, -2.2002652),    // t=57840.5s
            Pair(52.2027969, -2.2002764),    // t=57840.6s
            Pair(52.2027963, -2.2002878),    // t=57840.7s
            Pair(52.2027953, -2.2002992),    // t=57840.8s
            // ... continuing through the outage period (35-50s would be around 57874-57889)
            Pair(52.2027825, -2.2003916),    // t=57841.6s
            Pair(52.2027824, -2.2004034),    // t=57841.7s
            Pair(52.2027810, -2.2004154),    // t=57841.8s
            Pair(52.2027796, -2.2004273),    // t=57841.9s
            Pair(52.2027782, -2.2004390),    // t=57842.0s
            Pair(52.2027773, -2.2004511),    // t=57842.1s
            Pair(52.2027762, -2.2004631),    // t=57842.2s
            Pair(52.2027752, -2.2004749),    // t=57842.3s
            Pair(52.2027745, -2.2004870),    // t=57842.4s
            Pair(52.2027735, -2.2004985),    // t=57842.5s
            Pair(52.2027725, -2.2005102),    // t=57842.6s
            Pair(52.2027715, -2.2005217),    // t=57842.7s
            Pair(52.2027703, -2.2005331),    // t=57842.8s
            Pair(52.2027691, -2.2005443),    // t=57842.9s
            Pair(52.2027678, -2.2005555),    // t=57843.0s
            Pair(52.2027664, -2.2005665),    // t=57843.1s
            Pair(52.2027647, -2.2005772),    // t=57843.2s
            Pair(52.2027631, -2.2005881),    // t=57843.3s
            Pair(52.2027616, -2.2005991),    // t=57843.4s
            Pair(52.2027601, -2.2006098),    // t=57843.5s
            Pair(52.2027587, -2.2006207),    // t=57843.6s
            Pair(52.2027572, -2.2006317),    // t=57843.7s
            Pair(52.2027558, -2.2006424),    // t=57843.8s
            Pair(52.2027545, -2.2006535),    // t=57843.9s
            Pair(52.2027533, -2.2006644),    // t=57844.0s
            Pair(52.2027523, -2.2006753),    // t=57844.1s
            Pair(52.2027516, -2.2006865),    // t=57844.2s
            Pair(52.2027507, -2.2006976),    // t=57844.3s
            Pair(52.2027497, -2.2007087),    // t=57844.4s
            Pair(52.2027489, -2.2007200),    // t=57844.5s
            Pair(52.2027482, -2.2007314),    // t=57844.6s
            Pair(52.2027473, -2.2007429)     // t=57844.7s
        )
        
        println("=== MAP-MATCHING DIAGNOSTICS v2 ===")
        println()
        
        // 1. Analyze distance between reference trajectory and synthetic road segments
        val roadSegments = getSyntheticRoadNetwork(mapMatcher)
        
        println("1. REFERENCE TRAJECTORY vs SYNTHETIC ROADS")
        println("------------------------------------------")
        
        var minDistanceSum = 0.0
        var maxDistance = 0.0
        var totalPoints = 0
        
        for ((index, point) in referenceTrajectory.withIndex()) {
            val (lat, lon) = point
            
            // Find closest road segment to this reference point
            var minDistance = Double.MAX_VALUE
            var closestSegment: RoadSegment? = null
            
            for (segment in roadSegments) {
                val (_, _, distance) = segment.projectPoint(lat, lon)
                if (distance < minDistance) {
                    minDistance = distance
                    closestSegment = segment
                }
            }
            
            minDistanceSum += minDistance
            maxDistance = maxOf(maxDistance, minDistance)
            totalPoints++
            
            if (index < 5 || index % 10 == 0 || minDistance > 30.0) {
                println("Point $index: (${lat}, ${lon}) -> closest road ${closestSegment?.id} at ${minDistance.toInt()}m")
            }
        }
        
        val avgDistance = minDistanceSum / totalPoints
        println()
        println("Average distance to nearest road: ${avgDistance.toInt()}m")
        println("Maximum distance to nearest road: ${maxDistance.toInt()}m")
        println()
        
        // 2. Analyze which segments are selected during critical period
        println("2. SEGMENT SELECTION DURING CRITICAL PERIOD")
        println("-------------------------------------------")
        
        // Simulate positions that would occur during 35-50s outage (AI_ESTIMATION)
        val criticalPeriodPoints = referenceTrajectory.subList(25, 40) // approximate outage window
        
        for ((index, point) in criticalPeriodPoints.withIndex()) {
            val (lat, lon) = point
            
            // Create a navigation state as if from AI_ESTIMATION
            val aiState = NavigationState(
                latitude = lat,
                longitude = lon,
                speedKmh = 28.0,
                headingDegrees = 260.0, // approximate westward heading from data
                confidence = 0.7,
                gnssAvailable = false,
                source = NavigationSource.AI_ESTIMATION
            )
            
            val matchedState = mapMatcher.matchToRoad(aiState)
            
            // Check if position was changed (matched) or fell back to raw
            val wasMatched = abs(matchedState.latitude - lat) > 0.000001 || abs(matchedState.longitude - lon) > 0.000001
            val matchDistance = RoadSegment.calculateDistance(lat, lon, matchedState.latitude, matchedState.longitude)
            
            println("Outage point $index: raw(${lat}, ${lon}) -> matched(${matchedState.latitude}, ${matchedState.longitude})")
            println("  Matched: $wasMatched, Distance moved: ${matchDistance.toInt()}m")
            
            if (wasMatched && matchDistance > 50.0) {
                println("  *** LARGE DISPLACEMENT - LIKELY OFF-ROAD ***")
            }
            
            println()
        }
        
        // 3. Analyze why matched position appears off-road
        println("3. OFF-ROAD ANALYSIS")
        println("--------------------")
        
        val testPoint = referenceTrajectory[30] // Mid-trajectory point
        val (testLat, testLon) = testPoint
        
        val candidateSegments = findCandidateSegments(roadSegments, testLat, testLon, 50.0)
        println("Candidate segments for point ($testLat, $testLon):")
        
        for (segment in candidateSegments) {
            val (projLat, projLon, distance) = segment.projectPoint(testLat, testLon)
            val isCompatible = segment.isDirectionCompatible(260.0, 45.0)
            val score = scoreSegmentDetailed(segment, testLat, testLon, 260.0, 28.0)
            
            println("Segment ${segment.id}:")
            println("  Start: (${segment.startLatitude}, ${segment.startLongitude})")
            println("  End: (${segment.endLatitude}, ${segment.endLongitude})")
            println("  Projected to: ($projLat, $projLon)")
            println("  Distance: ${distance.toInt()}m")
            println("  Direction compatible: $isCompatible")
            println("  Score: ${score.toInt()}")
            println()
        }
        
        // 4. Distance vs road-type priority analysis
        println("4. SCORING PRIORITY ANALYSIS")
        println("-----------------------------")
        
        // Test with a point that has nearby secondary road vs distant primary road
        val nearSecondary = RoadSegment("near_secondary", testLat, testLon - 0.0001, testLat, testLon + 0.0001, RoadType.SECONDARY)
        val distantPrimary = RoadSegment("distant_primary", testLat + 0.001, testLon - 0.001, testLat + 0.001, testLon + 0.001, RoadType.PRIMARY)
        
        val nearScore = scoreSegmentDetailed(nearSecondary, testLat, testLon, 260.0, 28.0)
        val distantScore = scoreSegmentDetailed(distantPrimary, testLat, testLon, 260.0, 28.0)
        
        val nearDistance = nearSecondary.projectPoint(testLat, testLon).third
        val distantDistance = distantPrimary.projectPoint(testLat, testLon).third
        
        println("Near SECONDARY road: ${nearDistance.toInt()}m away, score: ${nearScore.toInt()}")
        println("Distant PRIMARY road: ${distantDistance.toInt()}m away, score: ${distantScore.toInt()}")
        println("Winner: ${if (nearScore > distantScore) "Near secondary" else "Distant primary"}")
        println()
        
        // 5. Recommend minimal fix
        println("5. RECOMMENDED MINIMAL FIX")
        println("--------------------------")
        
        if (avgDistance > 30.0) {
            println("ISSUE: Synthetic road network poorly aligned with V-Vw9 trajectory")
            println("SOLUTION: Generate trajectory-specific road segments along actual path")
        }
        
        if (maxDistance > 100.0) {
            println("ISSUE: Some trajectory points very far from any roads")
            println("SOLUTION: Increase search radius or add more road density")
        }
        
        println("RECOMMENDATION: Replace synthetic roads with trajectory-derived road corridor")
        println("- Extract smooth road path from V-Vw9 reference trajectory")
        println("- Create road segments along this path with appropriate bearing")
        println("- Increase distance penalty to prioritize closest roads")
    }
    
    private fun getSyntheticRoadNetwork(mapMatcher: MapMatcher): List<RoadSegment> {
        // Access the synthetic road network from MapMatcher
        // Since it's private, we'll recreate the same logic here
        val segments = mutableListOf<RoadSegment>()
        
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
        
        // Grid pattern
        for (i in -2..2) {
            for (j in -2..2) {
                val offsetLat = i * 0.003
                val offsetLon = j * 0.003
                
                segments.add(RoadSegment(
                    id = "grid_h_${i}_$j",
                    startLatitude = baseLatitude + offsetLat,
                    startLongitude = baseLongitude + offsetLon - 0.002,
                    endLatitude = baseLatitude + offsetLat,
                    endLongitude = baseLongitude + offsetLon + 0.002,
                    roadType = RoadType.SECONDARY
                ))
                
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
    
    private fun findCandidateSegments(
        roadNetwork: List<RoadSegment>,
        latitude: Double, 
        longitude: Double, 
        radiusMeters: Double
    ): List<RoadSegment> {
        return roadNetwork.filter { segment ->
            val (_, _, distance) = segment.projectPoint(latitude, longitude)
            distance <= radiusMeters
        }
    }
    
    private fun scoreSegmentDetailed(
        segment: RoadSegment,
        latitude: Double,
        longitude: Double,
        vehicleHeading: Double,
        vehicleSpeed: Double
    ): Double {
        val (_, _, distance) = segment.projectPoint(latitude, longitude)
        
        var score = 0.0
        
        // Distance penalty (closer is better)
        score -= distance / 50.0 * 50.0  // Using same config as MapMatcher
        
        // Direction compatibility
        if (segment.isDirectionCompatible(vehicleHeading, 45.0)) {
            score += 30.0
            
            val angleDiff = abs(normalizeAngleDiff(segment.bearing - vehicleHeading))
            val backwardAngleDiff = abs(normalizeAngleDiff(segment.bearing - vehicleHeading + 180.0))
            val minAngleDiff = min(angleDiff, if (!segment.oneway) backwardAngleDiff else 180.0)
            
            score += (45.0 - minAngleDiff) / 45.0 * 20.0
        } else {
            score -= 40.0
        }
        
        // Road type preference
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
    
    private fun normalizeAngleDiff(angleDiff: Double): Double {
        var normalized = angleDiff
        while (normalized > 180.0) normalized -= 360.0
        while (normalized < -180.0) normalized += 360.0
        return normalized
    }
}