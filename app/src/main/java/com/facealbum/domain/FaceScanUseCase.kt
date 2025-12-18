package com.facealbum.domain

import android.content.Context
import android.net.Uri
import com.facealbum.data.FaceDetectorWrapper
import com.facealbum.data.FaceEmbedder
import com.facealbum.data.PhotoRepository
import com.facealbum.model.CandidatePhoto
import com.facealbum.model.PhotoInfo
import com.facealbum.model.ScanProgress
import com.facealbum.util.BitmapLoader
import com.facealbum.util.FacePreprocessor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Use case for scanning photo library and finding face matches.
 */
class FaceScanUseCase(private val context: Context) {

    private val photoRepository = PhotoRepository(context)
    private val faceDetector = FaceDetectorWrapper(context)
    private val faceEmbedder = FaceEmbedder(context)

    // Cache embeddings to avoid reprocessing
    private val embeddingCache = mutableMapOf<Long, FloatArray>()

    /**
     * Compute embeddings from seed photos.
     *
     * @param seedUris URIs of seed photos selected by user
     * @return List of embeddings from largest face in each seed photo
     */
    suspend fun computeSeedEmbeddings(seedUris: List<Uri>): List<FloatArray> {
        val embeddings = mutableListOf<FloatArray>()

        for (uri in seedUris) {
            val bitmap = BitmapLoader.loadScaled(context, uri) ?: continue
            val face = faceDetector.detectLargestFace(bitmap) ?: continue
            val croppedFace = FacePreprocessor.cropAndPreprocess(bitmap, face.boundingBox)
            val embedding = faceEmbedder.getEmbedding(croppedFace) ?: continue

            embeddings.add(embedding)
        }

        return embeddings
    }

    /**
     * Scan photo library for faces matching the seed embeddings.
     *
     * @param seedEmbeddings Reference embeddings from seed photos
     * @param limit Maximum number of photos to scan
     * @param threshold Similarity threshold for matches
     * @return Flow emitting scan progress with found candidates
     */
    fun scanLibrary(
        seedEmbeddings: List<FloatArray>,
        limit: Int,
        threshold: Float
    ): Flow<Pair<ScanProgress, List<CandidatePhoto>>> = flow {
        if (seedEmbeddings.isEmpty()) {
            return@flow
        }

        // Get recent photos
        val photos = photoRepository.queryRecentPhotos(limit)
        val candidates = mutableListOf<CandidatePhoto>()

        photos.forEachIndexed { index, photo ->
            // Emit progress
            val progress = ScanProgress(
                current = index + 1,
                total = photos.size,
                currentPhotoUri = photo.uri,
                matchesFound = candidates.size
            )

            // Try to get or compute embedding
            val embedding = getOrComputeEmbedding(photo)

            if (embedding != null) {
                // Check if it matches any seed
                val (isMatch, similarity) = SimilarityMatcher.isMatch(
                    embedding,
                    seedEmbeddings,
                    threshold
                )

                if (isMatch) {
                    candidates.add(
                        CandidatePhoto(
                            photo = photo,
                            similarity = similarity,
                            isApproved = true
                        )
                    )
                }
            }

            // Emit current state
            emit(Pair(progress, candidates.toList()))
        }
    }

    /**
     * Get embedding from cache or compute it.
     */
    private suspend fun getOrComputeEmbedding(photo: PhotoInfo): FloatArray? {
        // Check cache first
        embeddingCache[photo.id]?.let { return it }

        // Compute new embedding
        val bitmap = BitmapLoader.loadScaled(context, photo.uri) ?: return null
        val face = faceDetector.detectLargestFace(bitmap) ?: return null
        val cropped = FacePreprocessor.cropAndPreprocess(bitmap, face.boundingBox)
        val embedding = faceEmbedder.getEmbedding(cropped) ?: return null

        // Cache for future use
        embeddingCache[photo.id] = embedding
        return embedding
    }

    /**
     * Clear the embedding cache to free memory.
     */
    fun clearCache() {
        embeddingCache.clear()
    }

    /**
     * Release resources.
     */
    fun close() {
        faceDetector.close()
        faceEmbedder.close()
    }
}
