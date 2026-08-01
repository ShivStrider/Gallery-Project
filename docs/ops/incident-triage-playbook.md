# Crash/Incident Triage Playbook

## Scope
This playbook covers model-load failures, scan exceptions, and export failures recorded by `CrashReporter`. Reporting is **local-only**: `CrashReporter` writes to the on-device log via Timber (debug builds only — no tree is planted in release). There is no network telemetry, no Crashlytics, no dashboard. Incidents reach us as user reports plus whatever the user can capture from `adb logcat`.

## Event taxonomy
`CrashReporter.recordNonFatal(throwable, source, context)` call sites emit these `source` values:

- `model_load`: TFLite model failed to initialize (`FaceEmbedder`).
- `detect_photo`: decode/detect stage failed for one photo (`FaceIndexUseCase`).
- `embed_photo`: embedding stage failed for one photo (`FaceIndexUseCase`).
- `cluster_export` / `cluster_export_partial`: export copy failed for an approved photo (`ClusterAlbumExportUseCase`).
- `face_index_worker` / `recluster_worker`: worker-level failure before retry.

## Data handling rules
- Never include photo URIs, filenames, album names, person names, embeddings, or image metadata in any log or report — debug logs included. Use stable numeric IDs and error enum names only.
- Persistent failure state lives in the app's own database (`scan_sessions.errorMessage`, and the export operation log once Phase 6 lands) — that is the recovery surface, not logs.

## Triage workflow
1. Ask the reporter for: app version, Android version, device model, and (if possible) a `adb logcat -s CrashReporter Timber` capture from a debug build reproducing the issue.
2. For `model_load`: verify the model asset is packaged (`mobile_face_net.tflite`, correct SHA-256 per INSTALL.md); the app degrades gracefully (detection without grouping) so this presents as "no groups appear".
3. For `detect_photo` / `embed_photo`: reproduce with a synthetic test library (never customer media); check for unsupported/corrupt formats.
4. For export failures: check storage permission state, free space, and destination collection availability; the typed failure enum in the result report names the failing stage.
5. File a bug with: stack trace, app/build version, `source`, context keys, and reproduction notes.

## Acceptance checks
- Debug build: force a non-fatal (e.g. temporarily rename the model asset) and confirm the `Non-fatal [model_load]` line appears in logcat.
- Run scan/export flows and verify failures surface in the UI error states and in `scan_sessions` rows, not only in logs.
