# RoNIN + ESKF Device Validation Guide

**Date**: September 14, 2026  
**APK**: Ready for deployment  
**Test Scenario**: 35-50s GNSS outage validation with S-Vw9/V-Vw9 datasets  

---

## 📱 **APK Deployment**

### **APK Location**
```
C:\Users\Prachi\AndroidStudioProjects\NavSync\app\build\outputs\apk\debug\app-debug.apk
```

### **Installation Command**
```bash
adb install app-debug.apk
```

### **Launch App**
```bash
adb shell am start -n com.example.navsync/.MainActivity
```

---

## 🔍 **Logcat Monitoring Setup**

### **Primary Validation Filter**
```bash
adb logcat -s RoninESKF:* ESKFImpl:* NavigationSimulator:*
```

### **Detailed Debug Filter** (includes buffer status)
```bash
adb logcat -s RoninESKF:* ESKFImpl:* NavigationSimulator:* CsvDataSource:*
```

### **Complete Logging** (for troubleshooting)
```bash
adb logcat | grep -E "RoninESKF|ESKFImpl|NavigationSimulator|CsvDataSource|RoninOnnx"
```

---

## 🎯 **Key Log Messages to Watch For**

### **1. RoNIN Model Loading**
```
RoninESKF: ✅ RoNIN model loaded successfully from ronin_lstm.onnx
```
**❌ Failure**: `❌ Failed to load RoNIN model`

### **2. Dataset Loading**
```
CsvDataSource: Loaded V-Vw9: V=XXX points, S=XXX sensor samples, synchronized=XXX points, duration: XX.Xs
```

### **3. GNSS Outage Control**
```
NavigationSimulator: 🚫 GNSS OUTAGE START at 35.0s (step XXX) - Switching to AI estimation
NavigationSimulator: 📡 GNSS RECOVERY at 50.0s (step XXX) - Returning to GNSS positioning
```

### **4. Buffer Status**
```
RoninESKF: Buffer filling: XXX/400 samples
RoninESKF: 🔄 400-sample window ready, triggering RoNIN inference (samples: 400)
```

### **5. RoNIN Inference Execution**
```
RoninESKF: 🧠 Executing RoNIN ONNX inference with 400×6 window...
RoninESKF: 🔍 RoNIN HACF velocity: vx_hacf=X.XXX m/s, vy_hacf=X.XXX m/s
```

### **6. Coordinate Transformation**
```
RoninESKF: 🔄 Coordinate transform: yaw_offset=XX.XXX° | NED velocity: vN=X.XXX m/s, vE=X.XXX m/s
```

### **7. ESKF Integration**
```
RoninESKF: ⚡ Sending to ESKF: updateWithVelocity(vN=X.XXX, vE=X.XXX, σ=X.XX)
RoninESKF: ✅ RoNIN→ESKF velocity measurement applied successfully
```

### **8. ESKF NIS Validation**
```
ESKFImpl: 🟢 Velocity NIS check passed: NIS=X.XX ≤ threshold=9.21
ESKFImpl: ✅ RoNIN velocity ACCEPTED: innovation=[X.XXX, X.XXX] m/s, correction=[X.XXX, X.XXX] m/s
```
**❌ Rejection**: `🔴 Velocity NIS check failed` and `❌ RoNIN velocity REJECTED`

### **9. Data Isolation Verification**
```
NavigationSimulator: 🔒 OUTAGE MODE: Only IMU+prev_estimate sent to AI (NO reference position)
NavigationSimulator: OUTAGE: t=XX.Xs | REF=(XX.XXXXXX,XX.XXXXXX) | EST=(XX.XXXXXX,XX.XXXXXX)
```

---

## 📊 **Validation Metrics Collection**

### **During 35-50s Outage Window**

**Count These Events**:
- RoNIN inferences executed: `🧠 Executing RoNIN ONNX inference`
- ESKF updates accepted: `✅ RoNIN velocity ACCEPTED`
- ESKF updates rejected: `❌ RoNIN velocity REJECTED`

**Record These Values** (at least 5-10 samples):
- `vx_hacf`, `vy_hacf`: RoNIN HACF velocities
- `yaw_offset`: Coordinate transformation angle
- `vN`, `vE`: Converted NED velocities
- `NIS`: Statistical validation scores

**Position Accuracy**:
- Compare `REF=` vs `EST=` coordinates during outage
- Calculate drift: final error - initial error / 15 seconds

---

## ⚠️ **Critical Verification Points**

### **✅ Must Confirm**

1. **RoNIN Model Loads**: See `✅ RoNIN model loaded successfully`
2. **Real Datasets Used**: See `CsvDataSource: Loaded V-Vw9` with actual sample counts
3. **Outage Window Exact**: 35.0s to 50.0s (15-second duration)
4. **RoNIN Inferences Execute**: Multiple `🧠 Executing RoNIN ONNX inference` during outage
5. **No Reference Leakage**: See `🔒 OUTAGE MODE: Only IMU+prev_estimate sent to AI`
6. **ESKF Updates Applied**: See `✅ RoNIN velocity ACCEPTED` messages
7. **Coordinate Transformation**: Non-zero yaw offsets and velocity conversions

### **🚨 Red Flags**

- `❌ Failed to load RoNIN model`: ONNX loading issue
- No `🧠 Executing RoNIN ONNX inference` during outage: Buffer not filling
- All `❌ RoNIN velocity REJECTED`: NIS threshold too strict
- Zero velocity values: Model inference problem
- Identical REF/EST coordinates: Reference data leaking into AI

---

## 📋 **Test Execution Steps**

### **1. Setup**
```bash
# Install APK
adb install app-debug.apk

# Start logging
adb logcat -s RoninESKF:* ESKFImpl:* NavigationSimulator:* > validation_log.txt
```

### **2. Run Validation**
1. **Launch NavSync app**
2. **Navigate to simulation/validation screen**
3. **Load V-Vw9 dataset** 
4. **Configure 35-50s GNSS outage**
5. **Start simulation**
6. **Monitor logs in real-time**

### **3. Data Collection**
- **Let simulation run to completion** (~60+ seconds)
- **Save complete log file**
- **Count metrics during 35-50s window**
- **Calculate position drift and accuracy**

---

## 📈 **Expected Results**

Based on implementation analysis:

| Metric | Expected Value |
|--------|----------------|
| **RoNIN Inferences** | ~15-20 during 15s outage |
| **Acceptance Rate** | 70-90% (NIS validation) |
| **Position Improvement** | 30-50% vs IMU-only baseline |
| **Velocity Corrections** | Non-zero vN, vE measurements |
| **Model Loading** | Success (ronin_lstm.onnx) |
| **Buffer Fill Time** | ~2-4 seconds (400 samples at ~200 Hz) |

---

## 🔧 **Troubleshooting**

### **No RoNIN Inferences**
- Check: `✅ RoNIN model loaded successfully`
- Check: Buffer filling progress logs
- Issue: ONNX model not found in assets

### **All Velocity Updates Rejected**
- Check: NIS threshold values in logs
- Issue: Excessive measurement uncertainty
- Solution: Verify coordinate transformations

### **No GNSS Outage**
- Check: Outage configuration in simulation
- Check: Time-based outage trigger logic
- Issue: Dataset not loaded properly

---

**🚀 Ready for device validation - Deploy APK and monitor the specified log tags to verify complete RoNIN+ESKF integration.**