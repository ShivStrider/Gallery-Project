# Phase 0 — Repository Audit

Date: 2026-07-29 · Commit audited: `0bcd6b9` ("Merge pull request #19") · Branch: `claude/face-grouping-android-app-wa04nq` (restarted from `origin/main`)

This audit was produced by three independent inspection passes (structure/build, feature code, tests/privacy/quality) before any implementation work, per the master plan's execution rules.

## 1. Repository structure

Single Gradle module `:app`, root project **FaceAlbum**, package `com.facealbum`, ~6,550 LOC Kotlin across 51 files.

```
Gallery-Project/
├── .github/workflows/android.yml      CI: unit tests + lint only
├── README.md, INSTALL.md, PROJECT_AUDIT.md, ROADMAP.md
├── docs/ops/incident-triage-playbook.md
├── docs/release/{compliance,qa-matrix,store-assets}.md
├── build.gradle.kts, settings.gradle.kts, gradle.properties
└── app/
    ├── build.gradle.kts, proguard-rules.pro
    ├── schemas/com.facealbum.data.db.FaceAlbumDatabase/{1,2,3}.json
    └── src/{main,test,androidTest}
```

Source packages (layer-first): `config/`, `data/` (+`db/`, `prefs/`), `domain/`, `model/`, `navigation/`, `telemetry/`, `ui/` (+`screens/`, `theme/`), `util/`, `work/`; `MainActivity`/`MainViewModel`/`FaceAlbumApp` at root.

## 2. Current build status

- Gradle 8.13 / AGP 8.13.2 / Kotlin 1.9.20 / KSP 1.9.20-1.0.14 / Compose compiler 1.5.4 (correct pairing).
- compileSdk/targetSdk **35**, minSdk **26**, Java/jvmTarget **17**.
- **Local builds fail on a fresh clone**: the `com.google.gms.google-services` plugin is applied unconditionally and `app/google-services.json` is absent (gitignored). CI works only because it heredocs a stub file.
- **Release builds fail by design**: `verifyFaceModelPresent` gates `assembleRelease` on `app/src/main/assets/mobile_face_net.tflite`, which is not in the repo (only `README_MODEL.txt`).
- Release signing **silently falls back to debug signing** when `ANDROID_KEYSTORE_BASE64` is unset.
- CI never assembles an APK (`test` + `lint` only) and never compiles the androidTest source set.
- No version catalog; all dependency versions are hardcoded strings in `app/build.gradle.kts`. `android.enableJetifier=true` is legacy cruft nothing needs.

## 3. Existing features (verified working paths)

The product pipeline exists end-to-end:

| Stage | Implementation |
|---|---|
| Discovery | `PhotoRepository.queryPhotosModifiedSince()` — incremental MediaStore delta, `image/%` only |
| Decode | `BitmapLoader` — 2-pass decode, 1024 px cap, EXIF orientation |
| Detection | `FaceDetectorWrapper` — ML Kit face-detection 16.1.6, FAST mode, minFaceSize 0.15 |
| Embedding | `FaceEmbedder` — TFLite MobileFaceNet 112×112→512-D, L2-normalized, `ModelState` graceful degradation |
| Clustering | `FaceClusterer` — online centroid assignment (cosine ≥ 0.6 assign / ≥ 0.75 merge), user merge, recompute |
| Persistence | Room v3: `photos`, `faces` (embedding BLOBs), `clusters`, `albums`, `scan_sessions`; tested migrations 1→2→3 |
| Background | `FaceIndexWorker`, `ReclusterWorker` — foreground dataSync, progress, resumable, 12 h periodic re-index |
| Review UI | Compose M3: Welcome (permissions) → People grid → ClusterDetail (rename/merge/reassign/multi-select/export) → ImageViewer; Settings (thresholds, theme, delete face data); ExportComplete |
| Export | **Copy-only**: `PhotoRepository.copyToAlbumWithResult()` (IS_PENDING insert into `Pictures/FaceAlbums/<album>`, typed failures, partial-row rollback, idempotent dedup naming) driven by `ClusterAlbumExportUseCase.export()/exportPartial()` |

