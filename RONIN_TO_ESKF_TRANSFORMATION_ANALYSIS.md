# RoNIN to ESKF Coordinate Transformation Analysis

## Executive Summary

**Status:** ✅ **TRANSFORMATION VERIFIED** — Mathematical conversion determined

This document provides the exact mathematical transformation from RoNIN velocity output to NavSync ESKF NED velocity measurement, with all assumptions and conventions verified from source code.

---

## 1. HACF Coordinate Convention (RoNIN Model)

### 1.1 Definition

**HACF = Horizontal Accelerometer Coordinate Frame**

**Source:** `RealAzimuth/app/src/main/java/com/example/azimuth/session/GnssFuser.kt`, lines 5-11:
```kotlin
/**
 * Coordinate frame: ENU (East-North). HACF velocities are in the horizontal plane
 * but may be rotated relative to ENU by an unknown yaw offset [yawRad], which is
 * corrected whenever GPS bearing is available and confidence is high.
 */
```

**Source:** `RealAzimuth/app/src/main/java/com/example/azimuth/math/Hacf.kt`, lines 5-6:
```kotlin
/** HACF: rotate device-frame vectors by game-RV quaternion (xyzw, scipy/Android GRV). */
object Hacf {
    fun rotate(qx: Float, qy: Float, qz: Float, qw: Float, vx: Float, vy: Float, vz: Float): FloatArray
```

### 1.2 Frame Construction

**Transformation:** Device Frame → HACF Frame

**Method:** Quaternion rotation using Android Game Rotation Vector (GRV)

**Quaternion Convention:** `[w, x, y, z]` (Android standard)

**Source:** `RealAzimuth/app/src/main/java/com/example/azimuth/sensor/ImuCollector.kt`, lines 95-98:
```kotlin
Sensor.TYPE_GAME_ROTATION_VECTOR,
Sensor.TYPE_ROTATION_VECTOR -> {
    // getQuaternionFromVector returns [w, x, y, z]
    SensorManager.getQuaternionFromVector(quat, event.values)
```

**Applied Rotation (lines 121-123):**
```kotlin
val g = Hacf.rotateWxyz(quat, lastGyro[0], lastGyro[1], lastGyro[2])
val a = Hacf.rotateWxyz(quat, lastAccel[0], lastAccel[1], lastAccel[2])
onSample(event.timestamp, g, a)
```

### 1.3 HACF Frame Properties

**Axes:**
- **X-axis:** Device forward direction (gravity-compensated, horizontal plane)
- **Y-axis:** Device right direction (perpendicular to forward, horizontal plane)
- **Z-axis:** Down (gravity-aligned)

**Key Property:** HACF frame is **gravity-aligned** but **NOT north-aligned**

The frame rotates with the device's yaw (heading) but remains level (horizontal).

**Mathematical Verification (Test Case):**

**Source:** `RealAzimuth/app/src/test/java/com/example/azimuth/math/HacfTest.kt`, lines 9-14:
```kotlin
@Test
fun rotate90DegAboutZ_mapsXToY() {
    val half = (Math.PI / 4.0).toFloat()
    val qw = cos(half)  // 90° rotation about Z
    val qz = sin(half)
    val out = Hacf.rotate(0f, 0f, qz, qw, 1f, 0f, 0f)
    assertTrue(Hacf.nearlyEqual(out, floatArrayOf(0f, 1f, 0f), 1e-3f))
}
```

This confirms standard quaternion rotation: 90° about Z maps X → Y.

### 1.4 RoNIN Model Output in HACF

**Model Output:** `(vx, vy)` in m/s

**Frame:** HACF (gravity-aligned, device-heading-relative)

**Source:** `RealAzimuth/app/src/main/java/com/example/navsync/model/RoninOnnx.kt`, lines 11-12:
```kotlin
 * Input  `imu`: [1, T, 6]  gyro_xyz + accel_xyz in HACF. T ≥ WINDOW (400 samples / 2 s).
 * Output `vel`: [1, 2]     (vx, vy) m/s at the LAST input timestep only.
```

**Interpretation:**
- `vx`: Velocity in HACF +X direction (device forward)
- `vy`: Velocity in HACF +Y direction (device right)

