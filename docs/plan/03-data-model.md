# Data Model (Room v4)

Database `FaceAlbumDatabase`, version 4 after Phase 6 (v3 shipped + additive `MIGRATION_3_4`). All tables device-local; the whole database is excluded from Android backup. Embeddings are biometric-derived data: never logged, never exported, erasable via Settings → Delete face data (which must also clear the export log).

## Existing tables (v3, unchanged)

### photos
Purpose: one row per MediaStore image the scanner has seen. PK `id` autogen; unique index `mediaStoreId`. Fields: `mediaStoreId`, `uri`, `displayName`, `dateTaken`, `dateModified`, `processedAt`, `faceCount`. Insert strategy ABORT (never REPLACE — a REPLACE would cascade-delete faces). Retention: cleared by "Delete face data"; rows reconciled against MediaStore on scan (Phase 2: rows whose backing media vanished are removed, changed rows re-indexed). `faceCount=0` rows are written even for undecodable photos so incremental scans advance.

### faces
Purpose: one detected face. PK `id`; FK `photoId`→photos CASCADE; FK `clusterId`→clusters SET_NULL; indexes on both FKs. Fields: bbox (4 ints), `embedding` (512×float32 little-endian BLOB, L2-normalized), `quality` (bbox/photo area ratio). Sensitive: the embedding is the biometric-derived payload.

### clusters
Purpose: one person group. PK `id`. Fields: `displayName` (null until named), `coverFaceId`, `faceCount`, `centroid` (running-mean BLOB, L2-normalized), timestamps. Reactive UI query: `summariesAtLeast(minSize)`.

### albums
Purpose: export history (one row per completed export). FK `clusterId` SET_NULL so recluster never erases history. Phase 6 keeps writing it (compatibility with ExportCompleteScreen) alongside the richer operation log.

### scan_sessions
Purpose: scan bookkeeping + orphan cancellation (`markOrphansCancelled` runs at scan start). Fields: timestamps, `status`, counts, `errorMessage`, `forceFullRescan`. Kept as-is.

## New tables (v4 — Phase 6)

### export_operations
One row per export the user confirmed. PK `id`; FK `clusterId`→clusters SET_NULL; indexes `clusterId`, `state`.
Fields: `albumName`, `destRelativePath` (`Pictures/FaceAlbums/<album>/`), `mode` (`COPY`|`MOVE`), `state`, `totalCount`, `createdAt`, `updatedAt`.
States: `PENDING → RUNNING → COMPLETED` (copy) · `RUNNING → AWAITING_DELETE_CONSENT → FINALIZING → COMPLETED` (move) · `COMPLETED_WITH_ERRORS` · `CANCELLED` · `UNDONE`. `AWAITING_DELETE_CONSENT` is durable: it survives process death and is re-offered via a banner on next launch.

### export_items
One row per file in an operation — **this is the per-file transaction log**. PK `id`; FK `operationId`→export_operations CASCADE; indexes `operationId`, (`operationId`,`state`).
Fields: `photoId` (informational), `sourceMediaStoreId`, `sourceUri`, `sourceDisplayName`, `sourceRelativePath` (captured at plan time — required for undo restore), `sourceSizeBytes`, `sourceSha256` (computed while streaming the copy), `destDisplayName` (conflict-resolved at plan time), `destUri`, `state`, `errorCode`, `updatedAt`.
State machine (every transition is one immediately-committed UPDATE):

```
PENDING ─copy─> COPIED ─verify─> VERIFIED ─consent granted─> SOURCE_DELETED
   │               │                │────────consent denied─> DELETE_DENIED
   ├─> COPY_FAILED └─> VERIFY_FAILED (dest deleted; source untouched)
SKIPPED_DUPLICATE (dedup hit whose checksum matched the source)
Undo:   VERIFIED / DELETE_DENIED / SKIPPED_DUPLICATE ─> UNDONE   (dest deleted)
        SOURCE_DELETED ─restore─> RESTORED (re-copied to original path, verified, dest deleted)
```

Invariants (enforced in code, asserted by the destructive-operation suite):
1. A source URI may enter a delete batch **only** from `VERIFIED`/`SKIPPED_DUPLICATE`.
2. `VERIFY_FAILED` always deletes the destination copy, never the source.
3. No state transition ever writes to a source file.

Retention: operations and items persist as the undo/audit record; wiped by "Delete face data". Copy mode: `VERIFIED` is terminal success.

## Migration strategy

- v1→v2, v2→v3: shipped, hand-written, tested — never edited.
- v3→v4: additive only (`CREATE TABLE` + indexes). Schema JSON `4.json` committed; `FaceAlbumDatabaseMigrationTest` extended with 3→4 and 1→4 paths.

## Thumbnails

No thumbnail table: Coil's disk/memory cache renders grid cells from content URIs on demand. Cached thumbnails are replaceable derivatives, never source data.
