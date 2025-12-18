package com.facealbum.navigation

import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.facealbum.MainViewModel
import com.facealbum.model.ScanState
import com.facealbum.ui.screens.*

/**
 * Navigation routes
 */
sealed class Screen(val route: String) {
    object Welcome : Screen("welcome")
    object SeedSelection : Screen("seed_selection")
    object Scanning : Screen("scanning")
    object Review : Screen("review")
    object ExportComplete : Screen("export_complete")
}

/**
 * Main navigation graph
 */
@Composable
fun NavGraph(
    navController: NavHostController,
    viewModel: MainViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val recentPhotos by viewModel.recentPhotos.collectAsState()
    val exportedCount by viewModel.exportedCount.collectAsState()

    NavHost(
        navController = navController,
        startDestination = Screen.Welcome.route
    ) {
        composable(Screen.Welcome.route) {
            WelcomeScreen(
                onPermissionGranted = {
                    viewModel.loadRecentPhotos(limit = 100)
                    navController.navigate(Screen.SeedSelection.route) {
                        popUpTo(Screen.Welcome.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.SeedSelection.route) {
            SeedSelectionScreen(
                photos = recentPhotos,
                selectedUris = uiState.seedUris,
                onToggleSelection = { uri -> viewModel.toggleSeedSelection(uri) },
                onContinue = {
                    viewModel.startScan()
                    navController.navigate(Screen.Scanning.route)
                }
            )
        }

        composable(Screen.Scanning.route) {
            // Monitor scan state and navigate when complete
            LaunchedEffect(uiState.scanState) {
                when (uiState.scanState) {
                    is ScanState.Complete -> {
                        navController.navigate(Screen.Review.route) {
                            popUpTo(Screen.SeedSelection.route)
                        }
                    }
                    is ScanState.Error -> {
                        // Handle error - for MVP, just go back
                        navController.popBackStack()
                    }
                    else -> { /* Continue scanning */ }
                }
            }

            val scanState = uiState.scanState
            if (scanState is ScanState.Scanning) {
                ScanningScreen(
                    progress = scanState.progress,
                    onCancel = {
                        viewModel.cancelScan()
                        navController.popBackStack()
                    }
                )
            }
        }

        composable(Screen.Review.route) {
            ReviewScreen(
                candidates = uiState.candidates,
                albumName = uiState.albumName,
                onAlbumNameChange = { name -> viewModel.setAlbumName(name) },
                onToggleApproval = { photoId -> viewModel.toggleCandidateApproval(photoId) },
                onExport = {
                    viewModel.exportPhotos()
                    navController.navigate(Screen.ExportComplete.route) {
                        popUpTo(Screen.Welcome.route)
                    }
                }
            )
        }

        composable(Screen.ExportComplete.route) {
            ExportCompleteScreen(
                exportedCount = exportedCount,
                albumName = uiState.albumName,
                onStartOver = {
                    viewModel.reset()
                    navController.navigate(Screen.SeedSelection.route) {
                        popUpTo(Screen.Welcome.route)
                    }
                }
            )
        }
    }
}
