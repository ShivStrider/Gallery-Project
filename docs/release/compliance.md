# FaceAlbum Release Compliance Notes

This document describes the app's *current* behavior as implemented in code. It is
re-verified whenever the manifest, dependency graph, or data-handling code changes.
Nothing below is aspirational — every claim maps to a specific code path or manifest
line, cited inline.

## 1) Ground truth this document is built on

- **No network capability.** The source `AndroidManifest.xml` declares no
  `INTERNET` permission, and no dependency in `app/build.gradle.kts` merges one
  into the built APK. There is no networking library (Retrofit/OkHttp/Ktor/etc.)
  and no cloud SDK anywhere in the dependency graph.
- **No third-party analytics or crash SDK.** Firebase/Crashlytics was removed
  from this codebase. `telemetry/CrashReporter.kt` is a thin wrapper over
  Timber; Timber only plants a tree in debug builds, and failure state is
  persisted locally in the app's own Room database (`scan_sessions`,
  `export_items.errorCode`) — never transmitted anywhere.
- **No account system.** There is no login, no user identifier, no device
  identifier collected for any purpose.
- **Fully on-device processing.** Photo scanning (`PhotoRepository`), face
  detection (`FaceDetectorWrapper`, ML Kit), embedding generation
  (`FaceEmbedder`, TensorFlow Lite), and clustering (`FaceClusterer`) all run
  locally. No photo, embedding, or metadata is ever sent off the device.
- **Export is copy-only in this release.** `ExportFeature.MOVE_ENABLED = false`
  gates a verified-move pipeline that exists in code but is not reachable from
  any UI surface yet. Only the copy path (`PhotoRepository.copyToAlbumChecked`
  / `copyToAlbumWithResult`) is user-facing: matching photos are copied into
  `Pictures/FaceAlbums/<PersonName>/`, and originals are left untouched.
- **`android:allowBackup="false"`** in the manifest, backed by
  `data_extraction_rules.xml` / `backup_rules.xml` that exclude every backup
  domain. See §7.

## 2) Biometric-derived data disclosure

The app performs on-device face recognition. This is a category most platforms
(including Play) require explicit disclosure for, regardless of whether the
data ever leaves the device.

- **What is derived:** for each detected face, a 512-dimension floating-point
  embedding vector (`FaceEmbedder.getEmbedding`) — a mathematical
  representation of facial geometry, not a photo of the face itself.
- **Where it lives:** the embedding is stored as a BLOB in the `faces` table
  of the local Room database (`face_album.db`). It never leaves the device:
  it is not uploaded, not backed up (§7), and not logged.
- **What it is used for:** comparing embeddings by cosine similarity
  (`SimilarityMatcher`) to group photos of the same person into a cluster
  (`FaceClusterer`), and letting the user assign a name to that cluster
  (`clusters.displayName`).
- **Grouping is probabilistic, not identification.** The app does not attempt
  to identify *who* a person is — it never compares against an external
  database, watchlist, or public figure — it only groups faces that look
  similar to each other. Matches can be wrong (a similar-looking stranger can
  land in the wrong cluster, or one person can end up split across two
  clusters); the in-app Welcome and Settings copy states this plainly.
- **User control:** Settings → "Delete face data" deletes every row in
  `photos`, `faces`, and `clusters`, as well as the export transaction log
  (`export_operations`, `export_items`), in one action. Denying or revoking
  photo permission in system settings stops all further processing.

## 3) Google Play Data safety answers

These map directly to the code as described in §1–2. No answer here should be
weakened or strengthened without re-verifying the corresponding code path.

### 3.1 Data collected

"Collected" in Play's terms means transmitted off the device. Nothing is:

- **Personal info, financial info, health and fitness, messages:** No
- **Photos and videos:** No — photos are *accessed* on-device (read from
  `MediaStore` to run detection/clustering) but never transmitted or uploaded
  anywhere. Play's Data safety form distinguishes "collected" (leaves the
  device) from "on-device only"; this app's photo handling is on-device only.
