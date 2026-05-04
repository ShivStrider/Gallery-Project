# Crash/Incident Triage Playbook

## Scope
This playbook covers model-load failures, scan exceptions, and export failures captured by CrashReporter.

## Event taxonomy
- `error_source=model_load`: TensorFlow model failed to initialize.
- `error_source=scan`: Non-fatal exception during scanning pipeline.
- `error_source=export`: Export copy failed for an approved photo.

## Data handling rules
- Never include photo URIs, filenames, album names, or image metadata in telemetry.
- Only include aggregate/non-sensitive keys (counts, threshold values, booleans, and build type).

## Triage workflow
1. Filter Crashlytics by app version and `build_type=internal` when validating a new release.
2. Group by `error_source` and identify top recurring exceptions.
3. For `model_load` errors:
   - verify model asset is packaged (`mobile_face_net.tflite`)
   - verify install path and app update channel
4. For `scan` errors:
   - compare `seed_count`, `threshold`, and `max_scan`
   - reproduce with a synthetic test library (no customer media)
5. For `export` errors:
   - check storage permission state and free-space status
   - verify destination media collection availability
6. File a bug with: stack trace, app/build version, `error_source`, custom keys, and reproduction notes.

## Acceptance checks
- Internal build: trigger `FirebaseCrashlytics.getInstance().log("crashlytics smoke")` and a test crash to confirm dashboard ingestion.
- Run scan/export flows and verify non-fatal errors appear with `error_source` and contextual keys.
