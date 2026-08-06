# FaceAlbum — install & run

End-to-end recipe for getting FaceAlbum running on your phone. Follow it once
top-to-bottom the first time; after that everything is a normal Android Studio
edit-compile-run cycle.

---

## 0. What you need before you start

| Item | Where to get it | Why |
|---|---|---|
| Android Studio Hedgehog or newer (2023.1.1+) | https://developer.android.com/studio | Builds the project. |
| Android SDK Platform 35 + Build-Tools 35 | Installed via Android Studio's SDK Manager | Matches `compileSdk = 35` / `targetSdk = 35` in `app/build.gradle.kts`. |
| JDK 17 (bundled with Android Studio) | Already there if you installed AS | Required by AGP 8.2. |
| A phone on Android 8.0 (API 26) or newer | Your pocket | App's min SDK. |
| ~~A MobileFaceNet `.tflite` weight~~ | **Now bundled** — see §3 | The face-embedding brain. Committed to the repo; nothing to source. |

Emulators technically work but are slow; the index loop touches MediaStore, ML
Kit, and TFLite, all of which want real hardware.

---

## 1. Get the source

```bash
git clone https://github.com/ShivStrider/Gallery-Project.git
cd Gallery-Project
git checkout claude/face-grouping-android-app-wa04nq
```

(This work lives on that branch until it lands on `main` — check
`git branch -a` if it has since been renamed or merged.)

Open the `Gallery-Project` folder in Android Studio. Let it finish "Gradle sync"
— it'll fetch Compose, Room, WorkManager, ML Kit, TFLite, KSP, etc. First sync
takes ~3–5 minutes.

---

## 2. No cloud services to set up

The app is fully offline by design: no Firebase, no `google-services.json`, no
API keys, no accounts. Crash/failure reporting is local-only (Timber, debug
builds). A fresh clone builds without any service configuration — skip
straight to the model step below.

---

## 3. The TFLite model — **now bundled, nothing to do**

The model ships with the repo:

```
app/src/main/assets/mobile_face_net.tflite     # 5,117,184 bytes
```

Its SHA-256 is pinned in `gradle.properties`, so `verifyFaceModelPresent`
fails the build if it is ever corrupted or swapped. Full provenance, license,
and the exact conversion command are in
[`app/src/main/assets/README_MODEL.txt`](app/src/main/assets/README_MODEL.txt).

Contract (see `FaceRecognitionConfig.kt`):

- **Input**: `1 × 112 × 112 × 3` float32, RGB, normalized to `[-1, 1]`
- **Output**: `1 × 128` float32, already L2-normalized by the graph

> **Corrections.** Earlier revisions of this file said the output was `1 × 512`
> and that the conversion input tensor was named `input`. Both were wrong: the
> sirius-ai graph emits **128** values from a placeholder named **`img_inputs`**,
> and `from_frozen_graph` is a TF1 entry point that lives at
> `tf.compat.v1.lite.TFLiteConverter` on TensorFlow 2. `EMBEDDING_SIZE` has been
> corrected to 128 to match the model that actually ships.

Swapping in a different model means updating `FaceRecognitionConfig.kt` to its
input size and output width, re-pinning `faceModelSha256`, and re-scanning the
library — all embeddings in one database must share a width.

### Candidate models (only if you want to replace the bundled one)

The routes below are kept for anyone substituting a different model.

**Option 3a — sirius-ai MobileFaceNet (Apache-2.0, the canonical match)**

Repo: https://github.com/sirius-ai/MobileFaceNet_TF

This is the **license-clean** source whose architecture matches our contract
(112×112 in, normalized to [-1, 1]) — though it emits 128-D, not 512-D as
this file used to claim. This is the model now bundled. Catch: the repo ships
a TensorFlow frozen graph (`.pb`), not a `.tflite`. You'll need to convert it
once:

```bash
# Inside a Python venv with TensorFlow 2.x installed
git clone https://github.com/sirius-ai/MobileFaceNet_TF
cd MobileFaceNet_TF/arch/pretrained_model

python - <<'PY'
import tensorflow as tf

# NOTE: from_frozen_graph is a TF1-era entry point. It is NOT on
# tf.lite.TFLiteConverter in TensorFlow 2 — calling it there raises
# AttributeError. Reach it through the compat.v1 shim, which TF 2.x still
# ships. (If you are on TF 1.15, drop the `.compat.v1`.)
converter = tf.compat.v1.lite.TFLiteConverter.from_frozen_graph(
    graph_def_file="MobileFaceNet_9925_9680.pb",
    input_arrays=["img_inputs"],
    output_arrays=["embeddings"],
    input_shapes={"img_inputs": [1, 112, 112, 3]},
)
open("mobile_face_net.tflite", "wb").write(converter.convert())
PY

# You should now have mobile_face_net.tflite ~5 MB
sha256sum mobile_face_net.tflite
mv mobile_face_net.tflite /path/to/Gallery-Project/app/src/main/assets/
```

