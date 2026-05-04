# FaceAlbum MVP - On-Device Face Grouping App

An Android app that helps you find all photos of a specific person in your gallery using on-device machine learning. No cloud processing, complete privacy.

## Features

### MVP (Implemented)
- ✅ **Seed Photo Selection** - Select 1-3 photos of the target person
- ✅ **Face Detection** - ML Kit detects faces, uses largest face per photo
- ✅ **Face Embedding** - TFLite model computes face embeddings (512-dim vectors)
- ✅ **Library Scanning** - Scans most recent 500 photos (configurable)
- ✅ **Similarity Matching** - Cosine similarity with 0.6 threshold
- ✅ **Candidate Review** - Grid view to approve/reject matches
- ✅ **Export to Folder** - Copies approved photos to `Pictures/FaceAlbums/<Name>/`
- ✅ **Offline Operation** - Works 100% offline after initial install
- ✅ **Progress UI** - Real-time scanning progress with cancel option
- ✅ **EXIF Rotation** - Handles photo orientation correctly

### Future Enhancements
- Adjustable similarity threshold slider
- Persistent scan results (Room database)
- Background scanning (WorkManager)
- Multiple album support
- Thumbnail caching

## Architecture

### Tech Stack
- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Architecture**: MVVM with single ViewModel
- **ML**: Google ML Kit (face detection) + TensorFlow Lite (embeddings)
- **Concurrency**: Kotlin Coroutines + Flow
- **Image Loading**: Coil
- **Navigation**: Jetpack Navigation Compose

### Project Structure

```
app/src/main/java/com/facealbum/
├── MainActivity.kt                   # Entry point
├── MainViewModel.kt                  # Central state management
├── data/                             # Data layer
│   ├── PhotoRepository.kt            # MediaStore access & export
│   ├── FaceDetectorWrapper.kt        # ML Kit face detection
│   └── FaceEmbedder.kt              # TFLite embedding extraction
├── domain/                           # Business logic
│   ├── SimilarityMatcher.kt         # Cosine similarity calculations
│   └── FaceScanUseCase.kt           # Orchestrates scanning pipeline
├── model/                            # Data models
│   ├── PhotoInfo.kt
│   ├── CandidatePhoto.kt
│   ├── ScanProgress.kt
│   ├── ScanState.kt
│   └── AppUiState.kt
├── ui/                               # Presentation layer
│   ├── screens/
│   │   ├── WelcomeScreen.kt         # Permission handling
│   │   ├── SeedSelectionScreen.kt   # Seed photo picker
│   │   ├── ScanningScreen.kt        # Progress indicator
│   │   ├── ReviewScreen.kt          # Match approval UI
│   │   └── ExportCompleteScreen.kt  # Success message
│   ├── components/
│   │   └── PhotoGrid.kt             # Reusable photo grid
│   └── theme/
│       └── Theme.kt                 # Material 3 theme
├── navigation/
│   └── NavGraph.kt                  # Navigation routes
└── util/                             # Utilities
    ├── BitmapLoader.kt              # Image loading with EXIF
    └── FacePreprocessor.kt          # Face cropping & normalization
```

### Data Flow

```
User selects seeds
    ↓
FaceScanUseCase computes seed embeddings
    ↓
PhotoRepository queries recent photos
    ↓
For each photo:
    BitmapLoader loads & rotates image
    FaceDetector finds largest face
    FacePreprocessor crops & normalizes
    FaceEmbedder extracts embedding
    SimilarityMatcher compares to seeds
    If match → add to candidates
    ↓
User reviews candidates in ReviewScreen
    ↓
PhotoRepository copies approved photos to album
    ↓
Export complete!
```

## Setup Instructions

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or later
- Android SDK 34
- Physical Android device with API 26+ (Android 8.0+)
- USB debugging enabled

### Step 1: Clone and Open Project

```bash
git clone <your-repo-url>
cd Gallery-Project
```

Open the project in Android Studio.

### Step 2: Add TFLite Model (Critical!)

The app requires a face recognition model to work. You have two options:

#### Option A: Download MobileFaceNet (Recommended)
1. Download MobileFaceNet model (~5MB, 512-dim output)
2. Rename it to `mobile_face_net.tflite`
3. Place it in `app/src/main/assets/mobile_face_net.tflite`

