# Architecture & Data Flow

## Approach

The existing architecture is retained: single `:app` module, layer-first packages, MVVM with Compose, coroutines + Flow, Room, WorkManager, manual dependency wiring (no DI framework — it would add indirection without value at this size). No rewrites; changes are targeted.

## Layer boundaries

| Concern | Owner |
|---|---|
| Media discovery | `data/PhotoRepository` (MediaStore only; no SAF needed — exports write to `Pictures/FaceAlbums/` which the app owns via MediaStore) |
| Image decoding | `util/BitmapLoader` (bounded 2-pass decode + EXIF orientation) |
| Face detection | `data/FaceDetectorWrapper` (ML Kit; detection only — no identity) |
| Embedding generation | `data/FaceEmbedder` (TFLite MobileFaceNet; `ModelState` degradation) + `util/FacePreprocessor` |
| Clustering | `domain/FaceClusterer`, `domain/SimilarityMatcher`, `domain/ReclusterUseCase` |
| Persistence | `data/db/*` (Room v4 after Phase 6), `data/prefs/UserPreferences` (DataStore) |
| Review UI | `ui/screens/*`, `MainViewModel` (+ new `ExportViewModel` in Phase 5/6) |
| File operations | `data/PhotoRepository` (copy/verify/delete primitives) + `domain/ExportMoveUseCase` (orchestration) |
| Background jobs | `work/FaceIndexWorker`, `work/ReclusterWorker`, new `work/ExportWorker` |
| Error reporting | `telemetry/CrashReporter` (local-only Timber) + persisted failure state (`scan_sessions`, `export_items.errorCode`) |

## End-to-end data flow

```
MediaStore ──query delta──> photos table
photos ──decode(≤1024px, EXIF)──> Bitmap ──ML Kit──> face boxes
face boxes ──crop 112×112──> TFLite ──512-D embedding──> faces table (BLOB)
embedding ──cosine vs cached centroids──> cluster assign/create ──> clusters table
clusters ──Flow──> People grid ──user review (rename/merge/reassign/select)──>
export plan (exact file list) ──preview+confirm──> export_operations/export_items
ExportWorker: copy──>verify(size+sha256+readable)──> [move mode] system consent
dialog ──> source delete confirmed ──> report (+undo path from the log)
```

Pipeline concurrency (existing, retained): decode+detect at concurrency 2 (1 on low-RAM devices) → `buffer(2)` → strictly serial embed+persist (TFLite `Interpreter` is not thread-safe; centroid updates must not race). Phase 3 change: inference moves *outside* the Room write transaction so the UI's reactive queries aren't blocked behind the model.

## Key architectural rules

1. DAOs may be used directly by domain classes (existing pattern) — no new repository layers for their own sake.
2. Every destructive file action lives behind `ExportMoveUseCase` + the `export_items` state machine; UI never calls delete primitives directly.
3. Source deletion is foreground-only (`MediaStore.createDeleteRequest`) — never from a worker. The app cannot and must not silently delete media it does not own.
4. Workers construct their dependencies exactly as `FaceIndexWorker` does today; new workers copy that pattern.
5. All tunables stay in `config/FaceRecognitionConfig` / `UserPreferences` — no scattered magic numbers.
