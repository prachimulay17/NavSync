package com.example.navsync.mapmatching

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for RoadSegment functionality.
 */
class RoadSegmentTest {

    @Test
    fun testPointProjection() {
        // Create a simple horizontal road segment
        val segment = RoadSegment(
            id = "test_segment",
            startLatitude = 52.0,
            startLongitude = -2.0,
            endLatitude = 52.0,
            endLongitude = -1.0,  // 1 degree east
            roadType = RoadType.PRIMARY
        )

        // Test point directly on the segment (midpoint)
        val (projLat, projLon, distance) = segment.projectPoint(52.0, -1.5)
        
        assertEquals("Projected latitude should match segment", 52.0, projLat, 0.000001)
        assertEquals("Projected longitude should match point", -1.5, projLon, 0.000001)
        assertEquals("Distance should be zero for point on segment", 0.0, distance, 0.1)
    }

    // @Test
    fun testPointProjectionOffSegment() {
        // Create a horizontal road segment
        val segment = RoadSegment(
            id = "test_segment",
            startLatitude = 52.0,
            startLongitude = -2.0,
            endLatitude = 52.0,
            endLongitude = -1.0,
            roadType = RoadType.PRIMARY
        )

        // Test point north of the segment
        val (projLat, projLon, distance) = segment.projectPoint(52.001, -1.5) // ~111m north
        
        assertEquals("Projected latitude should be on segment", 52.0, projLat, 0.000001)
        assertEquals("Projected longitude should match horizontal position", -1.5, projLon, 0.000001)
        assertTrue("Distance should be approximately 111m", abs(distance - 111.0) < 50.0)
    }

    @Test
    fun testDirectionCompatibility() {
        // East-west road segment (bearing ≈ 90°)
        val segment = RoadSegment(
            id = "test_segment",
            startLatitude = 52.0,
            startLongitude = -2.0,
            endLatitude = 52.0,
            endLongitude = -1.0,
            roadType = RoadType.PRIMARY,
            oneway = false
        )

        // Test vehicle heading east (90°) - should be compatible
        assertTrue("East heading should be compatible with east road", 
                   segment.isDirectionCompatible(90.0, 45.0))
        
        // Test vehicle heading west (270°) - should be compatible for two-way road
        assertTrue("West heading should be compatible with two-way east road", 
                   segment.isDirectionCompatible(270.0, 45.0))
        
        // Test vehicle heading north (0°) - should not be compatible
        assertFalse("North heading should not be compatible with east road", 
                    segment.isDirectionCompatible(0.0, 45.0))
    }

    @Test
    fun testOnewayDirectionCompatibility() {
        // One-way east road
        val segment = RoadSegment(
            id = "test_segment",
            startLatitude = 52.0,
            startLongitude = -2.0,
            endLatitude = 52.0,
            endLongitude = -1.0,
            roadType = RoadType.PRIMARY,
            oneway = true
        )

        // Test vehicle heading east (90°) - should be compatible
        assertTrue("East heading should be compatible with one-way east road", 
                   segment.isDirectionCompatible(90.0, 45.0))
        
        // Test vehicle heading west (270°) - should NOT be compatible for one-way road
        assertFalse("West heading should not be compatible with one-way east road", 
                    segment.isDirectionCompatible(270.0, 45.0))
    }

    @Test
    fun testBearingCalculation() {
        // Test north bearing (0°)
        val northBearing = RoadSegment.calculateBearing(52.0, -1.0, 53.0, -1.0)
        assertEquals("North bearing should be 0°", 0.0, northBearing, 1.0)
        
        // Test east bearing (90°)
        val eastBearing = RoadSegment.calculateBearing(52.0, -2.0, 52.0, -1.0)
        assertEquals("East bearing should be 90°", 90.0, eastBearing, 1.0)
        
        // Test south bearing (180°)
        val southBearing = RoadSegment.calculateBearing(53.0, -1.0, 52.0, -1.0)
        assertEquals("South bearing should be 180°", 180.0, southBearing, 1.0)
        
        // Test west bearing (270°)
        val westBearing = RoadSegment.calculateBearing(52.0, -1.0, 52.0, -2.0)
        assertEquals("West bearing should be 270°", 270.0, westBearing, 1.0)
    }

    @Test
    fun testDistanceCalculation() {
        // Test distance between two points (approximately 111km for 1° latitude difference)
        val distance = RoadSegment.calculateDistance(52.0, -1.0, 53.0, -1.0)
        assertTrue("Distance should be approximately 111km", abs(distance - 111000.0) < 5000.0)
        
        // Test zero distance
        val zeroDistance = RoadSegment.calculateDistance(52.0, -1.0, 52.0, -1.0)
        assertEquals("Distance between same points should be zero", 0.0, zeroDistance, 0.1)
    }
}