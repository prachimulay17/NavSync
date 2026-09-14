# RealAzimuth ML Model Analysis Report

## Executive Summary

**Repository Location:** `~/AndroidStudioProjects/NavSync/NavSync/RealAzimuth/`

**Status:** ✅ **COMPLETE ANDROID ML INFERENCE IMPLEMENTATION FOUND**

The RealAzimuth repository contains a **production-ready Android application** that implements:
1. **RoNIN LSTM model** for pedestrian velocity estimation
2. **ONNX Runtime inference** on Android
3. **GNSS fusion** with confidence gating
4. **HMM-based map matching** with OpenStreetMap
5. **Complete sensor pipeline** (IMU → HACF frame → Model → Velocity → Position)

**Key Finding:** This is NOT a training repository — it's a **deployment app** with a pre-trained model ready for Android integration.

---

## 1. ML Model Architecture

### Model File
- **Location:** `app/src/main/assets/ronin_lstm.onnx`
- **Framework:** ONNX (exported from PyTorch)
- **Original:** RoNIN (Robust Neural Inertial Navigation) bilinear LSTM
- **Implementation Class:** `com.example.azimuth.model.RoninOnnx.kt`

### Architecture Details
```
Model: RoNIN Bilinear LSTM (re-exported with output slicing)

Input Tensor:
  Name: "imu"
  Shape: [1, T, 6]  where T ≥ 400
  Format: [batch=1, time_steps, features=6]
  Features: [gyro_x, gyro_y, gyro_z, accel_x, accel_y, accel_z]
  
Output Tensor:
  Name: "vel" (inferred)
  Shape: [1, 2]
  Format: [batch=1, [vx, vy]]
  Values: (vx, vy) in m/s at the LAST timestep only
```

**Note:** The model was re-exported with `[:, -1, :]` slicing baked in, so it only returns velocity at the newest sample, not the full [1, T, 2] sequence. This saves memory on Android.

**Source Reference:**
```kotlin
// File: RoninOnnx.kt, lines 9-12
/**
 * RoNIN bilinear LSTM exported to ONNX.
 *
 * Input  `imu`: [1, T, 6]  gyro_xyz + accel_xyz in HACF. T ≥ WINDOW (400 samples / 2 s).
 * Output `vel`: [1, 2]     (vx, vy) m/s at the LAST input timestep only.
 */
```

---

## 2. Trained Model/Checkpoint

### Model File
- **Path:** `app/src/main/assets/ronin_lstm.onnx`
- **Size:** ❓ (file exists, size not checked)
- **Format:** ONNX
- **Runtime:** ONNX Runtime Android (`ai.onnxruntime:onnxruntime-android`)

### Gradle Dependency
```kotlin
// File: app/build.gradle.kts, line 52
implementation(libs.onnxruntime.android)
```

### Loading Code
```kotlin
// File: RoninOnnx.kt, lines 17-21
init {
    val bytes = context.assets.open(ASSET).use { it.readBytes() }
    session = env.createSession(bytes)
}
```

**Framework:** ONNX Runtime (not TensorFlow Lite, not PyTorch Mobile)

---

## 3. Input Format/Features

### Input Requirements

#### Raw Input: IMU Sensors
- **Accelerometer:** 3-axis (device frame)
- **Gyroscope:** 3-axis (device frame)
- **Game Rotation Vector:** Quaternion (for HACF transformation)

#### Preprocessing: HACF Frame Transformation
**CRITICAL:** Input MUST be in HACF (Horizontal Accelerometer Coordinate Frame), NOT device frame.

**Transformation:**
```kotlin
// File: ImuCollector.kt, lines 121-123
val g = Hacf.rotateWxyz(quat, lastGyro[0], lastGyro[1], lastGyro[2])
val a = Hacf.rotateWxyz(quat, lastAccel[0], lastAccel[1], lastAccel[2])
onSample(event.timestamp, g, a)
```

**HACF Definition (File: Hacf.kt):**
- Rotate device-frame vectors by Game Rotation Vector quaternion
- Quaternion format: [w, x, y, z] (Android convention)
- Output: Gravity-aligned horizontal frame
  - X-axis: Forward (device heading direction)
  - Y-axis: Right
  - Z-axis: Down (gravity-aligned)

