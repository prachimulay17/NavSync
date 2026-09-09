# Simulation Execution Flow

## Overview

Both **GNSS Active** and **GNSS Outage** modes show the Start button and work through the same execution flow. The selected GNSS configuration controls the shared NavigationSimulator behavior.

## Execution Flow

### Common Flow (Both Modes)

```
User on SimulationScreen
    ↓
Selects GNSS mode (Active or Outage)
    ↓
Configures parameters (dataset, outage timing)
    ↓
Presses START button
    ↓
SimulationViewModel.loadConfiguredDataset()
    ↓
NavigationViewModel.startSimulation(
    dataset,
    gnssOutageEnabled = !simulationConfig.gnssActive,
    outageStartStep,
    outageDurationSeconds
)
    ↓
NavigationSimulator configured and started
    ↓
Coroutine updates NavigationState every 1 second
    ↓
Both SimulationScreen and NavigationScreen observe live updates
```

## Mode-Specific Behavior

### GNSS Active Mode

**User Configuration:**
- Selects "GNSS Active" radio button
- `simulationConfig.gnssActive = true`

**Start Execution:**
```kotlin
navigationViewModel.startSimulation(
    dataset = loadedDataset,
    gnssOutageEnabled = false,    // !true = false
    outageStartStep = 20,          // Ignored when disabled
    outageDurationSeconds = 30     // Ignored when disabled
)
```

**Simulator Behavior:**
```kotlin
// In NavigationSimulator.nextState()
outageConfig.enabled = false

shouldBeInOutage = false && ... = false  // Always false

// Always executes this branch:
state = NavigationState(
    latitude = dataPoint.gnssData.latitude,
    longitude = dataPoint.gnssData.longitude,
    speedKmh = dataPoint.gnssData.speedKmh,
    headingDegrees = dataPoint.gnssData.headingDegrees,
    confidence = 0.95,
    gnssAvailable = true,        // ✅ GNSS available
    source = NavigationSource.GNSS  // ✅ Source is GNSS
)
```

**NavigationScreen Display:**
- Position: Dataset GNSS data
- Status: Green dot + "GNSS"
- Confidence: 95%
- System: "GNSS"

**Timeline:**
```
Second 0-99: GNSS data from dataset
             gnssAvailable = true
             source = GNSS
             confidence = 95%
```

### GNSS Outage Mode

**User Configuration:**
- Selects "GNSS Outage" radio button
- `simulationConfig.gnssActive = false`
- Sets `outageStartTime = 20`
- Sets `outageDuration = 30`

**Start Execution:**
```kotlin
navigationViewModel.startSimulation(
    dataset = loadedDataset,
    gnssOutageEnabled = true,     // !false = true
    outageStartStep = 20,
    outageDurationSeconds = 30
)
```

**Simulator Behavior:**
```kotlin
// In NavigationSimulator.nextState()
outageConfig.enabled = true
outageConfig.startStep = 20
outageConfig.durationSeconds = 30

// Calculate outage window
outageEndStep = 20 + 30 = 50

// Seconds 0-19
shouldBeInOutage = true && (step >= 20) && (step < 50)
                 = true && false && ... = false

state = NavigationState(
    latitude = dataPoint.gnssData.latitude,
    longitude = dataPoint.gnssData.longitude,
    ...,
    gnssAvailable = true,           // ✅ GNSS available
    source = NavigationSource.GNSS  // ✅ Source is GNSS
)

// Second 20 (outage begins)
shouldBeInOutage = true && (20 >= 20) && (20 < 50) = true
inOutage = true
inference.initialize(lastNavigationState)  // Initialize with last GNSS

state = NavigationState(
    latitude = inferenceResult.latitude,   // From NavSyncInference
    longitude = inferenceResult.longitude,
    ...,
    gnssAvailable = false,                      // ❌ GNSS unavailable
    source = NavigationSource.AI_ESTIMATION     // ✅ Using inference
)

// Seconds 21-49 (during outage)
shouldBeInOutage = true && (step >= 20) && (step < 50) = true

state = NavigationState(
    // Inference estimates using sensor data + previous state
    gnssAvailable = false,
    source = NavigationSource.AI_ESTIMATION
)

// Second 50 (recovery)
shouldBeInOutage = true && (50 >= 20) && (50 < 50) = false
inOutage = false
inference.reset()

state = NavigationState(
    latitude = dataPoint.gnssData.latitude,  // Back to GNSS
    longitude = dataPoint.gnssData.longitude,
    ...,
    gnssAvailable = true,                    // ✅ GNSS recovered
    source = NavigationSource.GNSS           // ✅ Back to GNSS
)
```

**NavigationScreen Display:**

**Seconds 0-19 (Pre-outage):**
- Position: Dataset GNSS data
- Status: Green dot + "GNSS"
- Confidence: 95%
- System: "GNSS"

**Second 20 (Outage begins):**
- Position: Switches to inference estimate
- Status: Yellow dot + "NavSync"
- Confidence: 75% (starts degrading)
- System: "AI_ESTIMATION"

