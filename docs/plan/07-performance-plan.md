# Performance Plan (~10 GB libraries)

## Principles

The pipeline is already bounded (streaming flows, `buffer(2)`, decode cap 1024 px, low-RAM concurrency fallback); the scaling problems are read amplification in clustering and unbounded SQL `IN` lists. Fix measured bottlenecks; do not optimize blindly.

## Targets (mid-tier device, Pixel 6a class)

| Metric | Target |
|---|---|
| Peak native+heap during scan | No OOM on 2 GB-heap-class devices; decode ≤ 1024 px; ≤ 2 bitmaps in flight (1 on low-RAM) |
| Index throughput | ~100 ms/photo; 1 000 photos ≤ 10 min end-to-end |
| `FaceClusterer.assign` | Amortized ≤ 5 ms at 5 000 faces / 200 clusters (in-memory centroids) |
| `mergeClose` | ≤ 1 s at 200 clusters |
| Cluster detail open | ≤ 500 ms at 2 000 photos (chunked `findByIds`) |
| People grid scroll | No dropped-frame bursts with 200 groups (Coil sized requests) |
| Export throughput | I/O-bound; checksum adds one read pass per file — acceptable; progress per item |
| DB queries | No unbounded `IN` (chunk at 900); no write transaction held across ML inference |
| Battery/thermal | Foreground dataSync + `requiresBatteryNotLow` (existing); scans resume, never restart |
| Cancellation | Worker cancel honored between items; no partial rows left |

## Benchmarks (Phase 7, guarded in CI where JVM-runnable)

MediaStore discovery (mocked cursor scale), thumbnail/grid (manual + macrobenchmark optional), detection+embedding per-photo timing (on-device manual matrix), DB insert batches, clustering at 100/1k/5k (JVM, seeded embeddings — CI-guarded), export copy+verify throughput (JVM temp files), gallery scrolling (manual), scan resume overhead.

Test libraries: 100 / 1 000 / 5 000+ synthetic images (script-generated, varied sizes ≈10 GB aggregate for the soak test). No personal photos.

## Time estimates (documented honestly for users)

~10 GB ≈ 3–5k photos: first full index expected 30–90 min depending on device, run as a resumable background job with progress + ETA; incremental re-scans touch only changed rows.

## Measured results (P4.5, clustering performance validation)

`app/src/test/java/com/facealbum/perf/ClusteringBenchmarkTest.kt` is the JVM/Robolectric
benchmark for `FaceClusterer` referenced above. It runs at 100, 1 000, and 5 000
synthetic faces (~20 / 100 / 200 distinct synthetic "identities" respectively, each
with several near-duplicate embeddings, deterministically generated with a fixed
seed — no real photos or face data).

**What it measures and asserts:**

- **`assign` amortized cost stays flat as face count grows.** The hard assertion
  is not on wall-clock — it's that `ClusterDao.all()` (the full centroid-table
  read) is called **exactly once** per clusterer instance, regardless of whether
  100 or 5 000 faces are assigned through it. This is the operation-count proxy
  for the P4.1/P4.2 in-memory centroid cache fix: before that fix, `assign`
  re-read and re-deserialized every centroid from Room on every single face,
  which would make this call count grow linearly with face count instead of
  staying at 1.
- **`mergeClose` does not reload centroids per merge.** Same technique: a
  scenario is constructed with ~300 pre-merge clusters (via an artificially
  strict assign threshold, forcing near-duplicates into separate clusters) so
  that a single `mergeClose()` call has to perform many merges. The hard
  assertion is that `ClusterDao.all()` is called exactly once for that whole
  pass, proving the merge loop works off the in-memory cache rather than
  re-querying Room after absorbing each pair.
- **Wall-clock is measured and printed, not hard-asserted**, guarded only by a
  loose sanity ceiling that a correct implementation could never approach even
  on a slow/noisy CI runner (50 ms/face amortized for `assign`; 5 s for the
  `mergeClose` pass, tightened from 10 s once the restart fix landed). The
  point of the ceiling is to catch a genuine algorithmic blowup, not to enforce
  the target numbers themselves — CI runners are shared and a tight wall-clock
  assertion would flake independently of any real regression.
- A separate test asserts the synthetic generator itself actually produces
  cosine similarity above the 0.6 assign threshold for same-identity variants
  and below it for different identities, with margin — otherwise the benchmark
  above would silently degenerate (e.g. everything landing in one cluster) and
  stop exercising the code paths it's meant to measure.

**Caveat found by this benchmark, since fixed (P4.2).** `mergeClose`'s pairwise
*comparison* loop used to `break@outer` and restart its scan — re-sorting the
whole cluster list — after every single merge, coupling cost to merge count
rather than cluster count. The benchmark measured that at 1801/2065 ms for 150
merges from 300 clusters, against a ≤ 1 s target, which is what justified
fixing it. Each pass now sorts once and absorbs into a per-pass dead set,
repeating passes only while the previous one merged something. Two tests in
`FaceClustererTest` pin the semantics the rewrite had to preserve: the larger
cluster still absorbs the smaller (keeping its id and user-assigned name), and
convergence still happens across passes when one merge newly brings a third
cluster within threshold. Measured effect below.

### Measured numbers

