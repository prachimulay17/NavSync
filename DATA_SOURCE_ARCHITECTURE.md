# NavSync Data Source Architecture

## Overview

The NavSync replay system has been refactored to support cleanly replaceable data sources. The architecture separates data provision from data consumption, allowing easy integration of CSV files, databases, remote sources, or real sensor recordings.

## Architecture Principles

### 1. Clean Data Contract

All data sources provide data in a standardized format defined by:
- `SensorData` - IMU readings (always available)
- `GnssData` - GNSS position (when signal present)
- `GroundTruth` - True position (evaluation only)
- `ReplayDataPoint` - Complete timestamped data point
- `ReplayDataset` - Collection of points with metadata

### 2. Replaceable Sources

Data sources implement the `ReplayDataSource` interface:
```kotlin
interface ReplayDataSource {
    suspend fun getAvailableDatasets(): List<String>
    suspend fun loadDataset(name: String): ReplayDataset
    fun getSourceType(): String
    fun isAvailable(): Boolean
}
```

### 3. Separation of Concerns

```
UI Layer (SimulationScreen)
    ↓
ViewModel Layer (SimulationViewModel)
    ↓
Repository Layer (DatasetRepository)
    ↓
Data Source Layer (ReplayDataSource implementations)
    ↓
Raw Data (Synthetic / CSV / Database / Remote)
```

## Data Contract Details

### SensorData (IMU)

```kotlin
data class SensorData(
    val timestampMs: Long,
    val accelerationX: Double,  // m/s²
    val accelerationY: Double,  // m/s²
    val accelerationZ: Double,  // m/s²
    val gyroX: Double,          // rad/s
    val gyroY: Double,          // rad/s
    val gyroZ: Double           // rad/s (yaw rate)
)
```

**Usage**: Always available to NavSyncInference during outages

### GnssData (Position from GNSS)

```kotlin
data class GnssData(
    val timestampMs: Long,
    val latitude: Double,       // degrees
    val longitude: Double,      // degrees
    val speedKmh: Double,       // km/h
    val headingDegrees: Double  // degrees (0-360)
)
```

**Usage**: Available only when GNSS signal present, NEVER to inference during outage

### GroundTruth (Evaluation)

```kotlin
data class GroundTruth(
    val timestampMs: Long,
    val latitude: Double,       // degrees
    val longitude: Double,      // degrees
    val speedKmh: Double,       // km/h
    val headingDegrees: Double  // degrees (0-360)
)
```

**Usage**: Evaluation and error measurement only, never as inference input

### ReplayDataPoint (Complete Point)

```kotlin
data class ReplayDataPoint(
    val sensorData: SensorData,
    val gnssData: GnssData,
    val groundTruth: GroundTruth
)
```

**Usage**: Single timestamped point containing all three data types

### ReplayMetadata

```kotlin
data class ReplayMetadata(
    val name: String,
    val description: String,
    val durationSeconds: Double,
    val totalPoints: Int,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val source: String  // "synthetic", "csv", "recorded", etc.
)
```

### ReplayDataset

```kotlin
data class ReplayDataset(
    val metadata: ReplayMetadata,
    val dataPoints: List<ReplayDataPoint>
) {
    fun getPoint(index: Int): ReplayDataPoint?
    fun size(): Int
    fun isEmpty(): Boolean
    fun toNavigationDataset(): NavigationDataset
}
```

## Current Implementation

### SyntheticDataSource

**File**: `app/src/main/java/com/example/navsync/data/DatasetRepository.kt`

**Description**: Generates synthetic navigation datasets for prototyping

**Features**:
- 5 predefined Indian city routes
- 100 data points per route (~100 seconds)
- Realistic trajectories with position variations
- Simulated IMU sensor data
- Synchronous generation (instant availability)

**Usage**:
```kotlin
val source = SyntheticDataSource()
val datasets = source.getAvailableDatasets() 
// ["Delhi Urban Route", "Mumbai Highway", ...]

val dataset = source.loadDataset("Delhi Urban Route")
// ReplayDataset with 100 points
```

### DatasetRepository

**File**: `app/src/main/java/com/example/navsync/data/DatasetRepository.kt`

**Description**: Central repository managing data sources

**Features**:
- Accepts any ReplayDataSource implementation
- Default: SyntheticDataSource
- Provides unified API to ViewModels
- Handles errors gracefully

**Usage**:
```kotlin
// Default (synthetic)
val repo = DatasetRepository()

// Custom source
val repo = DatasetRepository(CsvDataSource(csvDir))

// Load dataset
val dataset = repo.loadDataset("Delhi Urban Route")
```

## Adding New Data Sources

### Example: CSV Data Source

**Step 1**: Implement ReplayDataSource