- **Audio, files/docs, calendar, contacts, app activity, web browsing,
  device or other IDs, diagnostics:** No — no code path collects any of
  these categories, and there is no SDK present that could.

### 3.2 Data shared with third parties

- **Any data shared?** No. There is no network path for any data to travel
  over (§1), so there is nothing to share.

### 3.3 On-device processing declaration

Where the Play form asks about on-device-only processing (distinct from
"collection"):

- **Photos and videos:** processed on-device to detect faces, generate
  embeddings, and compare similarity. Not collected (not transmitted).
  Purpose: app functionality (person grouping and album export). Required
  for the app's core feature — there is no functional mode that skips it.
- **Biometric-derived data (face embeddings):** generated and stored
  on-device only, in the local Room database. Not collected. Purpose: app
  functionality (grouping faces into person clusters). User can delete at
  any time via Settings.

### 3.4 Security practices

- Data is processed and stored locally only; "data encrypted in transit" is
  not applicable because nothing is transmitted.
- Users can request deletion via Settings → "Delete face data" (in-app, no
  server round-trip needed since no server exists) or by uninstalling the
  app, which removes the local database entirely.

## 4) In-app disclosure text consistency

Store listing, onboarding copy, and permission rationale should all describe
the same behavior described in this document.

### Current in-app permission rationale string

`R.string.permission_rationale`:

> "FaceAlbum needs access to your photos to find faces and group them by
> person — all on your device."

This is accurate as written and needs no change.

### Feature claims safe to make in store listing / marketing copy

- "100% offline — no uploads, no accounts, no telemetry"
- "On-device face grouping"
- "Exports matched photos to Pictures/FaceAlbums/&lt;Name&gt;"
- "Face grouping is automatic and probabilistic — review and rename groups
  as needed"

### Claims that must NOT be made (not implemented)

- Real-time camera scanning (the app only scans the existing photo library)
- Cross-device sync or cloud backup of any kind
- Server-side recognition or matching against external identity databases
- Photo/video/document collection of any kind for the Play Data safety form
  (see §3.1 — the answer is "No" across the board)
- Moving (as opposed to copying) source photos during export — the
  user-reachable export path is copy-only in this release (§1)

## 5) Data handled by the app — matrix

| Data | Accessed on-device | Transmitted off-device | Stored (where) | Purpose | Code path |
|---|---:|---:|---|---|---|
| Photo/media image content | Yes | No | Not persisted (read, processed, discarded) | Face detection + embedding generation | `PhotoRepository.queryPhotosModifiedSince`, `FaceDetectorWrapper`, `FaceEmbedder.getEmbedding` |
| Photo metadata (MediaStore id, display name, date modified) | Yes | No | Room `photos` table | Incremental scan bookkeeping | `PhotoRepository.queryPhotosModifiedSince`, `PhotoEntity`/`PhotoDao` |
| Face embeddings (512-D vector, biometric-derived) | Derived | No | Room `faces` table (BLOB) | On-device similarity clustering | `FaceEmbedder.getEmbedding`, `FaceEntity`/`FaceDao`, `Embeddings.kt` |
| Person names (user-assigned) | User input | No | Room `clusters` table | Label a cluster with a name | `ClusterEntity`/`ClusterDao` |
| Exported photo copies | Yes (copy action) | No | Device storage, `Pictures/FaceAlbums/<Name>/` | User-requested album export | `PhotoRepository.copyToAlbumChecked`, `PhotoRepository.copyToAlbumWithResult` |
| Export transaction log (source paths, filenames, outcome) | Yes | No | Room `export_operations` / `export_items` tables | Resume, verify, and undo export operations | `ExportDao`, `ExportOperationEntity`, `ExportItemEntity` |
| Favourite-person set | User input | No | DataStore preferences (`face_album_prefs`) | Pin favourite people to the top of the grid | `data/prefs/UserPreferences.kt` |
| Account identifiers / email / phone / advertising ID / any device ID | No | No | N/A | N/A | No corresponding code path exists |