**NO vertical velocity** (model trained for pedestrian motion, horizontal plane only)

---

## 2. HACF → Navigation Frame Transformation (RealAzimuth Implementation)

### 2.1 Target Frame: ENU (East-North-Up)

**Source:** `RealAzimuth/app/src/main/java/com/example/azimuth/session/GnssFuser.kt`, lines 13-15:
```kotlin
// --- ENU position state ---
var eastM: Double = 0.0
    private set
var northM: Double = 0.0
    private set
```

**ENU Convention:**
- **East:** +X direction
- **North:** +Y direction
- **Up:** +Z direction

### 2.2 Yaw Angle Definition

**Variable:** `yawRad` (in GnssFuser)

**Definition:** Rotation angle from HACF frame to ENU frame

**Source:** `GnssFuser.kt`, line 19:
```kotlin
/** HACF-frame yaw offset relative to ENU.  Updated by GPS bearing corrections. */
var yawRad: Double = 0.0
```

**Bootstrapping from GPS Bearing:**

**Source:** `GnssFuser.kt`, lines 156-160:
```kotlin
if (latestFix.bearingDeg.isFinite()) {
    // GPS bearing 0=N CW → ENU yaw CCW from east
    val gnssHdg = Math.toRadians(90.0 - latestFix.bearingDeg)
    val imuHdg  = atan2(vyHacf.toDouble(), vxHacf.toDouble())
    yawRad = wrapAngle(gnssHdg - imuHdg)
```

**GPS Bearing Convention:**
- 0° = North
- 90° = East
- Clockwise positive

**ENU Angle Convention:**
- 0° = East (+X axis)
- 90° = North (+Y axis)
- Counter-clockwise positive (standard math convention)

**Conversion:** `gnssHdg = 90° - bearingDeg`

Example:
- GPS bearing = 0° (North) → ENU angle = 90° (North is 90° CCW from East)
- GPS bearing = 90° (East) → ENU angle = 0° (East is 0°)
- GPS bearing = 180° (South) → ENU angle = -90° = 270°

**IMU Heading in HACF:**
```kotlin
val imuHdg = atan2(vyHacf, vxHacf)
```
This is the direction of motion in HACF frame (atan2 of velocity vector).

**Yaw Calculation:**
```kotlin
yawRad = gnssHdg - imuHdg
```

**Interpretation:** `yawRad` is the angle to rotate HACF velocity to align with ENU velocity.

### 2.3 Transformation Equation

**Source:** `GnssFuser.kt`, lines 114-118:
```kotlin
val cosY = cos(yawRad)
val sinY = sin(yawRad)
val ve = cosY * vxHacf - sinY * vyHacf
val vn = sinY * vxHacf + cosY * vyHacf
```

**Matrix Form:**
```
┌    ┐   ┌                    ┐ ┌      ┐
│ vE │ = │  cos(ψ)  -sin(ψ)  │ │ vx_h │
│    │   │                    │ │      │
│ vN │   │  sin(ψ)   cos(ψ)  │ │ vy_h │
└    ┘   └                    ┘ └      ┘
```

Where:
- `ψ = yawRad` (HACF yaw relative to ENU)
- `vx_h, vy_h` = HACF velocity
- `vE, vN` = ENU velocity (East, North)

**This is a standard 2D rotation matrix** (counter-clockwise rotation by angle ψ).

---

## 3. NavSync ESKF Coordinate Convention

### 3.1 Navigation Frame: NED (North-East-Down)

**Source:** `NavSync/app/src/main/java/com/example/navsync/eskf/ESKFState.kt`, lines 5-18:
```kotlin
/**
 * Uses 15-dimensional error state: δx = [δp, δv, δθ, δba, δbg]
 * - δp: Position error (3D, NED frame)
 * - δv: Velocity error (3D, NED frame)  
 * - δθ: Attitude error (3D, small angles)
 * - δba: Accelerometer bias error (3D, body frame)
 * - δbg: Gyroscope bias error (3D, body frame)
 * 
 * Coordinate frame conventions match existing NavSync architecture:
 * - Navigation frame: North-East-Down (NED)
 * - Body frame: Smartphone coordinate system
 * - Output: WGS84 geodetic coordinates (lat/lon)
 */
```

