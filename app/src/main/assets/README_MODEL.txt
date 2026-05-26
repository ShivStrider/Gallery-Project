TFLite Model Placeholder
========================

The face-clustering pipeline requires a MobileFaceNet TFLite model at:

    app/src/main/assets/mobile_face_net.tflite

Required contract (matches FaceRecognitionConfig + FaceEmbedder):
  - Input  : 1 x 112 x 112 x 3 float32, normalized to [-1, 1]
  - Output : 1 x 512 float32

Recommended source (license-clean):
  - sirius-ai/MobileFaceNet_TF — MIT, exports to .tflite
    https://github.com/sirius-ai/MobileFaceNet_TF
  - Insightface mobilefacenet ONNX -> convert via tf2onnx + tflite converter.

After downloading, drop the file at the path above and record SHA-256 below.

    Filename : mobile_face_net.tflite
    SHA-256  : <fill in after adding>
    Size     : ~5 MB
    License  : Apache 2.0 / MIT (per source)

The app degrades gracefully if the model is missing — face detection still
runs but clustering is skipped and the indexer fails with a clear error.
