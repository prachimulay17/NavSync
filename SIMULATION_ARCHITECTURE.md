# NavSync Simulation Architecture

## Overview

The simulation architecture represents a **real GNSS-outage experiment** where dataset replay drives the navigation system and ML inference provides position estimates during outages using **only allowed sensor inputs**.

## Critical Data Separation

The architecture maintains strict separation between:

1. **Sensor Data** (IMU) - Always available to inference
2. **GNSS Data** - Available only when signal present, NEVER to inference during outage
3. **Ground Truth** - For evaluation only, NEVER as input to inference

This ensures the inference engine cannot "cheat" by accessing position data it wouldn't have in a real outage.

## Architecture Flow

```
SimulationScreen (Configure)
       ↓
SimulationViewModel (Config State)
       ↓
NavigationViewModel (Execution Control)
       ↓
NavigationSimulator (Dataset Replay + GNSS Outage Logic)
       ↓
├─ GNSS Available → GNSS Data → NavigationState
└─ GNSS Outage → Sensor Data + Previous State → NavSyncInference → NavigationState
       ↓
NavigationState (Observable State)
       ↓
NavigationScreen (Real-time Display)

Ground Truth (separate evaluation channel, never to inference)
```

## Data Flow During Simulation

### GNSS Available Mode
```
Dataset Point
  ├─ sensorData (stored for next step)
  ├─ gnssData → NavigationState
  └─ groundTruth (stored for evaluation)
```

### GNSS Outage Mode
```
Dataset Point
  ├─ sensorData → NavSyncInference
  │                    ↑
  │         previousState (from last step)
  │                    ↓
  │              InferenceResult → NavigationState
  ├─ gnssData (NOT provided to inference)
  └─ groundTruth (stored for evaluation only)
```

### GNSS Recovery
```
Dataset Point
  ├─ sensorData (stored for next step)
  ├─ gnssData → NavigationState
  └─ groundTruth (compare outage estimates vs truth)
```

## Key Components

### 1. Data Layer (`app/src/main/java/com/example/navsync/data/`)

#### SensorData
- IMU sensor readings (accelerometer, gyroscope)
- **Always available** to inference engine
- Does NOT contain position information

#### GnssData
- GNSS position (lat, lon, speed, heading)
- Available only when GNSS signal present
- **NEVER provided to inference during outage**

#### GroundTruth
- True position regardless of GNSS availability
- Used **ONLY for evaluation/metrics**
- Never as input to inference engine

#### DatasetPoint
- Complete data point with all three types
- Types are used separately based on simulation mode
- Enforces proper data separation

#### NavigationDataset
- Complete sequence of recorded navigation points
- Currently synthetic, designed for real dataset replacement

#### DatasetRepository
- Manages available datasets
- Currently generates synthetic datasets for 5 Indian city routes
- **Future**: Load from JSON/CSV files or remote sources

### 2. Inference Layer (`app/src/main/java/com/example/navsync/inference/`)

#### NavSyncInference Interface
**This is the ML integration point** for the trained model.

```kotlin
interface NavSyncInference {
    fun initialize(lastState: NavigationState)
    fun estimatePosition(
        sensorData: SensorData,      // IMU only
        previousState: NavigationState,  // Last position estimate
        deltaTimeMs: Long
    ): InferenceResult
    fun reset()
}
```

**Critical**: The interface receives:
- ✅ Sensor data (IMU)
- ✅ Previous navigation state
- ❌ NO ground truth position
- ❌ NO GNSS data during outage

#### DeadReckoningInference
- Default implementation using simple dead-reckoning
- Serves as baseline comparison for ML model
- Uses only previous state and gyroscope for heading
- Confidence degrades over time during outage

#### InferenceFactory
- Creates inference engine instances
- **Future**: Detect and load TensorFlow Lite model
- Current: Returns DeadReckoningInference

**To integrate trained ML model:**
1. Add TensorFlow Lite model file to `assets/`
2. Create `MLModelInference` implementing `NavSyncInference`
3. Update `InferenceFactory.createInference()` to load model
4. No changes needed to simulation or UI code

### 3. Simulation Layer (`app/src/main/java/com/example/navsync/simulation/`)

#### NavigationSimulator
The core simulation engine that orchestrates the GNSS outage experiment.

**Key Responsibilities:**
- Replays dataset points sequentially (1 per second)
- Tracks GNSS availability based on outage configuration
- Enforces proper data separation (sensor vs GNSS vs ground truth)
- Manages outage state transitions (entering/exiting)

**State Machine:**
- **GNSS Active**: Returns position from GNSS data
- **Outage Transition**: Initializes inference with last navigation state
- **In Outage**: Passes only sensor data + previous state to inference
- **Recovery**: Returns to GNSS data

**Data Flow Enforcement:**
```kotlin
// GNSS Outage - Only allowed inputs to inference
val inferenceResult = inference.estimatePosition(
    sensorData = dataPoint.sensorData,        // IMU only
    previousState = previousState,            // Last estimate
    deltaTimeMs = deltaTime
)
// dataPoint.gnssData is NOT passed
// dataPoint.groundTruth is available for evaluation only
```

#### OutageConfig
- `enabled`: Whether to simulate outage
- `startStep`: When outage begins (seconds into simulation)
- `durationSeconds`: How long outage lasts

### 4. ViewModel Layer

