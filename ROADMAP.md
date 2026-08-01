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
- Static analysis (detekt/ktlint). ⏳ *not added — CI runs `lint` only*

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

## Phase 4 — Grouping at scale & uncertainty 🟨

- In-memory centroid cache — removes the per-face full-table centroid read. ✅
- `mergeClose()` no longer restarts its scan after every merge. ✅
- `PhotoDao.findByIdsChunked()` respects the SQLite 999-variable limit. ✅
- Clustering benchmarks at 100 / 1k / 5k synthetic embeddings. 🟨
- "Review needed / Unassigned" group for low-confidence faces, instead of
  forcing them into singleton clusters. ⏳

## Phase 5 — Review UI completion 🟨

- Export preview sheet: exact file count, source folders, destination, size
  estimate, "also shows someone else" flags, Copy/Move choice. ✅
- Undo / export-report surface built on the operation log. ✅
- Image-viewer zoom state no longer bleeds between pages. ✅
- Split the 712-line `ClusterDetailScreen` into screen + components. ⏳
- Unassigned/review-group surface (pairs with the Phase 4 item). ⏳

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
  regression fails the build, but runner noise does not). 🟨
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

- Model-asset integrity: SHA-256 gate in `verifyFaceModelPresent`. 🟨
- Signed-build instructions with the env var names the code actually reads. 🟨
- Known-limitations document. 🟨
- `assembleRelease` CI job. ⏳ *blocked: the MobileFaceNet asset is not
  committed, so a release job would be permanently red*
- Final acceptance run against the spec's Definition of Done. ⏳

## Known gaps carried forward

1. Move export is implemented but off; on-device verification is required
   before the flag flips.
2. Low-confidence faces still become singleton clusters rather than landing in
   an explicit review queue.
3. No static analysis beyond Android Lint.
4. The MobileFaceNet `.tflite` asset is not in the repository; release builds
   fail by design until it is supplied (see `INSTALL.md`).