#### Feature Order
```kotlin
// File: FeatureBuffer.kt, lines 18-23
data[i]     = gx  // Gyro X (HACF)
data[i + 1] = gy  // Gyro Y (HACF)
data[i + 2] = gz  // Gyro Z (HACF)
data[i + 3] = ax  // Accel X (HACF)
data[i + 4] = ay  // Accel Y (HACF)
data[i + 5] = az  // Accel Z (HACF)
```

**Units:**
- Gyroscope: **rad/s** (Android default)
- Accelerometer: **m/s²** (Android default)

**Coordinate Frame:** **HACF** (Horizontal Accelerometer Coordinate Frame)
- NOT ENU (East-North-Up)
- NOT NED (North-East-Down)
- NOT device frame
- Frame is gravity-aligned but rotates with device yaw

#### Window/Sequence Size
- **Window:** 400 samples
- **Channels:** 6 (gyro_xyz + accel_xyz)
- **Total:** 400 × 6 = 2400 float values

**Source:**
```kotlin
// File: RoninOnnx.kt, lines 46-47
const val WINDOW = 400
const val CHANNELS = 6
```

#### Sampling Rate
- **Target:** 200 Hz (nominal)
- **Actual:** SENSOR_DELAY_FASTEST (varies by device)
  - Flagship devices: ~200 Hz
  - Budget devices (virtual gyro): ~50-100 Hz

**Window Duration:**
- At 200 Hz: 400 samples = **2.0 seconds**
- At 100 Hz: 400 samples = **4.0 seconds**

**Source:**
```kotlin
// File: ImuCollector.kt, line 55
sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST, h)

// File: WalkSession.kt, lines 447-448, comment
INFER_STRIDE.toFloat() / 200f   // nominal 200 Hz
```

#### Buffering Strategy
```kotlin
// File: FeatureBuffer.kt, lines 28-40
fun copyWindow(): FloatArray? {
    if (count < window) return null
    val out = FloatArray(window * channels)
    val oldest = write
    for (t in 0 until window) {
        val src = ((oldest + t) % window) * channels
        System.arraycopy(data, src, out, t * channels, channels)
    }
    return out
}
```
**Chronological order:** Oldest sample first, newest sample last (row-major).

---

## 4. Output Format

### Output
- **Shape:** [1, 2]
- **Values:** `(vx, vy)` in **m/s**
- **Frame:** **HACF** (same as input frame)
  - vx: Forward velocity (device heading direction)
  - vy: Right velocity (perpendicular to heading)

**NOT:**
- ❌ ENU velocity
- ❌ NED velocity
- ❌ Displacement (Δx, Δy)
- ❌ Position (lat, lon)
- ❌ Trajectory

**Source:**
```kotlin
// File: RoninOnnx.kt, lines 26-28
/**
 * Run inference on [window] (size = WINDOW × CHANNELS, row-major [time, channel]).
 * Returns (vx, vy) in m/s — the velocity at the newest sample in the window.
 */
fun predictVelocity(window: FloatArray): Pair<Float, Float>
```

### Inference Frequency
- **Stride:** Every 50 IMU samples
- **At 200 Hz:** 50 samples = 0.25 seconds → **4 Hz inference rate**
- **At 100 Hz:** 50 samples = 0.5 seconds → **2 Hz inference rate**

**Source:**
```kotlin
// File: WalkSession.kt, lines 359-361, 732
// Trigger inference every INFER_STRIDE samples
sinceInfer++
if (!buffer.isFull || sinceInfer < INFER_STRIDE) return
...
const val INFER_STRIDE = 50
```

---

## 5. Output Type Classification

### What the Model Outputs

✅ **VELOCITY** (instantaneous, 2D horizontal)
- vx, vy in m/s
- In HACF frame (gravity-aligned, device-heading-relative)
- At the NEWEST sample timestamp only

❌ **NOT Displacement** (would be Δx, Δy in meters)
❌ **NOT Position** (would be lat, lon or x, y in ENU)
❌ **NOT Heading** (requires velocity integration direction)
❌ **NOT Trajectory** (requires position history)

### How Velocity Becomes Position

The velocity is integrated by `GnssFuser.kt`:

