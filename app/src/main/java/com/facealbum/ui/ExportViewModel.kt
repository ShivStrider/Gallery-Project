package com.facealbum.ui

import android.app.Application
import android.content.IntentSender
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.facealbum.data.db.ExportOperationEntity
import com.facealbum.data.db.FaceAlbumDatabase
import com.facealbum.domain.ExportConsentUseCase
import com.facealbum.domain.ExportPlanner
import com.facealbum.domain.ExportReport
import com.facealbum.domain.ExportUndoUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Owns every piece of state the export flow needs: building a plan for the
 * user to review, committing it, tracking the resulting operation through to
 * a terminal state (or a stranded delete-consent prompt), and undo.
 *
 * This is the sole entry point screens use to reach [ExportPlanner] and
 * friends — no screen talks to the domain layer directly.
 */
class ExportViewModel(application: Application) : AndroidViewModel(application) {

    private val db: FaceAlbumDatabase = FaceAlbumDatabase.get(application)
    private val planner = ExportPlanner(application)
    private val consentUseCase = ExportConsentUseCase(application)
    private val undoUseCase = ExportUndoUseCase(application)

    private val _pendingPlan = MutableStateFlow<ExportPlanner.Plan?>(null)
    val pendingPlan: StateFlow<ExportPlanner.Plan?> = _pendingPlan.asStateFlow()

    /** Remembered alongside the plan so a mode change on re-plan targets the same subset. */
    private var pendingPhotoRowIds: List<Long>? = null

    private val _activeOperationId = MutableStateFlow<Long?>(null)
    val activeOperationId: StateFlow<Long?> = _activeOperationId.asStateFlow()

    private val _report = MutableStateFlow<ExportReport?>(null)
    val report: StateFlow<ExportReport?> = _report.asStateFlow()

    val awaitingConsent: StateFlow<List<ExportOperationEntity>> =
        db.exportDao().observeAwaitingConsent()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** One-shot outcomes that don't warrant navigating anywhere. */
    sealed interface UserMessage {
        object NothingToExport : UserMessage
        object MoveUnsupported : UserMessage
    }

    private val _messages = MutableSharedFlow<UserMessage>(replay = 0, extraBufferCapacity = 4)
    val messages: SharedFlow<UserMessage> = _messages.asSharedFlow()

    /** Fires once, with the new operation id, when a plan is successfully committed. */
    private val _navigateToReport = MutableSharedFlow<Long>(replay = 0, extraBufferCapacity = 1)
    val navigateToReport: SharedFlow<Long> = _navigateToReport.asSharedFlow()

    private var observeJob: Job? = null

    /**
     * Build a plan for the confirmation sheet. [photoRowIds] null means the
     * whole cluster; an explicit list means a subset export. The album name
     * is left for the planner to default from the cluster's display name —
     * callers only decide *which photos*, not the album name.
     */
    fun preparePlan(clusterId: Long, albumName: String, photoRowIds: List<Long>? = null) {
        pendingPhotoRowIds = photoRowIds
        viewModelScope.launch {
            val plan = planner.plan(
                clusterId = clusterId,
                requestedAlbumName = albumName,
                photoRowIds = photoRowIds,
                mode = ExportPlanner.Mode.COPY
            )
            _pendingPlan.value = plan
        }
    }

    fun dismissPlan() {
        _pendingPlan.value = null
        pendingPhotoRowIds = null
    }

    /**
     * Re-plans with [mode] (the user may have flipped Copy/Move after the
     * preview opened, so the committed plan must reflect that, not the one
     * built at [preparePlan] time) and commits it.
     */
    fun confirm(albumName: String, mode: ExportPlanner.Mode) {
        val currentPlan = _pendingPlan.value ?: return
        val photoRowIds = pendingPhotoRowIds
        viewModelScope.launch {
            val plan = planner.plan(
                clusterId = currentPlan.clusterId,
                requestedAlbumName = albumName,
                photoRowIds = photoRowIds,
                mode = mode
            )
            when (val result = planner.commit(plan)) {
                is ExportPlanner.CommitResult.Started -> {
                    _pendingPlan.value = null
                    pendingPhotoRowIds = null
                    _activeOperationId.value = result.operationId
                    _report.value = null
                    observeOperation(result.operationId)
                    Timber.i("Export confirmed: operation ${result.operationId}")
                    _navigateToReport.tryEmit(result.operationId)
                }
                ExportPlanner.CommitResult.NothingToDo -> {
                    _messages.tryEmit(UserMessage.NothingToExport)
                }
                ExportPlanner.CommitResult.MoveUnsupported -> {
                    _messages.tryEmit(UserMessage.MoveUnsupported)
                }
            }
        }
    }

    /** Refresh [report] whenever the operation reaches a state worth showing. */
    private fun observeOperation(operationId: Long) {
        observeJob?.cancel()
        observeJob = db.exportDao().observeOperation(operationId)
            .onEach { operation ->
                if (operation == null) return@onEach
                val worthRefreshing = operation.state in ExportOperationEntity.TERMINAL_STATES ||
                    operation.state == ExportOperationEntity.STATE_AWAITING_DELETE_CONSENT
                if (worthRefreshing) {
                    refreshReport(operationId)
                }
            }
            .launchIn(viewModelScope)
    }

    private suspend fun refreshReport(operationId: Long) {
        _report.value = ExportReport.load(db, operationId)
    }

    /** The delete-consent prompt for the first chunk of deletable sources, or null if none. */
    suspend fun consentRequest(operationId: Long): IntentSender? {
        val uris = consentUseCase.deletableSourceUris(operationId)
        val firstChunk = consentUseCase.chunkForConsent(uris).firstOrNull() ?: return null
        return consentUseCase.createDeleteRequest(firstChunk)
    }

    fun onConsentResult(operationId: Long, granted: Boolean) {
        viewModelScope.launch {
            consentUseCase.finalizeAfterConsent(operationId, granted)
            refreshReport(operationId)
        }
    }

    fun undo(operationId: Long) {
        viewModelScope.launch {
            undoUseCase.undo(operationId)
            refreshReport(operationId)
        }
    }
}
