package com.facealbum.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.facealbum.MainViewModel
import com.facealbum.ui.screens.ClusterDetailScreen
import com.facealbum.ui.screens.ExportCompleteScreen
import com.facealbum.ui.screens.PeopleScreen
import com.facealbum.ui.screens.SettingsScreen
import com.facealbum.ui.screens.WelcomeScreen

sealed class Screen(val route: String) {
    object Welcome : Screen("welcome")
    object People : Screen("people")
    object ClusterDetail : Screen("cluster/{clusterId}") {
        fun build(id: Long) = "cluster/$id"
        const val ARG = "clusterId"
    }
    object Settings : Screen("settings")
    object ExportComplete : Screen("export_complete")
}

@Composable
fun NavGraph(
    navController: NavHostController,
    viewModel: MainViewModel = viewModel()
) {
    val clusters by viewModel.clusters.collectAsState()
    val indexProgress by viewModel.indexProgress.collectAsState()
    val selectedCluster by viewModel.selectedCluster.collectAsState()
    val lastExportResult by viewModel.lastExportResult.collectAsState()
    val minClusterSize by viewModel.minClusterSize.collectAsState()
    val assignThreshold by viewModel.assignThreshold.collectAsState()
    val pendingThreshold by viewModel.pendingAssignThreshold.collectAsState()
    val reclusterProgress by viewModel.reclusterProgress.collectAsState()

    // Listen for one-shot export completions globally so any screen can react.
    LaunchedEffect(Unit) {
        viewModel.exportEvents.collect {
            navController.navigate(Screen.ExportComplete.route)
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
                onClusterClick = { id ->
                    viewModel.loadCluster(id)
                    navController.navigate(Screen.ClusterDetail.build(id))
                },
                onScanNow = { viewModel.startIndex(forceFullRescan = false) },
                onOpenSettings = { navController.navigate(Screen.Settings.route) }
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
                    onBack = { navController.popBackStack() },
                    onRename = { name -> viewModel.renameCluster(clusterId, name) },
                    onExport = { albumName -> viewModel.exportCluster(clusterId, albumName) },
                    onMerge = { intoId -> viewModel.mergeClusters(clusterId, intoId) }
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
                onBack = { navController.popBackStack() }
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