```kotlin
// File: GnssFuser.kt, lines 113-119
if (yawInitialised) {
    val cosY = cos(yawRad)
    val sinY = sin(yawRad)
    val ve = cosY * vxHacf - sinY * vyHacf  // HACF → ENU rotation
    val vn = sinY * vxHacf + cosY * vyHacf
    eastM += ve * dtSec                     // Euler integration
    northM += vn * dtSec
```

**Integration:**
1. Rotate HACF velocity → ENU velocity (using `yawRad` from GNSS bearing)
2. Integrate: `position += velocity * dt`
3. Frame: ENU (East-North-Up) in meters from local origin

---

## 6. Map Matching Implementation

### ✅ YES - Map Matching is Fully Implemented

**Implementation:** HMM-based map matching with Viterbi decoding

**File:** `com.example.azimuth.map.HMMMapMatcher.kt`

### Architecture

```
DR Position (ESKF-like dead reckoning)
        ↓
HMM Map Matcher (parallel map state)
        ↓
Viterbi Decoding (every 3-10s)
        ↓
Snap to Road (if within 30m of DR)
        ↓
Corrected Position
```

### Key Features

1. **Parallel State Tracking:**
   - DR state: `(drEast, drNorth)` — authoritative position
   - Map state: `(mapEast, mapNorth)` — HMM-snapped position
   - Only applies correction if gap < `maxDivergenceM` (30m default)

2. **Update Strategy:**
   - **Normal:** Every 10s on straight roads
   - **Junction:** Every 3s near junctions or during turns
   - **Source:** Lines 39-40 in HMMMapMatcher.kt

3. **HMM Components:**
   - **Emission Probability:** Distance + heading alignment
     ```kotlin
     // File: HMMMapMatcher.kt, lines 243-249
     val distTerm = exp(-cand.distM * cand.distM / (2f * sigmaDistM * sigmaDistM))
     val hdgTerm  = (maxOf(cosFwd, cosRev) + 1f) / 2f
     return (1f - headingWeight) * distTerm + headingWeight * distTerm * hdgTerm
     ```
   - **Transition Probability:** Road connectivity + shortest path
   - **Viterbi Decoder:** `ViterbiDecoder.decode()` in ViterbiDecoder.kt

4. **Safety Checks:**
   - Max divergence: 30m (discards corrections if map state diverged too far)
   - Candidate radius: 40m (only considers roads within 40m)
   - Heading weight: 0.45 (balances distance vs direction)

5. **GPS Recovery:**
   - Road snap within 15m of GPS fix
   - Heading-compatible road selection
   - **Source:** Lines 170-192 in HMMMapMatcher.kt

### OpenStreetMap Integration

**File:** `com.example.azimuth.map.OsmGraph.kt`

- Downloads OSM data for bounding box
- Builds road graph (nodes + edges)
- Provides candidate edge queries
- Shortest path search for transition probabilities

**Downloader:** `OsmDownloader.kt` (Overpass API queries)

---

## 7. Confidence/Uncertainty Output

### ❌ NO - Model Does NOT Output Confidence

The ONNX model outputs only `(vx, vy)` — no uncertainty.

### Confidence Source: GNSS Fusion Logic

Confidence is computed by `GnssFuser.kt` based on:

1. **GNSS Accuracy:**
   ```kotlin
   // File: GnssFuser.kt, lines 254-261
   val accW: Double = when {
       isTrustedGnssProvider(provUp) -> when {
           accM > 15f -> 0.0
           accM <= 5f -> 1.0
           else -> ((15f - accM) / 10f).toDouble().coerceIn(0.0, 1.0)
       }
       else -> 0.0
   }
   ```

2. **Innovation Gate (jump detection):**
   ```kotlin
   // File: GnssFuser.kt, lines 269-274
   val maxInnov = maxOf(25.0, 3.0 * accM)
   val jumpW = when {
       innovM > maxInnov -> 0.0
       innovM > 2.0 * maxOf(accM.toDouble(), 5.0) -> 0.25
       else -> 1.0
   }
   ```

3. **Speed Consistency:**
   ```kotlin
   // Lines 266-267
   if (isStill && innovM > 8.0) return 0.0
   if (imuSpeed < 0.2 && speedMps.isFinite() && speedMps > 1.5) return 0.0
   ```

