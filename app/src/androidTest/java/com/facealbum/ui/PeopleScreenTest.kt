package com.facealbum.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit4.runners.AndroidJUnit4
import com.facealbum.MainViewModel
import com.facealbum.data.db.ClusterSummary
import com.facealbum.ui.screens.PeopleScreen
import com.facealbum.ui.theme.FaceAlbumTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PeopleScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun noProgress() = MainViewModel.IndexProgress(
        running = false, done = 0, total = 0, faces = 0, clusters = 0
    )

    private fun runningProgress() = MainViewModel.IndexProgress(
        running = true, done = 3, total = 10, faces = 5, clusters = 2
    )

    private val snackbarHost = SnackbarHostState()

    @Test
    fun emptyState_showsFriendlyTitleAndScanButton() {
        composeTestRule.setContent {
            FaceAlbumTheme {
                PeopleScreen(
                    clusters = emptyList(),
                    indexProgress = noProgress(),
                    snackbarHostState = snackbarHost,
                    onClusterClick = {},
                    onScanNow = {},
                    onOpenSettings = {}
                )
            }
        }
        composeTestRule.onNodeWithText("Your people will appear here").assertIsDisplayed()
        composeTestRule.onNodeWithText("Scan").assertIsDisplayed()
    }

    @Test
    fun scanningState_showsScanningSubtitle() {
        composeTestRule.setContent {
            FaceAlbumTheme {
                PeopleScreen(
                    clusters = emptyList(),
                    indexProgress = runningProgress(),
                    snackbarHostState = snackbarHost,
                    onClusterClick = {},
                    onScanNow = {},
                    onOpenSettings = {}
                )
            }
        }
        composeTestRule.onNodeWithText("Looking through your photos — hang tight.")
            .assertIsDisplayed()
    }

    @Test
    fun progressBanner_showsWhenRunning() {
        composeTestRule.setContent {
            FaceAlbumTheme {
                PeopleScreen(
                    clusters = emptyList(),
                    indexProgress = runningProgress(),
                    snackbarHostState = snackbarHost,
                    onClusterClick = {},
                    onScanNow = {},
                    onOpenSettings = {}
                )
            }
        }
        composeTestRule.onNodeWithText("Scanning 3 of 10 · 5 faces found").assertIsDisplayed()
    }

    @Test
    fun clusterGrid_showsClusterName() {
        val clusters = listOf(
            ClusterSummary(id = 1L, displayName = "Alice", faceCount = 12, coverPhotoUri = null)
        )
        composeTestRule.setContent {
            FaceAlbumTheme {
                PeopleScreen(
                    clusters = clusters,
                    indexProgress = noProgress(),
                    snackbarHostState = snackbarHost,
                    onClusterClick = {},
                    onScanNow = {},
                    onOpenSettings = {}
                )
            }
        }
        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
        composeTestRule.onNodeWithText("12 photos").assertIsDisplayed()
    }

    @Test
    fun clusterTile_onClick_invokesCallback() {
        var clickedId = -1L
        val clusters = listOf(
            ClusterSummary(id = 42L, displayName = "Bob", faceCount = 3, coverPhotoUri = null)
        )
        composeTestRule.setContent {
            FaceAlbumTheme {
                PeopleScreen(
                    clusters = clusters,
                    indexProgress = noProgress(),
                    snackbarHostState = snackbarHost,
                    onClusterClick = { id -> clickedId = id },
                    onScanNow = {},
                    onOpenSettings = {}
                )
            }
        }
        composeTestRule.onNodeWithText("Bob").performClick()
        assertTrue("Expected cluster 42 click, got $clickedId", clickedId == 42L)
    }

    @Test
    fun errorBanner_showsErrorMessage() {
        val errorProgress = MainViewModel.IndexProgress(
            running = false, done = 0, total = 0, faces = 0, clusters = 0,
            errorMessage = "Model not found"
        )
        composeTestRule.setContent {
            FaceAlbumTheme {
                PeopleScreen(
                    clusters = emptyList(),
                    indexProgress = errorProgress,
                    snackbarHostState = snackbarHost,
                    onClusterClick = {},
                    onScanNow = {},
                    onOpenSettings = {}
                )
            }
        }
        composeTestRule.onNodeWithText("Model not found").assertIsDisplayed()
    }
}