Record the resulting SHA-256 in `app/src/main/assets/README_MODEL.txt`.

**Option 3b — use shubham0204's pre-built FaceNet 512 (Apache-2.0, needs config tweak)**

Repo: https://github.com/shubham0204/FaceRecognition_With_FaceNet_Android

The file `app/src/main/assets/facenet_512.tflite` is directly downloadable and
gives 512-D embeddings out of the box. **But** it expects 160×160 input, not
112×112, and uses a different normalization. To use it you'd need to update
`app/src/main/java/com/facealbum/config/FaceRecognitionConfig.kt`:

```kotlin
const val MODEL_INPUT_SIZE = 160   // was 112
```

…and edit `FacePreprocessor.bitmapToFloatArray()` to match the model's
expected normalization (standard score — mean/std per channel — rather than
[-1, 1]). Workable, but more lift than Option 3a.

**Option 3c — ship your own**

If you already have a `.tflite` from an in-house training run or a vendor,
drop it at `app/src/main/assets/mobile_face_net.tflite` and update
`FaceRecognitionConfig.kt` to match its input/output contract.

### Confirm the model loads

After adding the file:

```bash
ls -l app/src/main/assets/mobile_face_net.tflite
# Should be ~4–6 MB. If it's a few KB, your download was an HTML redirect, not the binary.
```

### The model checksum is already pinned

`app/build.gradle.kts`'s `verifyFaceModelPresent` task (which gates every
release build) verifies the asset's SHA-256 against the `faceModelSha256`
property, already set in `gradle.properties` to the digest of the committed
model:

```
72b5c2921d4fd4be3743dae54451ef2f0c13924ae9c048926152176383d657bf
```

If you replace the model, recompute and re-pin it:

```bash
sha256sum app/src/main/assets/mobile_face_net.tflite
```

You can also override per build without editing the file:

```bash
./gradlew bundleRelease -PfaceModelSha256=<the hex digest>
```

Emptying the property downgrades the task to an existence-only check that
passes with an integrity-not-verified warning.

---

## 4. Build & install

### From the command line