4. **Final Confidence:**
   ```kotlin
   return (accW * jumpW).coerceIn(0.0, 1.0)
   ```

**Confidence Range:** 0.0 - 1.0
- **0.4+:** Accept GNSS update
- **< 0.4:** Reject GNSS, rely on IMU dead reckoning

**Source:** `lastGnssConf` field in GnssFuser.kt

---

## 8. Preprocessing/Normalization

### Required Preprocessing

#### 1. Frame Transformation: Device → HACF
```kotlin
// File: ImuCollector.kt, lines 121-123
val g = Hacf.rotateWxyz(quat, lastGyro[0], lastGyro[1], lastGyro[2])
val a = Hacf.rotateWxyz(quat, lastAccel[0], lastAccel[1], lastAccel[2])
```

**Input:** Device-frame gyro/accel + Game Rotation Vector quaternion
**Output:** HACF-frame gyro/accel

**Rotation Algorithm (File: Hacf.kt, lines 5-15):**
```kotlin
fun rotate(qx: Float, qy: Float, qz: Float, qw: Float, vx: Float, vy: Float, vz: Float): FloatArray {
    val tx = 2f * (qy * vz - qz * vy)
    val ty = 2f * (qz * vx - qx * vz)
    val tz = 2f * (qx * vy - qy * vx)
    return floatArrayOf(
        vx + qw * tx + (qy * tz - qz * ty),
        vy + qw * ty + (qz * tx - qx * tz),
        vz + qw * tz + (qx * ty - qy * tx),
    )
}
```

This is a **quaternion rotation** (Hamilton product).

#### 2. Buffering: 400-sample sliding window
```kotlin
// File: FeatureBuffer.kt, lines 14-24
fun add(gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float) {
    val i = write * channels
    data[i] = gx; data[i + 1] = gy; data[i + 2] = gz
    data[i + 3] = ax; data[i + 4] = ay; data[i + 5] = az
    write = (write + 1) % window
    if (count < window) count++
}
```

**Circular buffer:** Keeps most recent 400 samples in chronological order.

#### 3. NO Normalization/Scaling

**The model expects RAW sensor values:**
- Gyroscope: rad/s (Android default)
- Accelerometer: m/s² (Android default)

**No mean subtraction, no standard deviation scaling, no clipping.**

**Evidence:**
```kotlin
// File: RoninOnnx.kt, lines 26-43
fun predictVelocity(window: FloatArray): Pair<Float, Float> {
    require(window.size == WINDOW * CHANNELS)
    val buf = ByteBuffer.allocateDirect(window.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    buf.put(window)  // Direct copy, no normalization
    buf.rewind()
    ...
}
```

### Summary

✅ **Required:** HACF frame transformation
✅ **Required:** 400-sample windowing
❌ **NOT Required:** Normalization/scaling
❌ **NOT Required:** Mean subtraction
❌ **NOT Required:** Gravity removal (model learns gravity in HACF frame)

---

## 9. Postprocessing

### Required Postprocessing

#### 1. Parse ONNX Output
```kotlin
// File: RoninOnnx.kt, lines 56-68
fun parseVel(raw: Any?): Pair<Float, Float> {
    if (raw is Array<*>) {
        val row = raw[0]
        if (row is FloatArray && row.size >= 2) return row[0] to row[1]
        ...
    }
    if (raw is FloatArray && raw.size >= 2) return raw[0] to raw[1]
    error("Unexpected ONNX output type")
}
```

**Output:** `(vx, vy)` in m/s (HACF frame)

#### 2. Speed Smoothing (ZUPT Detection)
```kotlin
// File: WalkSession.kt, lines 453-459
speedEma = alpha * currentSpeed + (1f - alpha) * speedEma
isStill = speedEma < ZUPT_THRESHOLD

companion object {
    const val ZUPT_THRESHOLD = 0.15f  // m/s — standing still
    const val ZUPT_EMA_ALPHA = 0.1f
}
```

**Purpose:** Detect zero-velocity updates (ZUPT) to avoid drift when stationary.

#### 3. Frame Rotation: HACF → ENU
```kotlin
// File: GnssFuser.kt, lines 114-118
val cosY = cos(yawRad)
val sinY = sin(yawRad)
val ve = cosY * vxHacf - sinY * vyHacf
val vn = sinY * vxHacf + cosY * vyHacf
```

