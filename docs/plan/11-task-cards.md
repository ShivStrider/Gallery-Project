# Task Cards & Agent Handoff Protocol

## Handoff protocol

Every unit follows: Opus inspects + writes the contract (referencing these docs) → Sonnet implements one card → Sonnet runs the card's commands and reports real output → Opus reviews (correctness, privacy, performance, complexity) → Sonnet fixes → commit at a stable checkpoint. One agent per file at a time; read the whole file and its callers before modifying; reuse before creating; no parallel implementations. After changes: build (CI), lint, unit tests, summarize modified files and unresolved warnings. Environment note: this dev sandbox has no Android SDK (network policy) — "Commands" run in CI on push; a card is done only when its CI run is green.

Standard commands (all cards, via CI): `./gradlew test lint assembleDebug assembleDebugAndroidTest`.
Standard failure rule (all cards): red CI → fix forward on the branch before starting the next card; never claim a pass without the run.

## Status

Verified against the source, not against memory of what was intended.

| Phase | State |
|---|---|
| P0 audit / Firebase removal / signing gate / CI assemble | **done** |
| P1 docs 01–11 | **done** (and kept current — this pass) |
| P2 partial photo access, notifications, reconcile, dead code | **done** (`util/PhotoAccess.kt`, `FaceIndexReconcileTest`) |
| P3 inference outside the write transaction, lazy model init, unit tests | **done** |
| P4 centroid cache, restart-free `mergeClose`, review-needed band, id chunking, benchmarks | **done** (`ReviewNeededScreen`, `ClusteringBenchmarkTest`, `PhotoDaoChunkingTest`) |
| P5 ClusterDetail split, export preview, viewer zoom-state keys | **done** (`ui/screens/clusterdetail/*`, `ExportPreviewSheet`) |
| P6 export log, planner/executor/consent/undo, worker, destructive suite | **done**; Move remains flag-gated by design |
| P7 benchmarks + measured numbers | **partial** — cost model recorded in `07-performance-plan.md`; the 10 GB soak test and memory-ceiling measurement need a device |
| P8 `allowBackup=false`, no INTERNET, log-identifier guard, compliance rewrite | **done**; **LICENSE file still missing** |
| P9 model asset bundled + checksum-pinned | **partial** — no `assembleRelease` CI job, and detekt was reverted (see D15 correction) |

### Unplanned work, from user-reported defects

None of this was in the original phase plan; all of it came from using the app.

| Item | State |
|---|---|
| Faces aligned before embedding (`util/FaceAligner`) — the actual cause of bad grouping | done |
| Export copies keep their original capture date | done |
| Order-independent `refineAssignments` + anti-chaining merge guard | done |
| Viewer horizontal swipe (gesture arbitration rewrite) | done, **unverified on hardware** |
| Photo metadata sheet, per-photo and album sizes | done |
| Repair pass for albums exported before the date fix | done |
| minSdk 26 → 29, because export could never work below 29 | done (D21) |

### Still open

- LICENSE file (P8.2 listed it; never added).
- `assembleRelease` CI job with signing from secrets (P9.1).
- Any on-device validation at all — see `docs/release/known-limitations.md`.
- Accuracy measurement against a labelled dataset; the clustering constants in
  D18/D19 are reasoned, not tuned.

---

---

### Task P2.1: Android 14 partial photo access
Owner: Sonnet · Reviewer: Opus
Objective: honor `READ_MEDIA_VISUAL_USER_SELECTED`; UI acknowledges limited access.
Reason: on Android 14 a "select photos" grant silently hides most of the library today.
Dependencies: none. Inspect: `AndroidManifest.xml`, `ui/screens/WelcomeScreen.kt`, `ui/screens/PeopleScreen.kt`, `MainViewModel.kt`. Modify: those four.
Steps: (1) declare `READ_MEDIA_VISUAL_USER_SELECTED`; (2) Welcome requests IMAGES+VISUAL_USER_SELECTED on 34+, treats partial grant as granted; (3) People shows a "Limited access — manage selection" banner when partial, deep-linking to the system reselect flow.
Tests: instrumentation permission-state rendering; manual on API 34 emulator (full/partial/denied).
Acceptance: partial grant scans the selected subset, banner visible, no dead-end; full grant unchanged.
Commit: `feat: handle Android 14 partial photo access`

