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
  loose (~10x the target) sanity ceiling that a correct implementation could
  never approach even on a slow/noisy CI runner. The point of the ceiling is to
  catch a genuine algorithmic blowup (e.g. an accidental return to O(n) Room
  reads), not to enforce the target numbers themselves — CI runners are shared
  and a tight wall-clock assertion would flake independently of any real
  regression.
- A separate test asserts the synthetic generator itself actually produces
  cosine similarity above the 0.6 assign threshold for same-identity variants
  and below it for different identities, with margin — otherwise the benchmark
  above would silently degenerate (e.g. everything landing in one cluster) and
  stop exercising the code paths it's meant to measure.

**Known caveat found while writing this test (not fixed here, out of scope for
P4.5):** `mergeClose`'s pairwise *comparison* loop appears to still restart its
scan from the top of the cluster list after every single merge (see the
`while (changed) { ... }` / `break@outer` structure in `FaceClusterer.kt`). This
is a comparison-count concern distinct from the DB-read-count guarantee this
benchmark asserts (which does hold) — it means a pass with many merges could
still cost more comparisons than a single O(n²) scan would suggest. Flagged for
follow-up; the benchmark's merge scenario is deliberately bounded (~300
pre-merge clusters, not one singleton per face at 5k scale) so this doesn't
blow up the test's own runtime.

### Measured numbers

From GitHub Actions run
[30713584503](https://github.com/ShivStrider/Gallery-Project/actions/runs/30713584503)
(commit `1de6829`), read off the workflow's **Benchmark output** step. Two
figures per row because the debug and release unit-test variants each run the
suite; both are given rather than averaged.

These are ubuntu-latest CI-runner numbers on synthetic embeddings under
Robolectric — useful for tracking relative change between runs, not a
substitute for on-device measurement. The targets they are compared against
were written for a mid-tier phone, so passing here is necessary, not
sufficient.

| Scale (faces) | Identities | Clusters formed | `assign` total (ms) | `assign` amortized (µs/face) | Target (amortized) |
|---|---|---|---|---|---|
| 100 | 20 | 20 | 33 / 44 | 330 / 440 | ≤ 5 000 µs/face (5 ms) |
| 1 000 | 100 | 100 | 253 / 289 | 253 / 289 | ≤ 5 000 µs/face (5 ms) |
| 5 000 | 200 | 200 | 1 195 / 1 255 | 239 / 251 | ≤ 5 000 µs/face (5 ms) |

`assign` comes in roughly 20× inside its target, and — the point of the
exercise — the amortized cost does **not** grow with scale: 5 000 faces against
200 clusters costs less per face (239–251 µs) than 100 faces against 20
clusters (330–440 µs), where JIT warm-up dominates. Clusters formed equals
identities exactly at every scale, so the run is genuinely exercising
assignment rather than collapsing into one blob or degenerating into
singletons.

Generator separation for the same run: `minIntraSim=0.798`,
`maxInterSim=0.0698` against an assign threshold of 0.6 — a wide margin either
side, which is what makes the numbers above meaningful.

| Scenario | Pre-merge clusters | Post-merge clusters | `mergeClose` total (ms) | Target |
|---|---|---|---|---|
| 300 forced pre-merge clusters (150 merges) | 300 | 150 | 1 801 / 2 065 | ≤ 1 000 ms at 200 clusters |

**`mergeClose` is the one number that does not look comfortable.** 1.8–2.1 s to
perform 150 merges is above the ≤ 1 s target, though not a like-for-like
comparison: the target is stated at 200 clusters and this scenario starts from
300. It is consistent with the comparison-restart caveat noted above — the
pairwise scan restarts and re-sorts after every merge, so cost grows with
*merges × clusters* rather than with clusters alone. The test still passes
because its wall-clock fence is deliberately loose (10 s), and the DB-read
invariant it actually asserts does hold. Treat this row as the measurement that
justifies fixing the restart, and re-measure at 200 clusters afterwards for a
clean comparison against the target.