## 4. Existing architectural patterns

MVVM (one activity-scoped God `MainViewModel`, 357 LOC), partial repository pattern (`PhotoRepository` wraps MediaStore; DAOs are accessed directly elsewhere), three domain use cases, coroutines + Flow throughout, **no DI framework** (constructor defaults + two `@Volatile` singletons), zero XML layouts.

## 5. Existing face detection and recognition code

See §3. Detection (ML Kit) and recognition (TFLite embedding + clustering) are correctly separated. No MediaPipe, no cloud ML, no seed/identification flow (a former seed-photo design was removed in DB migration 2→3; fossils remain in docs).

## 6. Existing storage implementation

MediaStore-only. No Storage Access Framework, no Photo Picker, no `READ_MEDIA_VISUAL_USER_SELECTED` (Android 14 partial access unhandled). Permissions: `READ_MEDIA_IMAGES`, `READ_EXTERNAL_STORAGE` (≤32), `POST_NOTIFICATIONS` (declared, **never requested at runtime**), `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`.

## 7. Existing database schema

Room v3, `exportSchema = true`, schemas committed. Entities: `PhotoEntity` (unique `mediaStoreId`), `FaceEntity` (FK photo CASCADE, FK cluster SET_NULL, 512-float LE BLOB), `ClusterEntity` (centroid BLOB, cover face), `AlbumEntity` (export history, FK cluster SET_NULL), `ScanSessionEntity`. Insert strategy deliberately ABORT (not REPLACE) to protect against cascade deletes.

## 8. Existing user interface

Six screens, Navigation-Compose typed routes, Material You dynamic color, light/dark. Known UI defects: `ImageViewerScreen` zoom state bleeds between pages (missing `remember` keys); `ClusterDetailScreen` is 740 LOC / 15 composables in one file; Settings back button reuses the person-detail back-label string.

## 9. Existing tests

- **JVM (6 files, 709 LOC, run in CI, all real)**: `SimilarityMatcherTest`, `FaceClustererTest` (Robolectric + in-memory Room), `ReclusterUseCaseTest`, `PhotoRepositoryTest` (MockK, rollback assertions), `FaceAlbumDatabaseMigrationTest` (MigrationTestHelper vs committed schemas — strongest file), `FaceRecognitionConfigTest`.
- **Instrumented (2 files, 275 LOC)**: `PeopleScreenTest`, `ClusterDetailScreenTest` — **cannot compile**: both import nonexistent `androidx.test.ext.junit4.runners.AndroidJUnit4`; CI never compiles this source set, so it went unnoticed.
- Untested core: `FaceIndexUseCase` (148-line `run()`), `MainViewModel`, `ClusterAlbumExportUseCase`, `Embeddings` round-trip, `BitmapLoader`, `FacePreprocessor`, both workers. No coverage tooling.

## 10. Duplicate or dead code

- Dead: `PhotoRepository.queryRecentPhotos()`, `PhotoRepository.copyToAlbum()`, `FaceDetectorWrapper.detectLargestFace()`, `SimilarityMatcher.isMatch()` (test-only), `PhotoDao.count()`, `FaceDao.delete(faceId)`, `AlbumDao` read methods (history written, never read), `MainViewModel.cancelIndex()` (no UI surface), `UserPreferences.setMergeThreshold()` (no UI), `FaceRecognitionConfig.DEFAULT_SIMILARITY_THRESHOLD`/`SCAN_BATCH_SIZE`, ~23 unused string resources (incl. 14 legacy `cluster_*` aliases).
- Duplicate: `ClusterDao.clear()` ≡ `deleteAll()`; ~60 lines duplicated between `export()`/`exportPartial()`; `IndexProgress`/`ReclusterProgress` near-twins; `SectionHeader` composable duplicated in two screens.
- **Not** dead despite appearances: `scan_sessions` — `markOrphansCancelled()` is used by `FaceIndexUseCase.run()`. Table stays.

