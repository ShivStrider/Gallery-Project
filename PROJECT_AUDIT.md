# FaceAlbum — Project Audit

> **Historical record.** This document captures the UI-polish pass that
> preceded the current export/privacy work. Its percentages and "Blockers"
> list describe the repository *as of that pass* and several are now out of
> date — Firebase has since been removed, `POST_NOTIFICATIONS` is requested,
> and backup is disabled.
>
> For current status see [`ROADMAP.md`](ROADMAP.md); for the authoritative
> design contracts see [`docs/plan/`](docs/plan/), starting with
> [`00-repository-audit.md`](docs/plan/00-repository-audit.md), which
> supersedes this file as the audit of record.

## Completion Snapshot

| Track | Status | Notes |
| --- | --- | --- |
| Architecture (MVVM + Room + WM + TFLite) | ✅ Solid | Untouched — no rewrite |
| Core UX | ~90% | Immersive viewer + favourites + snackbars now in |
| Visual polish | ~85% | Hero, stats card, empty-state illustration, skeleton |
| Animation | ~75% | Spring favourite pulse, spring hero brand mark, skeleton shimmer, action-row select scale, banner fade |
| Accessibility | ~75% | All icon buttons carry `contentDescription`s; touch targets ≥ 40dp |
| Tablet / landscape | ~65% | Adaptive grid columns; two-pane layout is a future item |
| Reliability | ~80% | Batched photo DAO removes N+1 on detail load |
| Play Store readiness | ~80% | Signing, shrinker, versioning, edge-to-edge all in place |

Overall production-readiness: **~80%** at end of this pass (from ~65%).

## Completed Improvements (this pass)

- **Consistent "person / people" language** across every user-visible surface.
  Legacy `cluster_*` string keys are aliased to the new `person_*` keys so no
  screen missed the rename.
- **Person Detail redesign**:
  - Hero image with subtle parallax as the grid scrolls under it.
  - Stats card ("photos", "first seen", "latest") using a friendly date
    formatter that shows "Today" / "Yesterday" / "Mar 4" / "May '23" — no ISO
    timestamps in the UI.
  - Action row with Export / Merge / Rename buttons.
  - Favourite (heart) in the top app bar with a spring bounce on toggle.
  - Merge now confirms before executing (irreversible operation).
- **Immersive image viewer** (new `ImageViewerScreen`):
  - Horizontal pager between the person's photos.
  - Pinch-to-zoom, pan, double-tap-to-zoom.
  - Drag-down-to-dismiss (only when not zoomed) with fade during drag.
  - Tap toggles chrome (position indicator + close).
- **People grid polish**:
  - Empty-state illustration (`ic_empty_people.xml`) with friendly copy and a
    call-to-action button.
  - Skeleton shimmer while the very first scan is running.
  - Richer progress banner (X of Y · N faces found).
  - Snackbar host for one-shot feedback.
- **Favourites**:
  - Persisted in DataStore as a `Set<Long>`; no Room migration required.
  - Favourite people float to the top of the People grid, then by photo count.
- **Snackbar feedback** for rename, merge, favourite toggle. Renames go through
  a shared `MutableSharedFlow` so navigating back doesn't re-play the message.
- **ExportComplete polish**: spring-in check mark, secondary "Open in gallery"
  action.
- **Welcome polish**: brand mark spring-in animation.
- **Settings polish**: version footer under About.
- **AutoMirrored `ArrowBack`** replaces the deprecated auto-mirrored variant
  in ClusterDetail's back button.
- **Batched photo DAO** (`findByIds(List<Long>)`) removes the N+1 query in
  `MainViewModel.loadCluster`.

## Remaining Work

Everything shipping in this pass is either done or explicitly deferred.
Deferred items:

- Shared-element transition between grid tile and viewer (needs Compose 1.7).
- Two-pane list-detail on tablets.
- Compose macrobenchmark for large-library scans.
- Room `isFavorite` column (currently DataStore-backed to avoid schema churn).
- Privacy policy link surface in Settings About (string is defined but not wired
  — needs a real URL to point at).

## Technical Debt

