# Privacy & Security Design

## Posture

Everything on-device, provably. The claim is enforced at four layers:

1. **No network capability**: no INTERNET permission in the *merged* manifest (verified after Firebase removal — the source manifest was already clean; the merged one is what counts), no networking library in the dependency graph, no cloud SDK.
2. **No backup exfiltration**: `android:allowBackup="false"` (Phase 8) — face embeddings, person names, and photo URIs never ride Android Auto Backup to Google Drive.
3. **No sensitive data in logs**: Timber plants only in debug; Phase 3 strips photo URIs and album/person names from all log calls anyway (IDs and enum names only); CI grep-guard keeps them out.
4. **Local-only failure reporting**: `CrashReporter` is a Timber wrapper; failure state persists in the app's own DB (`scan_sessions`, `export_items.errorCode`).

## Data classification

| Data | Class | Handling |
|---|---|---|
| Face embeddings (512-D) | Biometric-derived, sensitive | Room BLOB, device-only, backup-excluded, erasable, never logged |
| Person names (user-assigned) | Personal | Same handling as embeddings |
| Photo URIs/paths/filenames | Personal | Stored for operation only, never logged, backup-excluded |
| Thumbnails | Derived cache | Coil cache, replaceable, cleared with app data |
| Export transaction log | Personal (paths) | Device-only; wiped by Delete face data |

## Permissions (least privilege)

`READ_MEDIA_IMAGES` (33+) / `READ_EXTERNAL_STORAGE` (≤32) — reading the library; Android 14 partial grant (`READ_MEDIA_VISUAL_USER_SELECTED`) honored with explicit UI acknowledgment (Phase 2). `POST_NOTIFICATIONS` — requested in context when the first scan starts; denial degrades gracefully (scan still runs). `FOREGROUND_SERVICE(_DATA_SYNC)` — resumable scans/exports. **Not held**: INTERNET, WRITE_EXTERNAL_STORAGE, MANAGE_EXTERNAL_STORAGE, location, camera.

## Destructive-operation protection

Source deletion happens only through the OS consent dialog (`createDeleteRequest`), only for checksum-verified copies, only after an exact preview, with a persisted per-file log, resume, and undo. See `05-safe-export-design.md`.

## User transparency & control

- Welcome + Settings state plainly what is stored (embeddings, group data, on this device only) and that grouping is probabilistic and may be wrong.
- Settings → Delete face data erases photos/faces/clusters **and the export log** in one action.
- In-app privacy policy surface (Phase 8/9 wiring of the existing `settings_privacy_link`) — required for Play with biometric-adjacent data.

## Compliance honesty

`docs/release/compliance.md` is rewritten in Phase 8 to match the post-Firebase reality (Data safety: no data collected, no data shared; Diagnostics: none). No claim ships that the merged manifest or dependency graph contradicts — CI-verifiable where possible.