**Input:** HACF velocity `(vx, vy)`
**Output:** ENU velocity `(ve, vn)`
**Rotation Angle:** `yawRad` (HACF yaw relative to ENU north)

**Yaw Bootstrapping:**
- Initial yaw: From first confident GPS bearing
- Ongoing correction: Soft nudge when GPS bearing available
- **Source:** Lines 153-163, 171-187 in GnssFuser.kt

#### 4. Position Integration (Euler)
```kotlin
// File: GnssFuser.kt, lines 119-120
eastM += ve * dtSec
northM += vn * dtSec
```

**Method:** Simple Euler integration
**Frame:** ENU (East-North-Up) in meters from local origin

#### 5. GNSS Fusion (Confidence-Weighted Snap)
```kotlin
// File: GnssFuser.kt, lines 147-149
eastM  = (1.0 - cg) * eastM  + cg * ge
northM = (1.0 - cg) * northM + cg * gn
```

**Method:** Weighted average between IMU position and GNSS position
**Weight:** `cg` = GNSS confidence (0.0 - 1.0)

#### 6. Map Matching Correction (Optional)
```kotlin
// File: GnssFuser.kt, lines 208-212
fun applyExternalCorrection(correctedEastM: Double, correctedNorthM: Double) {
    eastM  = correctedEastM
    northM = correctedNorthM
    lastSrc = SRC_MAP
}
```

**Source:** HMM map matcher (every 3-10s during GNSS blackout)

### Summary

1. **Parse:** Extract (vx, vy) from ONNX output
2. **Smooth:** Speed EMA for ZUPT detection
3. **Rotate:** HACF → ENU using GPS-bootstrapped yaw
4. **Integrate:** Position += velocity × dt
5. **Fuse:** Confidence-weighted GNSS snap
6. **Map Match:** Optional road correction during outages

---

## 10. Android Integration Path

### ✅ **ALREADY COMPLETE** - This IS the Android Integration

The RealAzimuth app is a **production-ready Android application**, not a training repository.

### Integration Components

#### Runtime: ONNX Runtime Android
```kotlin
// File: app/build.gradle.kts, line 52
implementation(libs.onnxruntime.android)
```

**Version:** Check `libs.versions.toml` (not provided, but modern ONNX Runtime)

#### Model Loading
```kotlin
// File: RoninOnnx.kt, lines 17-21
private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
init {
    val bytes = context.assets.open(ASSET).use { it.readBytes() }
    session = env.createSession(bytes)
}
```

**Asset Path:** `app/src/main/assets/ronin_lstm.onnx`

#### Inference Call
```kotlin
// File: RoninOnnx.kt, lines 31-41
OnnxTensor.createTensor(env, buf, shape).use { input ->
    session.run(mapOf(INPUT to input)).use { results ->
        return parseVel(results[0].value)
    }
}
```

**Thread:** Default dispatcher (not UI thread)
**Frequency:** ~4 Hz (every 50 IMU samples at 200 Hz)

### Performance Considerations

1. **Model Size:** ONNX asset (~few MB, exact size unknown)
2. **Inference Time:** Unknown (not benchmarked in code)
3. **Memory:** Circular buffer (400 × 6 × 4 bytes = 9.6 KB)
4. **Battery:** Sensors at FASTEST rate + inference at 4 Hz

### NO Python Dependencies

✅ **Pure Kotlin/Android:**
- ONNX Runtime: Java/C++ native library
- No TensorFlow, no PyTorch Python
- No Python interpreter required

❌ **No Python-only components**

---

## 11. Dependencies & Python Components

### Android Dependencies (File: app/build.gradle.kts)

```kotlin
implementation(libs.androidx.core.ktx)
implementation(libs.androidx.activity.compose)
implementation(libs.androidx.compose.material3)
implementation(libs.androidx.lifecycle.runtime.ktx)
implementation(libs.onnxruntime.android)          // ← ML INFERENCE
implementation(libs.kotlinx.coroutines.android)
implementation(libs.play.services.location)       // ← GPS
```

### NO Python Dependencies

✅ **All inference runs on Android:**
- ONNX Runtime: Native C++ with Java bindings
- No Python scripts
- No server calls
- No cloud inference

### External Data: OpenStreetMap

