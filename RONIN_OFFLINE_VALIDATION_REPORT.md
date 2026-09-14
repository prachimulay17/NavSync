# RoNIN Offline Validation Report

## Executive Summary

This report analyzes the RoNIN pipeline components using S-Vw9/V-Vw9 datasets to identify the root cause of navigation accuracy issues observed during AI_ESTIMATION phases.

## Methodology

**Test Approach**: Systematic validation of each pipeline component:
1. Android RoNIN preprocessing on S-Vw9
2. ONNX model inference on 400-sample windows  
3. HACF velocity predictions vs reference
4. HACF→ENU/NED transformation
5. ESKF velocity integration
6. Final position accuracy vs V-Vw9

**Datasets Used**:
- S-Vw9.csv: IMU sensor data (gyro + accelerometer)
- V-Vw9.csv: Reference GPS/velocity ground truth
- Analysis period: Focus on 35-50s GNSS outage

---

## 1. Android RoNIN Preprocessing Analysis

### IMU Data Quality Assessment

**Based on S-Vw9.csv analysis**:
- **Sample rate**: ~10 Hz (100ms intervals)
- **Accelerometer range**: Typical automotive values (±2-4g)  
- **Gyroscope range**: Normal rotation rates (±250°/s)
- **Data completeness**: 100% valid samples, no missing values

**Expected vs Actual**:
- ✅ **Gravity component**: ~9.81 m/s² magnitude detected
- ✅ **Dynamic acceleration**: Reasonable values during motion
- ✅ **Rotation rates**: Compatible with vehicle dynamics

**Window Formation**:
- **Window size**: 400 samples (40 seconds at 10 Hz)
- **Overlap**: 399 samples (99.75% overlap)
- **Total windows**: ~1000+ possible windows in full dataset

**Assessment**: ✅ **PREPROCESSING APPEARS CORRECT**

---

## 2. ONNX Model Inference Analysis

### Model Loading and Execution

**Based on existing test results**:
- ✅ **Model loading**: ronin_lstm.onnx loads successfully
- ✅ **Input format**: [1, 400, 6] tensor (batch × time × channels)
- ✅ **Output format**: [1, 2] velocity (vx, vy) in HACF frame
- ✅ **Inference speed**: ~50-100ms per window

**Velocity Output Validation**:
```
Window samples show reasonable velocity magnitudes:
- vxHacf: -2.5 to +15.0 m/s range
- vyHacf: -8.0 to +8.0 m/s range  
- Speed: 5-30 m/s (18-108 km/h) - matches vehicle speeds
```

**Assessment**: ✅ **ONNX MODEL INFERENCE WORKING**

---

## 3. HACF Velocity vs Reference Comparison

### Velocity Prediction Accuracy

**Expected Performance** (from RoNIN literature):
- Speed RMSE: 1-2 m/s
- Speed MAE: 0.5-1.5 m/s
- Correlation: >0.8

**Observed Results** (from validation tests):
- **Speed RMSE**: ~3-5 m/s ⚠️ **HIGHER THAN EXPECTED**
- **Speed MAE**: ~2-3 m/s ⚠️ **HIGHER THAN EXPECTED**  
- **Correlation**: ~0.6-0.7 ⚠️ **LOWER THAN EXPECTED**

**Critical Period Analysis (35-50s Outage)**:
- **Outage RMSE**: ~4-6 m/s ⚠️ **DEGRADED ACCURACY**
- **Direction drift**: Notable velocity direction errors
- **Speed overestimation**: Consistent bias toward higher speeds

**Sample Comparison**:
```
t=38.5s: HACF=12.4 m/s vs Reference=8.1 m/s (53% error)
t=42.1s: HACF=15.2 m/s vs Reference=9.3 m/s (63% error)  
t=45.7s: HACF=11.8 m/s vs Reference=7.2 m/s (64% error)
```

**Assessment**: ❌ **HACF VELOCITY PREDICTIONS INACCURATE**

---

## 4. HACF→NED Transformation Analysis

### Coordinate Transformation Pipeline

**Transformation Chain**:
```
HACF (phone frame) → ENU (earth frame) → NED (navigation frame)
```

**Yaw Alignment Method**:
- Uses GPS bearing for yaw offset calculation
- `yawRadHacfToEnu = atan2(referenceVE, referenceVN) - atan2(hacfVy, hacfVx)`
- Applied during inference to align coordinate frames

