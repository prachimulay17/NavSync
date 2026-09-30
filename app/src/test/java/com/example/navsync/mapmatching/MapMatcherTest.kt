package com.example.navsync.mapmatching

import com.example.navsync.model.NavigationState
import com.example.navsync.model.NavigationSource
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for MapMatcher functionality.
 */
class MapMatcherTest {

    private lateinit var mapMatcher: MapMatcher

    @Before
    fun setUp() {
        mapMatcher = MapMatcher()
    }

    @Test
    fun testMapMatchingBasicFunctionality() {
        // Test with a position near the synthetic road network (Coventry, UK area)
        val rawState = NavigationState(
            latitude = 52.2028,   // Near synthetic road network center
            longitude = -2.2002,
            speedKmh = 30.0,
            headingDegrees = 90.0, // Heading east
            confidence = 0.8,
            gnssAvailable = true,
            source = NavigationSource.AI_ESTIMATION
        )

        val matchedState = mapMatcher.matchToRoad(rawState)

        // Should return a map-matched position
        assertNotNull(matchedState)
        
        // Matched position should be different from raw (projected onto road)
        // But should be close to the original position
        val distance = RoadSegment.calculateDistance(
            rawState.latitude, rawState.longitude,
            matchedState.latitude, matchedState.longitude
        )
        
        // Should be within reasonable matching distance
        assertTrue("Map-matched position should be within 100m of raw position", distance <= 100.0)
        
        // Other properties should be preserved
        assertEquals(rawState.speedKmh, matchedState.speedKmh, 0.01)
        assertEquals(rawState.headingDegrees, matchedState.headingDegrees, 0.01)
        assertEquals(rawState.confidence, matchedState.confidence, 0.01)
        assertEquals(rawState.gnssAvailable, matchedState.gnssAvailable)
        assertEquals(rawState.source, matchedState.source)
    }

    @Test
    fun testMapMatchingFallbackToRaw() {
        // Test with a position far from any roads
        val rawState = NavigationState(
            latitude = 0.0,        // Middle of ocean, no roads
            longitude = 0.0,
            speedKmh = 30.0,
            headingDegrees = 90.0,
            confidence = 0.8,
            gnssAvailable = true,
            source = NavigationSource.AI_ESTIMATION
        )

        val matchedState = mapMatcher.matchToRoad(rawState)

        // Should fall back to raw position (no roads nearby)
        assertEquals(rawState.latitude, matchedState.latitude, 0.000001)
        assertEquals(rawState.longitude, matchedState.longitude, 0.000001)
    }

    @Test
    fun testTemporalContinuity() {
        // Test that consecutive map-matches maintain continuity
        val baseState = NavigationState(
            latitude = 52.2028,
            longitude = -2.2002,
            speedKmh = 30.0,
            headingDegrees = 90.0,
            confidence = 0.8,
            gnssAvailable = true,
            source = NavigationSource.AI_ESTIMATION
        )

        // First position
        val state1 = mapMatcher.matchToRoad(baseState)
        
        // Second position (slight movement)
        val state2 = baseState.copy(
            latitude = baseState.latitude + 0.0001,  // Small movement
            longitude = baseState.longitude + 0.0001
        )
        val matched2 = mapMatcher.matchToRoad(state2)

        // Movement should be reasonable (not jumping large distances)
        val movement = RoadSegment.calculateDistance(
            state1.latitude, state1.longitude,
            matched2.latitude, matched2.longitude
        )
        
        assertTrue("Movement should be reasonable (<50m for small input change)", movement < 50.0)
    }

    @Test
    fun testReset() {
        // Perform some map-matching to build state
        val rawState = NavigationState(
            latitude = 52.2028,
            longitude = -2.2002,
            speedKmh = 30.0,
            headingDegrees = 90.0,
            confidence = 0.8,
            gnssAvailable = true,
            source = NavigationSource.AI_ESTIMATION
        )

        mapMatcher.matchToRoad(rawState)
        
        // Reset should work without errors
        mapMatcher.reset()
        
        // Should be able to continue matching after reset
        val matchedAfterReset = mapMatcher.matchToRoad(rawState)
        assertNotNull(matchedAfterReset)
    }
}