**Only dependency:** OSM road network data

**Download:** `OsmDownloader.kt` queries Overpass API
```kotlin
// Simplified from OsmDownloader.kt
fun downloadOsm(south: Double, west: Double, north: Double, east: Double): String {
    // Query: https://overpass-api.de/api/interpreter
    // Returns: OSM XML with roads in bounding box
}
```

**Processing:** Parsed locally into `OsmGraph` (nodes + edges)

**Offline Capable:** Yes, if OSM data is pre-cached

---

## 12. ESKF Integration Path

### What Can Be Supplied to ESKF Measurement Update?

#### Option A: Velocity Measurement (RECOMMENDED)

**What the Model Provides:**
- **vx, vy** in m/s (HACF frame)
- After rotation: **ve, vn** in m/s (ENU frame)

**ESKF Integration:**
```kotlin
// Hypothetical NavSync integration
val (vxHacf, vyHacf) = roninModel.predictVelocity(window)

// Rotate HACF → ENU (requires yaw from GNSS bearing or ESKF attitude)
val yaw = eskfState.yawRadians  // From ESKF attitude estimate
val ve = cos(yaw) * vxHacf - sin(yaw) * vyHacf
val vn = sin(yaw) * vxHacf + cos(yaw) * vyHacf

// Supply to ESKF as velocity measurement
eskf.updateWithVelocity(
    velocityNorth = vn,
    velocityEast = ve,
    uncertaintyMs = VELOCITY_MEASUREMENT_NOISE  // Tuning parameter
)
```

**Measurement Noise Estimation:**
- RoNIN paper reports ~0.5 m/s velocity error on test sets
- Conservative: σ_vel = **1.0 m/s** (measurement noise std dev)
- Aggressive: σ_vel = **0.5 m/s**

**Advantages:**
- Direct measurement from model
- No integration drift
- ESKF can fuse with other velocity sources

**ESKF Implementation Required:**
✅ **Already exists in NavSync:** `updateWithGNSSVelocity()` can be reused
```kotlin
// File: ESKF.kt (our project)
fun updateWithGNSSVelocity(
    velocityNorth: Double,
    velocityEast: Double, 
    velocityDown: Double,
    uncertaintyMs: Double
): ESKFResult
```

**Adaptation:**
```kotlin
eskf.updateWithGNSSVelocity(
    velocityNorth = vn.toDouble(),
    velocityEast = ve.toDouble(),
    velocityDown = 0.0,  // Model doesn't estimate vertical velocity
    uncertaintyMs = 1.0  // Tuning parameter
)
```

#### Option B: Position Measurement (After Integration)

**What RealAzimuth Computes:**
- **eastM, northM** in meters (ENU frame)
- From: `position += velocity × dt` (Euler integration)

**ESKF Integration:**
```kotlin
// After RealAzimuth fusion (velocity → position → GNSS fusion → map matching)
val (eastM, northM) = gnssFuser.fuseStep(vx, vy, dt, isStill, gnssFix)

// Convert ENU → WGS84
val (lat, lon) = enuToWgs84(eastM, northM, originLat, originLon)

// Supply to ESKF as position measurement
eskf.updateWithGNSSPosition(
    latitude = lat,
    longitude = lon,
    uncertaintyM = POSITION_MEASUREMENT_NOISE
)
```

**Measurement Noise Estimation:**
- After integration: Drift accumulates
- With GNSS fusion: Bounded by GNSS accuracy (~5-15m)
- With map matching: Further corrected (~5-10m)
- Conservative: σ_pos = **10 m**

**Advantages:**
- Includes GNSS fusion and map matching corrections
- Higher-level measurement (less frequent updates needed)

**Disadvantages:**
- Integration drift between GNSS fixes
- Duplication: RealAzimuth already does position fusion

#### Option C: Displacement Measurement (Delta Position)

**Compute:**
```kotlin
val (eastPrev, northPrev) = eskfState.getPreviousENU()
val (eastNew, northNew) = gnssFuser.fuseStep(...)
val deltaEast = eastNew - eastPrev
val deltaNorth = northNew - northPrev
```

**ESKF Integration:**
Would require custom measurement update (not currently in NavSync ESKF).

**Not Recommended:** Adds complexity without clear benefit.

---

## Recommended Integration Strategy

