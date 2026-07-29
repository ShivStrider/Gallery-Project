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
