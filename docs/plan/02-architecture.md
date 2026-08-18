# Architecture & Data Flow

## Approach

The existing architecture is retained: single `:app` module, layer-first packages, MVVM with Compose, coroutines + Flow, Room, WorkManager, manual dependency wiring (no DI framework — it would add indirection without value at this size). No rewrites; changes are targeted.

## Layer boundaries

| Concern | Owner |
|---|---|
| Media discovery | `data/PhotoRepository` (MediaStore only; no SAF needed — exports write to `Pictures/FaceAlbums/` which the app owns via MediaStore) |
| Image decoding | `util/BitmapLoader` (bounded 2-pass decode + EXIF orientation) |
| Face detection | `data/FaceDetectorWrapper` (ML Kit; detection only — no identity) |
| Embedding generation | `data/FaceEmbedder` (TFLite MobileFaceNet; `ModelState` degradation) + `util/FaceAligner` (5-point similarity transform onto the ArcFace template) with `util/FacePreprocessor` as the no-landmarks fallback |
| Clustering | `domain/FaceClusterer`, `domain/SimilarityMatcher`, `domain/ReclusterUseCase` |
| Persistence | `data/db/*` (Room **v4**, current), `data/prefs/UserPreferences` (DataStore) |
| Review UI | `ui/screens/*`, `ui/components/PhotoMetadataSheet` (self-contained, loads its own MediaStore detail), `MainViewModel`, `ui/ExportViewModel` |
| Formatting | `util/ByteFormat` (decimal/SI, matching `Formatter.formatFileSize`), `util/DateFormatting` |
| File operations | `data/PhotoRepository` (copy/verify/delete/date primitives) + `domain/ExportPlanner` / `ExportExecutor` / `ExportConsent` / `ExportUndoUseCase` (orchestration) + `domain/ExportDateRepairUseCase` (post-hoc MediaStore date repair) |
| Background jobs | `work/FaceIndexWorker`, `work/ReclusterWorker`, `work/ExportWorker` |
| Error reporting | `telemetry/CrashReporter` (local-only Timber) + persisted failure state (`scan_sessions`, `export_items.errorCode`) |

## End-to-end data flow

```
MediaStore ──query delta──> photos table
photos ──decode(≤1024px, EXIF)──> Bitmap ──ML Kit──> face boxes
face boxes ──align 5-point → 112×112──> TFLite ──128-D embedding──> faces table (BLOB)
embedding ──cosine vs cached centroids──> cluster assign/create ──> clusters table
clusters ──mergeClose (chain-guarded) [+ refineAssignments on full recluster]──> clusters
clusters ──Flow──> People grid ──user review (rename/merge/reassign/select)──>
export plan (exact file list) ──preview+confirm──> export_operations/export_items
ExportWorker: copy──>verify(size+sha256+readable)──> [move mode] system consent
dialog ──> source delete confirmed ──> report (+undo path from the log)
```

Pipeline concurrency (existing, retained): decode+detect at concurrency 2 (1 on low-RAM devices) → `buffer(2)` → strictly serial embed+persist (TFLite `Interpreter` is not thread-safe; centroid updates must not race). Inference now runs *outside* the Room write transaction (Phase 3, done) so the UI's reactive queries aren't blocked behind the model.

**Embedding-generation coupling.** Detector configuration and embedding are not independent: `FaceAligner` requires ML Kit landmarks, so `FaceDetectorWrapper` must run with `LANDMARK_MODE_ALL`. Changing the detector to FAST mode silently degrades grouping rather than failing — the crops still produce embeddings, they just stop measuring identity. A pipeline-version guard wipes faces/clusters and resets the per-photo watermark whenever this contract changes, because embeddings from two generations are not comparable and an incremental scan would otherwise mix them.

## Key architectural rules

1. DAOs may be used directly by domain classes (existing pattern) — no new repository layers for their own sake.
2. Every destructive file action lives behind the export use cases + the `export_items` state machine; UI never calls delete primitives directly.
3. Source deletion is foreground-only (`MediaStore.createDeleteRequest`) — never from a worker. The app cannot and must not silently delete media it does not own.
4. Workers construct their dependencies exactly as `FaceIndexWorker` does today; new workers copy that pattern.
5. All tunables stay in `config/FaceRecognitionConfig` / `UserPreferences` — no scattered magic numbers.
