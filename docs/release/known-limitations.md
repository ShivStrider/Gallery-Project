# Known Limitations

Honest list of what FaceAlbum does not (yet) do, or does with caveats, as of
this release. Verified against the current source — file references are given
so this doesn't drift the way `docs/release/qa-matrix.md`'s old seed-selection
section did.

## Move/delete export is disabled

`ExportFeature.MOVE_ENABLED` (`app/src/main/java/com/facealbum/config/ExportFeature.kt`)
is hard-coded `false`. The app is copy-only; `Copy` is fully functional, but
the UI never offers `Move` regardless of Android version.

Why: deleting a photo the app doesn't own requires the user to pass through a
system consent dialog (`MediaStore.createDeleteRequest`), and a bug in that
path destroys the user's only copy of a photo. The exit criterion for turning
Move on is the destructive-operation test suite going green
(`app/src/test/java/com/facealbum/domain/DestructiveExportSafetyTest.kt`,
design in `docs/plan/05-safe-export-design.md`). That suite is real and
passing in CI today, but it runs against a **simulated** MediaStore — a
`deletedSources` set standing in for the device, per the suite's own header
comment — not the actual system delete-confirmation UI. On-device
verification with synthetic photos (does the real consent dialog behave the
same way; does a declined/partial consent leave state consistent) is still
outstanding. Flipping the flag is a product decision requiring explicit
sign-off, not just a green test run.

## Move mode needs Android 11+ (API 30) even once enabled

`ExportPlanner.isMoveSupported()` (`app/src/main/java/com/facealbum/domain/ExportPlanner.kt`)
gates Move on `Build.VERSION.SDK_INT >= Build.VERSION_CODES.R`, because
`MediaStore.createDeleteRequest()` — the only way to delete media this app
doesn't own without holding broad storage permissions — was introduced in
API 30. On API 26–29, only Copy is available; a Move request on those
versions is rejected before any file is touched
(`ExportPlanner.commit()` returns `CommitResult.MoveUnsupported`, surfaced as
the `snack_move_unsupported` string). This is independent of the
`MOVE_ENABLED` flag above — both gates must pass before Move can run.

## Recognition accuracy is unmeasured

The face-embedding model **is** bundled (`app/src/main/assets/mobile_face_net.tflite`,
5,117,184 bytes, Apache-2.0 from sirius-ai/MobileFaceNet_TF, SHA-256 pinned in
`gradle.properties`). Provenance and the exact conversion command are in
`app/src/main/assets/README_MODEL.txt`. Release builds no longer fail for a
missing asset, and debug builds can index.

What has *not* been established is how well it groups. The converted model was
checked for input/output shape, dtype, finiteness, determinism, and unit-norm
output — not for accuracy. No benchmark was run against a labelled face
dataset, and at the time of writing the model has never processed a real
photograph, only synthetic tensors. Treat grouping quality on a real library as
unknown until someone runs a scan and looks at the result.

Related: the model emits **128-dimensional** embeddings, not the 512 that
earlier documentation in this repo claimed. `FaceRecognitionConfig.EMBEDDING_SIZE`
was corrected to 128 to match. Substituting a model of a different width means
changing that constant and re-scanning the whole library — embeddings of mixed
widths cannot be compared, and nothing detects such a mix at runtime.

## Grouping is probabilistic, not exact

Faces are clustered by cosine similarity against a running centroid
(`FaceClusterer.kt`), with defaults `CLUSTER_ASSIGN_THRESHOLD = 0.6` and
`CLUSTER_MERGE_THRESHOLD = 0.75` (`FaceRecognitionConfig.kt`). Two real
failure modes follow directly from that:

- The same person can be **split** across multiple groups (embedding drift
  across lighting/angle/age keeps similarity under the assign threshold).
- Two different people who look similar can be **merged** into one group.

Both thresholds are user-adjustable at runtime (Settings → *Grouping
strictness*, backed by `UserPreferences.assignThreshold` /
`.mergeThreshold`), and `settings_recluster` lets a user re-run clustering
against already-scanned faces without a full re-scan — but there is no
threshold setting that eliminates both failure modes at once; loosening one
worsens the other.

A related, one-way limitation: a user-requested merge
(`FaceClusterer.mergeUserRequested`) deletes the absorbed cluster row outright
and reassigns its faces — there is no stored record of the merge and no way to
split it back apart from the UI (`confirm_merge_body` warns "This can't be
undone from here," and it means it literally: it's not undo-able, not just
unexposed).

## Images only — no video

`PhotoRepository` queries `MediaStore.Images` exclusively, filtering
`MIME_TYPE LIKE 'image/%'` (`queryPhotosModifiedSince`,
`queryAllMediaStoreIds`). The manifest declares `READ_MEDIA_IMAGES` /
`READ_MEDIA_VISUAL_USER_SELECTED`, not `READ_MEDIA_VIDEO`. Videos in the
library are invisible to FaceAlbum; there is no video-frame extraction or
face detection over video anywhere in the codebase.

## Undo of a Move leaves a stale index row that self-heals, not instantly

When `ExportUndoUseCase.restoreItem` puts a deleted original back, it does so
through a fresh `MediaStore` insert (`PhotoRepository.restoreFromCopy`), which
is necessarily assigned a **new** MediaStore `_ID` — Android doesn't let an
app resurrect the old one. The app's `photos` table still has a row keyed to
the old, now-permanently-gone MediaStore id.

That stale row isn't cleaned up immediately. It self-heals on the *next*
incremental (or full) scan: `FaceIndexUseCase.reconcileDeletedPhotos` notices
the old id is no longer visible in MediaStore and deletes the row (faces
cascade), while the restored file — now a "new" photo with a fresh id and a
current `DATE_MODIFIED` — gets picked up and re-indexed as usual by the same
scan. Until that next scan runs, the restored photo won't appear grouped
under its person yet, and (harmlessly) an orphaned `photos` row for the
deleted id lingers. This is documented, intentional behavior — see
`docs/plan/05-safe-export-design.md`, Undo section — not a bug, but worth
knowing before filing "my restored photo didn't show up as grouped" as one.

## Android 14 partial photo access limits what gets scanned

On Android 14+, if the user grants "Select photos" rather than "Allow all"
(`READ_MEDIA_VISUAL_USER_SELECTED` without `READ_MEDIA_IMAGES` — see
`PhotoAccess.kt`), the app can only see the subset of photos the user
explicitly picked. `PhotoRepository.queryAllMediaStoreIds()`'s own doc comment
notes reconciliation must (and does) treat "not visible" the same as
"deleted" under this grant — so photos outside the selection are invisible to
scanning and clustering, not merely deprioritized. The app surfaces a banner
prompting the user to widen access, but doesn't distinguish "revoked" from
"never selected" in that state.

## Lower-RAM devices scan serially, i.e. slower

`FaceIndexUseCase.run()` checks `ActivityManager.isLowRamDevice` and drops the
face-detection pipeline's concurrency from 2 to 1 on such devices (to avoid
GC pressure from concurrent in-flight bitmaps at the 1024px max working
dimension). This is a deliberate tradeoff, not a defect, but it means scan
time on low-RAM hardware is not just a fixed multiple of what README's
"~100ms/photo on a Pixel-6-class device" figure suggests.
