# Simulation-to-Navigation Integration

## Overview

SimulationScreen and NavigationScreen are **fully integrated** through a shared NavigationViewModel instance. Both screens observe the same simulation state and use the same simulator instance.

## Architecture

```
MainActivity
    ↓
Shared ViewModels (Activity Scope)
    ├─ NavigationViewModel (contains simulator)
    └─ SimulationViewModel (configuration only)
    
NavigationViewModel
    ├─ NavigationSimulator (single instance)
    ├─ navigationState (observable)
    ├─ isSimulationRunning (observable)
    ├─ currentStep (observable)
    └─ totalSteps (observable)

SimulationScreen                NavigationScreen
    ↓ observes                      ↓ observes
    ├─ isSimulationRunning          ├─ navigationState
    ├─ currentStep                  └─ (updates live)
    └─ totalSteps
    ↓ controls
    ├─ startSimulation()
    ├─ stopSimulation()
    └─ resetSimulation()
```

## Integration Points

### 1. Shared ViewModel Instance (MainActivity.kt)

```kotlin
@Composable
fun NavSyncApp() {
    // Single ViewModel instance shared across both screens
    val navigationViewModel: NavigationViewModel = viewModel()
    val simulationViewModel: SimulationViewModel = viewModel()
    
    NavHost(...) {
        composable("navigation") {
            NavigationScreen(navigationViewModel = navigationViewModel)
        }
        composable("simulation") {
            SimulationScreen(
                simulationViewModel = simulationViewModel,
                navigationViewModel = navigationViewModel
            )
        }
    }
}
```

**Key**: Both screens receive the SAME `navigationViewModel` instance, ensuring they observe the same state.

### 2. SimulationScreen - Configuration & Control

SimulationScreen is responsible for:
- Configuring the experiment (dataset, GNSS mode, outage timing)
- Starting/stopping/resetting the simulation
- Displaying simulation progress

```kotlin
@Composable
fun SimulationScreen(
    simulationViewModel: SimulationViewModel,
    navigationViewModel: NavigationViewModel
) {
    val simulationConfig = simulationViewModel.simulationConfig
    val isRunning = navigationViewModel.isSimulationRunning
    val currentStep = navigationViewModel.currentStep
    val totalSteps = navigationViewModel.totalSteps
    
    // Start button
    onStart = {
        val dataset = simulationViewModel.loadConfiguredDataset()
        navigationViewModel.startSimulation(
            dataset = dataset,
            gnssOutageEnabled = !simulationConfig.gnssActive,
            outageStartStep = simulationConfig.outageStartTime,
            outageDurationSeconds = simulationConfig.outageDuration
        )
    }
}
```

### 3. NavigationScreen - Live Display

NavigationScreen is responsible for:
- Displaying the navigation interface
- Showing live position, speed, heading
- Indicating GNSS availability and source
- Updating automatically as simulation runs

```kotlin
@Composable
fun NavigationScreen(
    navigationViewModel: NavigationViewModel
) {
    val navigationState = navigationViewModel.navigationState
    
    // All UI components observe navigationState
    // Updates automatically every second during simulation
    MapArea(navigationState = navigationState)
    StatusBar(navigationState = navigationState)
    NavigationHUD(navigationState = navigationState)
}
```

### 4. NavigationViewModel - Single Source of Truth

NavigationViewModel contains:
- **One simulator instance** (not separate instances per screen)
- Observable state that both screens watch
- Simulation control methods

```kotlin
class NavigationViewModel : ViewModel() {
    private val simulator = NavigationSimulator()  // Single instance
    
    var navigationState by mutableStateOf(getDefaultState())
        private set
    
    var isSimulationRunning by mutableStateOf(false)
        private set
    
    fun startSimulation(dataset, gnssOutageEnabled, ...) {
        simulator.loadDataset(dataset)
        simulator.configureOutage(gnssOutageEnabled, ...)
        
        // Start coroutine that updates navigationState every second
        simulationJob = viewModelScope.launch {
            while (isActive && !simulator.isComplete()) {
                navigationState = simulator.nextState()
                currentStep = simulator.getCurrentStep()
                delay(1000L)
            }
        }
    }
}
```

## How Start Works

### GNSS Active Mode

User selects: "GNSS Active"

```
User presses Start
    ↓
SimulationScreen calls navigationViewModel.startSimulation(
    gnssOutageEnabled = false,  // No outage
    ...
)
    ↓
NavigationViewModel starts coroutine
    ↓
Every second:
    simulator.nextState()
        → Uses GNSS data from dataset
        → Returns NavigationState(gnssAvailable=true, source=GNSS)
    ↓
    navigationState updated
    ↓
NavigationScreen observes navigationState
    ↓
UI updates:
    - Position from dataset GNSS
    - "GNSS" indicator green
    - Confidence ~95%
```

### GNSS Outage Mode

