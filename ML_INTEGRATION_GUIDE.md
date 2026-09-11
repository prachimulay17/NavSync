# NavSync ML Model Integration Guide

## Overview

This guide explains how to integrate your trained ML model into the NavSync navigation pipeline. The system is designed to accept TensorFlow Lite models with minimal code changes.

## Integration Point

**File**: `app/src/main/java/com/example/navsync/inference/NavSyncInference.kt`

**Interface**: `NavSyncInference`

This interface defines the contract between the navigation system and your ML model. The current implementation uses simple dead-reckoning as a baseline. Your ML model should replace this.

## Integration Steps

### 1. Add TensorFlow Lite Dependency

Edit `app/build.gradle.kts`:

```kotlin
dependencies {
    // Existing dependencies...
    
    // Add TensorFlow Lite
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")  // Optional: for helper utilities
}
```

### 2. Add Your Model File

Place your trained `.tflite` model file in:

```
app/src/main/assets/navsync_model.tflite
```

Create the `assets` directory if it doesn't exist:

```bash
mkdir -p app/src/main/assets
```

### 3. Implement MLModelInference Class

Uncomment and modify the `MLModelInference` class in `NavSyncInference.kt`.

The template provides:
- Model loading from assets
- Input tensor preparation
- Output tensor parsing
- Fallback to dead-reckoning if model fails

### 4. Update InferenceFactory

Modify `InferenceFactory.createInference()`:

```kotlin
object InferenceFactory {
    fun createInference(context: Context): NavSyncInference {
        return try {
            MLModelInference(context)
        } catch (e: Exception) {
            Log.e("NavSync", "ML model failed to load, using fallback", e)
            DeadReckoningInference()
        }
    }
}
```

### 5. Update NavigationSimulator

Pass context to the factory:

```kotlin
class NavigationSimulator(
    private val context: Context,  // Add this parameter
    private val inference: NavSyncInference = InferenceFactory.createInference(context)
) {
    // ... rest of implementation
}
```

Then update NavigationViewModel to pass context.

## Input Data Available to ML Model

### 1. SensorData (IMU Readings)

```kotlin
data class SensorData(
    val timestampMs: Long,
    val accelerationX: Double,  // m/s²
    val accelerationY: Double,  // m/s²
    val accelerationZ: Double,  // m/s²
    val gyroX: Double,          // rad/s
    val gyroY: Double,          // rad/s
    val gyroZ: Double           // rad/s (yaw rate)
)
```

### 2. NavigationState (Previous Estimate)

```kotlin
data class NavigationState(
    val latitude: Double,       // degrees
    val longitude: Double,      // degrees
    val speedKmh: Double,       // km/h
    val headingDegrees: Double, // degrees (0-360)
    val confidence: Double,     // 0.0 - 1.0
    val gnssAvailable: Boolean,
    val source: NavigationSource
)
```

### 3. Time Delta

- `deltaTimeMs: Long` - Milliseconds since last update (~1000ms in simulation)

## Expected Output

Your model should return:

```kotlin
data class InferenceResult(
    val latitude: Double,       // Estimated latitude
    val longitude: Double,      // Estimated longitude
    val speedKmh: Double,       // Estimated speed
    val headingDegrees: Double, // Estimated heading
    val confidence: Double      // Confidence score (0.0-1.0)
)
```

### Confidence Score Guidelines

- **0.9-1.0**: High confidence (short outage, good sensor data)
- **0.7-0.9**: Medium confidence (moderate outage duration)
- **0.5-0.7**: Low confidence (long outage, sensor drift)
- **< 0.5**: Very low confidence (should be avoided)

The UI will:
- Show yellow "NavSync" indicator when confidence < 0.95
- Display confidence percentage to user
- Adjust visual feedback based on confidence

## Model Input/Output Formats

### Option A: Delta-Based Output

Model predicts change from previous position:

```kotlin
// Input features
[accel_x, accel_y, accel_z, gyro_x, gyro_y, gyro_z, 
 prev_speed, prev_heading, delta_time, prev_confidence]

// Output
[delta_lat, delta_lon, speed, heading, confidence]

// Apply to previous state
new_lat = prev_lat + delta_lat
new_lon = prev_lon + delta_lon
```

**Advantages**:
- Easier to learn (smaller numbers)
- Natural for time-series models
- Better handles accumulating errors

### Option B: Absolute Position Output

