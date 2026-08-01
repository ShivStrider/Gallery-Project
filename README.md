# FaceAlbum — On-Device Face Clustering for Android

A privacy-first photo organizer. FaceAlbum scans your photo library, groups every
detected face into clusters, and lets you name each person and export an album of
just their photos. Everything happens on-device — no uploads, no accounts, no
telemetry.

![Status](https://img.shields.io/badge/status-pre--release-blue)
![Platform](https://img.shields.io/badge/Android-8.0%2B-green)
![Language](https://img.shields.io/badge/Kotlin-1.9-purple)

## Features

- **Whole-library indexing** — scans every photo in `MediaStore`, not just the first N.
- **Automatic face grouping** — clusters faces by similarity so each person appears as their own tile (Google-Photos-style "People" view).
- **Tag faces** — rename a cluster once and the name sticks across rescans.
- **Export per-person albums** — copies all photos of one person into `Pictures/FaceAlbums/<Name>/`, visible immediately in Google Photos, Files, etc.
- **Incremental rescans** — subsequent scans only process photos modified since the last index pass.
- **Manual overrides** — merge two clusters that should be one person; the merge sticks.
- **100% offline** — no internet permission, no analytics, no third-party services beyond on-device ML Kit + TFLite.
- **Material You** — dynamic colors on Android 12+, dark theme, edge-to-edge.

## Architecture

### Tech stack

| Layer | Choice |
|---|---|
| Language | Kotlin 1.9 |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM, single ViewModel, `StateFlow` + `SharedFlow` |
| ML | Google ML Kit (face detection) + TensorFlow Lite (MobileFaceNet embeddings) |
| Persistence | Room 2.6 (`photos / faces / clusters / albums`) |
| Background | WorkManager 2.9 (foreground service, `dataSync` type) |
| Image loading | Coil |
| Logging / crash | Timber (local-only, debug builds) |
| Min / target SDK | API 26 (Android 8.0) / API 35 (Android 15) |

### Pipeline

```
WorkManager schedules FaceIndexWorker (foreground notification)
    ↓
PhotoRepository.queryPhotosModifiedSince(lastIndexed)
    ↓
For each photo (inside db.withTransaction):
    BitmapLoader (downscale + EXIF rotate)
    FaceDetectorWrapper.detectAllFaces (ML Kit, fast mode)
    For each face:
        FacePreprocessor (margin crop + 112×112 + normalize to [-1, 1])
        FaceEmbedder.getEmbedding (TFLite → 512-D L2-normalized vector)
        FaceClusterer.assign:
            best cluster by cosine similarity ≥ assign threshold → join + update centroid
            else → open a new singleton cluster
    ↓
End of batch: FaceClusterer.mergeClose()  // catches ordering effects
ClusterDao.deleteEmpty()
    ↓
PeopleScreen observes ClusterDao.summariesAtLeast(minSize) via Flow
ClusterDetailScreen lets you rename / merge / export
ClusterAlbumExportUseCase copies photos via PhotoRepository.copyToAlbumWithResult
    → Pictures/FaceAlbums/<Name>/
```

### Project layout

```
app/src/main/java/com/facealbum/
├── MainActivity.kt
├── MainViewModel.kt                    # cluster-based UI state, WorkManager bridge
├── FaceAlbumApp.kt                     # Timber init + periodic indexing
├── config/
│   └── FaceRecognitionConfig.kt        # thresholds, model input size, batch sizes
├── data/
│   ├── PhotoRepository.kt              # MediaStore queries + album export
│   ├── FaceDetectorWrapper.kt          # ML Kit wrapper (detectAllFaces / detectLargestFace)
│   ├── FaceEmbedder.kt                 # TFLite interpreter; degrades gracefully if model missing
│   └── db/
│       ├── FaceAlbumDatabase.kt
│       ├── PhotoEntity / PhotoDao
│       ├── FaceEntity / FaceDao
│       ├── ClusterEntity / ClusterDao (+ ClusterSummary projection)
│       ├── AlbumEntity / AlbumDao
│       └── Embeddings.kt               # FloatArray ↔ ByteArray serialization
├── domain/
│   ├── SimilarityMatcher.kt            # cosine similarity (pure)
│   ├── FaceClusterer.kt                # online assign + periodic merge
│   ├── FaceIndexUseCase.kt             # transactional per-photo indexing
│   └── ClusterAlbumExportUseCase.kt    # cluster → MediaStore export
├── work/
│   └── FaceIndexWorker.kt              # WorkManager + foreground notification
├── ui/
│   ├── screens/
│   │   ├── WelcomeScreen.kt
│   │   ├── PeopleScreen.kt             # cluster grid + progress
│   │   ├── ClusterDetailScreen.kt      # hero + photos + actions
│   │   ├── SettingsScreen.kt
│   │   └── ExportCompleteScreen.kt
│   └── theme/
│       ├── Color.kt                    # light + dark schemes
│       ├── Type.kt
│       ├── Spacing.kt                  # 4dp grid tokens
│       └── Theme.kt                    # Material You + edge-to-edge
├── navigation/
│   └── NavGraph.kt
├── telemetry/
│   └── CrashReporter.kt
└── util/
    ├── BitmapLoader.kt
    └── FacePreprocessor.kt
```

## Setup

> See [`INSTALL.md`](./INSTALL.md) for the full step-by-step install + first-run walkthrough (including how to source the TFLite model and verify the app works end-to-end). The notes below are a quick reference.

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 35
- Physical device on API 26+ (emulators work but are slow; ML Kit + TFLite + photo I/O all benefit from real hardware)

### 1. Clone

```bash
git clone <repo-url>
cd Gallery-Project
```

### 2. Drop in the TFLite model (required)

The app builds without the model, but indexing will fail at runtime with a
clear error. Put a MobileFaceNet weight at:

```
app/src/main/assets/mobile_face_net.tflite
```

**Contract** (matches `FaceRecognitionConfig` + `FaceEmbedder`):
- Input: `1 × 112 × 112 × 3` float32, normalized to `[-1, 1]`
- Output: `1 × 512` float32 (L2-normalized inside the app)

**Recommended (license-clean) sources:**
- [`sirius-ai/MobileFaceNet_TF`](https://github.com/sirius-ai/MobileFaceNet_TF) — MIT, exports directly to `.tflite`
- [`insightface`](https://github.com/deepinsight/insightface) ArcFace MobileFaceNet ONNX → convert with `tf2onnx` + `tflite_convert`

Record the SHA-256 and license in `app/src/main/assets/README_MODEL.txt`.

### 3. Build & run

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or just hit Run in Android Studio.

### 4. First launch

1. Grant photo access on the Welcome screen.
2. Indexing kicks off automatically as a foreground service — you'll see progress in the notification shade and as a banner in the app.
3. Once the first cluster reaches `minClusterSize` faces (default 3), it appears as a tile on the People screen.
4. Tap a tile → rename the person, merge with another cluster, or export an album.

## Usage tips

**Clustering quality**
- Default similarity thresholds (assign 0.6, merge 0.75) work well for MobileFaceNet weights trained on Asian / global faces. Tune in `FaceRecognitionConfig.kt` if you see over-merging or over-splitting.
- Bump `DEFAULT_MIN_CLUSTER_SIZE` in Settings to hide singletons.

**Performance**
- ~100 ms per photo on a mid-range device (Pixel 6 class): ~50 s for the most recent 500 photos, scales linearly.
- Indexing runs inside a foreground `dataSync` service so it survives screen-off but yields to the OS when battery is low.
- Tap *Re-scan entire library* in Settings to rebuild from scratch (e.g. after changing similarity thresholds).

**Privacy**
- Network: the app declares no `INTERNET` permission and depends on no SDK that adds one. Everything runs on-device.
- Storage writes: limited to `Pictures/FaceAlbums/<Name>/` via scoped `MediaStore` inserts. The app cannot modify any other directory.

## Database

| Table | Purpose |
|---|---|
| `photos` | One row per inspected photo (MediaStore id, dateModified, face count). Used for incremental scans. |
| `faces` | One row per detected face — bounding box, embedding (BLOB, 512 floats little-endian), quality, FK to cluster. |
| `clusters` | One row per person — display name (nullable until tagged), running-mean centroid, cover face, face count. |
| `albums` | History of exports — which cluster was exported to which path, when, how many photos. |
| `export_operations` / `export_items` | Per-file transaction log for the export pipeline (source path, filename, per-item outcome) — enables resume/verify/undo and is cleared by Settings → "Delete face data" along with `photos`/`faces`/`clusters`. |

Schema files are exported under `app/schemas/` for migration safety.

## Testing

```bash
./gradlew test
```

- `FaceClustererTest` — Robolectric + in-memory Room. Covers assign / dissimilar-split / `mergeClose` / `mergeUserRequested`.
- `PhotoRepositoryTest` — MockK over the MediaStore copy pipeline; verifies mime detection, source-open failure, finalize failure, rollback, unique naming.
- `SimilarityMatcherTest`, `FaceRecognitionConfigTest` — pure-JVM smoke tests.

The full end-to-end loop has to be exercised on a real device — see *Release acceptance* below.

## Release build & distribution

### Release optimization policy
- `release` builds must keep `isMinifyEnabled = true` and `isShrinkResources = true` (in `app/build.gradle.kts`).
- Any shrink-related regression must be fixed by updating `app/proguard-rules.pro` — never by disabling minification.
- Keep rules currently cover ML Kit, TensorFlow Lite, Compose metadata, Room entities/DAOs, and WorkManager workers.

### Secure signing workflow (keystore outside repo)
- Never commit keystore files or plaintext signing passwords.
- Configure signing from environment / CI secrets only — these are the exact
  names `app/build.gradle.kts` reads (`signingConfigs.release` +
  `decodeReleaseKeystore` + `verifyReleaseSigningConfigured`):
  - `ANDROID_KEYSTORE_BASE64` — the release `.jks`/`.keystore` file, base64-encoded.
  - `ANDROID_STORE_PASSWORD` — keystore password.
  - `ANDROID_KEY_ALIAS` — signing key alias inside the keystore.
  - `ANDROID_KEY_PASSWORD` — that key's password.
- To produce `ANDROID_KEYSTORE_BASE64` from an existing keystore file:
  ```bash
  base64 -w0 release.keystore > release.keystore.b64   # Linux
  base64 -i release.keystore -o release.keystore.b64   # macOS
  ```
  Store the contents of the `.b64` file as the `ANDROID_KEYSTORE_BASE64` secret
  (CI secret store, or your shell env for a local signed build) — never commit it.
- At build time, `decodeReleaseKeystore` decodes that secret into
  `app/build/keystore.jks` (inside the ignored `build/` directory, recreated
  every run) only when an actual release task executes — not on every
  test/lint/IDE-sync. `verifyReleaseSigningConfigured` then fails the build
  immediately if `ANDROID_KEYSTORE_BASE64` is unset, before any signing is
  attempted.
- CI decodes the keystore at runtime, signs the `release` build, then the
  temp keystore is discarded along with the rest of the ephemeral build
  workspace at the end of the job.
- `assembleRelease`/`bundleRelease`/`packageRelease` all refuse to run when
  `ANDROID_KEYSTORE_BASE64` is unset — a release artifact is never silently
  debug-signed. (Other tasks — `test`, `lint`, `assembleDebug` — are unaffected
  and don't require any of these variables.)
- Local one-off signed build, once the four variables above are exported in
  your shell:
  ```bash
  ANDROID_KEYSTORE_BASE64=$(cat release.keystore.b64) \
  ANDROID_STORE_PASSWORD=... \
  ANDROID_KEY_ALIAS=... \
  ANDROID_KEY_PASSWORD=... \
  ./gradlew bundleRelease
  ```

### Versioning
- `versionCode` increments for every distributable build.
- `versionName` follows `MAJOR.MINOR.PATCH`:
  - `PATCH` — bugfix / internal change, no behavior change.
  - `MINOR` — backward-compatible feature addition.
  - `MAJOR` — incompatible UX or behavior changes.

### CI artifact archival
For every release pipeline run, publish and retain:
- Signed `.aab` (required for Play Store)
- Optional signed universal `.apk`
- `mapping.txt` from R8
- SHA-256 checksums for every artifact
- Build metadata (`git sha`, `versionCode`, `versionName`, build timestamp)

### Release acceptance criteria
- CI produces a signed release AAB.
- Manual smoke on a real device:
  1. Fresh install → permission grant → indexing progress visible.
  2. After ~100 photos, ≥1 well-formed cluster appears; clearly different people stay separate.
  3. Rename a cluster, kill the app, re-open — name persists.
  4. Export an album → photo count matches; album visible in Google Photos under `FaceAlbums/<Name>/`.
  5. Add a new photo to the device → next scan picks it up incrementally.
  6. Airplane mode the whole time → zero crashes, no failed UI states.

## Privacy & security

- **No internet**: face detection, embedding, clustering, and export all run on-device. No `INTERNET` permission is declared, and no dependency merges one in.
- **No analytics, no crash telemetry**: failure reporting is local-only (Timber in debug builds; persisted failure records in the app database).
- **No backup exfiltration**: `android:allowBackup="false"`, plus `dataExtractionRules`/`fullBackupContent` excluding the database and DataStore as defence in depth — face embeddings and person names never ride Android Auto Backup to Google Drive.
- **Minimal permissions**: `READ_MEDIA_IMAGES` (Android 13+) / `READ_MEDIA_VISUAL_USER_SELECTED` (Android 14+ partial library grant) / `READ_EXTERNAL_STORAGE` (≤32) for reading the library, `POST_NOTIFICATIONS` for the indexer's foreground-service notification, and the foreground-service permissions WorkManager requires.
- **Scoped writes**: only `Pictures/FaceAlbums/<Name>/` via `MediaStore`. The app cannot touch any other folder.
- **Open source**: every line of the pipeline is auditable in this repo.

See [`docs/release/compliance.md`](./docs/release/compliance.md) for the full data-handling and Play Data safety writeup.

See [`docs/release/known-limitations.md`](./docs/release/known-limitations.md) for a truthful, source-verified list of what this release doesn't do or does with caveats (Move export, video, clustering accuracy, etc.).

## Roadmap

Next on the list (not yet implemented):
- [ ] On-device LLM-powered "describe this person" caption (experimental).
- [ ] Move/delete export (see `docs/release/known-limitations.md` — feature-flagged off pending the destructive-operation test suite).

Already shipped, despite once being roadmap items (kept here so this list stays
honest instead of re-drifting): moving a face to another person via *Move to
person…*, quality-based cover-face auto-upgrade, periodic background
re-indexing (`FaceIndexWorker`, every 12h), multi-photo selection + partial
export in cluster detail, and a Light/System/Dark theme toggle in Settings.

## Acknowledgments

- **Google ML Kit** — on-device face detection.
- **TensorFlow Lite** — efficient on-device inference.
- **MobileFaceNet** — compact face recognition model.
- **Jetpack Compose + Material 3** — UI toolkit.
- **Room, WorkManager, Coil, Timber** — the boring-but-excellent foundation.
