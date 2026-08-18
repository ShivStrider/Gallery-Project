# Testing Plan

CI (GitHub Actions) is the enforcement point: `test` (JVM incl. Robolectric), `lint`, `assembleDebug`, `assembleDebugAndroidTest` on every push.

**CI is currently the only verifier of anything in this repository.** No build
here has run on physical hardware. Treat every claim below as "passes in CI",
not "works on a phone". Instrumented runs on emulator are executed for release candidates (no emulator step in per-push CI for cost; compile-check catches rot). No personal photos anywhere in tests — synthetic images and seeded embeddings only.

## Unit tests (JVM, `app/src/test`)

**Current: 24 suites / 171 tests, all green.** The planned suites below have
landed; names differ from the original plan where the work consolidated.

| Suite | Covers |
|---|---|
| `SimilarityMatcherTest`, `FaceRecognitionConfigTest` | Cosine maths; the merge > assign threshold invariant |
| `FaceClustererTest` | assign, dissimilar-split, `mergeClose`, `mergeUserRequested`, refinement + hysteresis, anti-chaining guard |
| `ReclusterUseCaseTest`, `MergeCandidatePoolTest`, `ClusterDaoReviewNeededTest` | Full rebuild; merge candidacy; the review-needed band |
| `ClusteringBenchmarkTest` | Cost model at 100/1k/5k. Asserts **read counts**, not wall-clock |
| `FaceAlignerTest` | Similarity-transform maths, landmark ordering, fallback when landmarks are absent |
| `FacePreprocessorTest`, `EmbeddingsTest`, `BitmapLoaderTest` | Crop/normalisation range; BLOB round-trip + endianness; `inSampleSize` |
| `PhotoRepositoryTest` | Copy pipeline failures and rollback; unique naming; the `DATE_TAKEN`-ms / `DATE_MODIFIED`-s split; chunked size reads |
| `PhotoDaoChunkingTest` | >999-id SQLite variable limit |
| `ExportPlannerTest`, `ExportExecutorTest`, `ExportConsentUseCaseTest`, `ExportUndoUseCaseTest`, `ExportDaoTest` | Plan/execute/consent/undo and the per-file log |
| `DestructiveExportSafetyTest` | **The Move-mode release gate** — see below |
| `ExportDateRepairUseCaseTest` | Post-hoc date repair: source recovery, EXIF fallback, idempotence, refusal to guess |
| `FaceIndexReconcileTest`, `FaceIndexPipelineVersionGuardTest` | Deleted-photo reconciliation; wipe-and-reindex when the embedding contract changes |
| `FaceAlbumDatabaseMigrationTest` | Every migration path against the committed schemas |
| `ByteFormatTest` | Size formatting incl. boundaries and unknown/negative |

### Two failure modes this suite has actually hit

Recorded because both produce a *green* build while proving nothing:

1. **`@Before`/`@Test` with an expression body.** `fun setUp() = runBlocking { … }`
   returns non-Unit, so JUnit throws `InvalidTestClassError` and silently
   swallows the entire class, reporting it as one passing test. Always use block
   bodies. (`= runTest { … }` is safe: `TestResult` is a `Unit` typealias on JVM.)
2. **A benchmark whose fixture is too clean.** The refinement benchmark first
   passed with `movedFirstPass=0` — the synthetic identities separate so well
   that greedy assign was already perfect, so the pass hit an early return and
   never reached the code under test. A benchmark for a corrective pass must
   introduce the defect it corrects.

Also note `ClusteringBenchmarkTest.CountingClusterDao` is the only hand-written
`ClusterDao` implementor: adding a method to that interface without adding a
delegating override there fails the **entire** unit-test source set to compile,
which surfaces as `suites=0` rather than a localised failure.

## Integration tests (JVM + Robolectric)

- Discovery→DB: mocked ContentResolver cursor → photos rows (incl. duplicate MediaStore records, deleted-photo reconciliation, >999-id chunking).
- Embedding→group→export plan→verified move: in-memory Room end-to-end with fake file streams.
- Interrupted scan recovery (existing scan-session orphan handling) and interrupted export recovery (6.7 suite).

## Android instrumentation tests (`app/src/androidTest`)

Present and compiling in CI: `PeopleScreenTest`, `ClusterDetailScreenTest`,
`ExportPreviewSheetTest`. CI compiles these on every push
(`assembleDebugAndroidTest`) but does **not execute** them — there is no
emulator step.

Still to add: permission flow (full/partial/denied on API 33/34), consent-banner
resume UI, process recreation of the export flow, DB migration on-device,
rotation of ClusterDetail with active selection, and the viewer's gesture
arbitration (pager swipe vs pinch vs drag-to-dismiss), which is not meaningfully
testable on the JVM.

## Destructive-operation suite (release gate for Move mode)

See `05-safe-export-design.md` §Destructive-operation test suite. Runs in CI as JVM/Robolectric tests against temp directories plus an instrumented variant for MediaStore semantics on emulator before any release. Move mode's feature flag flips only when this suite is green.

## Manual QA

`docs/release/qa-matrix.md` (updated in Phase 8 to drop the obsolete seed-photo section): API 29 / 33 / 34 / 35 devices; verify Move absent below API 30; 10 GB library soak test per `07-performance-plan.md`.
