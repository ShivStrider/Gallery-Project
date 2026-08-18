# Testing Plan

CI (GitHub Actions) is the enforcement point: `test` (JVM incl. Robolectric), `lint`, `assembleDebug`, `assembleDebugAndroidTest` on every push. Instrumented runs on emulator are executed for release candidates (no emulator step in per-push CI for cost; compile-check catches rot). No personal photos anywhere in tests — synthetic images and seeded embeddings only.

## Unit tests (JVM, `app/src/test`)

Existing (kept): `SimilarityMatcherTest`, `FaceClustererTest`, `ReclusterUseCaseTest`, `PhotoRepositoryTest`, `FaceAlbumDatabaseMigrationTest`, `FaceRecognitionConfigTest`.

To add, by phase:
- **P3**: `EmbeddingsTest` (FloatArray↔ByteArray round-trip, endianness, NaN safety); `FacePreprocessorTest` (crop margins, clamp at photo edges, normalization range); `BitmapLoaderTest` (inSampleSize math).
- **P4**: `FaceClustererCacheTest` (cache vs DAO parity), `MergeCloseParityTest` (new merge loop ≡ old semantics), clustering benchmark guards at 100/1k/5k embeddings; `PhotoDaoChunkingTest` (2 500 ids).
- **P6**: `ExportPlanTest` (membership intersection, collision naming, preview counts), `ExportStateMachineTest` (every legal/illegal transition), `ExportVerifyTest` (size/checksum mismatch paths), `ExportWorkerResumeTest` (Robolectric: kill between transitions, re-run, assert convergence), `UndoTest`.
- Failure/retry: worker retry classification (`ModelNotReadyException` → failure; transient → retry) — extracted logic tested directly.

## Integration tests (JVM + Robolectric)

- Discovery→DB: mocked ContentResolver cursor → photos rows (incl. duplicate MediaStore records, deleted-photo reconciliation, >999-id chunking).
- Embedding→group→export plan→verified move: in-memory Room end-to-end with fake file streams.
- Interrupted scan recovery (existing scan-session orphan handling) and interrupted export recovery (6.7 suite).

## Android instrumentation tests (`app/src/androidTest`)

Existing Compose tests (kept, now compiling): `PeopleScreenTest`, `ClusterDetailScreenTest`.
To add: permission flow (full/partial/denied on API 33/34), export preview + confirm dialog behaviour, consent-banner resume UI, process recreation of the export flow, DB migration 3→4 on-device, rotation of ClusterDetail with active selection.

## Destructive-operation suite (release gate for Move mode)

See `05-safe-export-design.md` §Destructive-operation test suite. Runs in CI as JVM/Robolectric tests against temp directories plus an instrumented variant for MediaStore semantics on emulator before any release. Move mode's feature flag flips only when this suite is green.

## Manual QA

`docs/release/qa-matrix.md` (updated in Phase 8 to drop the obsolete seed-photo section): API 29 / 33 / 34 / 35 devices; verify Move absent below API 30; 10 GB library soak test per `07-performance-plan.md`.
