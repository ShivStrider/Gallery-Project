# Decision Log

Format: Problem → Options → Selected → Reason / Trade-offs / Risks / Reversal cost / Validation.

**D1 — Crash reporting.** Firebase Crashlytics (status quo) vs internal-flavor Crashlytics vs remove entirely. → **Remove entirely.** The APK must not hold INTERNET; collection was inverted (debug-on/release-off) so production reporting was dead anyway; removal also un-broke fresh-clone builds (google-services.json). Trade-off: no fleet crash telemetry — accepted for a privacy product; failure state persists locally. Reversal: re-add plugin+dep (~1 day). Validation: merged manifest has no INTERNET; fresh clone builds.

**D2 — Face embedding model.** MobileFaceNet TFLite (existing contract) vs FaceNet-512 vs MediaPipe embedder vs custom. → **MobileFaceNet, 112×112→512-D, cosine.** Entire codebase and DB already implement this contract; ~5 MB; adequate accuracy for personal-library grouping. **Licensing constraint**: weights derived from MS-Celeb-1M are not distributable — the committed/shipped model must have verifiable provenance and a permissive license, else the repo stays model-free with INSTALL.md sourcing (personal use). SHA-256 recorded and gated at release build. Reversal: any 512-D float model is drop-in; different dims require re-index + migration (moderate). Validation: benchmark + manual accuracy pass on a legal dataset.

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
