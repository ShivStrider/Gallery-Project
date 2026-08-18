# Face Processing & Clustering Design

## Detection vs recognition

- **Detection** (where is a face): ML Kit `face-detection:16.1.6`, bundled model, fully on-device. `PERFORMANCE_MODE_ACCURATE` + `LANDMARK_MODE_ALL`, `minFaceSize=0.15`. Detection failures resume with an empty list — a bad photo never kills a scan.
  - Both of those modes are **load-bearing, not preferences**: the aligner below needs the five landmarks, and FAST mode does not produce them reliably enough to align from. An earlier revision of this document specified "FAST mode, no landmarks/classification", which described the configuration that produced the original bad grouping.
- **Pose gating**: faces beyond `MAX_YAW_DEGREES` (40°) or `MAX_ROLL_DEGREES` (35°) are detected but not embedded. A steep profile cannot be aligned onto a frontal template without distortion, and the resulting embedding says more about the angle than the person.
- **Recognition** (same person across photos): MobileFaceNet embeddings + unsupervised clustering. There is no identification against named references and no cloud call.

## Embedding model contract

| Property | Value |
|---|---|
| Model | MobileFaceNet (TFLite), asset `app/src/main/assets/mobile_face_net.tflite` |
| Input | 1×112×112×3 float32, RGB, `(x - 127.5) / 128` → [-0.99609375, 0.99609375], **5-point aligned** to the ArcFace template |
| Output | 1×128 float32, L2-normalized by the graph (and again in-app, idempotent) |
| Runtime | TensorFlow Lite 2.14, CPU, 4 threads (GPU/NNAPI deliberately off — correctness first) |
| Quantization | float32 for v1 (quantized variant is a Phase 7+ benchmark decision, not a default) |
| Distance metric | Cosine similarity (equivalent to dot product on normalized vectors) |
| Size | ~5 MB |
| Integrity | SHA-256 recorded in INSTALL.md and verified by the `verifyFaceModelPresent` release gate |
| Licensing | **Resolved.** Apache-2.0, weights from `sirius-ai/MobileFaceNet_TF`, verified against that repo's LICENSE. Provenance, both checksums and the exact conversion command are in `app/src/main/assets/README_MODEL.txt`; the artifact SHA-256 is pinned as `faceModelSha256`. See decision log D2 |
| Degradation | `FaceEmbedder.ModelState.Failed`: detection-only mode, clear user-facing message, no crash |

### Preprocessing — alignment, not just cropping

`FaceAligner` warps each detected face onto the ArcFace/InsightFace canonical
112×112 five-point layout using a closed-form least-squares **similarity**
transform (uniform scale + rotation + translation). Landmark pairs are ordered
by x rather than trusting ML Kit's subject-relative LEFT/RIGHT naming, which
flips on a mirrored selfie.

Deliberately **not** `Matrix.setPolyToPoly`: with four or more points that fits a
perspective warp, which distorts the face and defeats the purpose. If any of the
five landmarks is missing, the code falls back to `FacePreprocessor`'s bbox crop
with 20% margin rather than skipping the face.

Orientation is corrected at decode time (EXIF) before detection, so inputs are
upright.

**Why this matters more than any threshold.** MobileFaceNet was trained and
evaluated exclusively on aligned crops (confirmed against the upstream
`utils/data_process.py`). Feeding it raw bbox crops made embeddings encode pose
and framing about as strongly as identity — the direct cause of the original
"everyone appears in everyone's group" behaviour. No amount of threshold tuning
fixes an embedding that is not measuring identity.

## Clustering design

Online centroid clustering (existing `FaceClusterer`, optimized in Phase 4):

- **Assign**: new embedding vs every cluster centroid (in-memory cache after Phase 4); best cluster wins if cosine ≥ `assignThreshold` (default 0.60, user-adjustable "strictness"); centroid updated as running mean then re-normalized; otherwise a new cluster opens.
- **Merge**: periodic `mergeClose()` pass unifies centroid pairs ≥ `mergeThreshold` (0.75, always > assign threshold — invariant tested in `FaceRecognitionConfigTest`).
- **Anti-chaining guard**: once either side of a candidate pair has ≥ `CLUSTER_MERGE_CHAIN_GUARD_SIZE` (8) faces, the pair must clear `mergeThreshold + CLUSTER_MERGE_CHAIN_GUARD_MARGIN` (0.05). Centroid linkage chains — A absorbs B, the shifted centroid now reaches C, and two different people are bridged. Face count is already cached and only grows within a pass, so it is a free signal that a cluster has absorbed others and its centroid may no longer sit near any single member. Chosen over average-linkage sampling because that would put Room reads inside the O(n²) pairwise loop.
- **Refinement (`refineAssignments`)**: `assign()` is a single greedy online pass, so a face is locked into whichever cluster existed when it arrived and is never revisited as that centroid drifts. The refinement pass runs up to three Lloyd-style sweeps over every face against the *final* centroids, entirely in memory off one face read and one centroid read. A face moves only if a different cluster is nearest, clears the assign threshold, and beats its current cluster by `REFINE_HYSTERESIS_MARGIN` (0.02) — without that margin a near-equidistant face flaps forever, because each move nudges both centroids. Wired into `ReclusterUseCase` only; incremental scans still get the merge pass alone.
- **Minimum cluster size**: groups below `minClusterSize` (user setting) are hidden from the main grid.
- **Uncertainty handling (Phase 4 addition)**: faces whose best match falls in the ambiguous band land in a "Review needed" surface instead of polluting named groups; accuracy beats coverage.
- **Incremental behaviour**: new photos are embedded and assigned against existing centroids without touching prior assignments; full recluster (threshold change) rebuilds everything from stored embeddings in one transaction — no re-scan, no re-inference.
- **Multi-person photos**: one row per face; a photo with faces in clusters A and B appears in both groups. Export planning intersects by photo, so the preview flags "contains other people".
- **Manual corrections**: rename, merge (user-requested merge unifies centroids), reassign a photo's faces out of a group. Corrections must survive recluster (Phase 4 verifies/pins user-confirmed memberships).
- **Cover face**: highest-quality face with 0.05 hysteresis to avoid thumbnail flapping.

## Benchmarking method

Synthetic embedding sets (seeded unit vectors with controlled intra/inter-person jitter — pattern already used by `FaceClustererTest`) at 100 / 1 000 / 5 000 faces; measured numbers in `07-performance-plan.md`. The assertions are on **read counts**, not wall-clock: an algorithmic regression must not be able to hide behind a fast runner, and a wall-clock threshold on a shared CI runner is a flake generator.

One caution learned the hard way, recorded in `07-performance-plan.md`: the generator separates identities so cleanly (`maxInterSim ≈ 0.07` against a 0.6 threshold) that greedy assign already produces perfect clusters. A benchmark for a *corrective* pass therefore has to introduce the defect it is meant to correct, or it measures an early return and passes while proving nothing.

**Accuracy remains unmeasured.** No benchmark has been run against a labelled dataset, and the thresholds in decision log D6/D18/D19 are reasoned rather than tuned. Real-image accuracy is to be validated manually against a small legally-usable dataset — never user photos, never in CI.
