# QA Matrix

## Mandatory Scenarios

### 1) Permission Flows
- Validate runtime permission behavior on **API 26–32** (legacy/storage/photo access pathways).
- Validate runtime permission behavior on **API 33+** (new media permission model and related prompts).
- Confirm first-run, deny, deny permanently, and grant-after-deny paths.

### 2) Scan Cancel/Resume Behavior
- Start a library scan and cancel mid-process.
- Verify app state consistency and progress persistence/rollback expectations.
- Resume scan and confirm it completes correctly without duplicate or missing results.

### 3) Export and Gallery Visibility
- Validate export **success** flow (file output, user confirmation, metadata if applicable).
- Validate export **failure** flow (storage unavailable, permission denied, I/O error handling).
- Confirm exported media visibility in major gallery apps after export/indexing.

### 4) Large-Library Performance Sanity
- Run sanity checks against a large media library.
- Validate scan responsiveness, memory stability, and acceptable completion behavior.
- Confirm no ANRs or severe UI stalls during core flows.

### 5) Lifecycle Behavior (Rotation/Background/Foreground)
- Verify in-progress operations and UI state across **device rotation**.
- Verify behavior when app moves to **background** and returns to **foreground**.
- Confirm no data loss, corrupted state, or crashes during lifecycle transitions.

## Blocking Release Exit Criteria
- **Zero P0/P1 defects** open at release decision time.
- **Crash-free smoke run** completed for release candidate build.
- **All mandatory scenarios passed on at least 3 API levels**.

## Acceptance Criteria
- QA signoff checklist is completed for each build candidate.