### Task P2.2: POST_NOTIFICATIONS runtime request
Owner: Sonnet · Reviewer: Opus
Objective: request notification permission in context before the first scan on 33+.
Reason: declared but never requested — the foreground scan notification is silently dropped.
Inspect/Modify: `PeopleScreen.kt` (scan FAB path) or `MainViewModel.startIndex` trigger point, `WelcomeScreen.kt`.
Acceptance: first scan on 33+ prompts once; denial still scans (progress visible in-app); no re-prompt loops.
Commit: `feat: request notification permission before first scan`

### Task P2.3: MediaStore reconciliation
Owner: Sonnet · Reviewer: Opus (contract: deletion semantics)
Objective: detect photos deleted/changed outside the app; clean DB and clusters accordingly.
Inspect: `PhotoRepository`, `FaceIndexUseCase`, `PhotoDao`, `FaceClusterer.recomputeFromFaces`. Modify: `FaceIndexUseCase` (reconcile step at scan start), `PhotoDao` (id/dateModified projection query).
Steps: diff DB rows vs MediaStore ids; vanished → delete photo row (faces cascade) + recompute affected clusters; `dateModified` changed → re-index that photo (existing re-index path).
Tests: JVM with mocked cursor: vanish, change, unchanged; cluster recompute called for affected clusters only.
Acceptance: deleting a photo outside the app removes it from groups after next scan; no orphan faces.
Commit: `feat: reconcile library changes made outside the app`

### Task P2.4: Discovery dead-code removal
Owner: Sonnet · Reviewer: Opus
Delete `queryRecentPhotos`, `copyToAlbum`, `detectLargestFace`, duplicate `ClusterDao.deleteAll`, unused strings (keep `settings_privacy_link`). Acceptance: grep-clean, CI green. Commit: `chore: remove dead discovery/export code`

### Task P3.1: Inference out of Room transactions
Owner: Sonnet · Reviewer: Opus (perf review)
Objective: `indexFromDetection` computes all embeddings for a photo first, then one short `withTransaction` for writes + assignment.
Reason: a many-face photo currently holds the SQLite write lock through N model invocations, starving UI Flows.
Inspect/Modify: `domain/FaceIndexUseCase.kt` (lines ~248–346).
Tests: existing pipeline tests still pass; new test asserts DAO write ordering unchanged for re-indexed photos (old faces deleted, clusters recomputed).
Acceptance: no TFLite call inside `db.withTransaction`; behavior identical.
Commit: `perf: keep ML inference outside Room write transactions`

### Task P3.2: Sensitive-data log scrub
Owner: Sonnet · Reviewer: Opus (privacy)
Objective: no photo URIs / album / person names in any log call; IDs and enum names only.
Inspect: `FaceIndexUseCase` (3 sites), `ClusterAlbumExportUseCase` (2), all Timber calls. Add CI grep-guard step for `\$\{?photo\.uri|albumName` patterns in log statements (best-effort).
Acceptance: audited call list in PR notes; guard active. Commit: `privacy: strip media identifiers from logs`

### Task P3.3: Pipeline unit tests + run() decomposition
Owner: Sonnet · Reviewer: Opus
Objective: `EmbeddingsTest`, `FacePreprocessorTest`, `BitmapLoaderTest`; extract `FaceIndexUseCase.run()` (148 lines) into named private steps without behavior change.
Acceptance: new tests green; `run()` ≤ ~40 lines orchestration. Commit: `test: cover embedding/preprocessing; decompose index pipeline`

### Task P4.1: Clusterer centroid cache
Owner: Sonnet · Reviewer: Opus (contract: cache coherence — clusterer becomes per-operation)
Objective: load centroids once per scan, update in place, write-through; also cache cover quality.
Reason: today every face deserializes every centroid BLOB (top scaling bottleneck).
Inspect/Modify: `domain/FaceClusterer.kt`, construction sites (`FaceIndexUseCase`, `MainViewModel`, `ReclusterUseCase`).
Tests: cache-vs-DAO parity on the existing `FaceClustererTest` scenarios; benchmark guard (5k faces/200 clusters ≤ target).
Acceptance: public API unchanged; per-face DAO reads eliminated; benchmarks within `07-performance-plan.md` targets.
Commit: `perf: in-memory centroid cache for clustering`

