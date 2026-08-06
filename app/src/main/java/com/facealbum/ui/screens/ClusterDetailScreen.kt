@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.facealbum.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.facealbum.MainViewModel
import com.facealbum.R
import com.facealbum.data.db.ClusterSummary
import com.facealbum.domain.ExportPlanner
import com.facealbum.ui.screens.clusterdetail.ActionRow
import com.facealbum.ui.screens.clusterdetail.MergePicker
import com.facealbum.ui.screens.clusterdetail.PersonHero
import com.facealbum.ui.screens.clusterdetail.PersonStatsCard
import com.facealbum.ui.screens.clusterdetail.PhotoCell
import com.facealbum.ui.screens.clusterdetail.ReassignPicker
import com.facealbum.ui.screens.clusterdetail.RenameDialog
import com.facealbum.ui.screens.clusterdetail.SectionHeader
import com.facealbum.ui.screens.clusterdetail.SelectionBanner
import com.facealbum.ui.theme.Spacing

/**
 * Person Detail — hero photo, stats card, action row, then a photo gallery.
 *
 * Selection mode:
 *  * long-press a photo → select mode banner appears with move / export / cancel;
 *  * tap outside selection → open the immersive viewer.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClusterDetailScreen(
    state: MainViewModel.ClusterDetailState,
    mergeCandidates: List<ClusterSummary>,
    selectedPhotoIds: Set<Long>,
    pendingExportPlan: ExportPlanner.Plan?,
    moveAvailable: Boolean,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onRequestExportPlan: (photoRowIds: List<Long>?) -> Unit,
    onDismissExportPlan: () -> Unit,
    onConfirmExportPlan: (albumName: String, mode: ExportPlanner.Mode) -> Unit,
    onMerge: (intoClusterId: Long) -> Unit,
    onToggleFavorite: () -> Unit,
    onPhotoTap: (photoIndex: Int) -> Unit,
    onTogglePhotoSelection: (photoId: Long) -> Unit,
    onClearSelection: () -> Unit,
    onReassignPhoto: (photoId: Long, toClusterId: Long) -> Unit
) {
    var renameDialogOpen by remember { mutableStateOf(false) }
    var mergeDialogOpen by remember { mutableStateOf(false) }
    var reassignPhotoId by remember { mutableStateOf<Long?>(null) }
    var confirmMergeTargetId by remember { mutableStateOf<Long?>(null) }

    val isSelecting = selectedPhotoIds.isNotEmpty()
    val displayName = state.displayName?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.people_unnamed)
    val cover = state.photos.firstOrNull()
    val gridState = rememberLazyGridState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.person_detail_back),
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    val favIcon = if (state.isFavorite)
                        Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder
                    val favDesc =
                        if (state.isFavorite) R.string.person_detail_unfavorite
                        else R.string.person_detail_favorite
                    val scale by animateFloatAsState(
                        targetValue = if (state.isFavorite) 1.15f else 1f,
                        animationSpec = spring(dampingRatio = 0.4f, stiffness = 500f),
                        label = "favScale"
                    )
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            imageVector = favIcon,
                            contentDescription = stringResource(favDesc),
                            tint = if (state.isFavorite)
                                MaterialTheme.colorScheme.tertiary
                            else Color.White,
                            modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { scaffoldPadding ->
        // Hero image intentionally sits *behind* the top-app-bar; we consume only
        // the bottom inset so system nav bar padding is respected.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = scaffoldPadding.calculateBottomPadding())
        ) {
            AnimatedContent(
                targetState = isSelecting,
                transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(120)) },
                label = "selectionBanner"
            ) { selecting ->
                if (selecting) {
                    SelectionBanner(
                        count = selectedPhotoIds.size,
                        showMove = selectedPhotoIds.size == 1,
                        onMove = { reassignPhotoId = selectedPhotoIds.first() },
                        onExport = { onRequestExportPlan(selectedPhotoIds.toList()) },
                        onCancel = onClearSelection
                    )
                } else {
                    Spacer(Modifier.height(0.dp))
                }
            }

            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(bottom = Spacing.xl),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    PersonHero(
                        coverPhoto = cover,
                        displayName = displayName,
                        gridScrollOffsetPx = gridState.firstVisibleItemScrollOffset,
                        onRename = { renameDialogOpen = true }
                    )
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    PersonStatsCard(
                        photoCount = state.photos.size,
                        firstAppearance = state.firstAppearance,
                        latestAppearance = state.latestAppearance
                    )
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    ActionRow(
                        selectedCount = selectedPhotoIds.size,
                        onExport = {
                            val ids = if (isSelecting) selectedPhotoIds.toList() else null
                            onRequestExportPlan(ids)
                        },
                        onMerge = { mergeDialogOpen = true },
                        onRename = { renameDialogOpen = true }
                    )
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(text = stringResource(R.string.person_detail_gallery_title))
                }

                itemsIndexed(state.photos) { index, photo ->
                    PhotoCell(
                        photo = photo,
                        isSelected = photo.id in selectedPhotoIds,
                        isSelecting = isSelecting,
                        onClick = {
                            if (isSelecting) onTogglePhotoSelection(photo.id)
                            else onPhotoTap(index)
                        },
                        onLongClick = { onTogglePhotoSelection(photo.id) }
                    )
                }
            }
        }
    }

    if (renameDialogOpen) {
        RenameDialog(
            initial = state.displayName.orEmpty(),
            onDismiss = { renameDialogOpen = false },
            onConfirm = { newName ->
                onRename(newName)
                renameDialogOpen = false
            }
        )
    }

    if (pendingExportPlan != null && pendingExportPlan.clusterId == state.clusterId) {
        ExportPreviewSheet(
            plan = pendingExportPlan,
            moveAvailable = moveAvailable,
            initialAlbumName = pendingExportPlan.albumName,
            onDismiss = onDismissExportPlan,
            onConfirm = onConfirmExportPlan
        )
    }

    if (mergeDialogOpen) {
        MergePicker(
            candidates = mergeCandidates,
            onDismiss = { mergeDialogOpen = false },
            onPick = { intoId ->
                mergeDialogOpen = false
                confirmMergeTargetId = intoId
            }
        )
    }

    confirmMergeTargetId?.let { targetId ->
        val target = mergeCandidates.firstOrNull { it.id == targetId }
        val fromName = state.displayName?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.people_unnamed)
        val toName = target?.displayName?.takeIf { !it.isNullOrBlank() }
            ?: stringResource(R.string.people_unnamed)
        AlertDialog(
            onDismissRequest = { confirmMergeTargetId = null },
            title = { Text(stringResource(R.string.confirm_merge_title)) },
            text = { Text(stringResource(R.string.confirm_merge_body, fromName, toName)) },
            confirmButton = {
                TextButton(onClick = {
                    onMerge(targetId)
                    confirmMergeTargetId = null
                }) { Text(stringResource(R.string.confirm_merge_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmMergeTargetId = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    val reassignId = reassignPhotoId
    if (reassignId != null) {
        ReassignPicker(
            candidates = mergeCandidates,
            onDismiss = { reassignPhotoId = null },
            onPick = { toClusterId ->
                onReassignPhoto(reassignId, toClusterId)
                reassignPhotoId = null
            }
        )
    }
}
