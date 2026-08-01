package com.facealbum.navigation

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.facealbum.MainViewModel
import com.facealbum.R
import com.facealbum.config.ExportFeature
import com.facealbum.data.db.ExportOperationEntity
import com.facealbum.ui.ExportViewModel
import com.facealbum.ui.screens.ClusterDetailScreen
import com.facealbum.ui.screens.ExportCompleteScreen
import com.facealbum.ui.screens.ImageViewerScreen
import com.facealbum.ui.screens.PeopleScreen
import com.facealbum.ui.screens.SettingsScreen
import com.facealbum.ui.screens.WelcomeScreen
import com.facealbum.ui.theme.Spacing
import com.facealbum.util.rememberHasPartialPhotoAccess
import kotlinx.coroutines.launch

sealed class Screen(val route: String) {
    object Welcome : Screen("welcome")
    object People : Screen("people")
    object ClusterDetail : Screen("cluster/{clusterId}") {
        fun build(id: Long) = "cluster/$id"
        const val ARG = "clusterId"
    }
    object ImageViewer : Screen("viewer/{clusterId}/{index}") {
        fun build(clusterId: Long, index: Int) = "viewer/$clusterId/$index"
        const val ARG_CLUSTER = "clusterId"
        const val ARG_INDEX = "index"
    }
    object Settings : Screen("settings")
    object ExportComplete : Screen("export_complete")
}

