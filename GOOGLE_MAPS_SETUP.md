# Google Maps SDK Setup

The NavSync application now uses **Google Maps SDK for Android** with **Jetpack Compose** to display live navigation with a real map.

## Quick Setup

1. **Get a Google Maps API Key** from [Google Cloud Console](https://console.cloud.google.com/)
2. **Enable** the "Maps SDK for Android" API
3. **Add the key** to `local.properties`:
   ```properties
   MAPS_API_KEY=YOUR_ACTUAL_API_KEY
   ```
4. **Build** the project:
   ```bash
   ./gradlew.bat assembleDebug
   ```

---

## Detailed Setup Instructions

### 1. Obtain Google Maps API Key

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project or select an existing one
3. Navigate to **APIs & Services** → **Library**
4. Search for **"Maps SDK for Android"**
5. Click **Enable**
6. Go to **APIs & Services** → **Credentials**
7. Click **Create Credentials** → **API Key**
8. Copy the API key

### 2. Secure Your API Key (Recommended)

1. Click **Restrict Key** on your API key
2. Under **Application restrictions**:
   - Select **Android apps**
   - Add package name: `com.example.navsync`
   - Add SHA-1 certificate fingerprint:
     ```bash
     keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
     ```
3. Under **API restrictions**:
   - Select **Restrict key**
   - Check **Maps SDK for Android**
4. Click **Save**

### 3. Configure API Key Locally

Create or edit `local.properties` in the project root:

```properties
sdk.dir=C\:\\Users\\YourUser\\AppData\\Local\\Android\\sdk
MAPS_API_KEY=AIzaSyA_your_actual_api_key_here
```

**Important:**
- `local.properties` is already in `.gitignore` and will NOT be committed
- Never hardcode the API key in source files
- Use `local.properties.example` as a template

### 4. Build and Run

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
./gradlew.bat assembleDebug
```

The app will automatically load the API key from `local.properties` during the build.

---

## Google Maps Integration Features

### Navigation-Style Map Display

- **Dark theme** matching NavSync visual language (dark navy/blue color scheme)
- **3D perspective** with 45° tilt for better spatial awareness
- **Camera tracking** follows vehicle position and heading smoothly
- **Minimal UI chrome** - no zoom controls, compass, or toolbar (navigation-first focus)

### Vehicle Visualization

- **Position marker** displaying current latitude/longitude from `NavigationState`
- **Heading arrow** rotates to show vehicle direction (0-360°)
- **Color-coded status:**
  - **Cyan** = GNSS available (high confidence)
  - **Orange** = AI/Dead Reckoning estimation (GNSS unavailable)
- **Confidence circle** expands when position uncertainty increases

### Route Display

- **Trajectory polyline** showing planned route or historical path
- **Cyan route line** with rounded caps and smooth joints
- **Forward projection** based on current heading and position
- Ready for integration with:
  - Navigation API route geometry
  - Dataset replay trajectory visualization
  - Turn-by-turn directions

### Camera Behavior

- **Follows vehicle** with 500ms smooth animation
- **Rotates** map to match vehicle heading (heading-up mode)
- **Zoom level 17** (city street detail)
- **User can interact** - pan, zoom, rotate, tilt gestures enabled

---

## Architecture

### Map Component Independence

`GoogleMapArea.kt` is designed to be completely independent:

**Input:**
```kotlin
GoogleMapArea(
    navigationState: NavigationState, // Position, heading, confidence, source
    modifier: Modifier
)
```

**No dependencies on:**
- Dataset replay implementation
- Simulation logic
- ML inference
- Navigation engine internals

**Clean separation:**
```
NavigationViewModel → NavigationState → GoogleMapArea
```

### Integration Point

File: `app/src/main/java/com/example/navsync/ui/NavigationScreen.kt`

```kotlin
GoogleMapArea(
    navigationState = navigationState,
    modifier = Modifier.fillMaxSize()
)
```

### Fallback Option

The original static map placeholder is preserved at:
```
app/src/main/java/com/example/navsync/ui/components/MapArea.kt
```

To revert to static map:
```kotlin
// In NavigationScreen.kt
import com.example.navsync.ui.components.MapArea // instead of GoogleMapArea
MapArea(navigationState, Modifier.fillMaxSize()) // instead of GoogleMapArea
```

---

## Customization

### Map Style

Edit `createDarkMapStyle()` in `GoogleMapArea.kt` to customize colors:

```kotlin
private fun createDarkMapStyle(): MapStyleOptions {
    return MapStyleOptions(
        """
        [
          {
            "featureType": "road.highway",
            "elementType": "geometry",
            "stylers": [{"color": "#YOUR_COLOR"}]
          }
          // ... more styling rules
        ]
        """.trimIndent()
    )
}
```

Use [Google Maps Styling Wizard](https://mapstyle.withgoogle.com/) to generate styles.

### Camera Settings

Edit camera parameters in `GoogleMapArea.kt`:

```kotlin
CameraPosition.Builder()
    .target(vehiclePosition)
    .zoom(17f)           // Change zoom level (10-21)
    .bearing(heading)    // Map rotation (0 = North up)
    .tilt(45f)           // 3D perspective (0-90°)
    .build()
```

### Route Generation

Replace `generateRoutePoints()` with real route data:

```kotlin
// Production implementation:
val routePoints = navigationViewModel.plannedRoute.polylinePoints
// or
val routePoints = datasetRepository.getTrajectoryPoints()
```

### Vehicle Marker

To use a custom vehicle icon instead of default marker:

```kotlin
// Create custom bitmap descriptor
val vehicleIcon = bitmapDescriptorFromVector(context, R.drawable.ic_vehicle_arrow)

Marker(
    state = markerState,
    icon = vehicleIcon,
    // ...
)
```

---

## Future Enhancements

Possible map feature additions:

1. **Turn-by-turn route geometry** from navigation APIs
2. **Historical trajectory replay** visualization
3. **Confidence heatmap** overlay during GNSS outages
4. **GNSS outage zones** highlighted on map
5. **Map matching corrections** shown as guide lines
6. **Traffic and road conditions** overlays
7. **POI markers** for testing waypoints
8. **Route alternatives** during navigation
9. **3D buildings** for urban canyon scenarios
10. **Satellite/hybrid** map type toggle

---

## Troubleshooting

### Map shows "For development purposes only" watermark

- Your API key is not configured or invalid
- Check `local.properties` contains `MAPS_API_KEY=...`
- Verify the key is enabled for "Maps SDK for Android" in Google Cloud Console

### Map is blank/gray

- No internet connection (Maps requires network)
- API key restrictions don't match your app package name or SHA-1 fingerprint
- Check Logcat for "Google Maps" errors

### Build fails with "Unresolved reference: Properties"

- Ensure `import java.util.Properties` is at the top of `app/build.gradle.kts`
- Clean and rebuild: `./gradlew.bat clean assembleDebug`

### Camera doesn't follow vehicle

- Verify `NavigationState` latitude/longitude values are changing
- Check `NavigationViewModel` is updating state correctly
- Ensure simulation is running (not paused)

---

## Dependencies Added

In `app/build.gradle.kts`:

```kotlin
// Google Maps SDK for Android with Compose
implementation("com.google.android.gms:play-services-maps:19.0.0")
implementation("com.google.maps.android:maps-compose:6.2.1")
implementation("com.google.maps.android:maps-compose-utils:6.2.1")
```

**Version notes:**
- `play-services-maps:19.0.0` - Latest stable Google Maps SDK
- `maps-compose:6.2.1` - Official Jetpack Compose wrapper
- `maps-compose-utils:6.2.1` - Utilities for markers, polylines, clustering

---

## References

- [Google Maps Platform Documentation](https://developers.google.com/maps/documentation/android-sdk)
- [Maps Compose Library](https://github.com/googlemaps/android-maps-compose)
- [Google Cloud Console](https://console.cloud.google.com/)
- [Map Styling Wizard](https://mapstyle.withgoogle.com/)
- [API Key Best Practices](https://developers.google.com/maps/api-security-best-practices)