From GitHub Actions run
[31072313418](https://github.com/ShivStrider/Gallery-Project/actions/runs/31072313418)
(commit `047ec3d`), read off the workflow's **Result summary** step. Two figures
per row because the debug and release unit-test variants each run the suite;
both are given rather than averaged. Where only one figure appears, both
variants printed the identical line and it was deduplicated.

Note these numbers are unaffected by `EMBEDDING_SIZE` dropping from 512 to 128
when the real model was bundled: the benchmark generates its own 512-dimension
synthetic vectors (`private val dim = 512`) and never reads
`FaceRecognitionConfig`, so it stays comparable across that change. Verified by
reading the test, not assumed.

These are ubuntu-latest CI-runner numbers on synthetic embeddings under
Robolectric — useful for tracking relative change between runs, not a
substitute for on-device measurement. The targets they are compared against
were written for a mid-tier phone, so passing here is necessary, not
sufficient.

| Scale (faces) | Identities | Clusters formed | `assign` total (ms) | `assign` amortized (µs/face) | Target (amortized) |
|---|---|---|---|---|---|
| 100 | 20 | 20 | 35 | 350 | ≤ 5 000 µs/face (5 ms) |
| 1 000 | 100 | 100 | 317 / 458 | 317 / 458 | ≤ 5 000 µs/face (5 ms) |
| 5 000 | 200 | 200 | 1 424 / 1 542 | 284.8 / 308.4 | ≤ 5 000 µs/face (5 ms) |

`assign` comes in roughly 16× inside its target, and — the point of the
exercise — the amortized cost does **not** grow with scale: 5 000 faces against
200 clusters costs 285–308 µs per face, no worse than 100 faces against 20
clusters at 350 µs, where JIT warm-up dominates. Clusters formed equals
identities exactly at every scale, so the run is genuinely exercising
assignment rather than collapsing into one blob or degenerating into
singletons. (An earlier run recorded 239–440 µs/face across the same scales;
the spread between runs is runner noise, which is exactly why the hard
assertions are on operation counts rather than wall-clock.)

Generator separation for the same run: `minIntraSim=0.798`,
`maxInterSim=0.0698` against an assign threshold of 0.6 — a wide margin either
side, which is what makes the numbers above meaningful.

| Scenario | Pre-merge clusters | Post-merge clusters | `mergeClose` total (ms) | Target |
|---|---|---|---|---|
| 300 forced pre-merge clusters (150 merges) | 300 | 150 | **232 / 569** | ≤ 1 000 ms at 200 clusters |

**`mergeClose` now clears its target with room to spare.** Removing the
per-merge restart took 150 merges from 300 clusters from 1801/2065 ms down to
**232/569 ms** — a 3–8× reduction, and now comfortably inside the ≤ 1 s target
even though this scenario starts from 300 clusters rather than the 200 the
target is stated at. That confirms the restart really was the dominant cost,
rather than the diagnosis being wrong about where the time went.

The benchmark's wall-clock fence was tightened from 10 s to 5 s alongside the
fix, so a regression back toward the old behaviour now fails the build instead
of passing quietly. The fence stays well above the measured figure on purpose:
it exists to catch an algorithmic regression, not to police runner variance —
note the 232 vs 569 ms spread between the two variants in a single run.

### `refineAssignments` (D18)

| Scale (faces) | Clusters | Faces moved | Converged on 2nd pass | Centroid-table reads | Total (ms) |
|---|---|---|---|---|---|
| 5 000 | 200 | 250 / 250 planted | yes (0 moved) | 1 | **1 481 / 1 546** |

The refinement pass is inherently O(iterations × faces × clusters) cosine
comparisons, and `ReclusterUseCase` runs it *inside the recluster's single Room
write transaction* — so its cost is a transaction held open, not just time.
What keeps that acceptable is that all of the work is in-memory float math off
**one** face read and **one** centroid read; the hard assertion is the read
count, exactly as for `assign` and `mergeClose`. Wall-clock is printed and
fenced loosely (60 s) rather than asserted tightly: at this scale the pass is
genuinely a few hundred million multiplies, and the fence exists to catch an
algorithmic regression, not runner variance.

**This benchmark initially proved nothing, and the fix is worth recording.**
Its first green run printed `movedFirstPass=0`. The synthetic identities are
separated too cleanly by construction (`maxInterSim ≈ 0.07` against a 0.6
threshold), so greedy `assign` already produced exactly 200 correct clusters —
refinement had nothing to move, took the `totalMoved == 0` early return, and
never reached the write-through code at all. Both assertions then held
vacuously: the read count was low because barely anything executed, and
"converges to zero moves" was trivially true because the first pass was also
zero. A benchmark in that state is worse than none, because it reads as
coverage.

The fixture now parks every twentieth face in a different cluster before the
pass runs — only the face rows move, so the stored centroids still describe the
correct membership and there is an unambiguous right answer to find. That is
the same order-dependence artifact `refineAssignments` exists to repair. With
real work to do, all 250 strays return to their correct cluster (reachable only
*through* the write-through path), and the second-pass zero is a genuine
convergence result rather than a restatement of the first.

The earlier `782 ms` figure was therefore a single sweep before an early
return, not the multi-sweep cost; **1 481 / 1 546 ms** is the honest number.

**Not measured:** all of the above is synthetic data on a CI runner, not a real
photo library on a phone. Nothing here says whether the grouping is *good* —
only that it is fast and self-consistent. The thresholds in D18/D19 remain
reasoned rather than tuned.