Model predicts absolute position:

```kotlin
// Input features
[accel_x, accel_y, accel_z, gyro_x, gyro_y, gyro_z,
 prev_lat, prev_lon, prev_speed, prev_heading, delta_time]

// Output
[lat, lon, speed, heading, confidence]
```

**Advantages**:
- Direct position estimate
- No accumulation needed
- May handle jumps better

### Option C: Sequence-Based (LSTM/Temporal)

Model uses sensor history:

```kotlin
// Input: Sequence of last N sensor readings
[
    [t-N: accel_x, accel_y, ..., prev_lat, prev_lon],
    [t-N+1: accel_x, accel_y, ..., prev_lat, prev_lon],
    ...
    [t: accel_x, accel_y, ..., prev_lat, prev_lon]
]

// Output
[lat, lon, speed, heading, confidence]
```

**Advantages**:
- Captures temporal patterns
- Better handles sensor noise
- More accurate for longer outages

## Feature Engineering Suggestions

### Recommended Features

1. **IMU Sensor Data** (required):
   - Raw accelerometer (X, Y, Z)
   - Raw gyroscope (X, Y, Z)

2. **Derived Motion Features**:
   - Acceleration magnitude: `sqrt(ax² + ay² + az²)`
   - Angular velocity magnitude: `sqrt(gx² + gy² + gz²)`
   - Forward acceleration: `ax * cos(heading) + ay * sin(heading)`
   - Lateral acceleration: `-ax * sin(heading) + ay * cos(heading)`

3. **Previous State Features**:
   - Previous latitude, longitude
   - Previous speed, heading
   - Previous confidence

4. **Time Features**:
   - Delta time since last update
   - Outage duration (if tracking)
   - Time since last GNSS fix

5. **Normalized Features**:
   - Normalize lat/lon to meters from starting point
   - Normalize speed to 0-1 range
   - Normalize heading to -π to π

### Feature Normalization Example

```kotlin
// In prepareTensorInput()
val normalizedSpeed = (speed - meanSpeed) / stdSpeed
val normalizedAccelX = (accelX - meanAccelX) / stdAccelX
val headingRad = Math.toRadians(heading)
val normalizedHeading = Math.sin(headingRad)  // For cyclical feature
```

## Model Architecture Recommendations

### Simple MLP (Baseline)

```
Input: [10 features]
Dense(64, activation='relu')
Dropout(0.2)
Dense(32, activation='relu')
Dense(5)  // Output: [delta_lat, delta_lon, speed, heading, confidence]
```

**Good for**: Quick prototyping, baseline comparison

### LSTM (Recommended)

```
Input: [sequence_length, features]
LSTM(64, return_sequences=True)
LSTM(32)
Dense(16, activation='relu')
Dense(5)
```

**Good for**: Temporal patterns, sensor history, longer outages

### CNN-LSTM (Advanced)

```
Input: [sequence_length, features]
Conv1D(32, kernel_size=3, activation='relu')
Conv1D(32, kernel_size=3, activation='relu')
MaxPooling1D(pool_size=2)
LSTM(64)
Dense(32, activation='relu')
Dense(5)
```

**Good for**: Feature extraction + temporal modeling

## Training Data Format

### Required Data Collection

For each recorded navigation session, collect:

1. **Ground Truth GNSS** (when available)
2. **IMU Sensor Data** (always)
3. **Ground Truth Position** (for evaluation)

### Dataset Structure

```json
{
  "route_name": "Delhi Urban Route",
  "duration_seconds": 600,
  "data_points": [
    {
      "timestamp_ms": 0,
      "gnss": {
        "latitude": 28.6139,
        "longitude": 77.2090,
        "speed_kmh": 42.0,
        "heading_degrees": 137.0
      },
      "sensors": {
        "accel_x": 0.12,
        "accel_y": -0.05,
        "accel_z": 9.81,
        "gyro_x": 0.001,
        "gyro_y": -0.002,
        "gyro_z": 0.05
      },
      "ground_truth": {
        "latitude": 28.6139,
        "longitude": 77.2090
      }
    },
    // ... more points
  ]
}
```

### Training Process

1. **Simulate GNSS Outages** in training data
2. **Train model** to predict position using only IMU + previous state
3. **Evaluate** against ground truth during simulated outages
4. **Measure** position error (meters) and confidence accuracy

