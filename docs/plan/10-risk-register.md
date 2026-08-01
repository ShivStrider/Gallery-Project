# Risk Register

Columns: Likelihood (L/M/H) · Impact (L/M/H) · Mitigation · Detection · Recovery · Owner (Opus = design/review, Sonnet = implementation).

| # | Risk | L | I | Mitigation | Detection | Recovery | Owner |
|---|---|---|---|---|---|---|---|
| R1 | Incorrect face match (wrong person in a group) | M | H (wrong photo could be moved) | Conservative assign threshold; review-needed band; mandatory pre-export preview; user corrections | User review; preview screen | Reassign/merge tools; undo restores moved files | Opus (thresholds) |
| R2 | Missed matches (same person split across groups) | H | M | Merge pass + user merge; strictness slider | User review | Manual merge; recluster at lower strictness | Opus |
| R3 | Large memory use / OOM at 10 GB | M | H | Bounded pipeline (≤2 bitmaps), 1024 px cap, low-RAM fallback; centroid cache is ~200×2 KB | Benchmarks; soak test | Reduce concurrency; smaller decode cap | Sonnet |
| R4 | Slow processing (hours for full index) | M | M | Incremental scans; honest ETA; resumable foreground job | P7 timing matrix | Tune batch/threads; document expectations | Sonnet |
| R5 | Thermal throttling / battery drain | M | M | `requiresBatteryNotLow`; serial inference; OS-managed dataSync FGS | Soak test on device | Pause/resume via WorkManager | Sonnet |
| R6 | Accidental deletion of user photos | L | **Critical** | Verified-copy-before-delete; consent dialog; state-machine invariants; destructive test suite gate; copy-only default until suite green | Suite in CI; log audit | Undo/restore path from verified copies; transaction log names every file | Opus (design) |
| R7 | Interrupted file moves (crash/restart/SD removal) | M | H | Per-item committed log; idempotent worker resume; durable consent state | `export_items` non-terminal states at launch | Resume worker; consent banner; report | Sonnet |
| R8 | MediaStore inconsistencies (duplicate rows, stale records) | M | M | Unique `mediaStoreId` + ABORT strategy; reconcile pass (P2.3); post-delete re-query in finalize | Reconcile diff counts | Re-scan; self-healing rows | Sonnet |
| R9 | Storage permission changes mid-operation (incl. Android 14 partial) | M | M | Partial-access UI; per-item failure capture; nothing deleted on failure | Permission state checks | Re-request; resume operation | Sonnet |
| R10 | Manufacturer quirks (aggressive killers, MIUI etc.) | M | M | Foreground service + WorkManager (survives most); resume-by-design | QA matrix devices | Scan/export resume | Sonnet |
| R11 | Model licensing (MS-Celeb-1M taint) | M | H (distribution) | D2: verified-provenance model or repo stays model-free; license recorded in README_MODEL | License review at P9 | Swap weights (drop-in for 512-D) | Opus |
| R12 | Database corruption | L | H | Room WAL; additive migrations; migration tests; embeddings re-derivable from photos by re-scan | Open-failure at launch | Delete face data + re-index (photos untouched) | Sonnet |
| R13 | App update during active processing | L | M | WorkManager reschedules after update; log-driven resume | Post-update launch checks | Same resume paths as R7 | Sonnet |
| R14 | Removable-storage loss mid-export | L | H | Verification catches unreadable dest; source never deleted unverified | VERIFY_* failures | Retry when volume returns; sources intact | Sonnet |
| R15 | CI-only build verification (no local Android SDK in dev sandbox) | H | M | CI assembles APK + androidTest on every push; small commits | Red CI on push | Fix-forward on branch | Sonnet |