```bash
# Build debug APK
./gradlew assembleDebug

# (Plug in your phone, enable USB debugging in Developer Options)
adb devices                # confirm the phone shows up
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### From Android Studio

1. Plug the phone in, accept the USB-debugging prompt.
2. Pick your phone from the device dropdown next to the Run button.
3. Click ▶ Run.

If the build fails, see §7 *Troubleshooting*.

---

## 5. First-run checklist

> Nobody has run this build on real hardware yet. Everything below is derived
> from the code, not from a device session — so treat it as what *should*
> happen, and see §5b for what to do when it doesn't.

1. **Open FaceAlbum** on your phone.
2. **Welcome screen** → tap *Grant Access*. On Android 13+ pick **"Allow all"**.
   Picking "Select photos…" is supported, but then the app can only ever see
   the subset you picked, and anything outside it is treated as deleted.
3. **People screen** appears, empty, with a **Scan photos** button. **The scan
   does not start on its own** — you have to tap it. (`MainViewModel.startIndex`
   is only called from that button; nothing auto-triggers it.) On Android 13+
   you'll be asked for notification permission at this point; declining is fine
   and the scan still runs, you just lose the progress notification.
4. While scanning you get a foreground notification ("Finding faces in your
   photos") and an in-app banner reading `Scanning 12 of 4382 · 7 faces found`.
5. Tiles appear as clusters reach the minimum group size — **3 by default**, so
   a person in one or two photos will not appear at all. Lower it in
   *Settings → Grouping → Minimum group size* if the grid looks emptier than
   expected.
6. **Tap a tile** → person detail. The action row has **Rename**, **Merge**,
   **Export album**, and a favourite toggle.
7. **Export album** does *not* immediately ask for a name. It first builds a
   plan, then opens a **"Review before exporting"** sheet showing the exact
   file count, the source folders, the destination, a size estimate, and a
   warning for photos that also contain someone else. You choose the album name
   and the mode there, then confirm. **Only Copy is available** — Move is
   implemented but disabled behind `ExportFeature.MOVE_ENABLED` pending
   on-device verification, so the Move option will not be offered.
8. Copies land in `Pictures/FaceAlbums/<name>/` and the completion screen
   offers **Undo**, which removes the copies it made.
9. **Merge two tiles of the same person**: open either, tap *Merge*, pick the
   other. Note the confirmation says this can't be undone, and it means it —
   there is no stored record of a merge to reverse.

Re-running a scan is incremental: only new or modified photos are processed.

## 5b. What to watch for, and what "wrong" looks like

These are the parts that have never been exercised, ranked by how likely they
are to bite:

| What to check | Looks right | Looks wrong → likely cause |
|---|---|---|
| **Grouping quality** — the big unknown | Same person's photos land together; different people stay apart | Everything in one giant group, or every photo its own group → the model's accuracy has never been measured (see `docs/release/known-limitations.md`). Try *Settings → Grouping strictness* before assuming a bug. |
| First scan completes | Banner counts up and finishes | Stalls at 0 → model failed to load; the People screen shows an error banner rather than crashing |
| Memory on a large library | Scan runs to completion | `OutOfMemoryError` → lower `MAX_BITMAP_DIMENSION` from 1024 |
| Backgrounding mid-scan | Resumes or continues | Silently stops → foreground-service/WorkManager issue worth reporting |
| Export of a large album | Progress notification, then "Album ready" | Partial album → check the completion screen's per-state tallies |

Worth knowing: grouping runs on **128-dimensional** embeddings from the bundled
MobileFaceNet. If you swap the model for one of a different width, every
existing embedding becomes incomparable and you must re-scan from scratch.

## 6. Verifying it actually works end-to-end

Smoke test (real device, ~5 minutes):

| Step | Expected |
|---|---|
| Grant photo permission, then tap **Scan photos** | Foreground notification shows up; banner shows progress climbing. |
| After ~100 photos scanned | At least one cluster with several faces of the same person; clearly different people stay separate. |
| Rename a cluster, kill app, re-open | Name is still there. |
| Export → look in Google Photos | New album `FaceAlbums/<Name>/` containing only that person's photos. |
| Add a new photo to camera roll → re-open | Next scan only processes new photos (banner total much smaller than first time). |
| Turn airplane mode on for the whole flow | Everything still works; no crash, no missing UI. |

If any of these fail, the most likely culprits are:
- Clusters look random → the bundled model's accuracy is unverified; this is
  the single most likely real defect, not a misconfiguration.
- Model file corrupted → `verifyFaceModelPresent` catches this on release
  builds via the pinned SHA-256, but debug builds don't run that gate.
- Photo permission scoped to a subset → reset photo permission in
  Settings → Apps → FaceAlbum → Permissions → "Allow all".

---

## 7. Troubleshooting

**`Could not find org.tensorflow:tensorflow-lite:2.14.0`** or similar
dependency error.
Cause: bad network or Gradle cache. Fix: `./gradlew --refresh-dependencies build`.

**App opens, indexes 0 photos.**
Photo permission was granted as "Selected photos only" or denied. Open
*Settings → Apps → FaceAlbum → Permissions → Photos and videos → Allow all*.
Then in the app, *Settings → Re-scan entire library*.

**Indexing runs but every cluster looks wrong / mixes people.**
Model contract mismatch. Confirm:
- file is at `app/src/main/assets/mobile_face_net.tflite`
- input is 112×112×3, normalized to [-1, 1]
- output width matches `FaceRecognitionConfig.EMBEDDING_SIZE` (128 for the bundled model)
Or update `FaceRecognitionConfig.kt` + `FacePreprocessor.kt` to match what your
model actually wants.

**`OutOfMemoryError` during scan.**
Lower `MAX_BITMAP_DIMENSION` in `FaceRecognitionConfig.kt` from `1024` to
`768`. Devices with < 3 GB RAM are tight on JPEG decode buffers.

**Indexing notification disappears after a few seconds.**
On some OEM skins the OS kills foreground services aggressively. Go to
*Phone Settings → Apps → FaceAlbum → Battery → Unrestricted*.

**Crash on first launch with `Face recognition model not found.`**
Self-explanatory: drop a `.tflite` at `app/src/main/assets/mobile_face_net.tflite`
and rebuild (incremental build is enough — Gradle picks up new assets).

**`./gradlew test` fails on Robolectric.**
That's the Room-backed `FaceClustererTest`. Make sure Java 17 is the active
JDK (`java -version`) — Android Studio's bundled JBR works.

---

## 8. Where to go next

- **Tune clustering**: `FaceRecognitionConfig.CLUSTER_ASSIGN_THRESHOLD` (0.6
  default) and `CLUSTER_MERGE_THRESHOLD` (0.75) live in
  `app/src/main/java/com/facealbum/config/FaceRecognitionConfig.kt`.
- **Run the QA matrix**: see `docs/release/qa-matrix.md`.
- **Cut a signed release**: see the *Release build & distribution* section of
  `README.md`.
- **Publish to Play Console**: privacy policy + data-safety form templates
  live in `docs/release/compliance.md`.
