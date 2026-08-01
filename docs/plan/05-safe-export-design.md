# Safe Album Export Design

"Export" means the user picks a person (or a subset of that person's photos) and the app produces `Pictures/FaceAlbums/<Album>/` containing those photos — as copies (Copy mode) or by relocating them (Move mode). Move is the headline feature and the highest-risk code in the product; this design makes data loss structurally impossible rather than merely unlikely.

## The ownership constraint (drives everything)

Source photos belong to other apps (camera, WhatsApp…). On API 30+ an app can only delete media it doesn't own through `MediaStore.createDeleteRequest()`, which shows a system confirmation UI and must be launched from a foreground Activity. Therefore a "move" is two phases with different executors:

- **Phase M1 — background (`ExportWorker`)**: copy + verify every file. Zero destructive actions. Restart-safe.
- **Phase M2 — foreground (UI)**: one batched consent dialog (chunks of ~250 URIs) covering only *verified* items. User declines → sources stay; the operation degrades to a completed copy. That is a safe outcome, not an error.

**API gate**: Move mode exists on API 30+ only. API 29 would need one `RecoverableSecurityException` round-trip per file; ≤28 would need `WRITE_EXTERNAL_STORAGE`. Below 30 the UI offers Copy only and `ExportMoveUseCase` rejects move plans. Destination files under `Pictures/FaceAlbums/` are app-owned, so undo-deletes of destinations never need consent.

**Rollout gate**: Move mode ships behind a flag that flips on only when the destructive-operation suite (below) is green in CI. Until then the app is copy-only, per the master execution rules.

## Flow

1. **Plan** — `ExportMoveUseCase.plan(clusterId, albumName, photoRowIds?, mode)`: resolves the photo set (null = whole cluster; ids are intersected against actual cluster membership as a defense against stale UI selection), queries source metadata (size, path, display name), resolves destination filenames (deterministic `makeStableDisplayName`; true collisions get `_1`, `_2`… decided *now*, not mid-copy), estimates bytes. Output backs the **preview UI**: exact file list, source folders, destination, size, per-photo other-people flags. Nothing is written yet.
2. **Commit** — inserts `export_operations` + `export_items` (all `PENDING`) in one transaction, enqueues `ExportWorker` (unique name `export_op_<id>`, KEEP policy).
3. **Copy** — per item: streamed copy through a SHA-256 digest via the existing IS_PENDING MediaStore insert (reusing `copyToAlbumWithResult`'s core, which already cleans up partial rows on failure) → `COPIED` (+`sourceSha256`, `destUri`) or `COPY_FAILED`.
4. **Verify** — re-open destination independently: exists, readable, size matches, SHA-256 matches → `VERIFIED`; any failure → delete the destination copy → `VERIFY_FAILED`. **Dedup is not verification**: a pre-existing same-named file must checksum-match the source to become `SKIPPED_DUPLICATE`; otherwise the suffixed name is used.
5. **Consent (move only)** — all items terminal → op `AWAITING_DELETE_CONSENT` (durable; re-offered by a banner after process death/restart). UI: "Copies verified — delete N originals?" → `createDeleteRequest(verified source URIs)`.
6. **Finalize** — on grant, *re-query MediaStore to confirm each source row is actually gone* before marking `SOURCE_DELETED`; write the `albums` history row; op `COMPLETED`. On deny: items `DELETE_DENIED`, op `COMPLETED_WITH_ERRORS`, copies kept.
7. **Report** — per-state counts, failures with reasons, and Undo.

## Resume semantics

Every item transition commits before the next item starts, so the log always reflects reality within one file. WorkManager re-runs the worker after death; it skips terminal items and re-processes `PENDING`/`COPIED` ones. An item that died mid-copy either left a partial row (cleaned by the existing IS_PENDING failure path) or a complete dest file (detected by name, then verified-or-recopied). `AWAITING_DELETE_CONSENT` resumes through the UI banner, not the worker.

## Undo

- Copy mode / move before deletion: delete destination copies (app-owned, no consent) → items `UNDONE`.
- Move after deletion: for each `SOURCE_DELETED` item, create a MediaStore row at the original `sourceRelativePath` + name, stream back from the destination, verify against `sourceSha256`, then delete the destination → `RESTORED`. The restored file has a new MediaStore id; the stale `photos` row heals on the next incremental scan (documented behaviour).

## Failure matrix

| Condition | Behaviour |
|---|---|
| Duplicate filename, identical content | `SKIPPED_DUPLICATE` (checksum-proven), counted as success |
| Duplicate filename, different content | Suffixed destination name chosen at plan time |
| Insufficient storage | Copy fails → `COPY_FAILED`, partial row cleaned, source untouched; preview warns beforehand via size estimate |
| Permission revoked mid-run | Copy/verify failures recorded per item; operation completes with errors; nothing deleted |
| Process death / device restart | Worker resumes from the log; consent re-offered via banner |
| Consent denied | `DELETE_DENIED`; verified copies kept; sources intact |
| Source vanished mid-run | `COPY_FAILED(SOURCE_OPEN_FAILED)`; skipped |
| Corrupted source | Copies then fails checksum? No — checksum is computed from the same stream; corruption at rest is copied faithfully. Verification protects against *copy* corruption |
| Destination unavailable / MediaStore insert fails | `COPY_FAILED(INSERT_FAILED)`; source untouched |

## Destructive-operation test suite (the gate)

Synthetic files in app-owned/test directories only:
1. Unselected files never appear in any delete batch (invariant 1 asserted at the `collectDeletableSourceUris` seam).
2. Any verification failure retains the source and removes the destination.
3. Kill + resume mid-operation: no lost files, no duplicates, log consistent.
4. Collisions never overwrite an existing different file.
5. Consent denied leaves every source intact and reports honestly.
6. A failed export leaves an actionable recovery state (retry/undo from the log).
