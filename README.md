# NavSync

## Reliable Navigation Beyond GNSS

NavSync is a smartphone-first navigation system designed to maintain reliable vehicle navigation when GNSS becomes degraded, unreliable, or unavailable.

Instead of treating navigation as simply **GNSS available / GNSS unavailable**, NavSync combines GNSS, inertial sensing, physics-based estimation, learned motion information, vehicle constraints, GNSS integrity assessment, and road context according to the reliability of the available information.

> **Navigation should adapt to the reliability of information, not simply its availability.**

---

## Why NavSync?

GNSS can become unreliable before it completely disappears.

A vehicle may experience:

- Signal blockage
- Multipath
- Urban GNSS degradation
- Temporary outages
- Sudden position jumps
- Inconsistent measurements
- Spoofing or other GNSS anomalies

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

NavSync combines physics-based navigation with learned vehicle-motion information.

### Physics-Based Estimation

The physics-based estimator maintains the navigation state using:

- GNSS
- Accelerometer
- Gyroscope
- Vehicle motion constraints
- Uncertainty propagation

The navigation state includes:

- Position
- Velocity
- Attitude
- Sensor biases
- Uncertainty

The current implementation uses an **Error-State Kalman Filter (ESKF)** as the primary estimator.

### Learned Motion Information

Machine learning is intended to learn vehicle-motion patterns from inertial data.

The learned component is designed to **support the navigation estimator**, rather than replace it with a black-box position predictor.

This allows learned information to work together with physical state estimation, constraints, and uncertainty.

---

## System Architecture

<img width="5068" height="3736" alt="properarch drawio (3)" src="https://github.com/user-attachments/assets/e6120900-fdd5-4907-b1fd-bb49423d32d8" />

The architecture is organized around several interacting layers:

### Sensor and Preprocessing Layer

Raw smartphone measurements are first prepared for navigation through:

- Sensor preprocessing
- Calibration
- Timestamp handling
- Time synchronization
- Phone-to-vehicle frame alignment

This layer establishes a consistent sensor representation before estimation.

### Parallel Information Layer

NavSync does not depend on a single information source.

The navigation system can use:

- GNSS measurements
- GNSS quality and anomaly information
- IMU-based inertial propagation
- Learned motion information
- Vehicle motion constraints
- Road and trajectory context

These sources provide complementary information to the navigation estimator.

### Navigation Estimation Layer

The physics-based estimator maintains the navigation state and its uncertainty.

GNSS provides absolute positioning information when it is sufficiently reliable, while inertial sensing provides continuous high-rate motion information.

Learned motion and vehicle constraints can provide additional corrections or constraints.

### Reliability and Consistency Layer

The system continuously evaluates whether available measurements are consistent with the maintained navigation state and physical motion.

This information can affect how strongly a measurement influences the estimator.

### Output Layer

The navigation engine produces:

- Position
- Velocity
- Attitude
- Uncertainty
- Measurement consistency
- Navigation diagnostics

This architecture allows NavSync to continue operating as GNSS conditions change instead of relying on a single hard GNSS/no-GNSS switch.

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

## GNSS Integrity and Recovery

GNSS availability does not automatically mean that a measurement is trustworthy.

NavSync is designed to evaluate GNSS using information such as:

- Estimator residuals
- Innovation consistency
- Position and velocity consistency
- Inertial trajectory consistency
- Vehicle motion constraints
- Spatial / road consistency
- GNSS quality indicators

The resulting assessment can directly influence GNSS trust within the navigation estimator.

When GNSS returns after an outage, it is not assumed to be immediately correct.

The recovered measurements are first checked against the maintained trajectory and then progressively reintroduced when they are consistent.

```text
GNSS Returns
     ↓
Consistency Check
     ↓
Recovery Credible?
     │
 ┌───┴────┐
 │        │
Yes       No
 │        │
 ↓        ↓
Gradual   Continue
Trust     Reject /
Restore   Downweight
```

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

The objective is to reduce physically or spatially implausible solutions while GNSS is degraded or unavailable.

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

## Initial Results

The current physics-based navigation baseline has been tested on self-collected smartphone sensor data.

The comparison below shows the current trajectory behavior under GNSS degradation and illustrates the effect of inertial drift and the current hybrid navigation approach.

<img width="1600" height="933" alt="trajectory comparison" src="https://github.com/user-attachments/assets/0bff51d0-249e-40ea-9c59-13774df54fc0" />

> **Note:** These are early prototype results and are being used to validate the navigation pipeline before the remaining components are integrated.

---

## What Makes NavSync Different?

NavSync is not based on a single new algorithm. Its focus is on how the different navigation components work together.

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

## Mobile Application

NavSync is being developed around a smartphone-first deployment model.

The mobile application will collect the sensor information required by the navigation engine and provide the runtime interface for navigation and diagnostics.

### Planned capabilities

- GNSS logging
- Accelerometer and gyroscope recording
- Sensor timestamp synchronization
- Device mounting configuration
- Navigation state visualization
- Navigation diagnostics

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

---