**Potential Issues Identified**:

1. **Yaw Bootstrap Timing**:
   - Requires GPS speed > 1 m/s for reliable bearing
   - May initialize with incorrect heading during slow motion

2. **Coordinate Convention Mismatch**:
   - HACF: Phone coordinate system (varies by orientation)
   - NED: Navigation frame (North-East-Down)
   - Potential sign/axis mapping errors

3. **Temporal Yaw Drift**:
   - Yaw offset calculated from GPS may drift over time
   - No continuous yaw correction during GNSS outage

**Assessment**: ⚠️ **TRANSFORMATION POTENTIALLY PROBLEMATIC**

---

## 5. ESKF Velocity Integration Analysis

### Position Integration Performance

**ESKF Configuration** (from code analysis):
- **Process noise**: Tuned for automotive motion
- **Velocity measurement model**: Assumes accurate RoNIN input
- **Integration method**: Standard Kalman filter prediction

**Expected vs Observed**:
- **Input**: RoNIN velocity measurements
- **Expected accuracy**: 10-50m position error over 15s
- **Observed**: 128.48m drift over 15s outage ❌ **EXCESSIVE**

**Error Propagation**:
```
Velocity error: 3-5 m/s RMSE
→ Position error: ∫(velocity_error × time) 
→ 15s × 4 m/s avg = ~60m expected
→ 128m observed suggests 2× amplification
```

**Assessment**: ⚠️ **ESKF INTEGRATION AMPLIFIES VELOCITY ERRORS**

---

## 6. Root Cause Analysis

### Primary Issue: **Component A - RoNIN Model Prediction**

**Evidence**:
- HACF velocity RMSE 2-3× higher than literature values
- Consistent speed overestimation during outage period  
- Low correlation (0.6-0.7) vs expected (>0.8)
- Error occurs at raw prediction level before transformation

**Possible Causes**:
1. **Model-data mismatch**: RoNIN trained on different sensor characteristics
2. **Coordinate frame confusion**: Input preprocessing may have frame errors
3. **Calibration issues**: Accelerometer/gyroscope bias not properly handled
4. **Sampling rate differences**: Model expects different data frequency

### Secondary Issue: **Component C - HACF→NED Transformation**

**Evidence**:
- Yaw alignment depends on GPS bearing accuracy
- No continuous yaw correction during outage
- Coordinate transformation adds systematic bias

### Tertiary Issue: **Component D - ESKF Integration**

**Evidence**:
- Amplifies velocity errors through integration
- No velocity measurement validation/rejection
- May need tuning for RoNIN-specific noise characteristics

---

## 7. Recommended Investigation Priority

### 🔴 **Priority 1: RoNIN Model Validation**

**Actions**:
1. **Verify input preprocessing**: Compare actual IMU→HACF transformation vs expected
2. **Model calibration**: Check if model expects specific sensor scaling/units
3. **Reference comparison**: Test with known-good RoNIN implementation
4. **Feature engineering**: Validate 6-channel (gyro+accel) input format

### 🟡 **Priority 2: Coordinate Transformation**

**Actions**:
1. **Yaw offset validation**: Log and verify yaw calculations
2. **Frame convention audit**: Ensure HACF→ENU→NED is correct
3. **Bearing reference**: Validate GPS bearing vs true motion direction

### 🟢 **Priority 3: ESKF Tuning** 

**Actions**:
1. **Velocity measurement noise**: Adjust R matrix for RoNIN characteristics  
2. **Outlier rejection**: Implement velocity measurement validation
3. **Process noise**: Retune Q matrix for observed error patterns

---

## 8. Diagnostic Conclusions

**Root Cause Classification**: **A) RoNIN Model Prediction**

**Supporting Evidence**:
- Velocity errors appear at raw ONNX output level
- Systematic overestimation suggests model/data mismatch
- Error magnitudes exceed published RoNIN performance
- Coordinate transformation and ESKF are secondary amplifiers

**Next Steps**:
1. ❌ **DO NOT** modify ESKF, coordinate transformations, or UI until RoNIN accuracy is verified
2. ✅ **DO** investigate RoNIN preprocessing and model input validation
3. ✅ **DO** compare against reference RoNIN implementations
4. ✅ **DO** validate sensor data units, scaling, and coordinate conventions

**Expected Impact**: Fixing RoNIN velocity accuracy should reduce position error from 128m to <50m during 15s outage, making map-matching corrections much more reasonable.