User selects: "GNSS Outage", start=20s, duration=30s

```
User presses Start
    ↓
SimulationScreen calls navigationViewModel.startSimulation(
    gnssOutageEnabled = true,
    outageStartStep = 20,
    outageDurationSeconds = 30
)
    ↓
NavigationViewModel starts coroutine
    ↓
Every second:
    Seconds 0-19:
        simulator.nextState()
            → Uses GNSS data from dataset
            → Returns NavigationState(gnssAvailable=true, source=GNSS)
    
    Second 20 (outage begins):
        simulator.nextState()
            → Initializes inference with last GNSS state
            → Uses inference with sensor data
            → Returns NavigationState(gnssAvailable=false, source=AI_ESTIMATION)
    
    Seconds 20-49:
        simulator.nextState()
            → Uses inference (sensor data + previous state)
            → Returns NavigationState(gnssAvailable=false, source=AI_ESTIMATION)
    
    Second 50 (recovery):
        simulator.nextState()
            → Returns to GNSS data from dataset
            → Returns NavigationState(gnssAvailable=true, source=GNSS)
    ↓
    navigationState updated each second
    ↓
NavigationScreen observes navigationState
    ↓
UI updates in real-time:
    - Before outage: GNSS position, green indicator
    - During outage: Inference position, yellow "NavSync" indicator
    - After recovery: GNSS position, green indicator
```

## State Synchronization

### Shared Observable State

Both screens observe the same state properties:

```kotlin
// NavigationViewModel state (observed by both screens)
var navigationState by mutableStateOf(...)        // NavigationScreen
var isSimulationRunning by mutableStateOf(...)    // Both screens
var currentStep by mutableStateOf(...)            // SimulationScreen
var totalSteps by mutableStateOf(...)             // SimulationScreen
```

When the state updates in NavigationViewModel:
1. Compose automatically recomposes both screens
2. NavigationScreen shows updated position/speed/heading
3. SimulationScreen shows updated progress

### Screen Navigation

User can switch between screens at any time:
- Simulation continues running in the background
- State remains synchronized
- No data loss or reset when switching screens

Example flow:
```
1. User on SimulationScreen
2. Presses Start → simulation begins
3. User switches to NavigationScreen (FAB button)
4. Sees live navigation updating every second
5. User switches back to SimulationScreen
6. Sees progress counter updating
7. User presses Stop → simulation pauses
```

## Benefits

✅ **Single source of truth**: One simulator instance, one state
✅ **Real-time sync**: Both screens always show current state
✅ **No duplication**: State is not copied or duplicated
✅ **Clean architecture**: Clear separation of concerns
✅ **Easy to test**: Can observe state changes from either screen
✅ **Navigation-friendly**: Switching screens doesn't affect simulation

## Implementation Details

### Why This Works

1. **Activity-scoped ViewModels**: ViewModels created at MainActivity level persist across navigation
2. **Compose State**: `mutableStateOf` triggers recomposition when state changes
3. **Coroutine Updates**: Simulation runs in ViewModel scope, updates state every second
4. **Observer Pattern**: Screens observe state, don't poll or copy it

### What Doesn't Happen

❌ Each screen does NOT have its own simulator
❌ State is NOT copied between screens
❌ NavigationScreen does NOT have static/demo data
❌ Starting simulation does NOT create a new state object

### What Does Happen

✅ MainActivity creates ONE NavigationViewModel
✅ NavigationViewModel creates ONE NavigationSimulator
✅ Both screens observe the SAME navigationState
✅ Simulator updates state every second
✅ Compose automatically recomposes both screens

## Testing the Integration

### GNSS Active Mode
1. Go to SimulationScreen
2. Select "GNSS Active"
3. Press Start
4. Switch to NavigationScreen
5. Observe: Position updates every second from dataset GNSS
6. Observe: Green GNSS indicator always on
7. Observe: Confidence stays ~95%

### GNSS Outage Mode
1. Go to SimulationScreen
2. Select "GNSS Outage"
3. Set start=10s, duration=20s
4. Press Start
5. Switch to NavigationScreen
6. Observe: Seconds 0-9 → green GNSS
7. Observe: Second 10 → yellow "NavSync" indicator
8. Observe: Seconds 10-29 → inference-based position
9. Observe: Second 30 → back to green GNSS
10. Observe: Confidence drops during outage, recovers after

### Control Verification
1. Start simulation from SimulationScreen
2. Switch to NavigationScreen (simulation continues)
3. Switch back to SimulationScreen
4. Press Stop (simulation pauses)
5. Observe: NavigationScreen no longer updates
6. Press Reset (simulation returns to beginning)
7. Observe: Both screens show initial state

## Future Enhancements

The integration is ready for:
- Real-time metrics display on both screens
- Evaluation overlay showing ground truth vs estimate
- Performance graphs during/after simulation
- Export simulation results
- Compare multiple inference methods
