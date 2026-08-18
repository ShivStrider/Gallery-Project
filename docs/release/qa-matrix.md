# QA Matrix

## Mandatory Scenarios

### 1) Permission Flows
- Validate runtime permission behavior on **API 29–32** (scoped-storage/photo access pathways).
- Validate runtime permission behavior on **API 33+** (new media permission model and related prompts).
- Confirm first-run, deny, deny permanently, and grant-after-deny paths.

### 2) Scan Cancel/Resume Behavior
- Start a library scan and cancel mid-process.
- Verify app state consistency and progress persistence/rollback expectations.
- Resume scan and confirm it completes correctly without duplicate or missing results.

### 3) Export and Gallery Visibility (Copy mode)
- Validate export **success** flow (file output, user confirmation, metadata if applicable).
- Validate export **failure** flow (storage unavailable, permission denied, I/O error handling).
- Confirm exported media visibility in major gallery apps after export/indexing.
- Confirm the **preview sheet** reports the true count: the number of files it
  promises must equal the number that appear at the destination.
- Export the **same person twice** into the same album name — the second run
  must dedup rather than overwrite or silently duplicate.
- **Undo** after a copy export removes exactly the copies it made and touches
  no original.
- Kill the app mid-export, reopen — the operation resumes without re-copying
  files already verified, and without duplicating them.

### 4) Large-Library Performance Sanity
- Run sanity checks against a large media library.
- Validate scan responsiveness, memory stability, and acceptable completion behavior.
- Confirm no ANRs or severe UI stalls during core flows.

### 5) Lifecycle Behavior (Rotation/Background/Foreground)
- Verify in-progress operations and UI state across **device rotation**.
- Verify behavior when app moves to **background** and returns to **foreground**.
- Confirm no data loss, corrupted state, or crashes during lifecycle transitions.

### 6) Android 14+ Partial Photo Access
Never exercised on hardware; the app treats "not visible under the current
grant" the same as "deleted", so a narrowed selection actively removes rows.
- Grant **"Select photos…"** rather than "Allow all" on API 34+. Confirm the
  limited-access banner appears and *Manage* re-opens the picker.
- Scan under a partial grant, then **widen** the selection — newly visible
  photos must be picked up by the next scan.
- Scan under "Allow all", then **narrow** to a subset. The photos now hidden
  are reconciled away and their clusters shrink. Confirm this is not
  mistaken for data loss, and that the counts recover when access is widened
  again.

### 7) Move Export and the Delete-Consent Dialog
**Only when `ExportFeature.MOVE_ENABLED` is turned on.** The destructive
safety suite validates against a *simulated* MediaStore, so every row below is
testing something CI has never actually observed. Run these on **synthetic
throwaway photos only — never on a real photo library.**
- Move requires **API 30+**; on API 29 the Move option must not be offered,
  and a Move request must be refused before any file is touched.
- Happy path: originals disappear only *after* the system consent dialog is
  accepted, and only files that were copied and checksum-verified.
- **Deny** the consent dialog: every original must survive, and the operation
  degrades to a completed copy.
- **Partially** accept (where the OS allows it): only the accepted files are
  gone; the rest are still present and still recorded as such.
- Kill the app while `AWAITING_DELETE_CONSENT`, reopen — the resume banner
  offers to finish, and no source was deleted in the meantime.
- **Undo after a completed move** restores originals before removing copies,
  and a restored photo re-appears in its group after the next scan (it gets a
  new MediaStore id, so this is expected to take one scan, not be instant).
- Confirm **no unselected photo** is ever included in a delete batch — pick a
  person who appears in group photos with others and check nobody else's
  originals were touched.

### 8) Grouping Quality
The bundled model's accuracy has never been measured (see
`docs/release/known-limitations.md`). This is a judgement scenario, not
pass/fail on a single run.
- On a library with several well-represented people, confirm each person's
  photos mostly land together and distinct people stay apart.
- Note the two expected failure modes separately: one person **split** across
  groups, and two people **merged** into one. They pull in opposite directions
  under *Settings → Grouping strictness*, so record which dominates before
  changing thresholds.
- Re-run *Re-group photos now* after changing strictness and confirm it
  completes without a re-scan.

## Blocking Release Exit Criteria
- **Zero P0/P1 defects** open at release decision time.
- **Crash-free smoke run** completed for release candidate build.
- **All mandatory scenarios passed on at least 3 API levels**.
- Scenario 7 is a hard gate on shipping Move mode, independent of the rest.

## Acceptance Criteria
- QA signoff checklist is completed for each build candidate.
