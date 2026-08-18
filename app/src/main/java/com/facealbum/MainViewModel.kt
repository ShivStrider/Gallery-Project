package com.facealbum

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.facealbum.config.FaceRecognitionConfig
import com.facealbum.data.PhotoRepository
import com.facealbum.data.db.ClusterSummary
import com.facealbum.data.db.FaceAlbumDatabase
import com.facealbum.data.db.PhotoEntity
import com.facealbum.data.db.findByIdsChunked
import com.facealbum.data.prefs.ThemePreference
import com.facealbum.data.prefs.UserPreferences
import com.facealbum.domain.ExportDateRepairUseCase
import com.facealbum.domain.FaceClusterer
import com.facealbum.work.FaceIndexWorker
import com.facealbum.work.ReclusterWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Top-level state holder for the app.
 *
 * Live clusters + progress come off Room + WorkManager. Favourite state and
 * theme come from DataStore. Snackbar messages fire once through a
 * [MutableSharedFlow] so re-composition doesn't re-play them.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db: FaceAlbumDatabase = FaceAlbumDatabase.get(application)
    private val prefs: UserPreferences = UserPreferences.get(application)
    private val photoRepository: PhotoRepository = PhotoRepository(application)

    // FaceClusterer caches centroids per instance, so it must be constructed
    // per operation — a long-lived instance would go stale against scans and
    // reclusters running in parallel workers.
    private fun newClusterer() = FaceClusterer(db.clusterDao(), db.faceDao())

    val minClusterSize: StateFlow<Int> = prefs.minClusterSize
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            FaceRecognitionConfig.DEFAULT_MIN_CLUSTER_SIZE
        )

    val assignThreshold: StateFlow<Float> = prefs.assignThreshold
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            FaceRecognitionConfig.CLUSTER_ASSIGN_THRESHOLD
        )

    private val _pendingAssignThreshold = MutableStateFlow<Float?>(null)
    val pendingAssignThreshold: StateFlow<Float?> = _pendingAssignThreshold.asStateFlow()

    val themePreference: StateFlow<ThemePreference> = prefs.themePreference
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ThemePreference.SYSTEM
        )

    val favoriteClusterIds: StateFlow<Set<Long>> = prefs.favoriteClusterIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val clusters: StateFlow<List<ClusterSummary>> =
        minClusterSize
            .flatMapLatest { min -> db.clusterDao().summariesAtLeast(min) }
            .combine(favoriteClusterIds) { summaries, favs ->
                // Favourites float to the top; within each bucket keep the DAO's
                // faceCount-desc ordering.
                summaries.sortedWith(
                    compareByDescending<ClusterSummary> { it.id in favs }
                        .thenByDescending { it.faceCount }
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The complement of [clusters]: clusters the "Minimum group size" setting
     * currently hides. Surfaced as a "Review needed" entry on the People grid
     * so faces below that size stay reachable instead of silently vanishing.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val reviewNeededClusters: StateFlow<List<ClusterSummary>> =
        minClusterSize
            .flatMapLatest { min -> db.clusterDao().summariesBelow(min) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val reviewNeededFaceCount: StateFlow<Int> =
        minClusterSize
            .flatMapLatest { min -> db.clusterDao().reviewNeededFaceCount(min) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    data class IndexProgress(
        val running: Boolean,
        val done: Int,
        val total: Int,
        val faces: Int,
        val clusters: Int,
        val errorMessage: String? = null
    )

    private val workFlow: Flow<List<WorkInfo>> =
        WorkManager.getInstance(application)
            .getWorkInfosForUniqueWorkFlow(FaceIndexWorker.UNIQUE_WORK_NAME)

    val indexProgress: StateFlow<IndexProgress> = workFlow
        .map { infos ->
            val info = infos.firstOrNull()
                ?: return@map IndexProgress(false, 0, 0, 0, 0)
            val data = if (info.state.isFinished) info.outputData else info.progress
            val running = info.state == WorkInfo.State.RUNNING ||
                info.state == WorkInfo.State.ENQUEUED
            IndexProgress(
                running = running,
                done = data.getInt(FaceIndexWorker.KEY_PROGRESS_DONE, 0),
                total = data.getInt(FaceIndexWorker.KEY_PROGRESS_TOTAL, 0),
                faces = data.getInt(FaceIndexWorker.KEY_PROGRESS_FACES, 0),
                clusters = data.getInt(FaceIndexWorker.KEY_PROGRESS_CLUSTERS, 0),
                errorMessage = if (info.state == WorkInfo.State.FAILED)
                    info.outputData.getString(FaceIndexWorker.KEY_ERROR_MESSAGE)
                else null
            )
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            IndexProgress(false, 0, 0, 0, 0)
        )

    data class ReclusterProgress(
        val running: Boolean,
        val done: Int,
        val total: Int,
        val clusters: Int,
        val errorMessage: String? = null
    )

    private val reclusterWorkFlow: Flow<List<WorkInfo>> =
        WorkManager.getInstance(application)
            .getWorkInfosForUniqueWorkFlow(ReclusterWorker.UNIQUE_WORK_NAME)

    val reclusterProgress: StateFlow<ReclusterProgress> = reclusterWorkFlow
        .map { infos ->
            val info = infos.firstOrNull()
                ?: return@map ReclusterProgress(false, 0, 0, 0)
            val data = if (info.state.isFinished) info.outputData else info.progress
            val running = info.state == WorkInfo.State.RUNNING ||
                info.state == WorkInfo.State.ENQUEUED
            ReclusterProgress(
                running = running,
                done = data.getInt(ReclusterWorker.KEY_PROGRESS_DONE, 0),
                total = data.getInt(ReclusterWorker.KEY_PROGRESS_TOTAL, 0),
                clusters = data.getInt(ReclusterWorker.KEY_PROGRESS_CLUSTERS, 0),
                errorMessage = if (info.state == WorkInfo.State.FAILED)
                    info.outputData.getString(ReclusterWorker.KEY_ERROR_MESSAGE)
                else null
            )
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ReclusterProgress(false, 0, 0, 0)
        )

    private val _selectedCluster = MutableStateFlow<ClusterDetailState?>(null)
    val selectedCluster: StateFlow<ClusterDetailState?> = _selectedCluster.asStateFlow()

    /**
     * Everything the Person Detail screen needs to render, including derived
     * "first appeared" / "latest appeared" timestamps and favourite state.
     */
    data class ClusterDetailState(
        val clusterId: Long,
        val displayName: String?,
        val photos: List<PhotoEntity>,
        val firstAppearance: Long?,
        val latestAppearance: Long?,
        val isFavorite: Boolean,
        /**
         * Sum of [PhotoRepository.queryTotalSizeBytes] across [photos]. Null
         * while the MediaStore read is still in flight — [loadCluster] fills
         * the rest of the state in first so the screen never blocks on this,
         * then patches this field in once the total resolves.
         */
        val totalSizeBytes: Long? = null
    )

    /** One-shot toast/snackbar messages surfaced by screens. */
    sealed interface UserMessage {
        data class Renamed(val name: String) : UserMessage
        data class Merged(val targetName: String?) : UserMessage
        data class Favorited(val on: Boolean) : UserMessage
    }

    private val _messages = MutableSharedFlow<UserMessage>(
        replay = 0, extraBufferCapacity = 4
    )
    val messages: SharedFlow<UserMessage> = _messages.asSharedFlow()

    private val _selectedPhotoIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedPhotoIds: StateFlow<Set<Long>> = _selectedPhotoIds.asStateFlow()

    fun togglePhotoSelection(photoId: Long) {
        val current = _selectedPhotoIds.value
        _selectedPhotoIds.value = if (photoId in current) current - photoId else current + photoId
    }

    fun clearPhotoSelection() {
        _selectedPhotoIds.value = emptySet()
    }

    fun startIndex(forceFullRescan: Boolean = false) {
        Timber.d("startIndex(forceFullRescan=$forceFullRescan)")
        FaceIndexWorker.enqueue(getApplication(), forceFullRescan = forceFullRescan)
    }

    fun cancelIndex() {
        FaceIndexWorker.cancel(getApplication())
    }

    fun setMinClusterSize(size: Int) {
        viewModelScope.launch {
            prefs.setMinClusterSize(size.coerceAtLeast(1))
        }
    }

    /** Snappy local mirror for slider dragging; not yet persisted. */
    fun previewAssignThreshold(value: Float) {
        _pendingAssignThreshold.value = value
    }

    /** Commit the dragged threshold to DataStore and discard the pending mirror. */
    fun commitAssignThreshold() {
        val pending = _pendingAssignThreshold.value ?: return
        viewModelScope.launch {
            prefs.setAssignThreshold(pending)
            _pendingAssignThreshold.value = null
        }
    }

    fun setThemePreference(pref: ThemePreference) {
        viewModelScope.launch {
            prefs.setThemePreference(pref)
        }
    }

    fun recluster() {
        ReclusterWorker.enqueue(getApplication())
    }

    /**
     * Outcome of the last export date-repair run, or null if it has not been
     * run this session. Rendered as the Settings row's subtitle so the result
     * is visible without a dialog.
     */
    private val _exportDateRepair = MutableStateFlow<ExportDateRepairUseCase.Result?>(null)
    val exportDateRepair: StateFlow<ExportDateRepairUseCase.Result?> =
        _exportDateRepair.asStateFlow()

    private var repairJob: Job? = null

    /**
     * Rewrites the capture dates on albums exported before that bug was
     * fixed. Touches only app-owned rows inside Pictures/FaceAlbums, never
     * image bytes and never a source photo, and is idempotent — an album
     * already carrying the right dates is counted and skipped.
     */
    fun repairExportDates() {
        // Guard against a second tap while the first pass is still walking
        // the export log; the work is idempotent but a concurrent run would
        // double-count into the tallies the UI shows.
        if (repairJob?.isActive == true) return
        repairJob = viewModelScope.launch {
            val result = ExportDateRepairUseCase(db, photoRepository).run()
            _exportDateRepair.value = result
        }
    }

    fun loadCluster(clusterId: Long) {
        if (_selectedCluster.value?.clusterId != clusterId) {
            _selectedPhotoIds.value = emptySet()
        }
        viewModelScope.launch {
            val cluster = db.clusterDao().byId(clusterId) ?: return@launch
            val photoIds = db.faceDao().photoIdsInCluster(clusterId)
            val photos = if (photoIds.isEmpty()) {
                emptyList()
            } else {
                db.photoDao().findByIdsChunked(photoIds).sortedByDescending { it.dateTaken }
            }
            val firstAppearance = photos.minOfOrNull { it.dateTaken }
            val latestAppearance = photos.maxOfOrNull { it.dateTaken }
            _selectedCluster.value = ClusterDetailState(
                clusterId = clusterId,
                displayName = cluster.displayName,
                photos = photos,
                firstAppearance = firstAppearance,
                latestAppearance = latestAppearance,
                isFavorite = clusterId in favoriteClusterIds.value,
                totalSizeBytes = null
            )

            // Total size is read fresh from MediaStore, which for a large
            // album is slower than the Room reads above — computed after the
            // rest of the state is already published so the screen renders
            // immediately with a "calculating…" placeholder instead of
            // waiting on this. queryTotalSizeBytes itself runs on
            // Dispatchers.IO, so this never blocks the main thread either way.
            val totalBytes = if (photos.isEmpty()) {
                0L
            } else {
                photoRepository.queryTotalSizeBytes(photos.map { it.mediaStoreId })
            }
            if (_selectedCluster.value?.clusterId == clusterId) {
                _selectedCluster.value = _selectedCluster.value?.copy(totalSizeBytes = totalBytes)
            }
        }
    }

    fun renameCluster(clusterId: Long, name: String) {
        viewModelScope.launch {
            val trimmed = name.trim()
            db.clusterDao().rename(clusterId, trimmed, System.currentTimeMillis())
            if (_selectedCluster.value?.clusterId == clusterId) {
                loadCluster(clusterId)
            }
            if (trimmed.isNotEmpty()) _messages.tryEmit(UserMessage.Renamed(trimmed))
        }
    }

    fun mergeClusters(fromClusterId: Long, intoClusterId: Long) {
        viewModelScope.launch {
            val targetName = db.clusterDao().byId(intoClusterId)?.displayName
            newClusterer().mergeUserRequested(fromClusterId, intoClusterId)
            if (_selectedCluster.value?.clusterId == fromClusterId) {
                _selectedCluster.value = null
            } else if (_selectedCluster.value?.clusterId == intoClusterId) {
                loadCluster(intoClusterId)
            }
            _messages.tryEmit(UserMessage.Merged(targetName))
        }
    }

    fun clearIndex() {
        viewModelScope.launch {
            db.faceDao().clear()
            db.clusterDao().clear()
            db.photoDao().clear()
            // The export log records source paths and file names, so "delete
            // face data" has to take it too (items cascade with operations).
            db.exportDao().clearOperations()
        }
    }

    /**
     * Every non-empty cluster except [excludeClusterId], for the merge
     * picker. Deliberately draws from both [clusters] (>= "Minimum group
     * size") and [reviewNeededClusters] (below it) rather than [clusters]
     * alone — the latter made two below-threshold clusters of the same
     * person impossible to merge into each other, since neither one could
     * ever appear as a target for the other. Both source lists are already
     * kept live by the People and Review Needed screens' own
     * `collectAsState()` calls, so reusing their `.value` here needs no new
     * StateFlow (and no new `ClusterDao` query, which would also require a
     * matching override in `ClusteringBenchmarkTest`'s hand-written
     * `CountingClusterDao`). Sorted by faceCount desc — the same primary key
     * `ClusterDao.summariesAtLeast` and `ClusterDao.summariesBelow` already
     * use — so the merged list reads as one predictable ranking rather than
     * two lists stuck together.
     */
    fun pickAvailableMergeTargets(excludeClusterId: Long): List<ClusterSummary> {
        return (clusters.value + reviewNeededClusters.value)
            .filter { it.id != excludeClusterId }
            .sortedByDescending { it.faceCount }
    }

    fun toggleFavorite(clusterId: Long) {
        val nowOn = clusterId !in favoriteClusterIds.value
        viewModelScope.launch {
            prefs.setClusterFavorite(clusterId, nowOn)
            if (_selectedCluster.value?.clusterId == clusterId) {
                _selectedCluster.value = _selectedCluster.value?.copy(isFavorite = nowOn)
            }
            _messages.tryEmit(UserMessage.Favorited(nowOn))
        }
    }

    /**
     * Move all faces from [photoId] that currently belong to [fromClusterId]
     * to [toClusterId], then recompute centroids on both clusters.
     */
    fun reassignFacesForPhoto(photoId: Long, fromClusterId: Long, toClusterId: Long) {
        if (fromClusterId == toClusterId) return
        viewModelScope.launch {
            val faces = db.faceDao().facesForPhoto(photoId)
                .filter { it.clusterId == fromClusterId }
            if (faces.isEmpty()) return@launch
            for (face in faces) {
                db.faceDao().assignToCluster(face.id, toClusterId)
            }
            val clusterer = newClusterer()
            clusterer.recomputeFromFaces(fromClusterId)
            clusterer.recomputeFromFaces(toClusterId)
            loadCluster(fromClusterId)
        }
    }
}