## 6) Permission justification

Declared Android permissions (`AndroidManifest.xml`):

| Permission | SDK range | Why it's requested |
|---|---|---|
| `READ_MEDIA_IMAGES` | 33+ | Read the photo library to run on-device face detection and clustering. |
| `READ_MEDIA_VISUAL_USER_SELECTED` | 34+ | Android 14 lets the user grant access to a *selection* of photos instead of the whole library ("Select photos"). The app honors that partial grant (`util/PhotoAccess.kt`) instead of forcing the user to grant full-library access; `WelcomeScreen` explains what a partial grant means for scan coverage. |
| `READ_EXTERNAL_STORAGE` | ≤32 | Equivalent photo-library read access on pre-Android-13 devices, where the scoped media permissions above don't exist yet. |
| `POST_NOTIFICATIONS` | 33+ | The indexing pipeline runs as a foreground service (below) and must show a progress notification while it runs; Android 13+ requires this permission to post any notification, including foreground-service ones. Requested at runtime from `PeopleScreen`; denial degrades gracefully and scanning still runs, just without a visible progress notification. |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | — | Lets the WorkManager-driven face-indexing job run as a foreground service (`dataSync` type — local media indexing) so long scans survive the user backgrounding the app. |

**Not held, and nothing in the dependency graph requests them:** `INTERNET`,
`WRITE_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`, any location permission,
`CAMERA`, `RECORD_AUDIO`, contacts, or SMS.

## 7) Backup and data-extraction posture

- `android:allowBackup="false"` (`AndroidManifest.xml`) — Android will not
  include this app in Auto Backup to Google Drive at all.
- `android:dataExtractionRules="@xml/data_extraction_rules"` and
  `android:fullBackupContent="@xml/backup_rules"` are set as defence in
  depth: both rule files exclude every backup domain (database, shared
  prefs, files, root, external), so even if `allowBackup` were ever flipped
  back to `true` by mistake, the face-embedding database and DataStore
  preferences still could not be copied off the device via cloud backup or
  device-to-device transfer.
- Rationale: the Room database holds biometric-derived face embeddings and
  user-assigned person names. Letting either ride Android's backup
  mechanisms to the user's Google account would contradict the app's core
  "stays on this device" claim, independent of whether Google's backup
  transport itself is trustworthy.

## 8) Known limitations to disclose honestly

- Grouping is probabilistic similarity matching, not verified identification.
  It can merge two different people who look alike, or split one person
  across multiple clusters, especially with poor image quality, extreme
  angles, or occluded faces.
- If the bundled TensorFlow Lite model file is missing from the APK's
  assets, embedding generation fails gracefully rather than crashing
  (`FaceEmbedder` degrades to a no-op) — indexing will not produce clusters
  in that state.
- Processing speed depends entirely on on-device hardware; there is no
  server-side fallback.

## 9) Pre-submission checklist

Before submitting to the Play Store:

- [ ] Host a public privacy policy reflecting this document and link it in
      Play Console → App content → Privacy policy.
- [ ] Complete the Play Data safety form using §3 above.
- [ ] Wire `R.string.settings_privacy_link` in Settings → About to the
      published policy URL (currently defined but not linked anywhere —
      tracked in `PROJECT_AUDIT.md`).
- [ ] Confirm `versionName`/`versionCode` in `app/build.gradle.kts` match the
      release build.
- [ ] Re-run this document's verification before every release: confirm the
      merged manifest still has no `INTERNET` permission, and no analytics,
      ads, or networking dependency has been added since the last review.
- [ ] If `ExportFeature.MOVE_ENABLED` is ever flipped to `true` for a
      release, update §1 and §4 of this document before shipping — the
      move-export UX and consent flow become user-reachable and must be
      described accurately.
