# Product Specification & MVP Scope

## Executive summary

FaceAlbum is a privacy-first Android app that scans photos already on the device, detects faces, groups photos of the same person, lets the user review and correct those groups, and exports one person's photos as an album — by **moving** only the explicitly selected files into a dedicated folder. Everything runs on-device; nothing is uploaded anywhere. The primary use case is separating valuable photos of specific people from the flood of media accumulated via WhatsApp, Instagram, and downloads.

## Product principle

Build the smallest reliable application that solves the core problem: find local photos → detect faces → group the same person → review → select → move safely → recover from errors. Accuracy over coverage: when grouping is uncertain, surface it for review instead of forcing a risky match. Safety over convenience: a source photo is never deleted until its copy is verified and the user has confirmed the deletion through the Android system dialog.

## MVP scope (in)

1. MediaStore-based photo discovery: camera, downloads, WhatsApp, Instagram, screenshots — every image MediaStore can see; incremental re-scan; resilient to deleted/moved photos.
2. On-device face detection (ML Kit) and face embeddings (MobileFaceNet TFLite, 128-D — recorded here as 512-D until the model was actually sourced and converted; the graph emits 128).
3. Automatic grouping (online centroid clustering, cosine similarity) with an explicit "review needed" surface for low-confidence faces.
4. Review UI: people grid, person detail, rename, merge, move a photo out of a group, multi-select, full-screen viewer.
5. Export as **Copy** (default until the destructive-operation test suite passes) or **Move** (API 30+; copy → verify → user-consented source deletion), with an exact pre-flight preview of every file affected, a persisted per-file transaction log, resume after interruption, a result report, and undo.
6. Resumable background processing (WorkManager foreground jobs) with progress, cancellation, and battery/low-RAM awareness.
7. Settings: grouping strictness, minimum group size, theme, re-scan, delete all face data.
8. Works with ~10 GB photo libraries without holding the library or all embeddings in memory.

## Explicitly out of scope

Social features; accounts; cloud sync/upload of any kind; photo editing; filters; sharing feeds; messaging; advertising; analytics collecting personal information; **video processing** (first release is images only); duplicate-cleaning beyond what safe export requires; complex animations; speculative settings. No INTERNET permission — the app has no network code path at all.

## Definition of done (release gate)

- Builds reproducibly from a fresh clone (debug) and with documented secrets + model asset (release).
- Scans accessible photos, processes a large library without crashing, resumes interrupted scans.
- Groups likely matches; uncertain faces go to review instead of wrong groups; user corrections stick.
- Export previews the exact file list; only selected files are ever touched; destination verified (existence, size, checksum, readability) before any source deletion; deletion only via the system consent dialog; interrupted exports resume without loss or duplication; unrelated files provably untouched (test suite).
- Facial data never leaves the device (no INTERNET, backup excluded, no sensitive data in logs).
- Automated tests pass in CI including the destructive-operation suite; performance measured at 100/1k/5k photos; limitations documented honestly.

An honest audit of this release gate, criterion by criterion, is kept in
[`docs/release/definition-of-done.md`](../release/definition-of-done.md).