## 11. Security or privacy concerns (ranked)

1. **`allowBackup="true"` with no backup rules** — the Room DB (face embeddings = biometric-derived data, photo URIs, person names) is eligible for Google Drive Auto Backup, contradicting the app's core "stays on this device" claim.
2. **Firebase Crashlytics ships** while README/compliance.md/Welcome screen claim "no internet, no telemetry". Its AAR merges `INTERNET` + `ACCESS_NETWORK_STATE` into the final APK. Collection is **inverted** (`isInternalBuild = BuildConfig.DEBUG`): on in debug, off in every release — the app pays the permission and SDK weight for dead functionality.
3. **`docs/release/compliance.md` Play Data-safety answers are factually wrong** ("Diagnostics: No", "no third-party analytics SDK") and would be filed with Google as-is.
4. Debug Timber logs include photo URIs and album names (safe today only because planting is debug-gated; nothing enforces that).
5. No in-app privacy policy surface (`settings_privacy_link` string exists, unwired) — a Play blocker for biometric-adjacent apps.
6. No LICENSE file despite "open source" README claim.

## 12. Build warnings

Not yet measured locally (build blocked by missing `google-services.json`; see §2). To be captured in Phase 0 build recovery after the Firebase removal unblocks configuration.

## 13. Dependency risks

- Firebase BOM 34.0.0 (to be removed — see decision log D1).
- TFLite 2.14.0 classic artifacts (not LiteRT); acceptable for now, migration is a reversible later decision.
- Compose BOM 2024.02 / Kotlin 1.9.20 are ~2 years old but internally consistent; upgrading is out of scope for MVP (churn without user value).
- ML Kit face-detection 16.1.6 bundled model: adds ~7 MB APK, fully offline — correct choice for privacy posture.

## 14. Features that should be removed

- Firebase Crashlytics + google-services plugin + `telemetry/CrashReporter` network path (D1). Nothing else is out of scope: no social/cloud/editing/video code exists.

## 15. Components that can be retained

Everything in §3 is retained. Reused untouched: `FaceEmbedder`/`ModelState`, `BitmapLoader`, `FacePreprocessor`, `SimilarityMatcher`, `Embeddings`, migrations 1→2/2→3, `ReclusterWorker`/`ReclusterUseCase`, `AlbumEntity`/`AlbumDao`, `ScanSession*`, `UserPreferences`, theme, `PeopleScreen`/`ImageViewerScreen`/`SettingsScreen`.

## 16. Gap analysis against the required product

| Requirement | Status | Gap |
|---|---|---|
| Scan local photos (MediaStore, incremental, resumable) | ✅ | Deletion/change reconciliation missing; Android 14 partial access unhandled |
| Detect faces on-device | ✅ | — |
| Group by person | ✅ | Scaling bottlenecks (per-face full centroid reads, O(n²) merge restarts); no "review needed/unassigned" uncertainty group |
| Review/merge/split/rename/select | ✅ | No pre-export preview of exact files; no unassigned surface |
| **Export by MOVING selected files** | ❌ | **Copy-only today. Needs verified move: copy → verify (size+checksum+readable) → consented source delete (`MediaStore.createDeleteRequest`, API 30+), per-file Room transaction log, resume, undo. The single largest work item.** |
| ~10 GB library performance | ⚠️ | Pipeline is bounded, but clusterer read amplification and `findByIds` >999 break at scale; no benchmarks |
| 100% local / no cloud | ⚠️ | Crashlytics INTERNET + allowBackup contradict it |
| Safe destructive operations | ❌ | No destructive-op test suite; no move machinery to test yet |
| Reproducible build | ❌ | google-services.json + model asset + silent debug signing |
| Honest docs/compliance | ❌ | 11 concrete doc-drift instances, wrong Data-safety answers |

**Conclusion:** this is a gap-closure project, not a rebuild. The critical path is: build recovery (Firebase removal) → verified move-export with transaction log → privacy hardening → scale fixes → destructive-op test gate → release.
