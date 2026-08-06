Bundled face-embedding model
============================

    File     : mobile_face_net.tflite
    Size     : 5,117,184 bytes
    SHA-256  : 72b5c2921d4fd4be3743dae54451ef2f0c13924ae9c048926152176383d657bf

This checksum is pinned as `faceModelSha256` in gradle.properties, so
`verifyFaceModelPresent` fails the build if the asset is ever corrupted,
truncated, or swapped.

Contract (matches FaceRecognitionConfig + FaceEmbedder)
-------------------------------------------------------
  Input  : 1 x 112 x 112 x 3 float32, RGB, normalized to [-1, 1]
  Output : 1 x 128 float32, already L2-normalized by the graph

Note the output is 128-D, NOT the 512-D that earlier revisions of this file
and INSTALL.md claimed. That claim was never true of this model; the graph's
terminal nodes are embeddings/Sum -> Maximum -> Rsqrt -> embeddings, emitting
128 values. FaceRecognitionConfig.EMBEDDING_SIZE was corrected to 128 to match.
FaceEmbedder L2-normalizes again after inference, which is harmless (the
operation is idempotent) and keeps it correct for a model that does not
normalize internally.

Provenance
----------
  Upstream : https://github.com/sirius-ai/MobileFaceNet_TF
  License  : Apache-2.0 (verified against the repo's LICENSE file; an earlier
             revision of this file said MIT, which was wrong)
  Source   : arch/pretrained_model/MobileFaceNet_9925_9680.pb @ branch master
  Source SHA-256 : fb046e5f723a70020962c6772a08c3c915a443ca19aaade732c2b84eea613f09
  Source size    : 5,956,310 bytes

Reproducing this file
---------------------
Converted with TensorFlow 2.15.1:

    python - <<'PY'
    import tensorflow as tf
    conv = tf.compat.v1.lite.TFLiteConverter.from_frozen_graph(
        graph_def_file="MobileFaceNet_9925_9680.pb",
        input_arrays=["img_inputs"],
        output_arrays=["embeddings"],
        input_shapes={"img_inputs": [1, 112, 112, 3]},
    )
    open("mobile_face_net.tflite", "wb").write(conv.convert())
    PY

Two things differ from the instructions previously in INSTALL.md, both of
which would have made that snippet fail:
  1. from_frozen_graph is a TF1 entry point. On TensorFlow 2 it lives at
     tf.compat.v1.lite.TFLiteConverter, not tf.lite.TFLiteConverter.
  2. The graph's input placeholder is named `img_inputs`, not `input`.

Conversion is not bit-reproducible across TensorFlow versions — a different
TF release can emit a differently-optimized flatbuffer with the same
behaviour but a different SHA-256. If you re-convert, expect to update the
checksum above and in gradle.properties.

What has NOT been verified
--------------------------
Recognition quality has not been measured. The conversion was checked for
shape, dtype, finiteness, determinism, and unit-norm output, but no accuracy
benchmark was run against a labelled face dataset, and the model has never
been executed on a real photo — only on synthetic tensors. Whether grouping
is actually good on a real library is still an open question.
