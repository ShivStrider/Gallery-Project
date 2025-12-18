package com.facealbum.data

import android.content.Context
import android.graphics.Bitmap
import com.facealbum.util.FacePreprocessor
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * TFLite-based face embedding extractor.
 *
 * Note: This class expects a MobileFaceNet model file at assets/mobile_face_net.tflite
 * For the MVP, you'll need to download and add this model file.
 */
class FaceEmbedder(private val context: Context) {

    private var interpreter: Interpreter? = null
    private val embeddingSize = 512  // MobileFaceNet outputs 512-dim embeddings

    init {
        try {
            val modelBuffer = loadModelFile()
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                // Optional: use NNAPI or GPU delegate for speed
                // addDelegate(NnApiDelegate())
            }
            interpreter = Interpreter(modelBuffer, options)
        } catch (e: Exception) {
            // Model file not found - this is expected for initial setup
            // In production, you'd handle this more gracefully
        }
    }

    /**
     * Load the TFLite model from assets.
     */
    private fun loadModelFile(): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd("mobile_face_net.tflite")
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Extract face embedding from a preprocessed face bitmap.
     *
     * @param preprocessedBitmap Face bitmap (should be 112x112, already cropped)
     * @return Normalized embedding vector, or null if model not loaded
     */
    fun getEmbedding(preprocessedBitmap: Bitmap): FloatArray? {
        val interp = interpreter ?: return null

        // Convert bitmap to input array
        val inputArray = FacePreprocessor.bitmapToFloatArray(preprocessedBitmap)

        // Reshape to [1, 112, 112, 3] format expected by model
        val inputBuffer = Array(1) {
            Array(112) { row ->
                Array(112) { col ->
                    floatArrayOf(
                        inputArray[(row * 112 + col) * 3],
                        inputArray[(row * 112 + col) * 3 + 1],
                        inputArray[(row * 112 + col) * 3 + 2]
                    )
                }
            }
        }

        // Run inference
        val outputBuffer = Array(1) { FloatArray(embeddingSize) }
        interp.run(inputBuffer, outputBuffer)

        // Normalize the embedding
        val embedding = outputBuffer[0]
        normalize(embedding)
        return embedding
    }

    /**
     * Normalize embedding to unit length (L2 normalization).
     */
    private fun normalize(embedding: FloatArray) {
        val norm = sqrt(embedding.map { it * it }.sum())
        if (norm > 0) {
            for (i in embedding.indices) {
                embedding[i] /= norm
            }
        }
    }

    /**
     * Release interpreter resources.
     */
    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