### Task P4.2: mergeClose without restart
Owner: Sonnet · Reviewer: Opus
Objective: merge within the cached list and continue scanning instead of restarting O(n²) after each merge.
Tests: parity property test vs old algorithm on seeded sets; ≤1 s at 200 clusters.
Commit: `perf: single-pass cluster merge`

### Task P4.3: Review-needed group + correction pinning
Owner: Opus (design) then Sonnet
Objective: ambiguous-band faces surface as an "Unassigned / review needed" pseudo-group; user reassignments survive recluster.
Inspect: `FaceClusterer.assign`, `ReclusterUseCase`, `PeopleScreen`, `ClusterDao.summariesAtLeast`.
Acceptance: no forced risky matches; corrections persist across recluster (test).
Commit: `feat: review-needed group and sticky manual corrections`

### Task P4.4: Chunked findByIds
Owner: Sonnet · Reviewer: Opus
900-id chunks helper used by `MainViewModel.loadCluster`; test with 2 500 ids. Commit: `fix: chunk photo id lookups below SQLite variable limit`

### Task P5.1: ExportViewModel extraction (copy-only)
Owner: Sonnet · Reviewer: Opus
Move export state/actions out of `MainViewModel` (`exportCluster`, `exportSelectedPhotos`, export events); NavGraph wiring. No behavior change. Commit: `refactor: dedicated ExportViewModel`

### Task P5.2: ClusterDetailScreen split + viewer zoom fix
Owner: Sonnet · Reviewer: Opus
Split 740-line file (dialogs/hero/grid into components); key `ImageViewerScreen` zoom state on page. Existing androidTests pass unchanged. Commit: `refactor: split person detail screen; fix viewer zoom bleed`

### Tasks P6.1–P6.7: Safe export
Owner: Sonnet per sub-task · Reviewer: Opus at 6.1 (schema), 6.3 (state machine), 6.7 (suite)
Exactly as specified in `05-safe-export-design.md` and `03-data-model.md`, in strict order: 6.1 DB v4 + migration test → 6.2 repository copy/verify primitives → 6.3 use case + worker (+ delete `ClusterAlbumExportUseCase` at parity) → 6.4 preview sheet + mode toggle (move flagged off) → 6.5 consent + resume banner → 6.6 undo + ExportComplete rewrite → 6.7 destructive suite; flag flips only on green suite.
Commits: `feat(db): export transaction log (v4)` · `feat: verified copy primitives` · `feat: export worker with resumable state machine` · `feat: export preview and mode selection` · `feat: consented source deletion for move` · `feat: export undo and report` · `test: destructive-operation suite; enable move mode`

### Task P7.1: Benchmarks + soak validation
Owner: Sonnet · Reviewer: Opus (final perf assessment)
JVM benchmark guards (clustering, export, chunking) in CI; manual device matrix + ~10 GB synthetic soak documented against `07-performance-plan.md` targets. Commit: `test: performance benchmarks and soak results`

### Task P8.1: Backup lockdown + merged-manifest audit
Owner: Sonnet · Reviewer: Opus (privacy audit sign-off)
`allowBackup=false`; CI step asserts merged manifest contains no INTERNET permission. Commit: `privacy: disable backup; assert offline manifest`

### Task P8.2: Compliance/doc truth pass
Owner: Sonnet · Reviewer: Opus
Rewrite `compliance.md` (no collection, biometric disclosure), fix remaining README/QA drift (drop seed-photo sections), add LICENSE, wire in-app privacy note + `settings_privacy_link`. Commit: `docs: truthful compliance and privacy surfaces`

### Task P9.1: Model asset + release CI
Owner: Opus (license verification) + Sonnet (wiring)
Source model per D2, record SHA-256, extend `verifyFaceModelPresent` with checksum; CI release job (signing from secrets, model gate); known-limitations doc; final Definition-of-Done review against `01-product-spec.md`.
Commit: `build: release pipeline with model integrity gate`