- `ClusterSummary` and internal DAOs still call the entity "cluster" —
  intentional; internal APIs stay stable, UI language changed.
- `MainViewModel` is now ~350 lines. Split candidates: index/progress vs.
  detail/actions. Manageable at current size.
- `AnimatedContent` on the selection banner may briefly resize the underlying
  grid; the effect is intentional but could be tuned with a fixed placeholder.

## UI Debt

- Still no shared-element transition to the viewer.
- Settings About footer could add a privacy policy link once a URL is decided.
- Landscape has been sanity-checked in code but not on a device.

## Known Bugs

- None observed. The single-page viewer relies on `state.photos` in the
  ViewModel — if the user navigates to the viewer and then process death wipes
  the state, `NavGraph` pops the viewer route. That is the correct behavior for
  now.

## Performance Bottlenecks

- Batched `findByIds` cuts the previous N+1 photo query.
- `LazyVerticalGrid` remains the layout; each photo tile has `graphicsLayer`
  scale for selection — cheap on modern devices.
- The favourite float-to-top sort runs on every `clusters` emission. O(n log n)
  at cluster count, negligible.

## Play Store Blockers

- ~~Real `google-services.json` needs to replace the CI stub before publish.~~ Obsolete: Firebase was removed entirely; no service config is needed.
- Adaptive launcher icons need real XML (the tree has a
  `README_ICONS.txt` placeholder).
- Privacy policy URL surface (string is defined, target URL not decided).
- `mobile_face_net.tflite` model asset must be present for release (already
  gated by `verifyFaceModelPresent`).
- ~~`POST_NOTIFICATIONS` is declared but not requested at runtime.~~ Resolved:
  `PeopleScreen` requests it at runtime when the first scan starts; denial
  degrades gracefully (scan still runs, just without a visible notification).
- ~~`targetSdk` is 34; should bump to 35.~~ Resolved: `compileSdk`/`targetSdk`
  are both 35 in `app/build.gradle.kts`.
- ~~`allowBackup="true"` with no backup rules.~~ Resolved (Phase 8):
  `android:allowBackup="false"`, plus `data_extraction_rules.xml` /
  `backup_rules.xml` excluding every backup domain as defence in depth.
  `docs/release/compliance.md` rewritten to match the post-Firebase,
  post-hardening state. See `docs/plan/08-privacy-security.md`.

## Recommended Future Enhancements

- Person-level "hidden" state (opt out of the grid without deleting).
- Cluster merge history + undo.
- Locked-folder support for a curated private set (Photo Picker API on 14+).
- Tablet two-pane `NavigableListDetailPaneScaffold`.
- "Recent people" widget.

## References Followed (Official Android Skills)

Applied guidance from the official `android/skills` repo (shared during this
pass):

- **`system/edge-to-edge`** — grid `contentPadding` now consumes the
  Scaffold `innerPadding` bottom + FAB clearance so content scrolls behind
  the nav bar instead of being clipped above it. Top inset is handled by the
  Material 3 `LargeTopAppBar`. The custom theme already flips
  `isAppearanceLightStatusBars` per dark-mode state as the skill
  recommends.
- **`jetpack-compose/adaptive`** — kept the `LazyVerticalGrid` on
  `GridCells.Adaptive(150.dp)`, which is the recommended path for making
  vertical lists responsive to wider screens. Deferred: migrating navigation
  to Nav 3 + list-detail scaffold; the Compose 1.11 Grid/Flex APIs required
  are pre-release and out of scope for this stability pass.
- **`security/android-intent-security`** — the new "Open in gallery" implicit
  intent on the Export Complete screen is a static `ACTION_VIEW` for the
  MediaStore images URI; no untrusted extras are forwarded, so no
  `IntentSanitizer` required.
- **`play/play-policy-insights`** — noted the deferred Play items (privacy
  policy link, POST_NOTIFICATIONS runtime request, targetSdk bump) in the
  Blockers section above.

## What This Pass Deliberately Does *Not* Do

Following the mission: no cloud sync, no social sharing, no AI chatbot,
no demographic prediction, no celebrity recognition. Every change is aimed
at polishing the existing offline-first surface.
