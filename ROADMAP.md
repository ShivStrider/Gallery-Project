# FaceAlbum — Roadmap

Progress is tracked against seven milestones. Each milestone is graded ✅ done,
🟨 in progress, ⏳ pending.

## Milestone 1 — Core functionality complete ✅

- On-device ML Kit face detection + MobileFaceNet embeddings.
- Incremental scan (skips unchanged photos) and full re-scan.
- Cluster CRUD: rename, merge, per-photo reassign.
- Export a person's photos to Pictures/FaceAlbums/&lt;name&gt;.
- Adjustable clustering strictness + minimum cluster size.
- Re-cluster without re-scan.
- Room migrations 1→2→3 preserving user data.

## Milestone 2 — UI redesign complete 🟨

- People grid: adaptive Google Photos–style tiles with gradient scrim. ✅
- Welcome: brand-mark + feature triplet. ✅ (can gain motion)
- Settings: sectioned Material 3 list with slider previews. ✅
- **Person Detail**: hero + stats card + action row + gallery. 🟨 (this pass)
- **Empty-state illustration** for the People grid. 🟨 (this pass)
- **ExportComplete**: celebratory motion + secondary action. 🟨 (this pass)

## Milestone 3 — Animation and interaction polish 🟨

- Cluster tile fade-in when the grid first paints.
- Skeleton shimmer while the first scan finishes.
- Immersive image viewer with pinch-zoom + drag-to-dismiss.
- Snackbar success/undo for rename, merge, export.
- Spring-in check on export-complete.

## Milestone 4 — Performance optimization ⏳

- Batch `photosByIds` DAO for Person Detail load.
- `derivedStateOf` around progress-banner formatting.
- Verify no unnecessary recomposition in People grid via Compose compiler metrics.
- Benchmark 10k / 25k / 50k / 100k photo libraries (macrobenchmark module — future).

## Milestone 5 — Accessibility and tablet support ⏳

- `contentDescription` audit on every icon-only IconButton.
- Touch-target ≥ 48dp check on hero action buttons.
- Landscape + tablet: two-pane list-detail (future) and wider adaptive columns.
- TalkBack pass: header order, live-region for scan progress.
- Large-font (200%) sanity pass — no clipped labels.

## Milestone 6 — Testing and bug fixing ⏳

- Existing unit tests continue to pass after this pass.
- Add Compose UI test for the new immersive viewer entry/exit.
- Add ViewModel test for `favorite` toggle once wired.

## Milestone 7 — Play Store release candidate ⏳

- Release build with real keystore.
- Adaptive launcher icons (foreground/background XML) shipped.
- Privacy policy URL wired into Settings → About.
- Play Console listing (title, short description, screenshots) — out of scope for this repo pass.

## What Ships in This Pass

- Person Detail redesign with hero + stats + action row.
- Immersive image viewer.
- Empty-state illustration + skeleton grid.
- Richer scan-progress banner + snackbars.
- Favorite (pin) at cluster level, persisted.
- Consistent "person / people" language across UI.
- Updated `PROJECT_AUDIT.md` and this file every step of the way.