**Seconds 21-49 (During outage):**
- Position: Inference estimates (sensor + previous state)
- Status: Yellow dot + "NavSync"
- Confidence: Degrading (75% → ~73% → ~71% ...)
- System: "AI_ESTIMATION"

**Second 50+ (Recovery):**
- Position: Back to dataset GNSS data
- Status: Green dot + "GNSS"
- Confidence: 95%
- System: "GNSS"

## State Propagation

Both modes use the same state propagation mechanism:

```
NavigationSimulator.nextState()
    ↓
Returns NavigationState with:
    - latitude, longitude, speed, heading
    - gnssAvailable (true/false)
    - source (GNSS or AI_ESTIMATION)
    - confidence (0.0 - 1.0)
    ↓
NavigationViewModel.navigationState updated
    ↓
Compose recomposition triggered
    ↓
┌─────────────────────┬─────────────────────┐
↓                     ↓                     ↓
SimulationScreen      NavigationScreen      All Components
- Progress display    - Map                 - StatusBar
- Step counter        - HUD                 - NavigationHUD
                      - Position display    - SystemStatus
```

## UI Component Response to State

### NavigationHUD Components

**NavigationSource Component:**
```kotlin
Row {
    Box(
        background = if (gnssAvailable) StatusGreen else StatusYellow
    )
    Text(
        text = if (gnssAvailable) "GNSS" else "NavSync"
    )
}
```

**SystemStatus Component:**
```kotlin
Text(text = source)  // "GNSS" or "AI_ESTIMATION"
```

**All displays respond dynamically** to navigationState - no hardcoded values.

## Key Differences Between Modes

| Aspect | GNSS Active | GNSS Outage |
|--------|-------------|-------------|
| **Start button** | ✅ Shows | ✅ Shows |
| **Execution** | ✅ Runs | ✅ Runs |
| **Outage enabled** | `false` | `true` |
| **GNSS availability** | Always `true` | Switches during outage |
| **Navigation source** | Always `GNSS` | Switches to `AI_ESTIMATION` |
| **Position source** | Dataset GNSS | Dataset GNSS → Inference → GNSS |
| **Confidence** | Always ~95% | Degrades during outage |
| **UI indicator** | Always green | Green → Yellow → Green |

## Implementation Details

### SimulationScreen Start Logic

```kotlin
onStart = {
    val dataset = simulationViewModel.loadConfiguredDataset()
    navigationViewModel.startSimulation(
        dataset = dataset,
        gnssOutageEnabled = !simulationConfig.gnssActive,  // Inverted!
        outageStartStep = simulationConfig.outageStartTime,
        outageDurationSeconds = simulationConfig.outageDuration
    )
}
```

**Note**: `gnssOutageEnabled` is the **inverse** of `gnssActive`:
- GNSS Active = true → gnssOutageEnabled = false
- GNSS Active = false → gnssOutageEnabled = true

### NavigationSimulator Decision Logic

```kotlin
val shouldBeInOutage = outageConfig.enabled &&     // Is outage mode enabled?
                       currentStep >= outageConfig.startStep &&  // Past start?
                       currentStep < outageEndStep               // Before end?

if (shouldBeInOutage) {
    // Use inference
    state = NavigationState(
        ...,
        gnssAvailable = false,
        source = NavigationSource.AI_ESTIMATION
    )
} else {
    // Use GNSS
    state = NavigationState(
        ...,
        gnssAvailable = true,
        source = NavigationSource.GNSS
    )
}
```

### NavigationScreen Observation

```kotlin
@Composable
fun NavigationScreen(navigationViewModel: NavigationViewModel) {
    // This updates automatically every second during simulation
    val navigationState = navigationViewModel.navigationState
    
    // All components receive the same live state
    MapArea(navigationState = navigationState)
    StatusBar(navigationState = navigationState)
    NavigationHUD(navigationState = navigationState)
}
```

## Verification Steps

### Test GNSS Active Mode

1. Open SimulationScreen
2. Select "GNSS Active"
3. Press Start
4. Switch to NavigationScreen
5. Observe: Position updates every second
6. Observe: Green GNSS indicator throughout
7. Observe: Confidence stays at 95%
8. Observe: System shows "GNSS"

### Test GNSS Outage Mode

1. Open SimulationScreen
2. Select "GNSS Outage"
3. Set start = 10s, duration = 20s
4. Press Start
5. Switch to NavigationScreen
6. Observe seconds 0-9: Green GNSS
7. Observe second 10: Switches to yellow NavSync
8. Observe seconds 10-29: NavSync active, confidence drops
9. Observe second 30: Returns to green GNSS
10. Observe: System label changes GNSS → AI_ESTIMATION → GNSS

## Summary

✅ **Both modes show Start button**
✅ **Both modes execute through same flow**
✅ **GNSS configuration controls NavigationSimulator**
✅ **NavigationState reflects actual GNSS availability**
✅ **NavigationScreen updates automatically from shared state**
✅ **No hardcoded "GNSS Active" display**
✅ **UI responds dynamically to state changes**
