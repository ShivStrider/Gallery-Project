package com.facealbum.data

import android.content.Context
import android.graphics.Bitmap
import com.facealbum.config.FaceRecognitionConfig
import com.facealbum.telemetry.CrashReporter
import com.facealbum.util.FacePreprocessor
import org.tensorflow.lite.Interpreter
import timber.log.Timber
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * Represents the state of the face embedding model.
 */
sealed class ModelState {
    object Ready : ModelState()
    data class Failed(val reason: String) : ModelState()
}

/**
 * TFLite-based face embedding extractor.
 *
 * Note: This class expects a MobileFaceNet model file at assets/mobile_face_net.tflite
 * For the MVP, you'll need to download and add this model file.
 */
class FaceEmbedder(private val context: Context) {

    private var interpreter: Interpreter? = null
    private val embeddingSize = FaceRecognitionConfig.EMBEDDING_SIZE

    /** Current state of the model - check this before using the embedder */
    var modelState: ModelState = ModelState.Ready
        private set

    init {
        Timber.d("Initializing FaceEmbedder")
        try {
            val modelBuffer = loadModelFile()
            val options = Interpreter.Options().apply {
                setNumThreads(FaceRecognitionConfig.TFLITE_NUM_THREADS)
                // Optional: use NNAPI or GPU delegate for speed
                // addDelegate(NnApiDelegate())
            }
            interpreter = Interpreter(modelBuffer, options)
            modelState = ModelState.Ready
            Timber.i("Face embedding model loaded successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to load face embedding model")
            CrashReporter.recordNonFatal(
                throwable = e,
                source = "model_load",
                context = mapOf("model_asset" to FaceRecognitionConfig.MODEL_FILE_NAME)
            )
            modelState = ModelState.Failed(
                when {
                    e.message?.contains(FaceRecognitionConfig.MODEL_FILE_NAME) == true ->
                        "Face recognition model not found. Please add ${FaceRecognitionConfig.MODEL_FILE_NAME} to assets folder."
                    else -> "Failed to load face recognition model: ${e.message}"
                }
            )
        }
    }

    /**
     * Check if the model is ready for use.
     */
    fun isReady(): Boolean = interpreter != null && modelState is ModelState.Ready

    /**
     * Load the TFLite model from assets.
     */
    private fun loadModelFile(): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(FaceRecognitionConfig.MODEL_FILE_NAME)
        return assetFileDescriptor.use { afd ->
            FileInputStream(afd.fileDescriptor).use { inputStream ->
                val fileChannel = inputStream.channel
                fileChannel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }
    }

    /**
     * Extract face embedding from a preprocessed face bitmap.
     *
     * @param preprocessedBitmap Face bitmap (should be MODEL_INPUT_SIZE x MODEL_INPUT_SIZE, already cropped)
     * @return Normalized embedding vector, or null if model not loaded
     */
    fun getEmbedding(preprocessedBitmap: Bitmap): FloatArray? {
        val interp = interpreter ?: return null

        val inputSize = FaceRecognitionConfig.MODEL_INPUT_SIZE

        // Convert bitmap to input array
        val inputArray = FacePreprocessor.bitmapToFloatArray(preprocessedBitmap)

        // Reshape to [1, inputSize, inputSize, 3] format expected by model
        val inputBuffer = Array(1) {
            Array(inputSize) { row ->
                Array(inputSize) { col ->
                    floatArrayOf(
                        inputArray[(row * inputSize + col) * 3],
                        inputArray[(row * inputSize + col) * 3 + 1],
                        inputArray[(row * inputSize + col) * 3 + 2]
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
