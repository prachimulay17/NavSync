package com.example.navsync.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.example.navsync.model.NavigationState
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.cos
import kotlin.math.sin

/**
 * MapLibre-based map component for NavSync navigation
 * 
 * Provides:
 * - Live camera tracking bound to NavigationState
 * - Dark navigation-style map theme
 * - Vehicle marker with heading indicator (follows estimated position)
 * - Confidence visualization
 * - Blue reference trajectory from V dataset
 * - Orange estimated trajectory from NavSync inference
 * - Map legend showing trajectory types
 * 
 * Uses OpenStreetMap tiles via MapLibre GL Native (open source, no API key required)
 */
@Composable
fun MapLibreMapArea(
    navigationState: NavigationState,
    trajectoryPoints: List<Pair<Double, Double>> = emptyList(),
    estimatedTrajectoryPoints: List<Pair<Double, Double>> = emptyList(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // Initialize MapLibre synchronously before any composables that create MapView
    // This must happen exactly once and before MapView is created
    // Using WellKnownTileServer.MapLibre for open-source tile sources (no API key required)
    val mapLibreInitialized = remember {
        MapLibre.getInstance(context, null, WellKnownTileServer.MapLibre)
        true
    }
    
    // Track map instance
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    
    // Only create MapView after MapLibre is initialized
    if (mapLibreInitialized) {
        Box(modifier = modifier.fillMaxSize()) {
            // Create map view
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    MapView(ctx).also { mv ->
                        mapView = mv
                        
                    mv.getMapAsync { map ->
                        mapLibreMap = map
                        
                        // Load OpenFreeMap Liberty style
                        val styleUrl = "https://tiles.openfreemap.org/styles/liberty"
                        android.util.Log.d("MapLibreMapArea", "Loading style from: $styleUrl")
                        
                        map.setStyle(styleUrl, object : Style.OnStyleLoaded {
                            override fun onStyleLoaded(style: Style) {
                                android.util.Log.d("MapLibreMapArea", "Style loaded successfully!")
                                
                                // Apply dark theme to base style layers
                                applyDarkNavigationTheme(style)
                                
                                // Add sources for overlays
                                style.addSource(GeoJsonSource("reference-route-source"))
                                style.addSource(GeoJsonSource("estimated-route-source"))
                                style.addSource(GeoJsonSource("confidence-source"))
                                style.addSource(GeoJsonSource("vehicle-source"))
                                
                                // Add layers
                                // Reference trajectory (blue line - V dataset)
                                style.addLayer(LineLayer("reference-route-layer", "reference-route-source").withProperties(
                                    lineColor("#2196F3"), // Blue
                                    lineWidth(4f),
                                    lineOpacity(0.8f)
                                ))
                                
                                // Estimated trajectory (orange line - NavSync inference)
                                style.addLayer(LineLayer("estimated-route-layer", "estimated-route-source").withProperties(
                                    lineColor("#FF9800"), // Orange
                                    lineWidth(4f),
                                    lineOpacity(0.9f)
                                ))
                                
                                style.addLayer(CircleLayer("confidence-layer", "confidence-source").withProperties(
                                    circleRadius(20f),
                                    circleColor("#00E676"),
                                    circleOpacity(0.3f),
                                    circleStrokeColor("#00E676"),
                                    circleStrokeWidth(2f),
                                    circleStrokeOpacity(0.8f)
                                ))
                                
                                // Add vehicle marker icon
                                addVehicleMarkerIcon(style)
                                
                                style.addLayer(SymbolLayer("vehicle-layer", "vehicle-source").withProperties(
                                    iconImage("vehicle-marker-cyan"),
                                    iconRotate(0f),
                                    iconRotationAlignment("map"),
                                    iconAllowOverlap(true),
                                    iconIgnorePlacement(true),
                                    iconSize(1f)
                                ))
                            
                                // Initial camera position
                                updateCamera(map, navigationState, animate = false)
                                
                                // Initial overlays
                                updateOverlays(map, navigationState, trajectoryPoints, estimatedTrajectoryPoints, style)
                            }
                        })
                        
                        // Configure map settings
                        map.uiSettings.apply {
                            isCompassEnabled = false
                            isLogoEnabled = false
                            isAttributionEnabled = true
                            isRotateGesturesEnabled = true
                            isScrollGesturesEnabled = true
                            isTiltGesturesEnabled = true
                            isZoomGesturesEnabled = true
                        }
                    }
                }
            },
            update = { mv ->
                // Update camera and overlays when NavigationState changes
                mapLibreMap?.let { map ->
                    map.style?.let { style ->
                        updateCamera(map, navigationState, animate = true)
                        updateOverlays(map, navigationState, trajectoryPoints, estimatedTrajectoryPoints, style)
                    }
                }
            }
        )
        
            // Map Legend Overlay
            MapLegend(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                showEstimated = estimatedTrajectoryPoints.isNotEmpty()
            )
        }
    
        // Handle lifecycle events
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> mapView?.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView?.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView?.onPause()
                    Lifecycle.Event.ON_STOP -> mapView?.onStop()
                    Lifecycle.Event.ON_DESTROY -> mapView?.onDestroy()
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                mapView?.onDestroy()
            }
        }
    }
}