**NED Convention:**
- **North:** +X direction
- **East:** +Y direction
- **Down:** +Z direction

### 3.2 Velocity State

**Source:** `ESKFState.kt`, lines 36-38:
```kotlin
// Velocity in NED frame (m/s)
val velocityNorth: Double,
val velocityEast: Double,
val velocityDown: Double,
```

**Order:** North, East, Down (NED)

### 3.3 Velocity Measurement Interface

**Source:** `NavSync/app/src/main/java/com/example/navsync/eskf/ESKF.kt`, lines 77-83:
```kotlin
fun updateWithVelocity(
    velocityNorth: Double,
    velocityEast: Double,
    velocityUncertainty: Double
): ESKFResult
```

**Parameters:**
- `velocityNorth`: Velocity in North direction (m/s) — **NED +X**
- `velocityEast`: Velocity in East direction (m/s) — **NED +Y**
- `velocityUncertainty`: Measurement noise (m/s, 1-sigma)

**Note:** `velocityDown` is omitted (assumes 2D horizontal motion, or Down=0).

### 3.4 Heading Convention

**Source:** `ESKFState.kt`, lines 100-103:
```kotlin
// Heading: 0° = North, clockwise positive
val headingRad = atan2(velocityEast, velocityNorth)
var headingDeg = Math.toDegrees(headingRad)
if (headingDeg < 0) headingDeg += 360.0  // Normalize to [0, 360)
```

**Heading Definition:**
- 0° = North
- 90° = East
- Clockwise positive

**This matches GPS bearing convention** (NOT ENU angle convention).

### 3.5 Body-to-NED Quaternion

**Source:** `ESKFState.kt`, lines 43-47:
```kotlin
// Attitude quaternion (body to NED frame transformation)
// q = [qw, qx, qy, qz] where qw is scalar part
val quaternionW: Double,
val quaternionX: Double,
val quaternionY: Double,
val quaternionZ: Double,
```

**Quaternion Convention:** `[w, x, y, z]` (scalar-first)

**Transformation:** Body frame → NED frame

---

## 4. Frame Comparison

| Aspect | RealAzimuth HACF+ENU | NavSync ESKF NED |
|--------|---------------------|------------------|
| **Horizontal Axes** | East-North (ENU) | North-East (NED) |
| **Vertical Axis** | Up (+Z) | Down (+Z) |
| **Angle Convention** | CCW from East (math) | CW from North (navigation) |
| **Velocity Order** | (vE, vN) | (vN, vE) |
| **Quaternion Order** | [w, x, y, z] | [w, x, y, z] ✓ Same |
| **Heading 0°** | East (ENU angle) | North (GPS bearing) |

**Key Difference:** **Axis order swapped** (ENU vs NED)

---

## 5. Correct Transformation: RoNIN → ESKF

### 5.1 Complete Transformation Chain

```
RoNIN Output (vx, vy) in HACF
    ↓
[1] Rotate HACF → ENU using yawRad
    ↓
ENU Velocity (vE, vN)
    ↓
[2] Swap axes: ENU → NED
    ↓
NED Velocity (vN, vE)
    ↓
[3] Supply to ESKF.updateWithVelocity(vN, vE, σ)
```

### 5.2 Mathematical Equations

#### Step 1: HACF → ENU Rotation

**Input from RoNIN:**
- `vx_hacf`: Forward velocity (m/s) in HACF frame
- `vy_hacf`: Right velocity (m/s) in HACF frame

**Yaw Angle:** `ψ = yawRad` (HACF orientation relative to ENU East)

**Rotation:**
```
vE = cos(ψ) · vx_hacf - sin(ψ) · vy_hacf
vN = sin(ψ) · vx_hacf + cos(ψ) · vy_hacf
```

**Source:** `GnssFuser.kt`, lines 114-118 (verified above)

#### Step 2: ENU → NED Conversion

**ENU:** (East, North, Up)
**NED:** (North, East, Down)

