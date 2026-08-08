# FaceAlbum — Roadmap

Work is tracked against the ten phases frozen in [`docs/plan/`](docs/plan/).
Each item is graded ✅ done, 🟨 in progress, ⏳ pending.

The contracts themselves live in `docs/plan/` — this file is only the status
board. Where the two disagree, the contract wins and this file is stale.

## Phase 0 — Audit & build recovery ✅

- Repository audit committed (`docs/plan/00-repository-audit.md`). ✅
- Firebase/Crashlytics removed entirely — restores the genuine "no INTERNET"
  claim and unblocks fresh-clone builds (no `google-services.json` needed). ✅
- `androidTest` sources compile again (bad `AndroidJUnit4` import); CI now runs
  `assembleDebug` + `assembleDebugAndroidTest` so it cannot regress. ✅
- Release builds fail loudly when signing env is absent instead of silently
  debug-signing. ✅
- Static analysis (detekt/ktlint). ⏳ *attempted and reverted.* detekt 1.23.7
  applied cleanly against Kotlin 1.9.20 — no plugin or version problem, the
  build stayed green — but the `detekt` task finished in 2–5 seconds and
  produced no report on any run, so it analysed nothing. Three attempts to
  read the task-outcome line out of the CI log failed: the step runs mid-job
  and the GitHub API only serves log tails, which the assemble output buries.
  Rather than leave configuration that looks like static analysis while
  checking nothing, it was removed whole. Anyone retrying should first work
  out whether the base `detekt` task is NO-SOURCE under AGP (the custom
  `source.setFrom(...)` is the prime suspect) or whether the useful task is a
  variant one such as `detektMain`, and should print the task outcome at the
  END of the job where a short tail can reach it.*

## Phase 1 — Requirements & architecture freeze ✅

All eleven contract documents committed to `docs/plan/`: product spec,
architecture, data model, face pipeline, safe-export design, testing plan,
performance plan, privacy/security, decision log (D1–D16), risk register
(R1–R15), task cards.

## Phase 2 — Discovery hardening ✅

- Android 14 partial photo access (`READ_MEDIA_VISUAL_USER_SELECTED`) detected
  and surfaced, with a "manage selection" affordance. ✅
- `POST_NOTIFICATIONS` requested in context when the first scan starts. ✅
- Reconcile pass: photos deleted or modified outside the app are cleaned up and
  their clusters recomputed. ✅
- Dead discovery/export code and stale string aliases removed. ✅

## Phase 3 — Pipeline correctness & efficiency ✅

- TFLite inference moved outside the Room write transaction. ✅
- Photo URIs, filenames and album names stripped from all logging; CI grep
  guard keeps them out. ✅
- Unit tests for `FacePreprocessor` and `BitmapLoader` sampling math. ✅
- `FaceDetectorWrapper` / `FaceEmbedder` built lazily, so nothing native is
  allocated on ModelNotReady paths and `close()` skips what was never used. ✅

## Phase 4 — Grouping at scale & uncertainty ✅

- In-memory centroid cache — removes the per-face full-table centroid read. ✅
- `mergeClose()` no longer re-reads centroids from Room per merge, and no
  longer restarts its pairwise scan after each merge — each pass sorts once
  and absorbs into a per-pass dead set, so comparison cost is decoupled from
  merge count. ✅ *measured 232/569 ms for 150 merges from 300 clusters, down
  from 1801/2065 ms, now inside the ≤ 1 s @ 200-cluster target*
- `PhotoDao.findByIdsChunked()` respects the SQLite 999-variable limit. ✅
- Clustering benchmarks at 100 / 1k / 5k synthetic embeddings, asserting on
  operation counts rather than wall-clock so they catch a complexity
  regression without flaking on a noisy runner. ✅ *measured: `assign` is flat
  at 239–440 µs/face across all three scales, ~20× inside its target*
- "Review needed" group surfacing faces the minimum-group-size filter hides,
  which were previously unreachable in the UI entirely. ✅ *implemented as a
  query over existing data, not a second assign threshold in the clusterer —
  that remains open, and composes with this*

