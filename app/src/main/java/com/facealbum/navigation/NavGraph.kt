package com.facealbum.navigation

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.facealbum.MainViewModel
import com.facealbum.R
import com.facealbum.ui.screens.ClusterDetailScreen
import com.facealbum.ui.screens.ExportCompleteScreen
import com.facealbum.ui.screens.ImageViewerScreen
import com.facealbum.ui.screens.PeopleScreen
import com.facealbum.ui.screens.SettingsScreen
import com.facealbum.ui.screens.WelcomeScreen
import com.facealbum.util.rememberHasPartialPhotoAccess

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
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    val clusters by viewModel.clusters.collectAsState()
    val indexProgress by viewModel.indexProgress.collectAsState()
    val selectedCluster by viewModel.selectedCluster.collectAsState()
    val lastExportResult by viewModel.lastExportResult.collectAsState()
    val minClusterSize by viewModel.minClusterSize.collectAsState()
    val assignThreshold by viewModel.assignThreshold.collectAsState()
    val pendingThreshold by viewModel.pendingAssignThreshold.collectAsState()
    val reclusterProgress by viewModel.reclusterProgress.collectAsState()
    val themePreference by viewModel.themePreference.collectAsState()
    val selectedPhotoIds by viewModel.selectedPhotoIds.collectAsState()

    val renamedMsg = stringResource(R.string.snack_renamed)
    val mergedMsg = stringResource(R.string.snack_merged)
    val favOnMsg = stringResource(R.string.snack_favorited)
    val favOffMsg = stringResource(R.string.snack_unfavorited)
    val unnamed = stringResource(R.string.people_unnamed)

    LaunchedEffect(Unit) {
        viewModel.exportEvents.collect {
            navController.navigate(Screen.ExportComplete.route)
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
                    onBack = {
                        viewModel.clearPhotoSelection()
                        navController.popBackStack()
                    },
                    onRename = { name -> viewModel.renameCluster(clusterId, name) },
                    onExport = { albumName -> viewModel.exportCluster(clusterId, albumName) },
                    onMerge = { intoId -> viewModel.mergeClusters(clusterId, intoId) },
                    onToggleFavorite = { viewModel.toggleFavorite(clusterId) },
                    onPhotoTap = { index ->
                        navController.navigate(Screen.ImageViewer.build(clusterId, index))
                    },
                    onTogglePhotoSelection = viewModel::togglePhotoSelection,
                    onClearSelection = viewModel::clearPhotoSelection,
                    onExportSelected = { albumName -> viewModel.exportSelectedPhotos(clusterId, albumName) },
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
            val result = lastExportResult
            ExportCompleteScreen(
                exportedCount = result?.successCount ?: 0,
                albumName = result?.albumName ?: "",
                onStartOver = {
                    navController.popBackStack(Screen.People.route, inclusive = false)
                }
            )
        }
    }
}
