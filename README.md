# NavSync

## Reliable Navigation Beyond GNSS

NavSync is a smartphone-first navigation system designed to maintain reliable vehicle navigation when GNSS becomes degraded, unreliable, or unavailable.

Instead of treating navigation as simply **GNSS available / GNSS unavailable**, NavSync combines GNSS, inertial sensing, physics-based estimation, learned motion information, vehicle constraints, and road context according to the reliability of the available information.

> **Navigation should adapt to the reliability of information, not simply its availability.**

---

## Why NavSync?

GNSS can become unreliable before it completely disappears.

A vehicle may experience:

- signal blockage
- multipath
- urban GNSS degradation
- temporary outages
- sudden position jumps
- inconsistent measurements
- spoofing or other GNSS anomalies

A simple fallback to dead reckoning is also not sufficient because inertial errors accumulate over time.

NavSync is designed to handle this transition continuously:

```text
Reliable GNSS
      ↓
Degraded GNSS
      ↓
Low-trust GNSS
      ↓
GNSS Outage
      ↓
Inertial + Learned Motion + Vehicle Constraints
      ↓
GNSS Recovery
      ↓
Consistency Check
      ↓
Gradual GNSS Re-integration
```

---

## Core Approach

NavSync combines two complementary sources of information.

### Physics-Based Estimation

A physics-based estimator maintains the navigation state using:

- GNSS
- accelerometer
- gyroscope
- vehicle motion constraints
- uncertainty propagation

The navigation state includes:

- Position
- Velocity
- Attitude
- Sensor biases
- Uncertainty

The current implementation uses an **Error-State Kalman Filter (ESKF)** as the primary estimator.

### Learned Motion Information

Machine learning is used to learn vehicle-motion patterns from inertial data.

The learned component is intended to **support the navigation estimator**, not replace it with a black-box position predictor.

This allows learned information to work together with physical state estimation and uncertainty.

---

## Reliability-Aware Fusion

The key system principle is that measurements should not have a fixed level of trust.

```text
Sensor Measurement
        ↓
Reliability Assessment
        ↓
Appropriate Influence
        ↓
Navigation Estimator
```

When GNSS is reliable, it can strongly correct the inertial estimate.

When GNSS becomes degraded, its influence can be reduced.

When a measurement becomes inconsistent, it can be rejected or downweighted.

When GNSS is unavailable, the system continues using the remaining information sources while tracking increasing uncertainty.

This makes **sensor reliability part of the navigation process**, rather than using a simple GNSS ON/OFF switch.

---

## System Architecture

```text
                    ┌──────────────────┐
                    │      GNSS        │
                    └────────┬─────────┘
                             ↓
                    ┌──────────────────┐
                    │ GNSS Quality &   │
                    │ Anomaly Check    │
                    └────────┬─────────┘
                             │
                             ↓
┌──────────────┐     ┌──────────────────┐
│ Smartphone   │────►│ Physics-Based    │
│ IMU          │     │ State Estimator  │
└──────────────┘     │ ESKF / InEKF     │
                     └────────┬─────────┘
                              │
            ┌─────────────────┼─────────────────┐
            ↓                 ↓                 ↓
   ┌────────────────┐ ┌───────────────┐ ┌─────────────────┐
   │ Learned Motion │ │ Vehicle       │ │ Road /          │
   │ Information    │ │ Constraints   │ │ Trajectory      │
   │                │ │               │ │ Consistency     │
   └────────────────┘ └───────────────┘ └─────────────────┘
            │                 │                 │
            └─────────────────┼─────────────────┘
                              ↓
                    ┌──────────────────┐
                    │ Navigation State │
                    │ + Uncertainty    │
                    └──────────────────┘
```

---

## Navigation Pipeline

```text
Smartphone GNSS + IMU
          ↓
Preprocessing & Calibration
          ↓
Time Synchronization
          ↓
Phone-to-Vehicle Alignment
          ↓
GNSS Quality Assessment
          ↓
Physics-Based Estimation
          ↓
Learned Motion + Vehicle Constraints
          ↓
Reliability-Aware Fusion
          ↓
Navigation State + Uncertainty
          ↓
Road / Trajectory Consistency
          ↓
GNSS Recovery Verification
          ↓
Gradual GNSS Re-integration
```

---

## Initial Results

The current physics-based navigation baseline has been tested on self-collected smartphone sensor data.

The comparison below shows the current trajectory results during GNSS degradation.

<img width="1600" height="933" alt="trajectory comparison" src="https://github.com/user-attachments/assets/0bff51d0-249e-40ea-9c59-13774df54fc0" />

> **Note:** These are early prototype results and are being used to validate the navigation pipeline before adding the remaining components.

---

## What Makes NavSync Different?

NavSync is not based on a single new algorithm. Its focus is on how different navigation components work together.

### 1. Reliability-Driven Fusion

GNSS and other measurements are not treated as equally reliable at all times.