#### NavigationViewModel
- Controls automatic simulation playback
- Updates NavigationState every second using coroutines
- Manages simulation lifecycle (start/stop/reset)
- Exposes observable state for UI
- Tracks current step and progress

**Key Methods:**
- `startSimulation()`: Begins automatic dataset replay
- `stopSimulation()`: Pauses playback
- `resetSimulation()`: Returns to beginning

#### SimulationViewModel
- Manages simulation configuration
- Provides dataset selection
- Controls GNSS mode and outage parameters
- Loads configured dataset for replay

### 5. UI Layer

#### SimulationScreen
- Configuration interface for experiment setup
- Dataset selection (5 synthetic routes)
- GNSS mode: Active vs Outage
- Outage timing: Start time and duration
- Real-time status display
- Start/Stop/Reset controls (automatic replay, no manual stepping)
- Disables configuration during active simulation

#### NavigationScreen
- Real-time navigation display
- Observes NavigationState from NavigationViewModel
- Updates automatically as simulation runs (1 per second)
- Shows GNSS availability and source
- Displays confidence level
- Visual distinction between GNSS and AI estimation

## Real-Time Experiment Flow

1. **User configures experiment** (SimulationScreen)
   - Selects dataset (e.g., "Delhi Urban Route")
   - Chooses "GNSS Outage" mode
   - Sets outage start time (e.g., 20s)
   - Sets outage duration (e.g., 30s)

2. **User starts simulation** (automatic replay)
   - SimulationViewModel loads dataset
   - NavigationViewModel receives configuration
   - NavigationSimulator begins automatic replay (1 update/sec)

3. **Automatic playback**
   - Seconds 0-19: Uses GNSS data from dataset
   - Second 20: Outage begins, inference initialized with last state
   - Seconds 20-49: Inference receives ONLY sensor data + previous state
   - Second 50: GNSS recovery, returns to GNSS data
   - Continues until dataset complete

4. **NavigationScreen updates in real-time**
   - Position, speed, heading update each second
   - Status indicator shows GNSS available/unavailable
   - Source changes (GNSS → AI_ESTIMATION → GNSS)
   - Confidence level reflects estimation quality

## Integration Points for Future Development

### 1. ML Model Integration
Replace `DeadReckoningInference` with trained model:
```kotlin
class MLModelInference : NavSyncInference {
    private lateinit var interpreter: Interpreter
    
    init {
        val model = loadModelFile(context, "navsync_model.tflite")
        interpreter = Interpreter(model)
    }
    
    override fun estimatePosition(
        sensorData: SensorData,
        previousState: NavigationState,
        deltaTimeMs: Long
    ): InferenceResult {
        // Prepare input tensor from sensor data + previous state
        val input = prepareTensorInput(sensorData, previousState)
        
        // Run inference
        val output = FloatArray(OUTPUT_SIZE)
        interpreter.run(input, output)
        
        // Parse output to position estimate
        return parseInferenceOutput(output)
    }
}
```

### 2. Real Dataset Loading
Update `DatasetRepository`:
```kotlin
fun loadDataset(name: String): NavigationDataset {
    // Load from JSON file with proper structure
    val json = loadFromAssets("datasets/$name.json")
    return parseDataset(json)  // Separates sensor/gnss/groundtruth
}
```

### 3. ESKF/IEKF Integration
Add Kalman filter after inference:
```kotlin
class FilteredInference(
    private val inference: NavSyncInference,
    private val filter: ExtendedKalmanFilter
) : NavSyncInference {
    override fun estimatePosition(
        sensorData: SensorData,
        previousState: NavigationState,
        deltaTimeMs: Long
    ): InferenceResult {
        val rawEstimate = inference.estimatePosition(sensorData, previousState, deltaTimeMs)
        return filter.update(rawEstimate, sensorData)
    }
}
```

### 4. Evaluation Metrics
Add evaluation layer using ground truth:
```kotlin
class SimulationEvaluator {
    fun evaluateOutage(
        estimates: List<NavigationState>,
        groundTruths: List<GroundTruth>
    ): OutageMetrics {
        return OutageMetrics(
            meanError = calculateMeanError(estimates, groundTruths),
            maxError = calculateMaxError(estimates, groundTruths),
            confidenceAccuracy = evaluateConfidence(estimates, groundTruths)
        )
    }
}
```

## Current Limitations

- Synthetic datasets (not real recorded data)
- Simple dead-reckoning inference (no ML model yet)
- 1 second update interval (not real-time sensor rate)
- No sensor noise simulation
- No map data or map matching
- No evaluation metrics display

## Benefits of This Architecture

✅ **Data integrity**: Ground truth never leaks to inference engine
✅ **Realistic**: Inference has only what real system would have
✅ **Replaceable components**: Each layer can be swapped independently
✅ **Testable**: Easy to test with different datasets and inference engines
✅ **Observable**: UI automatically updates from ViewModel state
✅ **ML-ready**: Clear integration point for trained models
✅ **Incremental**: Can add features without rewriting

## Next Steps

1. **Record real datasets** from actual drives with GNSS + IMU
2. **Train ML model** for position estimation during outages
3. **Integrate TensorFlow Lite** model into InferenceFactory
4. **Add ESKF/IEKF** state estimation filter
5. **Implement evaluation metrics** using ground truth
6. **Add performance visualization** (error distance, confidence accuracy)
7. **Implement map matching** for road-network constraint
8. **Visualize trajectory** comparison (estimate vs ground truth)
