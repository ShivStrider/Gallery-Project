# Decision Log

Format: Problem → Options → Selected → Reason / Trade-offs / Risks / Reversal cost / Validation.

**D1 — Crash reporting.** Firebase Crashlytics (status quo) vs internal-flavor Crashlytics vs remove entirely. → **Remove entirely.** The APK must not hold INTERNET; collection was inverted (debug-on/release-off) so production reporting was dead anyway; removal also un-broke fresh-clone builds (google-services.json). Trade-off: no fleet crash telemetry — accepted for a privacy product; failure state persists locally. Reversal: re-add plugin+dep (~1 day). Validation: merged manifest has no INTERNET; fresh clone builds.

**D2 — Face embedding model.** MobileFaceNet TFLite vs FaceNet-512 vs MediaPipe embedder vs custom. → **MobileFaceNet, 112×112→128-D, cosine — now sourced, converted, and committed.** Weights come from sirius-ai/MobileFaceNet_TF (Apache-2.0, verified against the repo's LICENSE), converted from the published frozen graph with TensorFlow 2.15.1; provenance, both checksums, and the exact conversion command are recorded in `app/src/main/assets/README_MODEL.txt`, and the artifact SHA-256 is pinned via `faceModelSha256` so `verifyFaceModelPresent` gates it. **Correction to the original decision**: this contract was recorded as 512-D, which was never true of this model — the graph emits 128 values, so `EMBEDDING_SIZE` was corrected to 128. The distributability concern is resolved for this artifact: Apache-2.0, upstream-published weights. Reversal: any model is drop-in given a matching `MODEL_INPUT_SIZE`/`EMBEDDING_SIZE` and a re-pinned checksum; a different width requires a full re-scan (embeddings of mixed widths cannot be compared). Still unvalidated: recognition accuracy has not been measured against a labelled dataset.

**D3 — Distribution target.** Play Store now vs sideload now/Play later. → **Sideload-ready first, Play-rigor docs maintained.** (User did not answer; assumption recorded, easily re-prioritized.)

**D4 — Face detection library.** ML Kit bundled (existing) vs MediaPipe vs OpenCV. → **Keep ML Kit** (on-device, proven in codebase, no network). Trade-off: +7 MB APK for the bundled model — correct for privacy.

**D5 — Clustering algorithm.** Online centroid (existing) vs DBSCAN/HAC batch vs hybrid. → **Keep online centroid + merge pass**, add in-memory cache and a review-needed band. Reason: incremental by nature (new photos don't re-cluster the world), simple, already tested; full recluster exists for threshold changes. Trade-offs: order sensitivity, centroid drift — mitigated by merge pass + user corrections. Reversal: `ReclusterUseCase` isolates the algorithm (moderate).

**D6 — Similarity thresholds.** assign 0.60 / merge 0.75 (existing, user-adjustable strictness) with merge > assign invariant. Validation: threshold-band tests + review-needed group absorbs the ambiguous zone.

**D7 — Database.** Room (existing, v3→v4 additive). Never edit shipped migrations.

**D8 — Background processing.** WorkManager foreground dataSync workers (existing pattern) for scan, recluster, and export M1. Consent (M2) is UI-only by OS design.

**D9 — Storage access.** MediaStore-only; no SAF. Destination is app-owned `Pictures/FaceAlbums/` via MediaStore inserts; source deletion via `createDeleteRequest`. SAF folder-picking rejected: adds a permission-persistence maze for no MVP gain. Reversal: SAF destination could be added later behind the same use-case seam.

**D10 — Export method.** Copy→verify(SHA-256+size+readable)→consented delete, per-file Room transaction log, two-phase (background copy / foreground consent), API 30+ gate for Move, copy-only until the destructive suite is green. Alternatives (direct move, rename-based move) rejected: not atomic across volumes, impossible on unowned media under scoped storage.

**D11 — Undo strategy.** Log-driven: copy-mode undo deletes app-owned copies; post-delete move undo restores from verified copies then deletes them. No trash/staging dir (doubles storage, another consent round).

**D12 — Thumbnail caching.** Coil only; no persistent thumbnail store (replaceable derivative data).

**D13 — Dependency injection.** None (manual wiring, existing). Hilt adds build+cognitive cost with no swap-in need at this scale.

**D14 — Supported Android versions.** minSdk 26, target 35 (existing). Move mode 30+. Rationale in `05-safe-export-design.md`.

**D15 — Static analysis.** detekt (CI) + AGP lint; version pinned compatible with Kotlin 1.9.20. ktlint deferred — one formatter/analyzer is enough to start.

**D16 — Kotlin/Compose versions.** Stay on Kotlin 1.9.20 / Compose BOM 2024.02 for MVP: internally consistent, upgrade is churn without user value mid-plan; revisit post-release.
