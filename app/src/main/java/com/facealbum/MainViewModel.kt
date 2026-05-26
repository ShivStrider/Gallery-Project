package com.facealbum

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.facealbum.config.FaceRecognitionConfig
import com.facealbum.data.db.ClusterSummary
import com.facealbum.data.db.FaceAlbumDatabase
import com.facealbum.data.db.PhotoEntity
import com.facealbum.data.prefs.UserPreferences
import com.facealbum.domain.ClusterAlbumExportUseCase
import com.facealbum.domain.FaceClusterer
import com.facealbum.work.FaceIndexWorker
import com.facealbum.work.ReclusterWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Top-level state holder for the face-clustering UI.
 *
 * The library is indexed by [FaceIndexWorker] (a foreground service). Live
 * progress is read off WorkManager's `Flow<WorkInfo>`. Clusters live in Room
 * and surface through [ClusterDao.summariesAtLeast] so the People grid stays
 * in sync as the worker writes new faces.
 *
 * User preferences (threshold, min cluster size) are durable via [UserPreferences].
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db: FaceAlbumDatabase = FaceAlbumDatabase.get(application)
    private val prefs: UserPreferences = UserPreferences.get(application)
    private val clusterer = FaceClusterer(db.clusterDao(), db.faceDao())
    private val exportUseCase = ClusterAlbumExportUseCase(application)

    val minClusterSize: StateFlow<Int> = prefs.minClusterSize
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            FaceRecognitionConfig.DEFAULT_MIN_CLUSTER_SIZE
        )

    /**
     * Persisted match strictness. Source of truth is DataStore. While the user
     * drags the slider we mirror the latest value in [_pendingAssignThreshold]
     * for snappy UI feedback and commit on release.
     */
    val assignThreshold: StateFlow<Float> = prefs.assignThreshold
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            FaceRecognitionConfig.CLUSTER_ASSIGN_THRESHOLD
        )

    private val _pendingAssignThreshold = MutableStateFlow<Float?>(null)
    val pendingAssignThreshold: StateFlow<Float?> = _pendingAssignThreshold.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val clusters: StateFlow<List<ClusterSummary>> =
        minClusterSize
            .flatMapLatest { min -> db.clusterDao().summariesAtLeast(min) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    data class ClusterDetailState(
        val clusterId: Long,
        val displayName: String?,
        val photos: List<PhotoEntity>
    )

    /**
     * One-shot export-completed signal. We use a `SharedFlow` rather than a
     * `StateFlow<Result?>` so navigating back to a cluster detail screen after a
     * successful export does not re-trigger navigation to ExportComplete.
     */
    private val _exportEvents = MutableSharedFlow<ClusterAlbumExportUseCase.Result>(
        replay = 0,
        extraBufferCapacity = 1
    )
    val exportEvents: SharedFlow<ClusterAlbumExportUseCase.Result> = _exportEvents.asSharedFlow()

    /** Snapshot of the most recently completed export, displayed on ExportComplete. */
    private val _lastExportResult = MutableStateFlow<ClusterAlbumExportUseCase.Result?>(null)
    val lastExportResult: StateFlow<ClusterAlbumExportUseCase.Result?> = _lastExportResult.asStateFlow()

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

    fun recluster() {
        ReclusterWorker.enqueue(getApplication())
    }

    fun loadCluster(clusterId: Long) {
        viewModelScope.launch {
            val cluster = db.clusterDao().byId(clusterId) ?: return@launch
            val photoIds = db.faceDao().photoIdsInCluster(clusterId)
            val photos = photoIds.mapNotNull { db.photoDao().findById(it) }
                .sortedByDescending { it.dateTaken }
            _selectedCluster.value = ClusterDetailState(
                clusterId = clusterId,
                displayName = cluster.displayName,
                photos = photos
            )
        }
    }

    fun renameCluster(clusterId: Long, name: String) {
        viewModelScope.launch {
            db.clusterDao().rename(clusterId, name.trim(), System.currentTimeMillis())
            // Refresh detail state if open.
            if (_selectedCluster.value?.clusterId == clusterId) {
                loadCluster(clusterId)
            }
        }
    }

    fun mergeClusters(fromClusterId: Long, intoClusterId: Long) {
        viewModelScope.launch {
            clusterer.mergeUserRequested(fromClusterId, intoClusterId)
            if (_selectedCluster.value?.clusterId == fromClusterId) {
                _selectedCluster.value = null
            } else if (_selectedCluster.value?.clusterId == intoClusterId) {
                loadCluster(intoClusterId)
            }
        }
    }

    fun exportCluster(clusterId: Long, albumName: String) {
        viewModelScope.launch {
            val result = exportUseCase.export(clusterId, albumName)
            _lastExportResult.value = result
            _exportEvents.tryEmit(result)
        }
    }

    fun clearIndex() {
        viewModelScope.launch {
            // Photos cascade to faces; clusters need explicit clearing.
            db.faceDao().clear()
            db.clusterDao().clear()
            db.photoDao().clear()
        }
    }

    fun pickAvailableMergeTargets(excludeClusterId: Long): List<ClusterSummary> {
        return clusters.value.filter { it.id != excludeClusterId }
    }
}
