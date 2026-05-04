package com.facealbum

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.facealbum.data.ModelState
import com.facealbum.data.PhotoRepository
import com.facealbum.domain.FaceScanUseCase
import com.facealbum.model.AppUiState
import com.facealbum.model.CandidatePhoto
import com.facealbum.model.PhotoInfo
import com.facealbum.model.ScanState
import com.facealbum.telemetry.CrashReporter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Main ViewModel managing the app's state and business logic.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val photoRepository = PhotoRepository(application)
    private val faceScanUseCase = FaceScanUseCase(application)

    private var scanJob: Job? = null

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private val _recentPhotos = MutableStateFlow<List<PhotoInfo>>(emptyList())
    val recentPhotos: StateFlow<List<PhotoInfo>> = _recentPhotos.asStateFlow()

    private val _exportedCount = MutableStateFlow(0)
    val exportedCount: StateFlow<Int> = _exportedCount.asStateFlow()

    /**
     * Load recent photos for seed selection.
     */
    fun loadRecentPhotos(limit: Int = 100) {
        viewModelScope.launch {
            try {
                Timber.d("Loading recent photos, limit=$limit")
                val photos = photoRepository.queryRecentPhotos(limit)
                _recentPhotos.value = photos
                Timber.i("Loaded ${photos.size} recent photos")
            } catch (e: Exception) {
                Timber.e(e, "Failed to load recent photos")
            }
        }
    }

    /**
     * Toggle selection of a seed photo.
     */
    fun toggleSeedSelection(uri: Uri) {
        _uiState.update { state ->
            val currentSeeds = state.seedUris
            val newSeeds = if (uri in currentSeeds) {
                currentSeeds - uri
            } else {
                if (currentSeeds.size < 3) {
                    currentSeeds + uri
                } else {
                    currentSeeds  // Already at max
                }
            }
            state.copy(seedUris = newSeeds)
        }
    }

    /**
     * Start scanning the library with selected seeds.
     */
    fun startScan() {
        Timber.d("Starting scan with ${_uiState.value.seedUris.size} seed photos")

        // Check if model is ready before starting
        val modelState = faceScanUseCase.getModelState()
        if (modelState is ModelState.Failed) {
            Timber.e("Cannot start scan: model not ready - ${modelState.reason}")
            _uiState.update {
                it.copy(scanState = ScanState.Error(modelState.reason))
            }
            return
        }

        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            try {
                // Compute seed embeddings
                val seedEmbeddings = faceScanUseCase.computeSeedEmbeddings(
                    _uiState.value.seedUris
                )

                if (seedEmbeddings.isEmpty()) {
                    Timber.w("No faces found in seed photos")
                    _uiState.update {
                        it.copy(
                            scanState = ScanState.Error("No faces found in seed photos")
                        )
                    }
                    return@launch
                }

                _uiState.update { it.copy(seedEmbeddings = seedEmbeddings) }

                // Start scanning
                faceScanUseCase.scanLibrary(
                    seedEmbeddings = seedEmbeddings,
                    limit = _uiState.value.maxPhotosToScan,
                    threshold = _uiState.value.similarityThreshold
                ).collect { (progress, candidates) ->
                    _uiState.update {
                        it.copy(
                            scanState = ScanState.Scanning(progress),
                            candidates = candidates
                        )
                    }
                }

                // Scan complete
                val finalCandidates = _uiState.value.candidates
                Timber.i("Scan complete: found ${finalCandidates.size} matches")
                _uiState.update {
                    it.copy(
                        scanState = ScanState.Complete(it.candidates)
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Scan failed with error")
                CrashReporter.recordNonFatal(
                    throwable = e,
                    source = "scan",
                    context = mapOf(
                        "seed_count" to _uiState.value.seedUris.size.toString(),
                        "threshold" to _uiState.value.similarityThreshold.toString(),
                        "max_scan" to _uiState.value.maxPhotosToScan.toString()
                    )
                )
                _uiState.update {
                    it.copy(
                        scanState = ScanState.Error(e.message ?: "Unknown error")
                    )
                }
            }
        }
    }

    /**
     * Cancel ongoing scan.
     */
    fun cancelScan() {
        Timber.d("Cancelling scan")
        scanJob?.cancel()
        scanJob = null
        _uiState.update {
            it.copy(scanState = ScanState.Idle)
        }
    }

    /**
     * Toggle approval status of a candidate photo.
     */
    fun toggleCandidateApproval(photoId: Long) {
        _uiState.update { state ->
            val updatedCandidates = state.candidates.map { candidate ->
                if (candidate.photo.id == photoId) {
                    candidate.copy(isApproved = !candidate.isApproved)
                } else {
                    candidate
                }
            }
            state.copy(candidates = updatedCandidates)
        }
    }

    /**
     * Set the album name.
     */
    fun setAlbumName(name: String) {
        _uiState.update { it.copy(albumName = name) }
    }

    /**
     * Export approved photos to the album.
     */
    fun exportPhotos() {
        viewModelScope.launch {
            val albumName = _uiState.value.albumName.ifBlank { "Person" }
            val approvedPhotos = _uiState.value.candidates.filter { it.isApproved }

            var successCount = 0

            for (candidate in approvedPhotos) {
                val result = photoRepository.copyToAlbum(
                    sourceUri = candidate.photo.uri,
                    albumName = albumName,
                    originalFileName = candidate.photo.displayName
                )
                if (result != null) {
                    successCount++
                } else {
                    CrashReporter.recordNonFatal(
                        throwable = IllegalStateException("Export copy returned null"),
                        source = "export",
                        context = mapOf(
                            "album_name_length" to albumName.length.toString(),
                            "approved_count" to approvedPhotos.size.toString()
                        )
                    )
                }
            }

            _exportedCount.value = successCount
        }
    }

    /**
     * Get count of approved photos.
     */
    fun getApprovedCount(): Int {
        return _uiState.value.candidates.count { it.isApproved }
    }

    /**
     * Reset the app state for a new scan.
     */
    fun reset() {
        _uiState.value = AppUiState()
        _exportedCount.value = 0
        faceScanUseCase.clearCache()
    }

    override fun onCleared() {
        super.onCleared()
        faceScanUseCase.close()
    }
}