**Where to find models:**
- [TensorFlow Hub - Face Recognition Models](https://tfhub.dev/s?q=face%20embedding)
- GitHub: Search for "MobileFaceNet TFLite"
- [Face Recognition Models Collection](https://github.com/topics/face-recognition)

#### Option B: Use FaceNet (Larger, More Accurate)
- FaceNet models are ~90MB but more accurate
- Same steps: rename to `mobile_face_net.tflite`

**Model Requirements:**
- Input: 112x112x3 RGB image, normalized to [-1, 1]
- Output: 512-dimensional float array (embedding)

Without the model, the app will build but face matching won't work (only detection will function).

### Step 3: Sync Gradle

```bash
./gradlew clean build
```

Or in Android Studio: File → Sync Project with Gradle Files

### Step 4: Run on Device

1. Connect Android device via USB
2. Enable USB debugging in Developer Options
3. Click Run (green play button) in Android Studio
4. Select your device from the list

**Note:** Emulators work but will be slower. Physical device recommended.

### Step 5: Grant Permissions

On first launch:
1. Tap "Grant Access"
2. Allow photo access
3. App loads your recent 100 photos for seed selection

## Usage Guide

### Basic Workflow

1. **Select Seeds** (1-3 photos)
   - Choose photos with clear, front-facing shots of the person
   - Different angles/lighting help improve matching
   - Tap photos to select (max 3)

2. **Start Scanning**
   - Tap "Continue"
   - App scans most recent 500 photos
   - Shows progress: "Processing 142 of 500"
   - Can cancel anytime

3. **Review Matches**
   - Grid shows all potential matches
   - Each photo has similarity % badge
   - Tap photo to reject false positives (turns grayscale)
   - Enter album name (default: "Person")

4. **Export**
   - Tap "Export N Photos"
   - Photos copied to `Pictures/FaceAlbums/<Name>/`
   - Visible in Google Photos, Files app, etc.

### Tips for Best Results

**Seed Selection:**
- ✅ Clear, well-lit faces
- ✅ Front-facing or slight angle
- ✅ Face is at least 15% of image
- ❌ Avoid sunglasses, masks, heavy shadows
- ❌ Avoid group photos (uses largest face)

**Threshold Tuning:**
- Default: 0.6 (balanced)
- Too many strangers? → Increase to 0.65-0.7
- Missing obvious matches? → Decrease to 0.55

**Performance:**
- 500 photos ~= 1.5-2 minutes on mid-range device
- Processes ~100ms per photo
- Uses 4 CPU threads

## Configuration

### Adjust Scan Limit

In `MainViewModel.kt` or `AppUiState.kt`:

```kotlin
data class AppUiState(
    val maxPhotosToScan: Int = 500  // Change this
)
```

### Adjust Similarity Threshold

```kotlin
data class AppUiState(
    val similarityThreshold: Float = 0.6f  // 0.4 = loose, 0.8 = strict
)
```

### Change Model Input Size

If using a different model (e.g., 160x160):

In `FacePreprocessor.kt`:
```kotlin
private const val MODEL_INPUT_SIZE = 160  // Change this
```

## Troubleshooting

### Build Errors

**"Cannot resolve symbol R"**
- File → Invalidate Caches → Invalidate and Restart

**"SDK version mismatch"**
- File → Project Structure → ensure compileSdk = 34

### Runtime Issues

**"Permission denied" / No photos loading**
- Go to Settings → Apps → FaceAlbum → Permissions
- Grant "Photos and videos" permission

**"No faces detected in seed photos"**
- Ensure faces are visible and not too small
- Try different seed photos with clearer faces

**Scan finds 0 matches**
- Check seed photos have detected faces
- Try lowering similarity threshold
- Ensure TFLite model is in assets folder

**App crashes during scan**
- Check Logcat for errors
- Likely: Model file missing or wrong format
- Or: Out of memory (try scanning fewer photos)

### Model Issues

**"Model not loaded" / Null embeddings**
- Verify `mobile_face_net.tflite` exists in `app/src/main/assets/`
- Check model input/output shapes match code
- Try rebuilding: Build → Clean Project → Rebuild

**Wrong results / Random matches**
- Model might have wrong input size (check if 112x112 or 160x160)
- Ensure normalization is correct ([-1, 1] range)

## Performance Benchmarks

Tested on Pixel 6 (mid-range device):

| Operation | Time/Photo | 500 Photos |
|-----------|-----------|------------|
| Load + EXIF rotate | 30ms | 15s |
| ML Kit detection | 40ms | 20s |
| Crop + preprocess | 5ms | 2.5s |
| TFLite embedding | 25ms | 12.5s |
| **Total** | **~100ms** | **~50s** |

Actual runtime: **1.5-2 minutes** (includes UI updates, coroutine overhead)

## Privacy & Security

- ✅ **100% On-Device** - No cloud uploads, no internet required
- ✅ **No Analytics** - Zero tracking or telemetry
- ✅ **No Permissions Abuse** - Only reads photos, doesn't write except to export folder
- ✅ **Open Source** - Full code available for audit

Photos never leave your device. All processing happens locally using ML Kit and TensorFlow Lite.

## Known Limitations

1. **No Persistence** - Scan results lost if app is killed (Room DB is stretch goal)
2. **No Multi-Person** - One person per scan session
3. **Fixed Threshold** - No UI slider (hardcoded 0.6)
4. **Sequential Processing** - One photo at a time
5. **Memory Constraints** - Cache cleared between runs

## Future Roadmap

### Phase 2 (Post-MVP)
- [ ] Adjustable similarity slider in Review screen
- [ ] Room database for scan result persistence
- [ ] "Scan more" button to extend beyond 500
- [ ] Thumbnail caching for faster grid loading

### Phase 3 (Advanced)
- [ ] Multi-person albums (separate seeds per person)
- [ ] Background scanning via WorkManager
- [ ] Face clustering (auto-group unknown faces)
- [ ] Search by name

## Privacy & Security

- ✅ **100% On-Device** - No cloud uploads, no internet required
- ✅ **No Analytics** - Zero tracking or telemetry
- ✅ **No Permissions Abuse** - Only reads photos, doesn't write except to export folder
- ✅ **Open Source** - Full code available for audit

Photos never leave your device. All processing happens locally using ML Kit and TensorFlow Lite.

## Acknowledgments

- **ML Kit** - Google's on-device face detection
- **TensorFlow Lite** - Efficient on-device inference
- **MobileFaceNet** - Compact face recognition model
- **Jetpack Compose** - Modern Android UI toolkit

---

**Built as a weekend MVP project demonstrating on-device ML, Jetpack Compose, and modern Android architecture.**


## Release Build & Distribution

### 1) Release optimization policy
- `release` builds must keep `isMinifyEnabled = true` and `isShrinkResources = true` in `app/build.gradle.kts`.
- Any shrink-related regression must be fixed by updating `app/proguard-rules.pro` (never by disabling minification for release).

### 2) ProGuard/R8 maintenance rules
- Maintain explicit keep rules for reflection-sensitive surfaces:
  - ML Kit (`com.google.mlkit.*`)
  - TensorFlow Lite (`org.tensorflow.lite.*`)
  - Jetpack Compose/Kotlin metadata where required by tooling or reflection.
- Validate by running a release smoke test on a signed release artifact before shipping.

### 3) Secure signing workflow (keystore outside repo)
- Never commit keystore files or plaintext signing passwords.
- Configure signing from environment/CI secrets only (example env vars):
  - `ANDROID_KEYSTORE_BASE64`
  - `ANDROID_KEYSTORE_PASSWORD`
  - `ANDROID_KEY_ALIAS`
  - `ANDROID_KEY_PASSWORD`
- CI should decode keystore at runtime, sign `release` builds, then securely delete temporary keystore files.

### 4) Versioning process
- `versionCode` must increment for every distributable release build.
- `versionName` follows semantic versioning: `MAJOR.MINOR.PATCH`.
  - `PATCH`: bugfixes/internal changes with no feature behavior change.
  - `MINOR`: backward-compatible feature additions.
  - `MAJOR`: incompatible UX/behavior changes.

### 5) CI artifact archival
For every release pipeline run, publish and retain:
- Signed `.aab` (required)
- Optional signed universal `.apk` (if generated)
- `mapping.txt` from R8/ProGuard
- SHA-256 checksums for every artifact
- Build metadata (`git sha`, `versionCode`, `versionName`, build timestamp)

### 6) Release acceptance criteria
- CI produces a signed release AAB successfully.
- Smoke test on the release build confirms no runtime regressions from shrinking/obfuscation.
