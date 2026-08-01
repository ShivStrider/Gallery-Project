package com.facealbum.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facealbum.domain.ExportPlanner
import com.facealbum.ui.screens.ExportPreviewSheet
import com.facealbum.ui.theme.FaceAlbumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The preview is the last thing a user sees before files are touched, so it
 * has to state the real numbers and never offer a mode the build can't
 * safely perform.
 */
@RunWith(AndroidJUnit4::class)
class ExportPreviewSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun item(
        id: Long,
        size: Long = 1_000_000L,
        others: Boolean = false,
        folder: String? = "DCIM/Camera/"
    ) = ExportPlanner.PlannedItem(
        photoRowId = id,
        sourceMediaStoreId = id,
        sourceUri = "content://media/external/images/media/$id",
        sourceDisplayName = "IMG_$id.jpg",
        sourceRelativePath = folder,
        sizeBytes = size,
        destDisplayName = "IMG_$id.jpg",
        containsOtherPeople = others
    )

    private fun plan(
        items: List<ExportPlanner.PlannedItem>,
        rejected: Int = 0
    ) = ExportPlanner.Plan(
        clusterId = 1L,
        albumName = "Ada",
        destRelativePath = "Pictures/FaceAlbums/Ada/",
        mode = ExportPlanner.Mode.COPY,
        items = items,
        rejectedCount = rejected
    )

    @Test
    fun showsExactFileCountDestinationAndSize() {
        composeTestRule.setContent {
            FaceAlbumTheme {
                ExportPreviewSheet(
                    plan = plan(listOf(item(1), item(2), item(3))),
                    moveAvailable = false,
                    initialAlbumName = "Ada",
                    onDismiss = {},
                    onConfirm = { _, _ -> }
                )
            }
        }
        composeTestRule.onNodeWithText("3 photos").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pictures/FaceAlbums/Ada/").assertIsDisplayed()
        composeTestRule.onNodeWithText("3.0 MB").assertIsDisplayed()
        composeTestRule.onNodeWithText("DCIM/Camera/").assertIsDisplayed()
    }

    @Test
    fun warnsWhenPhotosContainOtherPeople() {
        composeTestRule.setContent {
            FaceAlbumTheme {
                ExportPreviewSheet(
                    plan = plan(listOf(item(1, others = true), item(2))),
                    moveAvailable = false,
                    initialAlbumName = "Ada",
                    onDismiss = {},
                    onConfirm = { _, _ -> }
                )
            }
        }
        composeTestRule
            .onNodeWithText("1 of these photos also shows someone else.")
            .assertIsDisplayed()
    }

    @Test
    fun reportsSkippedPhotos() {
        composeTestRule.setContent {
            FaceAlbumTheme {
                ExportPreviewSheet(
                    plan = plan(listOf(item(1)), rejected = 2),
                    moveAvailable = false,
                    initialAlbumName = "Ada",
                    onDismiss = {},
                    onConfirm = { _, _ -> }
                )
            }
        }
        composeTestRule
            .onNodeWithText("2 photos can't be exported and will be skipped.")
            .assertIsDisplayed()
    }

    /** With move gated off, the destructive option must not be reachable. */
    @Test
    fun moveOptionIsHiddenWhenUnavailable() {
        composeTestRule.setContent {
            FaceAlbumTheme {
                ExportPreviewSheet(
                    plan = plan(listOf(item(1))),
                    moveAvailable = false,
                    initialAlbumName = "Ada",
                    onDismiss = {},
                    onConfirm = { _, _ -> }
                )
            }
        }
        composeTestRule.onNodeWithText("Move").assertDoesNotExist()
        composeTestRule
            .onNodeWithText("Your photos stay where they are. Copies are added to the album.")
            .assertIsDisplayed()
    }

    @Test
    fun confirmDefaultsToCopyMode() {
        var confirmedMode: ExportPlanner.Mode? = null
        composeTestRule.setContent {
            FaceAlbumTheme {
                ExportPreviewSheet(
                    plan = plan(listOf(item(1))),
                    moveAvailable = true,
                    initialAlbumName = "Ada",
                    onDismiss = {},
                    onConfirm = { _, mode -> confirmedMode = mode }
                )
            }
        }
        composeTestRule.onNodeWithText("Export").performClick()
        assertEquals(ExportPlanner.Mode.COPY, confirmedMode)
    }

    /** Choosing Move must explain that originals go only after verification. */
    @Test
    fun selectingMoveExplainsVerificationBeforeDeletion() {
        composeTestRule.setContent {
            FaceAlbumTheme {
                ExportPreviewSheet(
                    plan = plan(listOf(item(1))),
                    moveAvailable = true,
                    initialAlbumName = "Ada",
                    onDismiss = {},
                    onConfirm = { _, _ -> }
                )
            }
        }
        composeTestRule.onNodeWithText("Move").performClick()
        composeTestRule
            .onNodeWithText(
                "Each photo is copied and checked first. Only then are you asked to " +
                    "delete the originals — nothing is removed without your confirmation."
            )
            .assertIsDisplayed()
    }
}
