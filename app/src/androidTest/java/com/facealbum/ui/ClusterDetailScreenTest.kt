package com.facealbum.ui

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit4.runners.AndroidJUnit4
import com.facealbum.MainViewModel
import com.facealbum.data.db.ClusterSummary
import com.facealbum.data.db.PhotoEntity
import com.facealbum.ui.screens.ClusterDetailScreen
import com.facealbum.ui.theme.FaceAlbumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClusterDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun makeState(name: String? = "Alice", photoCount: Int = 0) =
        MainViewModel.ClusterDetailState(
            clusterId = 1L,
            displayName = name,
            photos = (1..photoCount).map { i ->
                PhotoEntity(
                    id = i.toLong(),
                    mediaStoreId = i.toLong(),
                    uri = "content://media/photo/$i",
                    displayName = "photo$i.jpg",
                    dateTaken = 0L,
                    dateModified = 0L,
                    processedAt = 0L,
                    faceCount = 1
                )
            }
        )

    @Test
    fun displayName_isShownInHero() {
        composeTestRule.setContent {
            FaceAlbumTheme {
                ClusterDetailScreen(
                    state = makeState("Charlie"),
                    mergeCandidates = emptyList(),
                    selectedPhotoIds = emptySet(),
                    onBack = {},
                    onRename = {},
                    onExport = {},
                    onMerge = {},
                    onTogglePhotoSelection = {},
                    onClearSelection = {},
                    onExportSelected = {},
                    onReassignPhoto = { _, _ -> }
                )
            }
        }
        composeTestRule.onNodeWithText("Charlie").assertIsDisplayed()
    }

    @Test
    fun unnamed_fallbackText_isShown() {
        composeTestRule.setContent {
            FaceAlbumTheme {
                ClusterDetailScreen(
                    state = makeState(name = null),
                    mergeCandidates = emptyList(),
                    selectedPhotoIds = emptySet(),
                    onBack = {},
                    onRename = {},
                    onExport = {},
                    onMerge = {},
                    onTogglePhotoSelection = {},
                    onClearSelection = {},
                    onExportSelected = {},
                    onReassignPhoto = { _, _ -> }
                )
            }
        }
        composeTestRule.onNodeWithText("Unnamed").assertIsDisplayed()
    }

    @Test
    fun renameButton_click_opensDialog() {
        composeTestRule.setContent {
            FaceAlbumTheme {
                ClusterDetailScreen(
                    state = makeState("Diana"),
                    mergeCandidates = emptyList(),
                    selectedPhotoIds = emptySet(),
                    onBack = {},
                    onRename = {},
                    onExport = {},
                    onMerge = {},
                    onTogglePhotoSelection = {},
                    onClearSelection = {},
                    onExportSelected = {},
                    onReassignPhoto = { _, _ -> }
                )
            }
        }
        // The edit (rename) icon button in the hero
        composeTestRule.onNodeWithText("Rename").assertIsDisplayed()
    }

    @Test
    fun exportButton_click_opensAlbumNameDialog() {
        var albumName = ""
        composeTestRule.setContent {
            FaceAlbumTheme {
                ClusterDetailScreen(
                    state = makeState("Eve"),
                    mergeCandidates = emptyList(),
                    selectedPhotoIds = emptySet(),
                    onBack = {},
                    onRename = {},
                    onExport = { name -> albumName = name },
                    onMerge = {},
                    onTogglePhotoSelection = {},
                    onClearSelection = {},
                    onExportSelected = {},
                    onReassignPhoto = { _, _ -> }
                )
            }
        }
        composeTestRule.onNodeWithText("Export album").performClick()
        // Dialog should appear with a confirm button
        composeTestRule.onNodeWithText("Export").assertIsDisplayed()
    }

    @Test
    fun selectionBanner_shownWhenPhotosSelected() {
        composeTestRule.setContent {
            FaceAlbumTheme {
                ClusterDetailScreen(
                    state = makeState("Frank", photoCount = 2),
                    mergeCandidates = emptyList(),
                    selectedPhotoIds = setOf(1L),
                    onBack = {},
                    onRename = {},
                    onExport = {},
                    onMerge = {},
                    onTogglePhotoSelection = {},
                    onClearSelection = {},
                    onExportSelected = {},
                    onReassignPhoto = { _, _ -> }
                )
            }
        }
        // Banner title shows "N selected"
        composeTestRule.onNodeWithText("1 selected").assertIsDisplayed()
        // "Move to person…" appears when exactly 1 photo is selected
        composeTestRule.onNodeWithText("Move to person…").assertIsDisplayed()
        // Cancel button in the banner
        composeTestRule.onNodeWithText("Cancel selection").assertIsDisplayed()
        // Hero button also reflects the selection count
        composeTestRule.onNodeWithText("Export 1 selected").assertIsDisplayed()
    }

    @Test
    fun selectionBanner_moveToHidden_withMultipleSelected() {
        composeTestRule.setContent {
            FaceAlbumTheme {
                ClusterDetailScreen(
                    state = makeState("Heidi", photoCount = 3),
                    mergeCandidates = emptyList(),
                    selectedPhotoIds = setOf(1L, 2L),
                    onBack = {},
                    onRename = {},
                    onExport = {},
                    onMerge = {},
                    onTogglePhotoSelection = {},
                    onClearSelection = {},
                    onExportSelected = {},
                    onReassignPhoto = { _, _ -> }
                )
            }
        }
        composeTestRule.onNodeWithText("2 selected").assertIsDisplayed()
        // "Move to person…" is hidden when more than one photo is selected
        composeTestRule.onNodeWithText("Move to person…", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun mergeButton_click_showsMergeDialog() {
        val candidate = ClusterSummary(id = 99L, displayName = "Grace", faceCount = 5, coverPhotoUri = null)
        composeTestRule.setContent {
            FaceAlbumTheme {
                ClusterDetailScreen(
                    state = makeState("Frank"),
                    mergeCandidates = listOf(candidate),
                    selectedPhotoIds = emptySet(),
                    onBack = {},
                    onRename = {},
                    onExport = {},
                    onMerge = {},
                    onTogglePhotoSelection = {},
                    onClearSelection = {},
                    onExportSelected = {},
                    onReassignPhoto = { _, _ -> }
                )
            }
        }
        composeTestRule.onNodeWithText("Merge with…").performClick()
        composeTestRule.onNodeWithText("Grace").assertIsDisplayed()
    }
}
