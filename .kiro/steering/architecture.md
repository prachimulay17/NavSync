# NavSync Architecture

## Current Architecture

The existing application uses:

- Kotlin
- Jetpack Compose
- MVVM
- NavigationViewModel
- NavigationState
- NavigationSimulator

Current flow:

NavigationSimulator
        ↓
NavigationViewModel
        ↓
NavigationScreen

## Target Architecture

The application should gradually evolve toward:

Presentation
    ↓
ViewModel
    ↓
Navigation Engine / Domain
    ↓
Repository
    ↓
Simulation / Sensors / ML

Do not perform a large architectural rewrite.

Prefer incremental evolution of the existing project.

## State

NavigationState should remain the central state contract
between the navigation engine and UI.

The UI should not directly contain navigation/business logic.

The ViewModel should expose UI-observable state.

## Simulation

Simulation must remain replaceable by real data sources.

The simulation layer should eventually be replaceable by:

- Android GNSS
- Android IMU
- ML inference output
- NavSync navigation engine

without requiring a complete UI rewrite.

## Future Components

Possible future components include:

- LocationRepository
- SensorRepository
- MLInferenceRepository
- NavigationEngine
- ESKF/IEKF estimator
- MapMatchingEngine

These should be introduced only when required by the
corresponding feature.

## Dependency Policy

Do not introduce a dependency unless there is a clear
requirement for it.

Do not add Hilt, Room, Google Maps, Mapbox, or other major
libraries simply for architectural completeness.