@Composable
fun NavGraph(
    navController: NavHostController,
    viewModel: MainViewModel = viewModel(),
    exportViewModel: ExportViewModel = viewModel(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    val clusters by viewModel.clusters.collectAsState()
    val indexProgress by viewModel.indexProgress.collectAsState()
    val selectedCluster by viewModel.selectedCluster.collectAsState()
    val minClusterSize by viewModel.minClusterSize.collectAsState()
    val assignThreshold by viewModel.assignThreshold.collectAsState()
    val pendingThreshold by viewModel.pendingAssignThreshold.collectAsState()
    val reclusterProgress by viewModel.reclusterProgress.collectAsState()
    val themePreference by viewModel.themePreference.collectAsState()
    val selectedPhotoIds by viewModel.selectedPhotoIds.collectAsState()

    val pendingExportPlan by exportViewModel.pendingPlan.collectAsState()
    val exportReport by exportViewModel.report.collectAsState()
    val awaitingConsent by exportViewModel.awaitingConsent.collectAsState()

    val renamedMsg = stringResource(R.string.snack_renamed)
    val mergedMsg = stringResource(R.string.snack_merged)
    val favOnMsg = stringResource(R.string.snack_favorited)
    val favOffMsg = stringResource(R.string.snack_unfavorited)
    val unnamed = stringResource(R.string.people_unnamed)
    val nothingToExportMsg = stringResource(R.string.snack_nothing_to_export)
    val moveUnsupportedMsg = stringResource(R.string.snack_move_unsupported)

    val coroutineScope = rememberCoroutineScope()
    var consentOperationId by remember { mutableStateOf<Long?>(null) }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val operationId = consentOperationId
        if (operationId != null) {
            exportViewModel.onConsentResult(operationId, result.resultCode == Activity.RESULT_OK)
        }
        consentOperationId = null
    }

    LaunchedEffect(Unit) {
        exportViewModel.navigateToReport.collect {
            navController.navigate(Screen.ExportComplete.route)
        }
    }

    LaunchedEffect(Unit) {
        exportViewModel.messages.collect { msg ->
            val text = when (msg) {
                ExportViewModel.UserMessage.NothingToExport -> nothingToExportMsg
                ExportViewModel.UserMessage.MoveUnsupported -> moveUnsupportedMsg
            }
            snackbarHostState.showSnackbar(text)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg ->
            val text = when (msg) {
                is MainViewModel.UserMessage.Renamed -> renamedMsg.format(msg.name)
                is MainViewModel.UserMessage.Merged ->
                    mergedMsg.format(msg.targetName?.takeIf { it.isNotBlank() } ?: unnamed)
                is MainViewModel.UserMessage.Favorited ->
                    if (msg.on) favOnMsg else favOffMsg
            }
            snackbarHostState.showSnackbar(text)
        }
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Welcome.route
    ) {
        composable(Screen.Welcome.route) {
            WelcomeScreen(
                onPermissionGranted = {
                    viewModel.startIndex(forceFullRescan = false)
                    navController.navigate(Screen.People.route) {
                        popUpTo(Screen.Welcome.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.People.route) {
            Box(modifier = Modifier.fillMaxSize()) {
                PeopleScreen(
                    clusters = clusters,
                    indexProgress = indexProgress,
                    snackbarHostState = snackbarHostState,
                    onClusterClick = { id ->
                        viewModel.loadCluster(id)
                        navController.navigate(Screen.ClusterDetail.build(id))
                    },
                    onScanNow = { viewModel.startIndex(forceFullRescan = false) },
                    onOpenSettings = { navController.navigate(Screen.Settings.route) },
                    limitedAccess = rememberHasPartialPhotoAccess()
                )

                // The delete-consent prompt must run in the foreground and can be
                // stranded by process death between the copy phase and the system
                // dialog, so it is re-offered here every time this screen composes
                // rather than being dismissible — see ExportConsentUseCase's kdoc.
                val awaitingOperation = awaitingConsent.firstOrNull()
                if (awaitingOperation != null) {
                    ExportConsentBanner(
                        operation = awaitingOperation,
                        onContinue = {
                            val operationId = awaitingOperation.id
                            coroutineScope.launch {
                                val sender = exportViewModel.consentRequest(operationId)
                                if (sender != null) {
                                    consentOperationId = operationId
                                    consentLauncher.launch(
                                        IntentSenderRequest.Builder(sender).build()
                                    )
                                }
                            }
                        },
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }
            }
        }

        composable(
            route = Screen.ClusterDetail.route,
            arguments = listOf(navArgument(Screen.ClusterDetail.ARG) { type = NavType.LongType })
        ) { backStackEntry ->
            val clusterId = backStackEntry.arguments?.getLong(Screen.ClusterDetail.ARG) ?: -1L
            val state = selectedCluster
            if (state != null && state.clusterId == clusterId) {
                ClusterDetailScreen(
                    state = state,
                    mergeCandidates = viewModel.pickAvailableMergeTargets(clusterId),
                    selectedPhotoIds = selectedPhotoIds,
                    pendingExportPlan = pendingExportPlan,
                    moveAvailable = ExportFeature.moveAvailable(),
                    onBack = {
                        viewModel.clearPhotoSelection()
                        exportViewModel.dismissPlan()
                        navController.popBackStack()
                    },
                    onRename = { name -> viewModel.renameCluster(clusterId, name) },
                    onRequestExportPlan = { photoRowIds ->
                        exportViewModel.preparePlan(clusterId, "", photoRowIds)
                    },
                    onDismissExportPlan = { exportViewModel.dismissPlan() },
                    onConfirmExportPlan = { albumName, mode ->
                        exportViewModel.confirm(albumName, mode)
                    },
                    onMerge = { intoId -> viewModel.mergeClusters(clusterId, intoId) },
                    onToggleFavorite = { viewModel.toggleFavorite(clusterId) },
                    onPhotoTap = { index ->
                        navController.navigate(Screen.ImageViewer.build(clusterId, index))
                    },
                    onTogglePhotoSelection = viewModel::togglePhotoSelection,
                    onClearSelection = viewModel::clearPhotoSelection,
                    onReassignPhoto = { photoId, toClusterId ->
                        viewModel.reassignFacesForPhoto(photoId, clusterId, toClusterId)
                    }
                )
            }
        }

        composable(
            route = Screen.ImageViewer.route,
            arguments = listOf(
                navArgument(Screen.ImageViewer.ARG_CLUSTER) { type = NavType.LongType },
                navArgument(Screen.ImageViewer.ARG_INDEX) { type = NavType.IntType }
            )
        ) { backStackEntry ->
            val clusterId = backStackEntry.arguments?.getLong(Screen.ImageViewer.ARG_CLUSTER) ?: -1L
            val index = backStackEntry.arguments?.getInt(Screen.ImageViewer.ARG_INDEX) ?: 0
            val state = selectedCluster
            // Only render if we still have the cluster loaded — otherwise pop.
            LaunchedEffect(clusterId, state) {
                if (state == null || state.clusterId != clusterId) {
                    navController.popBackStack()
                }
            }
            if (state != null && state.clusterId == clusterId) {
                ImageViewerScreen(
                    photos = state.photos,
                    initialIndex = index,
                    onClose = { navController.popBackStack() }
                )
            }
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                minClusterSize = minClusterSize,
                onMinClusterSizeChange = viewModel::setMinClusterSize,
                assignThreshold = assignThreshold,
                pendingAssignThreshold = pendingThreshold,
                reclusterProgress = reclusterProgress,
                onPreviewThreshold = viewModel::previewAssignThreshold,
                onCommitThreshold = viewModel::commitAssignThreshold,
                onRecluster = viewModel::recluster,
                onRescanAll = { viewModel.startIndex(forceFullRescan = true) },
                onDeleteIndex = { viewModel.clearIndex() },
                onBack = { navController.popBackStack() },
                themePreference = themePreference,
                onThemeChange = viewModel::setThemePreference
            )
        }

        composable(Screen.ExportComplete.route) {
            ExportCompleteScreen(
                exportedCount = exportReport?.exportedCount ?: 0,
                albumName = exportReport?.albumName ?: "",
                onStartOver = {
                    navController.popBackStack(Screen.People.route, inclusive = false)
                },
                report = exportReport,
                onUndo = {
                    exportReport?.let { report -> exportViewModel.undo(report.operationId) }
                }
            )
        }
    }
}

/**
 * Shown on the People screen whenever a move export is parked waiting for the
 * system delete-confirmation prompt (e.g. the app was killed before the user
 * answered it). Tapping through re-launches that prompt via
 * [ExportViewModel.consentRequest].
 */
@Composable
private fun ExportConsentBanner(
    operation: ExportOperationEntity,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.md),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(Spacing.md)
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = stringResource(
                    R.string.export_consent_banner_message,
                    operation.totalCount,
                    operation.albumName
                ),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            TextButton(onClick = onContinue) {
                Text(stringResource(R.string.export_consent_banner_action))
            }
        }
    }
}