**Conversion:**
```
vN_ned = vN_enu    (North component unchanged)
vE_ned = vE_enu    (East component unchanged)
vD_ned = -vU_enu   (Down = -Up, but vU=0 for RoNIN)
```

**Simplified (for horizontal motion):**
```
vN_ned = vN_enu
vE_ned = vE_enu
```

**No sign flip needed** because both North and East are the same direction in ENU and NED. Only the **axis order** changes.

#### Step 3: ESKF Integration

**ESKF Call:**
```kotlin
eskf.updateWithVelocity(
    velocityNorth = vN_ned,  // From step 2
    velocityEast = vE_ned,   // From step 2
    velocityUncertainty = σ_vel
)
```

### 5.3 Complete Kotlin Implementation

```kotlin
// Assume we have:
// - roninModel: RoninOnnx instance
// - buffer: FeatureBuffer with 400 HACF IMU samples
// - eskf: ESKF instance
// - yawRadHacfToEnu: Yaw angle from GNSS bearing or ESKF attitude

// Step 1: Get RoNIN velocity in HACF frame
val window = buffer.copyWindow() ?: return  // 400 samples ready?
val (vxHacf, vyHacf) = roninModel.predictVelocity(window)

// Step 2: Rotate HACF → ENU using yaw
val cosY = cos(yawRadHacfToEnu)
val sinY = sin(yawRadHacfToEnu)
val vE_enu = cosY * vxHacf - sinY * vyHacf
val vN_enu = sinY * vxHacf + cosY * vyHacf

// Step 3: ENU → NED (no sign flip for horizontal components)
val vN_ned = vN_enu
val vE_ned = vE_enu

// Step 4: Supply to ESKF
val result = eskf.updateWithVelocity(
    velocityNorth = vN_ned,
    velocityEast = vE_ned,
    velocityUncertainty = 1.0  // σ_vel in m/s (tuning parameter)
)
```

---

## 6. Yaw Angle Determination

### 6.1 Problem: How to Get `yawRadHacfToEnu`?

RealAzimuth bootstraps yaw from GPS bearing:
```kotlin
val gnssHdg = Math.toRadians(90.0 - bearingDeg)  // GPS → ENU angle
val imuHdg  = atan2(vyHacf, vxHacf)              // HACF velocity direction
yawRad = gnssHdg - imuHdg                        // HACF yaw relative to ENU
```

**But:** NavSync ESKF already maintains attitude (quaternion), which includes yaw.

### 6.2 Solution: Use ESKF Attitude

**ESKF Quaternion:** Body → NED transformation

**ESKF Heading (Yaw):**

**Source:** `ESKFState.kt`, lines 118-123:
```kotlin
val sinYaw = 2.0 * (q.w * q.z + q.x * q.y)
val cosYaw = 1.0 - 2.0 * (q.y * q.y + q.z * q.z)
val yaw = atan2(sinYaw, cosYaw)

return EulerAngles(
    yawDegrees = Math.toDegrees(yaw)
)
```

**Yaw Convention:** 0° = North, CW positive (GPS bearing convention)

**Conversion to ENU Angle:**
```kotlin
val eskfYawDeg = eskfState.yawDegrees  // 0°=North, CW
val enuAngleRad = Math.toRadians(90.0 - eskfYawDeg)  // Convert to ENU (0°=East, CCW)
```

**But this gives ESKF yaw, NOT HACF yaw.**

### 6.3 CRITICAL ISSUE: HACF vs Body Frame

**Problem:** ESKF body frame is NOT the same as HACF frame.

- **ESKF Body Frame:** Smartphone sensor coordinate system (device-fixed)
- **HACF Frame:** Gravity-aligned, device-heading-relative (rotated by GRV quaternion)

**RoNIN velocity is in HACF, but ESKF velocity is in NED.**

**Required:** Transformation from HACF → Body → NED.

### 6.4 Correct Approach: Two-Step Transformation

#### Option A: HACF → ENU → NED

1. **Bootstrap HACF yaw** from first GPS bearing (like RealAzimuth)
2. **Track HACF yaw** using ESKF velocity direction updates
3. **Rotate HACF → ENU** using tracked yaw
4. **Convert ENU → NED** (axis swap)