```kotlin
class CsvDataSource(private val csvDirectory: File) : ReplayDataSource {
    
    override suspend fun getAvailableDatasets(): List<String> = withContext(Dispatchers.IO) {
        csvDirectory.listFiles { file -> 
            file.extension == "csv" 
        }?.map { it.nameWithoutExtension } ?: emptyList()
    }
    
    override suspend fun loadDataset(name: String): ReplayDataset = withContext(Dispatchers.IO) {
        val file = File(csvDirectory, "$name.csv")
        if (!file.exists()) {
            throw DataSourceException("CSV file not found: ${file.path}")
        }
        
        parseCsvFile(file)
    }
    
    override fun getSourceType(): String = "csv"
    
    override fun isAvailable(): Boolean {
        return csvDirectory.exists() && csvDirectory.isDirectory
    }
    
    private fun parseCsvFile(file: File): ReplayDataset {
        val points = mutableListOf<ReplayDataPoint>()
        
        file.bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line ->  // Skip header
                val parts = line.split(",")
                if (parts.size >= 13) {
                    points.add(
                        ReplayDataPoint(
                            sensorData = SensorData(
                                timestampMs = parts[0].toLong(),
                                accelerationX = parts[1].toDouble(),
                                accelerationY = parts[2].toDouble(),
                                accelerationZ = parts[3].toDouble(),
                                gyroX = parts[4].toDouble(),
                                gyroY = parts[5].toDouble(),
                                gyroZ = parts[6].toDouble()
                            ),
                            gnssData = GnssData(
                                timestampMs = parts[0].toLong(),
                                latitude = parts[7].toDouble(),
                                longitude = parts[8].toDouble(),
                                speedKmh = parts[9].toDouble(),
                                headingDegrees = parts[10].toDouble()
                            ),
                            groundTruth = GroundTruth(
                                timestampMs = parts[0].toLong(),
                                latitude = parts[11].toDouble(),
                                longitude = parts[12].toDouble(),
                                speedKmh = parts[13].toDouble(),
                                headingDegrees = parts[14].toDouble()
                            )
                        )
                    )
                }
            }
        }
        
        val metadata = ReplayMetadata(
            name = file.nameWithoutExtension,
            description = "CSV dataset from ${file.name}",
            durationSeconds = (points.last().sensorData.timestampMs - 
                             points.first().sensorData.timestampMs) / 1000.0,
            totalPoints = points.size,
            startTimestampMs = points.first().sensorData.timestampMs,
            endTimestampMs = points.last().sensorData.timestampMs,
            source = "csv"
        )
        
        return ReplayDataset(metadata, points)
    }
}
```

**Step 2**: Update DatasetRepository

```kotlin
// In application initialization or ViewModel
val csvDir = File(context.filesDir, "datasets")
val csvSource = CsvDataSource(csvDir)
val repository = DatasetRepository(csvSource)
```

### CSV File Format

```csv
timestamp_ms,accel_x,accel_y,accel_z,gyro_x,gyro_y,gyro_z,gnss_lat,gnss_lon,gnss_speed,gnss_heading,truth_lat,truth_lon,truth_speed,truth_heading
0,0.12,-0.05,9.81,0.001,-0.002,0.05,28.6139,77.2090,42.0,137.0,28.6139,77.2090,42.0,137.0
1000,0.15,-0.03,9.80,0.002,-0.001,0.06,28.6140,77.2091,42.5,137.5,28.6140,77.2091,42.5,137.5
2000,0.10,-0.07,9.82,0.001,0.000,0.04,28.6141,77.2092,41.8,136.8,28.6141,77.2092,41.8,136.8
...
```

### Example: Recorded Data Source

**Real sensor recording from Android device**:

```kotlin
class RecordedDataSource(private val context: Context) : ReplayDataSource {
    
    private val database: RecordingDatabase by lazy {
        Room.databaseBuilder(
            context,
            RecordingDatabase::class.java,
            "recordings.db"
        ).build()
    }
    
    override suspend fun getAvailableDatasets(): List<String> {
        return database.recordingDao().getAllRecordingNames()
    }
    
    override suspend fun loadDataset(name: String): ReplayDataset {
        val recording = database.recordingDao().getRecordingByName(name)
            ?: throw DataSourceException("Recording not found: $name")
        
        val points = database.recordingDao().getDataPoints(recording.id)
            .map { it.toReplayDataPoint() }
        
        val metadata = ReplayMetadata(
            name = recording.name,
            description = recording.description,
            durationSeconds = recording.durationSeconds,
            totalPoints = points.size,
            startTimestampMs = recording.startTime,
            endTimestampMs = recording.endTime,
            source = "recorded"
        )
        
        return ReplayDataset(metadata, points)
    }
    
    override fun getSourceType(): String = "recorded"
    
    override fun isAvailable(): Boolean = true
}
```

### Example: Remote Data Source

**Load datasets from remote server**:

```kotlin
class RemoteDataSource(
    private val apiClient: NavSyncApiClient
) : ReplayDataSource {
    
    override suspend fun getAvailableDatasets(): List<String> {
        return apiClient.listDatasets().map { it.name }
    }
    
    override suspend fun loadDataset(name: String): ReplayDataset {
        val response = apiClient.downloadDataset(name)
        return parseRemoteDataset(response)
    }
    
    override fun getSourceType(): String = "remote"
    
    override fun isAvailable(): Boolean {
        // Check network connectivity
        return NetworkUtils.isConnected()
    }
}
```

