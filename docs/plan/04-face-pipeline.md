# Face Processing & Clustering Design

## Detection vs recognition

- **Detection** (where is a face): ML Kit `face-detection:16.1.6`, bundled model, fully on-device. FAST mode, no landmarks/classification, `minFaceSize=0.15`. Detection failures resume with an empty list — a bad photo never kills a scan.
- **Recognition** (same person across photos): MobileFaceNet embeddings + unsupervised clustering. There is no identification against named references and no cloud call.

## Embedding model contract

| Property | Value |
|---|---|
| Model | MobileFaceNet (TFLite), asset `app/src/main/assets/mobile_face_net.tflite` |
| Input | 1×112×112×3 float32, RGB, normalized to [-1, 1] |
| Output | 1×512 float32, L2-normalized in-app |
| Runtime | TensorFlow Lite 2.14, CPU, 4 threads (GPU/NNAPI deliberately off — correctness first) |
| Quantization | float32 for v1 (quantized variant is a Phase 7+ benchmark decision, not a default) |
| Distance metric | Cosine similarity (equivalent to dot product on normalized vectors) |
| Size | ~5 MB |
| Integrity | SHA-256 recorded in INSTALL.md and verified by the `verifyFaceModelPresent` release gate |
| Licensing | Must be verified for the chosen weights before release; MobileFaceNet weights trained on MS-Celeb-1M derivatives are NOT acceptable for distribution — see decision log D2 |
| Degradation | `FaceEmbedder.ModelState.Failed`: detection-only mode, clear user-facing message, no crash |

Preprocessing: ML Kit bbox + 20% margin → crop → scale 112×112 → float array. Orientation is corrected at decode time (EXIF) before detection, so crops are upright.

## Clustering design

Online centroid clustering (existing `FaceClusterer`, optimized in Phase 4):

- **Assign**: new embedding vs every cluster centroid (in-memory cache after Phase 4); best cluster wins if cosine ≥ `assignThreshold` (default 0.60, user-adjustable "strictness"); centroid updated as running mean then re-normalized; otherwise a new cluster opens.
- **Merge**: periodic `mergeClose()` pass unifies centroid pairs ≥ `mergeThreshold` (0.75, always > assign threshold — invariant tested in `FaceRecognitionConfigTest`).
- **Minimum cluster size**: groups below `minClusterSize` (user setting) are hidden from the main grid.
- **Uncertainty handling (Phase 4 addition)**: faces whose best match falls in the ambiguous band land in a "Review needed" surface instead of polluting named groups; accuracy beats coverage.
- **Incremental behaviour**: new photos are embedded and assigned against existing centroids without touching prior assignments; full recluster (threshold change) rebuilds everything from stored embeddings in one transaction — no re-scan, no re-inference.
- **Multi-person photos**: one row per face; a photo with faces in clusters A and B appears in both groups. Export planning intersects by photo, so the preview flags "contains other people".
- **Manual corrections**: rename, merge (user-requested merge unifies centroids), reassign a photo's faces out of a group. Corrections must survive recluster (Phase 4 verifies/pins user-confirmed memberships).
- **Cover face**: highest-quality face with 0.05 hysteresis to avoid thumbnail flapping.

## Benchmarking method

Synthetic embedding sets (seeded unit vectors with controlled intra/inter-person jitter — pattern already used by `FaceClustererTest`) at 100 / 1 000 / 5 000 faces; wall-clock and allocation budgets per `07-performance-plan.md`. Real-image accuracy is validated manually against a small legally-usable dataset — never user photos, never in CI.