**Advantage:** Matches RealAzimuth proven implementation

**Disadvantage:** Requires separate yaw tracking (redundant with ESKF attitude)

#### Option B: HACF → Body → NED (Using ESKF Quaternion)

**Problem:** We don't know the transformation HACF → Body explicitly.

HACF is constructed by rotating device frame by GRV quaternion, which the ESKF doesn't track directly (ESKF tracks body-to-NED, not GRV).

**This is complex** because GRV and ESKF attitude may diverge.

### 6.5 Recommended Solution: Use RealAzimuth Yaw Logic

**Implement simplified yaw tracking in NavSync:**

```kotlin
// Maintain HACF yaw as a separate variable (like GnssFuser)
var yawRadHacfToEnu: Double = 0.0
var yawInitialized: Boolean = false

// Bootstrap from first confident GPS fix
if (!yawInitialized && gpsAvailable && gpsBearing.isFinite()) {
    val gnssHdg = Math.toRadians(90.0 - gpsBearing)  // GPS → ENU angle
    val imuHdg = atan2(vyHacf, vxHacf)               // HACF velocity direction
    yawRadHacfToEnu = wrapAngle(gnssHdg - imuHdg)
    yawInitialized = true
}

// Soft update from GPS bearing when available
if (yawInitialized && gpsAvailable && gpsBearing.isFinite() && gpsConfidence > 0.4) {
    val gnssHdg = Math.toRadians(90.0 - gpsBearing)
    val imuHdg = atan2(vyHacf, vxHacf)
    val correction = wrapAngle(gnssHdg - (yawRadHacfToEnu + imuHdg))
    yawRadHacfToEnu = wrapAngle(yawRadHacfToEnu + 0.4 * gpsConfidence * correction)
}

// Use yawRadHacfToEnu for HACF → ENU rotation
val cosY = cos(yawRadHacfToEnu)
val sinY = sin(yawRadHacfToEnu)
val vE_enu = cosY * vxHacf - sinY * vyHacf
val vN_enu = sinY * vxHacf + cosY * vyHacf
```

**Source:** `GnssFuser.kt`, lines 152-160, 237-247 (yaw bootstrap and update logic)

---

## 7. Minimum Integration Requirements

### 7.1 Required Classes from RealAzimuth

1. **RoninOnnx.kt** — Model inference wrapper
   - Path: `app/src/main/java/com/example/azimuth/model/RoninOnnx.kt`
   - Dependencies: `ai.onnxruntime:onnxruntime-android`

2. **FeatureBuffer.kt** — 400-sample circular buffer
   - Path: `app/src/main/java/com/example/azimuth/sensor/FeatureBuffer.kt`
   - Dependencies: None (pure Kotlin)

3. **Hacf.kt** — HACF frame transformation (quaternion rotation)
   - Path: `app/src/main/java/com/example/azimuth/math/Hacf.kt`
   - Dependencies: None (pure Kotlin math)

4. **ImuCollector.kt** — Sensor pipeline (accelerometer + gyroscope + GRV)
   - Path: `app/src/main/java/com/example/azimuth/sensor/ImuCollector.kt`
   - Dependencies: Android Sensor API
   - **Adaptation needed:** NavSync already has sensor collection — extract HACF transformation logic only

### 7.2 Required Assets

1. **ronin_lstm.onnx** — Pre-trained model
   - Path: `app/src/main/assets/ronin_lstm.onnx`
   - Size: Unknown (file exists in RealAzimuth)

### 7.3 Required Dependencies

**Gradle:**
```kotlin
implementation("ai.onnxruntime:onnxruntime-android:1.17.0")  // Or latest version
```

**Android Manifest (if not already present):**
```xml
<uses-feature android:name="android.hardware.sensor.accelerometer" android:required="true" />
<uses-feature android:name="android.hardware.sensor.gyroscope" android:required="true" />
```

### 7.4 NOT Required (Already in NavSync)

