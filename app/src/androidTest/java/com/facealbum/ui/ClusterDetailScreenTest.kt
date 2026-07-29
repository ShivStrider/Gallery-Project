package com.facealbum.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facealbum.MainViewModel
import com.facealbum.data.db.ClusterSummary
import com.facealbum.data.db.PhotoEntity
import com.facealbum.ui.screens.ClusterDetailScreen
import com.facealbum.ui.theme.FaceAlbumTheme
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
            },
            firstAppearance = null,
            latestAppearance = null,
            isFavorite = false
        )

    private fun render(
        state: MainViewModel.ClusterDetailState,
        selected: Set<Long> = emptySet(),
        candidates: List<ClusterSummary> = emptyList(),
        onExport: (String) -> Unit = {},
        onMerge: (Long) -> Unit = {}
    ) {
        composeTestRule.setContent {
            FaceAlbumTheme {
                ClusterDetailScreen(
                    state = state,
                    mergeCandidates = candidates,
                    selectedPhotoIds = selected,
                    onBack = {},
                    onRename = {},
                    onExport = onExport,
                    onMerge = onMerge,
                    onToggleFavorite = {},
                    onPhotoTap = {},
                    onTogglePhotoSelection = {},
                    onClearSelection = {},
                    onExportSelected = {},
                    onReassignPhoto = { _, _ -> }
                )
            }
        }
    }

    @Test
    fun displayName_isShownInHero() {
        render(makeState("Charlie"))
        composeTestRule.onNodeWithText("Charlie").assertIsDisplayed()
    }

    @Test
    fun unnamed_fallbackText_isShown() {
        render(makeState(name = null))
        composeTestRule.onNodeWithText("Unnamed").assertIsDisplayed()
    }

    @Test
    fun renameAction_isShownInActionRow() {
        render(makeState("Diana"))
        composeTestRule.onNodeWithText("Rename").assertIsDisplayed()
    }

    @Test
    fun exportButton_click_opensAlbumNameDialog() {
        render(makeState("Eve"))
        composeTestRule.onNodeWithText("Export album").performClick()
        composeTestRule.onNodeWithText("Export").assertIsDisplayed()
    }

    @Test
    fun selectionBanner_shownWhenPhotosSelected() {
        render(makeState("Frank", photoCount = 2), selected = setOf(1L))
        composeTestRule.onNodeWithText("1 selected").assertIsDisplayed()
        composeTestRule.onNodeWithText("Move to person…").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
        // Action-row button also reflects the selection count.
        composeTestRule.onNodeWithText("Export 1").assertIsDisplayed()
    }

    @Test
    fun selectionBanner_moveToHidden_withMultipleSelected() {
        render(makeState("Heidi", photoCount = 3), selected = setOf(1L, 2L))
        composeTestRule.onNodeWithText("2 selected").assertIsDisplayed()
        composeTestRule.onNodeWithText("Move to person…", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun mergeButton_click_showsMergeDialog() {
        val candidate = ClusterSummary(
            id = 99L, displayName = "Grace", faceCount = 5, coverPhotoUri = null
        )
        render(makeState("Frank"), candidates = listOf(candidate))
        composeTestRule.onNodeWithText("Merge").performClick()
        composeTestRule.onNodeWithText("Grace").assertIsDisplayed()
    }
}
