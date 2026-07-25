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

## Milestone 6 — Testing and bug fixing 🟨

- Existing unit tests continue to pass after this pass. ✅ CI runs `./gradlew test`:
  28 tests on the debug variant, 23 on release, 0 failures.
- Room migration tests against exported schemas. ✅ `FaceAlbumDatabaseMigrationTest`
  validates 1→2, 2→3 and 1→3 plus data preservation, against the committed baselines in
  `app/schemas/`. Written as a Robolectric unit test so CI actually executes it.
- Add Compose UI test for the new immersive viewer entry/exit. ⏳
- Add ViewModel test for `favorite` toggle once wired. ⏳
- **Instrumented tests are never executed.** `ClusterDetailScreenTest` and
  `PeopleScreenTest` exist under `app/src/androidTest/`, but the workflow only runs
  `test` and `lint` — there is no emulator job, so nothing in `androidTest/` has run in
  CI. Either add a `connectedCheck` job or stop treating that directory as coverage.

## Milestone 7 — Play Store release candidate 🟨

- Adaptive launcher icons (foreground/background XML) shipped. ✅
  `mipmap-anydpi-v26/ic_launcher{,_round}.xml` + `drawable/ic_launcher_{background,foreground}.xml`.
- `targetSdk` / `compileSdk` 35, Java 17 bytecode. ✅ Required for Play uploads from
  Aug 2025. Needed an AGP 8.13.2 / Gradle 8.13 toolchain upgrade to get there.
- Release signing plumbing. ✅ `signingConfigs.release` reads `ANDROID_KEYSTORE_BASE64`
  and the `decodeReleaseKeystore` task materialises it lazily, so only actual release
  tasks pay for it.
- Runtime `POST_NOTIFICATIONS` request on Android 13+. ⏳ PR #20 open — flip to ✅ on merge.
- Supply the real keystore + real `google-services.json`. ⏳ **owner action** — both are
  secrets, so they can only ever arrive from the environment, never from this repo.
  CI stubs `google-services.json`; a fresh clone must too or the build fails at
  `:app:processDebugGoogleServices` before compiling anything.
- Privacy policy URL wired into Settings → About. ⏳ **needs a hosted URL.** Note the
  string `settings_privacy_link` already exists in `strings.xml` but is referenced
  nowhere in code — the label was added, the link never was.
- `versionCode` currently 1 on `main` (2 in PR #21) with no release job to stamp it —
  it must increase on every upload, so automate it before the first submission.
- Play Console listing (title, short description, screenshots) — out of scope for this repo pass.

## What Ships in This Pass

- Person Detail redesign with hero + stats + action row.
- Immersive image viewer.
- Empty-state illustration + skeleton grid.
- Richer scan-progress banner + snackbars.
- Favorite (pin) at cluster level, persisted.
- Consistent "person / people" language across UI.
- Updated `PROJECT_AUDIT.md` and this file every step of the way.
