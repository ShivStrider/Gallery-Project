# FaceAlbum — install & run

End-to-end recipe for getting FaceAlbum running on your phone. Follow it once
top-to-bottom the first time; after that everything is a normal Android Studio
edit-compile-run cycle.

---

## 0. What you need before you start

| Item | Where to get it | Why |
|---|---|---|
| Android Studio Hedgehog or newer (2023.1.1+) | https://developer.android.com/studio | Builds the project. |
| Android SDK Platform 34 + Build-Tools 34 | Installed via Android Studio's SDK Manager | Matches `compileSdk = 34`. |
| JDK 17 (bundled with Android Studio) | Already there if you installed AS | Required by AGP 8.2. |
| A phone on Android 8.0 (API 26) or newer | Your pocket | App's min SDK. |
| **A MobileFaceNet `.tflite` weight** | See §3 | The face-embedding brain. App will *build* without it but won't *work*. |

Emulators technically work but are slow; the index loop touches MediaStore, ML
Kit, and TFLite, all of which want real hardware.

---

## 1. Get the source

```bash
git clone https://github.com/ShivStrider/Gallery-Project.git
cd Gallery-Project
git checkout claude/festive-bardeen-idw9Z
```

(The clustering work is on that branch until it lands on `main`.)

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

## 3. Drop in the TFLite model — **the critical step**

The app expects this file:

```
app/src/main/assets/mobile_face_net.tflite
```

with this contract (see `FaceRecognitionConfig.kt`):

- **Input**: `1 × 112 × 112 × 3` float32, normalized to `[-1, 1]`
- **Output**: `1 × 512` float32

If your model uses a different input size or normalization, the app builds and
indexing runs without crashing — clusters just come out as noise. So the
contract matters.

### Candidate models

I haven't shipped a model with the repo because of license + binary-history
concerns. Pick one of the routes below.

**Option 3a — sirius-ai MobileFaceNet (Apache-2.0, the canonical match)**

Repo: https://github.com/sirius-ai/MobileFaceNet_TF

This is the **license-clean** source whose architecture matches our contract
exactly (112×112 in, 512-D out, normalized to [-1, 1]). Catch: the repo ships
a TensorFlow frozen graph (`.pb`), not a `.tflite`. You'll need to convert it
once:

```bash
# Inside a Python venv with TensorFlow 2.x installed
git clone https://github.com/sirius-ai/MobileFaceNet_TF
cd MobileFaceNet_TF/arch/pretrained_model

python - <<'PY'
import tensorflow as tf
converter = tf.lite.TFLiteConverter.from_frozen_graph(
    graph_def_file="MobileFaceNet_9925_9680.pb",
    input_arrays=["input"],
    output_arrays=["embeddings"],
    input_shapes={"input": [1, 112, 112, 3]},
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

1. **Open FaceAlbum** on your phone.
2. **Welcome screen** → tap *Grant Access*. On Android 13+ pick "Allow all" so
   the indexer can see every photo (not just a subset).
3. **People screen** appears. The first scan kicks off automatically — you'll
   see a foreground notification "Finding faces in your photos" and a progress
   banner in the app showing `Scanning 12 / 4382 · 7 faces`.
4. After a few minutes (depends on library size + device CPU), the first
   cluster tiles appear. By default a cluster needs ≥ 3 faces to show up —
   lower this in *Settings → Minimum cluster size* if you want to see
   singletons too.
5. **Tap a tile** → cluster detail. Tap the ✎ next to the placeholder name to
   rename ("Mum", "Sarah", whatever).
6. **Tap *Export album*** in the detail screen → enter a name → confirm.
   Photos land in `Pictures/FaceAlbums/<name>/` and are immediately visible in
   Google Photos and Files.
7. **Merge two tiles that are the same person**: open the more-faces tile,
   tap *Merge with…*, pick the other one. The smaller cluster folds in.

The scan progress banner disappears when indexing finishes. Re-running is
incremental — only new or modified photos get processed.

---

## 6. Verifying it actually works end-to-end

Smoke test (real device, ~5 minutes):

| Step | Expected |
|---|---|
| Grant photo permission | Foreground notification shows up; banner shows progress climbing. |
| After ~100 photos scanned | At least one cluster with several faces of the same person; clearly different people stay separate. |
| Rename a cluster, kill app, re-open | Name is still there. |
| Export → look in Google Photos | New album `FaceAlbums/<Name>/` containing only that person's photos. |
| Add a new photo to camera roll → re-open | Next scan only processes new photos (banner total much smaller than first time). |
| Turn airplane mode on for the whole flow | Everything still works; no crash, no missing UI. |

If any of these fail, the most likely culprits are:
- Model file missing → see §3.
- Model contract mismatch → clusters look random.
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
- output is exactly 512 floats
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
