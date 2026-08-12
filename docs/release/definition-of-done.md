# Definition of Done — acceptance audit

Audits the release gate in [`docs/plan/01-product-spec.md`](../plan/01-product-spec.md)
against what the repository can actually demonstrate today.

**The headline: this is not release-ready, and the reason is uniform.** Nearly
everything is implemented and verified *in CI, on a JVM, against synthetic
data*. Almost nothing has been verified *on an Android device*. Every ❌ and 🟨
below traces back to that one gap. A single afternoon with a phone would
convert most of them.

Legend: ✅ met and evidenced · 🟨 partially met · ❌ not met

---

## 1. Builds reproducibly from a fresh clone (debug) and with documented secrets + model asset (release)

**🟨 Debug yes, release unproven.**

Debug builds from a clean clone with no configuration: Firebase and its
`google-services.json` requirement are gone, and the MobileFaceNet asset is now
committed with its SHA-256 pinned in `gradle.properties`. CI assembles a debug
APK on every push, which is direct evidence.

Release has never been attempted. `assembleRelease` requires four signing
env vars and `verifyReleaseSigningConfigured` fails loudly without them — by
design — but no CI job exercises that path, so "documented secrets produce a
signed build" is a claim from reading the Gradle config, not an observation.

## 2. Scans accessible photos, processes a large library without crashing, resumes interrupted scans

**❌ Not verified.**

The code exists: MediaStore incremental discovery, a bounded two-stage pipeline
(decode+detect concurrent, TFLite serial), `scan_sessions` rows with orphan
recovery, WorkManager foreground jobs, and a low-RAM concurrency fallback.

None of it has run on a device. No large library has been scanned, no scan has
been interrupted and resumed, and no memory ceiling has been measured. The
~10 GB soak in the performance plan is outstanding. This is the single largest
unknown in the project.

## 3. Groups likely matches; uncertain faces go to review instead of wrong groups; user corrections stick

**❌ Not met, in two distinct ways.**

*Grouping accuracy is unmeasured.* The bundled model was verified for input and
output shape, dtype, finiteness, determinism and unit-norm output — not for
recognition quality. No labelled-dataset benchmark has been run, and the model
has never processed a real photograph.

*"Uncertain faces go to review" is not what was built.* The Review-needed
surface added in P4.3 shows clusters below the minimum-group-size setting,
which fixes a real bug — those faces were previously unreachable entirely. But
it is not an uncertainty band. A face whose best match sits just under the
assign threshold still becomes a new singleton cluster rather than being parked
for review, and a face just over the threshold is still assigned outright with
no signal that it was marginal. The spec's intent — *uncertain* faces avoid
wrong groups — is not yet implemented.

User corrections persisting across a recluster has unit coverage, so that
sub-clause holds.

## 4. Export: exact preview, only selected files touched, destination verified before deletion, consent-gated deletion, resumable, unrelated files provably untouched

**🟨 Implemented and tested; validated against a simulated MediaStore.**

Strongest area of the project. `ExportPlanner` produces an inspectable plan
before anything is touched; every copy streams through SHA-256 and is re-read
and compared before becoming deletion-eligible; deletion is gated on
`MediaStore.createDeleteRequest`; each per-file transition commits before the
next file, so process death is resumable; undo restores before cleanup with
three explicit refusals. Nine tests in `DestructiveExportSafetyTest` cover the
invariants, including that unselected files never enter a delete batch and that
consent denial leaves everything intact.

Two reasons this is 🟨 rather than ✅. The suite exercises a *simulated*
MediaStore — a `deletedSources` set standing in for the OS — so the real system
consent dialog has never been involved. And Move is disabled behind
`ExportFeature.MOVE_ENABLED`, so the destructive path does not ship at all
today. "Provably untouched" is proven against a model of Android, not Android.

## 5. Facial data never leaves the device

**✅ Met, and enforced rather than asserted.**

No INTERNET permission in the *merged* manifest, no networking library in the
dependency graph (Firebase removed entirely), `allowBackup="false"` plus
data-extraction and full-backup rules excluding every domain, and a CI grep
guard that fails the build if a Timber call logs a photo URI, album name or
display name. Each of these is checked mechanically, not just documented.

## 6. Automated tests pass in CI including the destructive suite; performance measured at 100/1k/5k photos; limitations documented honestly

**🟨 Two of three.**

Tests: ✅ — CI runs the full suite on every push and reports per-suite counts,
specifically so a silently-skipped class cannot hide behind a green badge. The
destructive suite is among them.

Performance: 🟨 — measured at 100/1k/5k **synthetic embeddings**, not photos.
That covers clustering (`assign` flat at 285–308 µs/face; `mergeClose` 232/569 ms
after the restart fix) but says nothing about decode, detection, embedding, or
end-to-end throughput, which are the parts that dominate a real scan and need a
device.

Limitations documented: ✅ — see
[`known-limitations.md`](known-limitations.md), with claims cited to source.

---

## What would actually close this

In dependency order, and mostly not code:

1. **Run it on a phone.** Closes most of §2, gives the first real signal on §3,
   and is a prerequisite for everything below.
2. **Judge grouping quality on a real library.** If it is poor, that is a model
   or threshold problem and it is better to learn it now than after §4.
3. **Move-mode verification with synthetic photos** against the real consent
   dialog, which is the stated exit criterion for the feature flag.
4. **Memory ceiling and the ~10 GB soak** — the remaining Phase 7 items.
5. **A release build** with real signing, to retire the §1 caveat.
6. **An uncertainty band** for §3, if step 2 shows marginal assignments are
   actually causing wrong groups. Worth deferring until there is evidence.
