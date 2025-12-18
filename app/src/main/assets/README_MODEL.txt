TFLite Model Placeholder
========================

For the app to work, you need to add a FaceNet/MobileFaceNet model file here:
- Filename: mobile_face_net.tflite
- Expected output: 512-dimensional embedding
- Input size: 112x112x3 (RGB image normalized to [-1, 1])

Recommended models:
1. MobileFaceNet (~5MB) - Good accuracy/speed balance
2. FaceNet (larger, ~90MB) - Higher accuracy but slower

You can find these models on:
- TensorFlow Hub
- GitHub repositories for face recognition

For development/testing without a model:
- The app will detect this and handle gracefully
- Face detection will still work, but similarity matching will be disabled