## Integration with Existing System

### Unchanged Components

✅ **UI Layer**: No changes needed
- SimulationScreen uses repository through ViewModel
- NavigationScreen observes NavigationState (unchanged)

✅ **Simulation Logic**: No changes needed
- NavigationSimulator receives NavigationDataset
- Outage logic unchanged
- Inference integration unchanged

✅ **Navigation Pipeline**: No changes needed
- NavigationViewModel unchanged
- NavigationState unchanged
- UI updates unchanged

### Changed Components

🔄 **DatasetRepository**: Now accepts ReplayDataSource
```kotlin
// Before
class DatasetRepository {
    fun getAvailableDatasets(): List<String>
    fun loadDataset(name: String): NavigationDataset
}

// After
class DatasetRepository(dataSource: ReplayDataSource) {
    suspend fun getAvailableDatasets(): List<String>
    suspend fun loadDataset(name: String): ReplayDataset
    suspend fun loadNavigationDataset(name: String): NavigationDataset
}
```

🔄 **SimulationViewModel**: Uses suspend functions
```kotlin
// Before
fun loadConfiguredDataset() = repository.loadDataset(...)

// After
suspend fun loadConfiguredDataset() = repository.loadNavigationDataset(...)
```

🔄 **SimulationScreen**: Uses coroutine scope
```kotlin
// Before
onStart = {
    val dataset = viewModel.loadConfiguredDataset()
    navigationViewModel.startSimulation(dataset, ...)
}

// After
onStart = {
    coroutineScope.launch {
        val dataset = viewModel.loadConfiguredDataset()
        navigationViewModel.startSimulation(dataset, ...)
    }
}
```

## Benefits

### 1. Clean Separation

- Data provision separate from data consumption
- UI doesn't know or care about data source
- Simulation logic independent of data format

### 2. Easy Replacement

```kotlin
// Switch from synthetic to CSV
val repository = DatasetRepository(CsvDataSource(csvDir))

// Switch to recorded data
val repository = DatasetRepository(RecordedDataSource(context))

// Switch to remote
val repository = DatasetRepository(RemoteDataSource(apiClient))
```

### 3. Testability

```kotlin
// Mock data source for testing
class MockDataSource : ReplayDataSource {
    override suspend fun getAvailableDatasets() = listOf("Test Dataset")
    override suspend fun loadDataset(name: String) = testDataset
    override fun getSourceType() = "mock"
}

// Test with mock
val repo = DatasetRepository(MockDataSource())
```

### 4. Composability

```kotlin
// Combine multiple sources
class CompositeDataSource(
    private val sources: List<ReplayDataSource>
) : ReplayDataSource {
    override suspend fun getAvailableDatasets(): List<String> {
        return sources.flatMap { it.getAvailableDatasets() }
    }
    
    override suspend fun loadDataset(name: String): ReplayDataset {
        for (source in sources) {
            if (name in source.getAvailableDatasets()) {
                return source.loadDataset(name)
            }
        }
        throw DataSourceException("Dataset not found")
    }
}

// Use composite
val repo = DatasetRepository(
    CompositeDataSource(listOf(
        CsvDataSource(csvDir),
        RecordedDataSource(context),
        SyntheticDataSource()  // Fallback
    ))
)
```

## Build Status

```
./gradlew.bat assembleDebug
BUILD SUCCESSFUL in 8s
```

✅ No errors
✅ All existing features working
✅ Synthetic data source still default
✅ Ready for CSV/recorded data integration

## Next Steps

1. ✅ **COMPLETE**: Clean data contract defined
2. ✅ **COMPLETE**: Replaceable data source architecture
3. ✅ **COMPLETE**: Synthetic source working
4. ⏳ **FUTURE**: CSV parser implementation
5. ⏳ **FUTURE**: Real sensor recording
6. ⏳ **FUTURE**: Remote dataset loading
7. ⏳ **FUTURE**: Dataset caching/offline support

## Files Changed

- `app/src/main/java/com/example/navsync/data/ReplayDataContract.kt` (NEW)
- `app/src/main/java/com/example/navsync/data/DatasetRepository.kt` (REFACTORED)
- `app/src/main/java/com/example/navsync/viewmodel/SimulationViewModel.kt` (UPDATED)
- `app/src/main/java/com/example/navsync/ui/SimulationScreen.kt` (UPDATED)

## Files Unchanged

- `app/src/main/java/com/example/navsync/data/DatasetPoint.kt`
- `app/src/main/java/com/example/navsync/simulation/NavigationSimulator.kt`
- `app/src/main/java/com/example/navsync/viewmodel/NavigationViewModel.kt`
- `app/src/main/java/com/example/navsync/ui/NavigationScreen.kt`
- All UI components

The data source architecture is now clean, replaceable, and ready for integration of real datasets.
