package com.example.navsync.model

data class NavigationState(
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double,
    val headingDegrees: Double,
    val confidence: Double,
    val gnssAvailable: Boolean,
    val source: NavigationSource
)

enum class NavigationSource {
    GNSS,
    DEAD_RECKONING,
    AI_ESTIMATION
}