### Loss Function Suggestions

```python
# Position loss (meters)
position_loss = haversine_distance(pred_lat, pred_lon, true_lat, true_lon)

# Combined loss
total_loss = (
    position_loss +
    lambda_speed * abs(pred_speed - true_speed) +
    lambda_heading * angular_distance(pred_heading, true_heading) +
    lambda_confidence * confidence_calibration_loss
)
```

## Testing Your Model

### 1. Unit Test the Model

```kotlin
@Test
fun testMLModelInference() {
    val inference = MLModelInference(context)
    
    val lastState = NavigationState(
        latitude = 28.6139,
        longitude = 77.2090,
        speedKmh = 42.0,
        headingDegrees = 137.0,
        confidence = 0.95,
        gnssAvailable = true,
        source = NavigationSource.GNSS
    )
    
    inference.initialize(lastState)
    
    val sensorData = SensorData(
        timestampMs = 1000,
        accelerationX = 0.1,
        accelerationY = 0.0,
        accelerationZ = 9.8,
        gyroX = 0.0,
        gyroY = 0.0,
        gyroZ = 0.05
    )
    
    val result = inference.estimatePosition(sensorData, lastState, 1000)
    
    // Assert results are reasonable
    assertTrue(result.confidence in 0.0..1.0)
    assertTrue(result.speedKmh >= 0)
    // ... more assertions
}
```

### 2. Integration Test with Simulator

Run simulation with your model and compare:
- Position error vs dead-reckoning
- Confidence calibration
- Runtime performance

### 3. Benchmark Performance

Measure:
- Inference time (target: < 100ms)
- Memory usage
- Battery impact

## Performance Optimization

### Model Size

- **Target**: < 5 MB for mobile deployment
- Use quantization: `TFLiteConverter.optimizations = [tf.lite.Optimize.DEFAULT]`
- Consider pruning if model is large

### Inference Speed

- **Target**: < 100ms per inference call
- Use GPU acceleration if available
- Consider NNAPI delegate for Android optimization

### Example Optimization

```kotlin
val options = Interpreter.Options().apply {
    setNumThreads(4)
    // Use GPU if available
    if (CompatibilityList().isDelegateSupportedOnThisDevice) {
        addDelegate(GpuDelegate())
    }
    // Or use NNAPI
    setUseNNAPI(true)
}

interpreter = Interpreter(modelBuffer, options)
```

## Validation Metrics

### Position Accuracy

- **Mean Error**: Average distance error in meters
- **95th Percentile Error**: Error exceeded in only 5% of cases
- **Max Error**: Worst-case error

### Confidence Calibration

- Model confidence should match actual accuracy
- Plot: Predicted confidence vs actual accuracy
- Ideal: Close to diagonal line

### Comparison to Baseline

Your model should outperform dead-reckoning:
- Lower mean error
- Better confidence calibration
- Graceful degradation over time

## Troubleshooting

### Model Fails to Load

- Check file path: `app/src/main/assets/navsync_model.tflite`
- Verify TensorFlow Lite dependency
- Check model compatibility with TFLite 2.14.0

### Inference Crashes

- Verify input tensor dimensions match model
- Check for NaN/Inf values in input
- Ensure thread-safe access to interpreter

### Poor Accuracy

- Check feature normalization
- Verify sensor coordinate frame
- Review training data quality
- Consider adding more features

### High Inference Time

- Use model quantization
- Reduce model complexity
- Enable hardware acceleration
- Profile with Android Studio

## Next Steps After Integration

1. **Validate on Test Routes**: Run on unseen datasets
2. **Compare to Baseline**: Measure improvement over dead-reckoning
3. **Fine-tune Confidence**: Calibrate confidence scores
4. **Add ESKF**: Consider Kalman filtering for smoother output
5. **Map Matching**: Integrate with road network constraints
6. **Production Testing**: Test on real devices in various scenarios

## Support Resources

### Code References

- `NavSyncInference.kt` - Interface and baseline implementation
- `NavigationSimulator.kt` - How inference is called
- `DatasetRepository.kt` - Dataset format
- `NavigationState.kt` - State data structures

### Documentation

- `SIMULATION_ARCHITECTURE.md` - Overall system architecture
- `END_TO_END_VERIFICATION.md` - How the pipeline works
- This guide - ML integration specifics

### Contact

For questions about integration, contact the NavSync platform team.