/**
 * Update map camera to follow vehicle
 */
private fun updateCamera(
    map: MapLibreMap,
    navigationState: NavigationState,
    animate: Boolean
) {
    val position = CameraPosition.Builder()
        .target(LatLng(navigationState.latitude, navigationState.longitude))
        .zoom(16.0)
        .bearing(navigationState.headingDegrees)
        .tilt(45.0)
        .build()
    
    if (animate) {
        map.animateCamera(CameraUpdateFactory.newCameraPosition(position), 500)
    } else {
        map.cameraPosition = position
    }
}

/**
 * Update all map overlays (vehicle marker, confidence circle, trajectories)
 */
private fun updateOverlays(
    map: MapLibreMap,
    navigationState: NavigationState,
    referenceTrajectoryPoints: List<Pair<Double, Double>>,
    estimatedTrajectoryPoints: List<Pair<Double, Double>>,
    style: Style
) {
    val vehiclePosition = Point.fromLngLat(navigationState.longitude, navigationState.latitude)
    
    // Update reference trajectory (blue line - complete V dataset)
    if (referenceTrajectoryPoints.isNotEmpty()) {
        val referenceGeoJson = FeatureCollection.fromFeatures(listOf(
            Feature.fromGeometry(LineString.fromLngLats(referenceTrajectoryPoints.map { 
                Point.fromLngLat(it.second, it.first) 
            }))
        ))
        (style.getSource("reference-route-source") as? GeoJsonSource)?.setGeoJson(referenceGeoJson)
    } else {
        // Show synthetic route if no reference data
        val syntheticRoute = generateRoutePoints(navigationState.latitude, navigationState.longitude, navigationState.headingDegrees)
        val syntheticGeoJson = FeatureCollection.fromFeatures(listOf(
            Feature.fromGeometry(LineString.fromLngLats(syntheticRoute.map { 
                Point.fromLngLat(it.second, it.first) 
            }))
        ))
        (style.getSource("reference-route-source") as? GeoJsonSource)?.setGeoJson(syntheticGeoJson)
    }
    
    // Update estimated trajectory (orange line - NavSync inference)
    if (estimatedTrajectoryPoints.isNotEmpty()) {
        val estimatedGeoJson = FeatureCollection.fromFeatures(listOf(
            Feature.fromGeometry(LineString.fromLngLats(estimatedTrajectoryPoints.map { 
                Point.fromLngLat(it.second, it.first) 
            }))
        ))
        (style.getSource("estimated-route-source") as? GeoJsonSource)?.setGeoJson(estimatedGeoJson)
    } else {
        // Clear estimated trajectory if no data
        (style.getSource("estimated-route-source") as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }
    
    // Update confidence circle
    val confidenceRadius = 20.0 * (1.0 - navigationState.confidence.coerceIn(0.0, 1.0))
    if (confidenceRadius > 5.0) {
        val confidenceColor = if (navigationState.gnssAvailable) "#00E676" else "#FFC107"
        val confidenceGeoJson = FeatureCollection.fromFeatures(listOf(
            Feature.fromGeometry(vehiclePosition)
        ))
        (style.getSource("confidence-source") as? GeoJsonSource)?.setGeoJson(confidenceGeoJson)
        
        // Update circle color
        (style.getLayer("confidence-layer") as? CircleLayer)?.setProperties(
            circleColor(confidenceColor),
            circleStrokeColor(confidenceColor),
            circleRadius(confidenceRadius.toFloat())
        )
    } else {
        (style.getSource("confidence-source") as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }
    
    // Update vehicle marker (always follows estimated/NavigationState position)
    val iconName = if (navigationState.gnssAvailable) "vehicle-marker-cyan" else "vehicle-marker-orange"
    val vehicleGeoJson = FeatureCollection.fromFeatures(listOf(
        Feature.fromGeometry(vehiclePosition)
    ))
    (style.getSource("vehicle-source") as? GeoJsonSource)?.setGeoJson(vehicleGeoJson)
    
    // Update vehicle rotation and icon
    (style.getLayer("vehicle-layer") as? SymbolLayer)?.setProperties(
        iconImage(iconName),
        iconRotate(navigationState.headingDegrees.toFloat())
    )
}

/**
 * Generate route points for trajectory visualization
 * Returns list of (latitude, longitude) pairs
 */
private fun generateRoutePoints(lat: Double, lng: Double, headingDegrees: Double): List<Pair<Double, Double>> {
    val points = mutableListOf<Pair<Double, Double>>()
    
    val headingRadians = Math.toRadians(headingDegrees)
    val metersPerDegreeLatitude = 111320.0
    val metersPerDegreeLongitude = 111320.0 * cos(Math.toRadians(lat))
    
    var currentLat = lat
    var currentLng = lng
    
    for (i in 0..10) {
        points.add(Pair(currentLat, currentLng))
        
        val distanceMeters = 50.0
        val latOffset = (distanceMeters * cos(headingRadians)) / metersPerDegreeLatitude
        val lngOffset = (distanceMeters * sin(headingRadians)) / metersPerDegreeLongitude
        
        currentLat += latOffset
        currentLng += lngOffset
        
        if (i % 3 == 0) {
            val curveFactor = 0.0001 * (if (i % 2 == 0) 1 else -1)
            currentLng += curveFactor
        }
    }
    
    return points
}

/**
 * Add vehicle marker icons to map style
 */
private fun addVehicleMarkerIcon(style: Style) {
    val cyanMarkerBitmap = createVehicleMarkerBitmap(android.graphics.Color.parseColor("#00BFFF"))
    style.addImage("vehicle-marker-cyan", cyanMarkerBitmap)
    
    val orangeMarkerBitmap = createVehicleMarkerBitmap(android.graphics.Color.parseColor("#FF9800"))
    style.addImage("vehicle-marker-orange", orangeMarkerBitmap)
}

/**
 * Create vehicle marker bitmap with navigation arrow
 */
private fun createVehicleMarkerBitmap(color: Int): Bitmap {
    val size = 80
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    
    // Outer circle (white background)
    paint.color = android.graphics.Color.WHITE
    paint.style = Paint.Style.FILL
    canvas.drawCircle(size / 2f, size / 2f, 28f, paint)
    
    // Inner circle (colored)
    paint.color = color
    canvas.drawCircle(size / 2f, size / 2f, 24f, paint)
    
    // Directional arrow pointing up
    paint.color = android.graphics.Color.WHITE
    paint.style = Paint.Style.FILL
    
    val arrowPath = Path()
    val centerX = size / 2f
    val centerY = size / 2f
    
    arrowPath.moveTo(centerX, centerY - 16f) // Tip
    arrowPath.lineTo(centerX - 8f, centerY + 8f) // Left base
    arrowPath.lineTo(centerX + 8f, centerY + 8f) // Right base
    arrowPath.close()
    
    canvas.drawPath(arrowPath, paint)
    
    return bitmap
}

/**
 * Apply dark navigation theme to the loaded map style
 */
private fun applyDarkNavigationTheme(style: Style) {
    // Apply dark theme adjustments to existing base map layers
    // Reduce brightness and saturation for night/navigation mode
    try {
        // Get all layer IDs
        val layers = style.layers
        
        for (layer in layers) {
            when (layer) {
                is org.maplibre.android.style.layers.FillLayer -> {
                    // Darken fill layers (land, water, buildings)
                    try {
                        val currentOpacity = layer.fillOpacity?.value as? Float
                        if (currentOpacity != null) {
                            layer.setProperties(
                                fillOpacity(currentOpacity * 0.7f)
                            )
                        }
                    } catch (e: Exception) {
                        // Skip if property cannot be modified
                    }
                }
                is org.maplibre.android.style.layers.LineLayer -> {
                    // Adjust road/border line visibility
                    try {
                        val currentOpacity = layer.lineOpacity?.value as? Float
                        if (currentOpacity != null) {
                            layer.setProperties(
                                lineOpacity(currentOpacity * 0.8f)
                            )
                        }
                    } catch (e: Exception) {
                        // Skip if property cannot be modified
                    }
                }
                is org.maplibre.android.style.layers.SymbolLayer -> {
                    // Reduce label text opacity slightly for less distraction
                    try {
                        val currentOpacity = layer.textOpacity?.value as? Float
                        if (currentOpacity != null) {
                            layer.setProperties(
                                textOpacity(currentOpacity * 0.85f)
                            )
                        }
                    } catch (e: Exception) {
                        // Skip if property cannot be modified
                    }
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("MapLibreMapArea", "Error applying dark theme: ${e.message}")
    }
}

/**
 * Unobtrusive map legend showing trajectory types.
 */
@Composable
private fun MapLegend(
    modifier: Modifier = Modifier,
    showEstimated: Boolean = false
) {
    Column(
        modifier = modifier
            .background(
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(12.dp)
    ) {
        // Reference trajectory legend item
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Blue line indicator
            Spacer(
                modifier = Modifier
                    .size(width = 16.dp, height = 3.dp)
                    .background(
                        color = Color(0xFF2196F3),
                        shape = RoundedCornerShape(1.5.dp)
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Reference",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
        
        // Estimated trajectory legend item (only show if estimated trajectory exists)
        if (showEstimated) {
            Spacer(modifier = Modifier.padding(vertical = 2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Orange line indicator  
                Spacer(
                    modifier = Modifier
                        .size(width = 16.dp, height = 3.dp)
                        .background(
                            color = Color(0xFFFF9800),
                            shape = RoundedCornerShape(1.5.dp)
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "NavSync",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