## Phase 5 — Review UI completion ✅

- Export preview sheet: exact file count, source folders, destination, size
  estimate, "also shows someone else" flags, Copy/Move choice. ✅
- Undo / export-report surface built on the operation log. ✅
- Image-viewer zoom state no longer bleeds between pages. ✅
- Split the 712-line `ClusterDetailScreen` into a 274-line screen plus five
  component files under `ui/screens/clusterdetail/`. ✅
- Review-needed surface in the People grid, routing into the existing
  cluster-detail screen for rename and reassignment. ✅

## Phase 6 — Safe album export ✅ (move mode gated)

The core new engineering of this branch. Complete and tested:

- **Room v4** transaction log — `export_operations` + `export_items`, additive
  migration 3→4, committed schema, migration tests, CI schema-drift guard.
- **Verified copy** — every file streams through SHA-256 at write time and is
  re-read and compared before it is ever eligible for deletion.
- **`ExportPlanner`** — one entry point producing an inspectable plan before
  anything is touched; replaces the duplicated `export()`/`exportPartial()`.
- **`ExportExecutor`** — copy → verify, each transition committed before the
  next file, so process death leaves a resumable state.
- **Two-phase move** — background copy+verify, then a foreground system consent
  dialog (`MediaStore.createDeleteRequest`, chunked); the app does not own the
  source photos, so this split is load-bearing, not a style choice.
- **Undo** — restore-before-cleanup ordering, with three explicit refusals
  (failed restore keeps the copy; a deduped destination is never deleted; a
  missing original path reports `NOT_RESTORABLE`).
- **Destructive-operation safety suite** — 9 tests covering the invariants:
  unselected files never enter a delete batch, verify failure keeps the source
  and removes the destination, consent denial leaves everything intact.

**Move remains disabled** (`ExportFeature.MOVE_ENABLED = false`). The safety
suite validates against a *simulated* MediaStore; an on-device pass with
synthetic photos against the real system delete dialog is the outstanding exit
criterion, and enabling the flag needs an explicit human decision.

## Phase 7 — Performance & large-library validation 🟨

- Clustering benchmarks with assertions on operation counts (so a complexity
  regression fails the build, but runner noise does not), with measured
  figures recorded in the performance plan. ✅
- Measured memory ceiling for the indexing pipeline. ⏳
- ~10 GB soak run, cancellation and resume end-to-end. ⏳

## Phase 8 — Privacy, security & destructive audit ✅

- `allowBackup="false"` plus data-extraction and full-backup rule files
  excluding every domain — embeddings and person names cannot ride Auto Backup
  to Google Drive. ✅
- No INTERNET in the *merged* manifest; no networking library in the graph. ✅
- Sensitive identifiers kept out of logs, enforced by a CI guard. ✅
- `docs/release/compliance.md` rewritten to match reality (it previously cited
  deleted code paths and gave wrong Data-safety answers). ✅

## Phase 9 — Release prep 🟨

- Model asset sourced, converted, committed, and checksum-pinned:
  MobileFaceNet from sirius-ai (Apache-2.0), 112×112 in / 128-D out, with
  `verifyFaceModelPresent` gating its SHA-256. ✅
- Signed-build instructions, verified against the env var names the code
  actually reads. ✅
- [`docs/release/known-limitations.md`](docs/release/known-limitations.md),
  every claim cited to a source file. ✅
- `assembleRelease` CI job. ⏳ *no longer blocked by the missing model asset —
  now only needs signing secrets available to the workflow*
- Final acceptance run against the spec's Definition of Done. ⏳

## Known gaps carried forward

1. Move export is implemented but off; on-device verification is required
   before the flag flips.
2. Grouping accuracy on real photos is entirely unmeasured — the bundled model
   has never processed a photograph. Largest open risk, and the one most
   likely to make the app feel broken regardless of whether the code is right.
3. No static analysis beyond Android Lint. detekt 1.23.7 was added and then
   reverted — see Phase 0.