### **VELOCITY MEASUREMENT (Option A)**

1. **Run RoNIN inference** → get (vx, vy) in HACF
2. **Rotate HACF → ENU** using ESKF attitude estimate
3. **Supply to ESKF.updateWithGNSSVelocity()** with σ_vel = 1.0 m/s
4. **Frequency:** Every 50 IMU samples (~4 Hz at 200 Hz sampling)

### Why This Approach?

✅ **Minimal Duplication:**
- NavSync ESKF already handles:
  - Position integration
  - GNSS fusion
  - Covariance propagation
  
✅ **Complementary:**
- RoNIN: Velocity estimation from IMU
- ESKF: Sensor fusion + uncertainty quantification
- GNSS: Position anchor
- Map matching: Road constraint (optional add-on)

✅ **No Redundant Integration:**
- Don't use GnssFuser.kt from RealAzimuth
- Don't duplicate position integration
- Let ESKF handle all fusion

### What to Borrow from RealAzimuth:

1. **RoninOnnx.kt** - Model inference wrapper
2. **FeatureBuffer.kt** - 400-sample windowing
3. **Hacf.kt** - Frame transformation
4. **ImuCollector.kt** - Sensor pipeline (adapt to NavSync)
5. **(Optional) HMMMapMatcher.kt** - Map matching (separate integration)

### What to SKIP:

❌ **GnssFuser.kt** - NavSync ESKF already does this better
❌ **Position integration** - ESKF handles this
❌ **GNSS confidence logic** - ESKF has NIS gating

---

## Python Dependencies: NONE

✅ **No Python required for inference**
✅ **No training code in this repository**
✅ **No offline processing scripts**

This is a **deployment app**, not a training pipeline.

---

## Summary: Key Findings

| Aspect | Details |
|--------|---------|
| **Model** | RoNIN Bilinear LSTM (ONNX) |
| **Input** | [1, 400, 6] HACF IMU (gyro+accel, 200 Hz, 2s window) |
| **Output** | [1, 2] Velocity (vx, vy) in m/s (HACF frame) |
| **Framework** | ONNX Runtime Android (no Python) |
| **Preprocessing** | Device → HACF rotation (quaternion), 400-sample buffer |
| **Postprocessing** | HACF → ENU rotation, Euler integration, GNSS fusion |
| **Map Matching** | ✅ YES (HMM + Viterbi + OSM) |
| **Confidence** | ❌ NO (model), ✅ YES (GNSS fusion logic) |
| **Android Ready** | ✅ YES (production app, not training code) |
| **ESKF Integration** | Use `updateWithGNSSVelocity()` after HACF → ENU rotation |
| **Measurement Noise** | σ_vel ≈ 1.0 m/s (conservative estimate) |
| **Inference Rate** | ~4 Hz (every 50 samples at 200 Hz) |

---

## Next Steps for NavSync Integration

1. **Copy Model File:**
   - `ronin_lstm.onnx` → NavSync `app/src/main/assets/`

2. **Add ONNX Runtime Dependency:**
   ```kotlin
   implementation("ai.onnxruntime:onnxruntime-android:1.17.0")  // Or latest
   ```

3. **Port Inference Classes:**
   - `RoninOnnx.kt`
   - `FeatureBuffer.kt`
   - `Hacf.kt`

4. **Adapt Sensor Pipeline:**
   - Modify `NavSync/NavigationSimulator.kt` to use `ImuCollector.kt` pattern
   - Collect accelerometer + gyroscope + Game Rotation Vector

5. **Integrate with ESKF:**
   ```kotlin
   val (vxHacf, vyHacf) = roninModel.predictVelocity(buffer.copyWindow()!!)
   val yaw = eskfState.yawRadians
   val ve = cos(yaw) * vxHacf - sin(yaw) * vyHacf
   val vn = sin(yaw) * vxHacf + cos(yaw) * vyHacf
   eskf.updateWithGNSSVelocity(vn, ve, 0.0, 1.0)
   ```

6. **(Optional) Add Map Matching:**
   - Port `HMMMapMatcher.kt`, `OsmGraph.kt`, `ViterbiDecoder.kt`
   - Download OSM data for test area
   - Apply corrections to ESKF position estimates

---

**END OF ANALYSIS**
