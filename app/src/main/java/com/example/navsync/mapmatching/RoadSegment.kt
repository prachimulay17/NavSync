package com.example.navsync.mapmatching

import kotlin.math.*

/**
 * Represents a road segment for map-matching.
 * Contains geometry and attributes needed for position projection.
 */
data class RoadSegment(
    val id: String,
    val startLatitude: Double,
    val startLongitude: Double,
    val endLatitude: Double,
    val endLongitude: Double,
    val roadType: RoadType = RoadType.UNKNOWN,
    val speedLimitKmh: Double? = null,
    val oneway: Boolean = false,
    val bearing: Double = calculateBearing(startLatitude, startLongitude, endLatitude, endLongitude)
) {
    
    /**
     * Length of road segment in meters.
     */
    val lengthMeters: Double = calculateDistance(startLatitude, startLongitude, endLatitude, endLongitude)
    
    /**
     * Calculate the closest point on this road segment to a given position.
     * 
     * @param latitude Position latitude
     * @param longitude Position longitude
     * @return Triple of (projected lat, projected lon, distance from segment in meters)
     */
    fun projectPoint(latitude: Double, longitude: Double): Triple<Double, Double, Double> {
        // Convert to Cartesian coordinates for easier calculation
        val segmentStartX = longitudeToMeters(startLongitude, startLatitude)
        val segmentStartY = latitudeToMeters(startLatitude)
        val segmentEndX = longitudeToMeters(endLongitude, endLatitude)
        val segmentEndY = latitudeToMeters(endLatitude)
        
        val pointX = longitudeToMeters(longitude, latitude)
        val pointY = latitudeToMeters(latitude)
        
        // Vector from segment start to end
        val segmentVectorX = segmentEndX - segmentStartX
        val segmentVectorY = segmentEndY - segmentStartY
        val segmentLengthSq = segmentVectorX * segmentVectorX + segmentVectorY * segmentVectorY
        
        if (segmentLengthSq < 1e-6) {
            // Segment is essentially a point
            return Triple(startLatitude, startLongitude, calculateDistance(latitude, longitude, startLatitude, startLongitude))
        }
        
        // Vector from segment start to point
        val pointVectorX = pointX - segmentStartX
        val pointVectorY = pointY - segmentStartY
        
        // Project point onto segment line (parameterized by t)
        val t = (pointVectorX * segmentVectorX + pointVectorY * segmentVectorY) / segmentLengthSq
        
        // Clamp t to [0, 1] to stay on segment
        val clampedT = t.coerceIn(0.0, 1.0)
        
        // Calculate projected point
        val projectedX = segmentStartX + clampedT * segmentVectorX
        val projectedY = segmentStartY + clampedT * segmentVectorY
        
        // Convert back to lat/lon
        val projectedLat = metersToLatitude(projectedY)
        val projectedLon = metersToLongitude(projectedX, projectedLat)
        
        // Calculate distance from original point to projected point
        val distance = calculateDistance(latitude, longitude, projectedLat, projectedLon)
        
        return Triple(projectedLat, projectedLon, distance)
    }
    
    /**
     * Check if the segment direction is compatible with vehicle heading.
     * 
     * @param vehicleHeading Vehicle heading in degrees (0=North, clockwise)
     * @param tolerance Angular tolerance in degrees
     * @return true if segment direction is compatible with vehicle direction
     */
    fun isDirectionCompatible(vehicleHeading: Double, tolerance: Double = 45.0): Boolean {
        val angleDiff = normalizeAngle(abs(bearing - vehicleHeading))
        val backwardAngleDiff = normalizeAngle(abs(bearing - vehicleHeading + 180.0))
        
        return angleDiff <= tolerance || (!oneway && backwardAngleDiff <= tolerance)
    }
    
    companion object {
        /**
         * Calculate bearing between two points in degrees (0=North, clockwise).
         */
        fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val lat1Rad = Math.toRadians(lat1)
            val lat2Rad = Math.toRadians(lat2)
            val deltaLonRad = Math.toRadians(lon2 - lon1)
            
            val y = sin(deltaLonRad) * cos(lat2Rad)
            val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(deltaLonRad)
            
            val bearing = Math.toDegrees(atan2(y, x))
            return normalizeAngle(bearing)
        }
        
        /**
         * Calculate distance between two points in meters.
         */
        fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val earthRadius = 6371000.0 // meters
            
            val lat1Rad = Math.toRadians(lat1)
            val lat2Rad = Math.toRadians(lat2)
            val deltaLatRad = Math.toRadians(lat2 - lat1)
            val deltaLonRad = Math.toRadians(lon2 - lon1)
            
            val a = sin(deltaLatRad / 2) * sin(deltaLatRad / 2) +
                    cos(lat1Rad) * cos(lat2Rad) *
                    sin(deltaLonRad / 2) * sin(deltaLonRad / 2)
            
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return earthRadius * c
        }
        
        /**
         * Normalize angle to [0, 360) degrees.
         */
        private fun normalizeAngle(angle: Double): Double {
            var normalized = angle % 360.0
            if (normalized < 0) normalized += 360.0
            return normalized
        }
        
        // Simple Mercator projection helpers for local calculations
        private fun latitudeToMeters(latitude: Double): Double = latitude * 111000.0
        private fun longitudeToMeters(longitude: Double, latitude: Double): Double = 
            longitude * 111000.0 * cos(Math.toRadians(latitude))
        private fun metersToLatitude(meters: Double): Double = meters / 111000.0
        private fun metersToLongitude(meters: Double, latitude: Double): Double = 
            meters / (111000.0 * cos(Math.toRadians(latitude)))
    }
}

/**
 * Road types for map-matching preferences.
 */
enum class RoadType {
    UNKNOWN,
    MOTORWAY,
    PRIMARY,
    SECONDARY,
    TERTIARY,
    RESIDENTIAL,
    SERVICE
}