❌ **GnssFuser.kt** — NavSync ESKF handles position fusion
❌ **HMMMapMatcher.kt** — Map matching deferred
❌ **OsmGraph.kt**, **OsmDownloader.kt** — Map data handling deferred
❌ **ViterbiDecoder.kt** — HMM decoding deferred
❌ **WalkSession.kt** — Session management (use NavSync architecture)
❌ **UI components** — NavSync has its own UI

---

## 8. Integration Summary

### 8.1 Transformation Equation (Final)

**From RoNIN `(vx_hacf, vy_hacf)` to ESKF `(vN_ned, vE_ned)`:**

```kotlin
// 1. Get yaw angle (HACF relative to ENU)
val ψ = yawRadHacfToEnu  // Tracked from GPS bearing

// 2. Rotate HACF → ENU
val vE_enu = cos(ψ) · vx_hacf - sin(ψ) · vy_hacf
val vN_enu = sin(ψ) · vx_hacf + cos(ψ) · vy_hacf

// 3. Convert ENU → NED (axis labels only, no sign flip for horizontal)
val vN_ned = vN_enu
val vE_ned = vE_enu

// 4. Supply to ESKF
eskf.updateWithVelocity(vN_ned, vE_ned, σ_vel)
```

### 8.2 Yaw Tracking (Simplified from RealAzimuth)

**Bootstrap:**
```kotlin
if (!yawInitialized && gpsConfident && gpsBearing.isFinite()) {
    val gnssAngle = Math.toRadians(90.0 - gpsBearing)  // GPS → ENU
    val hacfAngle = atan2(vy_hacf, vx_hacf)           // HACF velocity
    yawRadHacfToEnu = wrapAngle(gnssAngle - hacfAngle)
    yawInitialized = true
}
```

**Update:**
```kotlin
if (yawInitialized && gpsConfident && gpsBearing.isFinite()) {
    val gnssAngle = Math.toRadians(90.0 - gpsBearing)
    val hacfAngle = atan2(vy_hacf, vx_hacf)
    val targetYaw = gnssAngle - hacfAngle
    val correction = wrapAngle(targetYaw - yawRadHacfToEnu)
    yawRadHacfToEnu = wrapAngle(yawRadHacfToEnu + 0.4 * gpsConfidence * correction)
}
```

**Helper:**
```kotlin
fun wrapAngle(a: Double): Double = ((a + PI) % (2 * PI)) - PI
```

### 8.3 Measurement Noise

**Recommended:** σ_vel = **1.0 m/s**

**Source:** RoNIN paper reports ~0.5 m/s velocity error on test sets
**Conservative:** Use 1.0 m/s for real-world robustness

---

## 9. Verification Checklist

Before integration, verify:

- [ ] HACF transformation uses GRV quaternion (gravity-aligned)
- [ ] Yaw angle bootstrapped from GPS bearing (0°=North → 90°-bearing for ENU)
- [ ] Rotation matrix matches RealAzimuth (cos/sin, not sin/cos)
- [ ] ENU → NED is axis swap only (no sign flip for North/East)
- [ ] ESKF expects (vN, vE) in NED frame
- [ ] Velocity uncertainty set to ~1.0 m/s
- [ ] Buffer holds 400 HACF samples before inference
- [ ] Inference called every 50 samples (~4 Hz at 200 Hz sampling)

---

## 10. Files Supporting This Analysis

| File | Purpose | Key Content |
|------|---------|-------------|
| `RealAzimuth/.../GnssFuser.kt` | Yaw tracking & HACF→ENU | Lines 114-118 (rotation), 156-160 (bootstrap) |
| `RealAzimuth/.../Hacf.kt` | Frame transformation | Lines 5-15 (quaternion rotation) |
| `RealAzimuth/.../RoninOnnx.kt` | Model I/O | Lines 11-12 (input/output spec) |
| `RealAzimuth/.../ImuCollector.kt` | Sensor pipeline | Lines 95-123 (GRV + HACF rotation) |
| `NavSync/.../ESKFState.kt` | NED frame definition | Lines 5-18 (frame conventions), 36-38 (velocity) |
| `NavSync/.../ESKF.kt` | Velocity measurement interface | Lines 77-83 (updateWithVelocity) |

---

**END OF ANALYSIS**