Their reliability directly affects how strongly they influence the navigation solution.

### 2. Continuous Trust Adaptation

The system is designed to handle the transition from healthy GNSS to degraded GNSS and finally to an outage without relying on a simple binary switch.

### 3. Closed-Loop Integrity

Consistency information can directly affect sensor trust.

A suspicious GNSS measurement is not only detected; its influence on the navigation solution can also be reduced.

### 4. Physics-Grounded Learning

Machine learning provides learned motion information while the physics-based estimator remains responsible for maintaining the navigation state.

### 5. Verified GNSS Recovery

When GNSS returns, the system does not blindly jump to the recovered position.

The recovered measurements are checked against the maintained trajectory before their influence is restored.

---

## Vehicle and Road Constraints

Inertial navigation naturally accumulates error.

NavSync can use additional information about vehicle motion and road structure to keep the estimated trajectory physically plausible.

Potential constraints include:

- Non-holonomic constraints
- Zero-velocity updates
- Vehicle motion characteristics
- Trajectory history
- Road geometry
- Probabilistic map matching

These constraints are treated as additional information rather than absolute truth.

---

## Uncertainty

NavSync is designed to estimate both the navigation state and its uncertainty.

The system can provide:

- Position
- Velocity
- Attitude
- Position uncertainty
- Measurement consistency
- Navigation diagnostics

During a GNSS outage, uncertainty should increase as absolute positioning information becomes unavailable.

The system therefore aims to communicate not only:

> **Where is the vehicle?**

but also:

> **How certain is the estimate?**

---

## Mobile Application

NavSync is being developed around a smartphone-first deployment model.

The mobile application will collect the sensor information required by the navigation engine and provide the runtime interface for navigation and diagnostics.

### Mobile App Screenshots

[IMAGE PLACEHOLDER — Mobile app home / recording screen]

[IMAGE PLACEHOLDER — Mobile app navigation screen]

[IMAGE PLACEHOLDER — Mobile app sensor / diagnostics screen]

---

## Smartphone-First

The initial platform is a standard smartphone.

The system uses sensors already available on the device:

- GNSS
- Accelerometer
- Gyroscope
- Magnetometer where required
- Device orientation sensors where useful

No dedicated navigation hardware is required for the core system.

The same navigation engine can later be adapted to other edge or vehicle computing platforms.

---

## Current Development

The project is being developed incrementally.

Current work focuses on establishing a reliable physics-based navigation baseline before adding more complex components.

### Current focus

- Smartphone sensor data
- Sensor timing and preprocessing
- Phone-to-vehicle frame alignment
- IMU propagation
- ESKF-based navigation
- GNSS updates
- Uncertainty estimation
- GNSS degradation experiments
- Controlled replay and evaluation

### Planned components

- Learned vehicle-motion model
- GNSS quality and anomaly model
- Adaptive measurement reliability
- Vehicle constraints
- Probabilistic road / trajectory consistency
- Verified GNSS recovery
- On-device inference
- Android runtime integration

---

## Development Philosophy

NavSync is being developed around a simple principle:

> **Build the navigation system step by step and validate each layer before adding the next one.**

The project therefore prioritizes:

- Real sensor data
- Reproducible experiments
- Measurable results
- Physics-based validation
- Controlled failure scenarios
- Explicit uncertainty
- Clear separation between implemented and planned components

The goal is to build a navigation system that can be tested and understood, rather than relying on an opaque end-to-end model.

---

## Project Structure

```text
NavSync/
├── data/
│   ├── raw/
│   └── processed/
├── scripts/
├── src/
├── experiments/
├── results/
├── docs/
└── README.md
```

The structure will evolve as the navigation engine and Android runtime are developed.

---

## Technology Stack

| Area | Technologies |
|---|---|
| Languages | Python, C++, Kotlin |
| Navigation | ESKF / InEKF, INS |
| Machine Learning | PyTorch |
| Numerical Computing | NumPy, SciPy |
| Mobile | Android SDK, Sensor APIs, GNSS APIs |
| Mapping | OpenStreetMap, geospatial tools |
| Backend / Services | FastAPI where required |
| Deployment | On-device / Edge Computing |

---

## Long-Term Direction

NavSync is designed as a software-first navigation engine that can run on existing computing hardware.

The development path is:

```text
Smartphone
    ↓
Fleet / Edge Device
    ↓
Vehicle Computer
    ↓
Broader GNSS-Resilient Platforms
```

The long-term goal is to provide navigation resilience without requiring a completely new dedicated navigation hardware stack.

---

## Status

NavSync is currently under active development.

The physics-based navigation baseline is being validated first. More advanced components such as learned motion estimation, adaptive GNSS reliability, road consistency, and verified recovery will be integrated progressively after their individual assumptions are validated.

[DIAGRAM PLACEHOLDER — Final system overview]

[IMAGE PLACEHOLDER — NavSync demonstration / trajectory comparison]
