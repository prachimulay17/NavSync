package com.example.navsync.mapmatching

import com.example.navsync.model.NavigationState
import com.example.navsync.model.NavigationSource
import org.junit.Test

/**
 * Direct analysis of V-Vw9 trajectory vs synthetic road network.
 */
class TrajectoryAnalysis {

    @Test
    fun analyzeTrajectoryRoadAlignment() {
        // V-Vw9 trajectory sample (first 20 seconds, covering start to outage period)
        val vVw9Points = listOf(
            Pair(52.2028035, -2.2002006),  // Start
            Pair(52.2027986, -2.2002543),  
            Pair(52.2027963, -2.2002878),
            Pair(52.2027943, -2.2003106),
            Pair(52.2027919, -2.2003334),
            Pair(52.2027889, -2.2003564),  // Around 10s
            Pair(52.2027857, -2.2003798),
            Pair(52.2027824, -2.2004034),
            Pair(52.2027796, -2.2004273),
            Pair(52.2027773, -2.2004511),  // Around 20s (approaching outage)
            Pair(52.2027725, -2.2005102),  // Outage period starts around here
            Pair(52.2027691, -2.2005443),
            Pair(52.2027647, -2.2005772),
            Pair(52.2027616, -2.2005991),
            Pair(52.2027572, -2.2006317),  // Mid-outage
            Pair(52.2027533, -2.2006644),
            Pair(52.2027497, -2.2007087),
            Pair(52.2027482, -2.2007314)   // Late outage
        )
        
        // Synthetic road network (recreating MapMatcher's logic)
        val baseLatitude = 52.2028
        val baseLongitude = -2.2002
        
        val mainEastWest = RoadSegment(
            id = "main_ew_1",
            startLatitude = baseLatitude,
            startLongitude = baseLongitude - 0.01,
            endLatitude = baseLatitude,
            endLongitude = baseLongitude + 0.01,
            roadType = RoadType.PRIMARY
        )
        
        val mainNorthSouth = RoadSegment(
            id = "main_ns_1", 
            startLatitude = baseLatitude - 0.01,
            startLongitude = baseLongitude,
            endLatitude = baseLatitude + 0.01,
            endLongitude = baseLongitude,
            roadType = RoadType.PRIMARY
        )
        
        // Analysis results will be written here
        val diagnostics = StringBuilder()
        
        diagnostics.appendLine("=== TRAJECTORY ROAD ALIGNMENT ANALYSIS ===")
        diagnostics.appendLine()
        
        // 1. Calculate distances from trajectory to main roads
        diagnostics.appendLine("1. DISTANCE TO MAIN ROADS")
        diagnostics.appendLine("-------------------------")
        
        var totalDistanceEW = 0.0
        var totalDistanceNS = 0.0
        var maxDistanceEW = 0.0
        var maxDistanceNS = 0.0
        
        vVw9Points.forEachIndexed { index, (lat, lon) ->
            val (_, _, distanceEW) = mainEastWest.projectPoint(lat, lon)
            val (_, _, distanceNS) = mainNorthSouth.projectPoint(lat, lon)
            
            totalDistanceEW += distanceEW
            totalDistanceNS += distanceNS
            maxDistanceEW = maxOf(maxDistanceEW, distanceEW)
            maxDistanceNS = maxOf(maxDistanceNS, distanceNS)
            
            if (index < 5 || index % 5 == 0 || distanceEW > 100 || distanceNS > 100) {
                diagnostics.appendLine("Point $index: ($lat, $lon)")
                diagnostics.appendLine("  EW road distance: ${distanceEW.toInt()}m")
                diagnostics.appendLine("  NS road distance: ${distanceNS.toInt()}m")
            }
        }
        
        val avgDistanceEW = totalDistanceEW / vVw9Points.size
        val avgDistanceNS = totalDistanceNS / vVw9Points.size
        
        diagnostics.appendLine()
        diagnostics.appendLine("SUMMARY:")
        diagnostics.appendLine("EW Road - Avg: ${avgDistanceEW.toInt()}m, Max: ${maxDistanceEW.toInt()}m")
        diagnostics.appendLine("NS Road - Avg: ${avgDistanceNS.toInt()}m, Max: ${maxDistanceNS.toInt()}m")
        diagnostics.appendLine()
        
        // 2. Analyze trajectory bearing vs road bearings
        diagnostics.appendLine("2. TRAJECTORY BEARING ANALYSIS") 
        diagnostics.appendLine("------------------------------")
        
        val trajectoryBearing = RoadSegment.calculateBearing(
            vVw9Points.first().first, vVw9Points.first().second,
            vVw9Points.last().first, vVw9Points.last().second
        )
        
        diagnostics.appendLine("Overall trajectory bearing: ${trajectoryBearing.toInt()}°")
        diagnostics.appendLine("EW road bearing: ${mainEastWest.bearing.toInt()}°")
        diagnostics.appendLine("NS road bearing: ${mainNorthSouth.bearing.toInt()}°")
        
        val ewAlignment = kotlin.math.abs(trajectoryBearing - mainEastWest.bearing)
        val nsAlignment = kotlin.math.abs(trajectoryBearing - mainNorthSouth.bearing)
        
        diagnostics.appendLine("Alignment with EW road: ${ewAlignment.toInt()}° difference")
        diagnostics.appendLine("Alignment with NS road: ${nsAlignment.toInt()}° difference")
        diagnostics.appendLine()
        
        // 3. Simulate map-matching selection during outage
        diagnostics.appendLine("3. MAP-MATCHING SIMULATION")
        diagnostics.appendLine("--------------------------")
        
        val mapMatcher = MapMatcher()
        val outagePoints = vVw9Points.subList(10, 17) // Simulate outage period
        
        outagePoints.forEachIndexed { index, (lat, lon) ->
            val aiState = NavigationState(
                latitude = lat,
                longitude = lon,
                speedKmh = 28.0,
                headingDegrees = trajectoryBearing,
                confidence = 0.7,
                gnssAvailable = false,
                source = NavigationSource.AI_ESTIMATION
            )
            
            val matched = mapMatcher.matchToRoad(aiState)
            val displacement = RoadSegment.calculateDistance(lat, lon, matched.latitude, matched.longitude)
            
            diagnostics.appendLine("Outage point $index:")
            diagnostics.appendLine("  Raw: ($lat, $lon)")
            diagnostics.appendLine("  Matched: (${matched.latitude}, ${matched.longitude})")
            diagnostics.appendLine("  Displacement: ${displacement.toInt()}m")
            
            if (displacement > 50) {
                diagnostics.appendLine("  *** LARGE DISPLACEMENT - LIKELY ISSUE ***")
            }
            diagnostics.appendLine()
        }
        
        // 4. Identify root causes
        diagnostics.appendLine("4. ROOT CAUSE ANALYSIS")
        diagnostics.appendLine("----------------------")
        
        if (avgDistanceEW > 50 && avgDistanceNS > 50) {
            diagnostics.appendLine("ISSUE: Trajectory is far from both main synthetic roads")
            diagnostics.appendLine("CAUSE: Synthetic road network not aligned with actual V-Vw9 path")
        }
        
        if (maxOf(ewAlignment, nsAlignment) > 45) {
            diagnostics.appendLine("ISSUE: Trajectory direction doesn't match synthetic road bearings")
            diagnostics.appendLine("CAUSE: Vehicle follows a different path than grid-based roads")
        }
        
        if (avgDistanceEW < avgDistanceNS && ewAlignment < nsAlignment) {
            diagnostics.appendLine("OBSERVATION: EW road is better aligned")
            diagnostics.appendLine("RECOMMENDATION: Create road segments along actual trajectory path")
        } else if (avgDistanceNS < avgDistanceEW && nsAlignment < ewAlignment) {
            diagnostics.appendLine("OBSERVATION: NS road is better aligned")
            diagnostics.appendLine("RECOMMENDATION: Create road segments along actual trajectory path")
        } else {
            diagnostics.appendLine("OBSERVATION: Neither synthetic road aligns well")
            diagnostics.appendLine("RECOMMENDATION: Replace synthetic network with trajectory-derived roads")
        }
        
        diagnostics.appendLine()
        diagnostics.appendLine("5. RECOMMENDED FIX")
        diagnostics.appendLine("------------------")
        diagnostics.appendLine("Create road segments that follow the actual V-Vw9 trajectory:")
        diagnostics.appendLine("- Extract centerline from reference GPS points")
        diagnostics.appendLine("- Generate road segments every 100-200m along path")
        diagnostics.appendLine("- Set road bearing to match trajectory direction")
        diagnostics.appendLine("- Increase distance penalty weight to prioritize proximity")
        
        // Output diagnostics
        println(diagnostics.toString())
        
        // Write to file for easy inspection
        java.io.File("trajectory_analysis_results.txt").writeText(diagnostics.toString())
    }
}