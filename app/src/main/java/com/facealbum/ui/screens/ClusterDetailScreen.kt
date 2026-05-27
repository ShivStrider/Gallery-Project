@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.facealbum.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.MergeType
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.facealbum.MainViewModel
import com.facealbum.R
import com.facealbum.data.db.ClusterSummary
import com.facealbum.data.db.PhotoEntity
import com.facealbum.ui.theme.Spacing

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClusterDetailScreen(
    state: MainViewModel.ClusterDetailState,
    mergeCandidates: List<ClusterSummary>,
    selectedPhotoIds: Set<Long>,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onExport: (albumName: String) -> Unit,
    onMerge: (intoClusterId: Long) -> Unit,
    onTogglePhotoSelection: (photoId: Long) -> Unit,
    onClearSelection: () -> Unit,
    onExportSelected: (albumName: String) -> Unit,
    onReassignPhoto: (photoId: Long, toClusterId: Long) -> Unit
) {
    var renameDialogOpen by remember { mutableStateOf(false) }
    var exportDialogOpen by remember { mutableStateOf(false) }
    var mergeDialogOpen by remember { mutableStateOf(false) }
    var reassignPhotoId by remember { mutableStateOf<Long?>(null) }

    val isSelecting = selectedPhotoIds.isNotEmpty()
    val displayName = state.displayName?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.people_unnamed)
    val cover = state.photos.firstOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.cluster_detail_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            // Selection mode banner — shown as soon as one photo is long-pressed.
            if (isSelecting) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.cluster_detail_select_mode, selectedPhotoIds.size),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        // "Move to" only available when a single photo is selected (reassign
                        // acts on one photo's detected faces at a time).
                        if (selectedPhotoIds.size == 1) {
                            TextButton(onClick = {
                                reassignPhotoId = selectedPhotoIds.first()
                            }) {
                                Text(
                                    stringResource(R.string.cluster_detail_move_to),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        TextButton(onClick = {
                            val defaultName = state.displayName.orEmpty()
                            onExportSelected(defaultName)
                        }) {
                            Text(stringResource(R.string.export), color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        TextButton(onClick = onClearSelection) {
                            Text(stringResource(R.string.cluster_detail_cancel_select), color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(bottom = Spacing.xl),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                // Hero header spans the full grid width.
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ClusterHero(
                        coverPhoto = cover,
                        displayName = displayName,
                        photoCount = state.photos.size,
                        selectedCount = selectedPhotoIds.size,
                        onRename = { renameDialogOpen = true },
                        onExport = { exportDialogOpen = true },
                        onMerge = { mergeDialogOpen = true }
                    )
                }

                items(state.photos, key = { it.id }) { photo ->
                    PhotoCell(
                        photo = photo,
                        isSelected = photo.id in selectedPhotoIds,
                        isSelecting = isSelecting,
                        onClick = {
                            if (isSelecting) {
                                // In selection mode a tap toggles the photo's selected state.
                                onTogglePhotoSelection(photo.id)
                            }
                            // Outside selection mode, a plain tap does nothing here (a future
                            // full-screen viewer could be opened instead).
                        },
                        onLongClick = {
                            // Long-press always enters/continues selection mode regardless of
                            // current state. This is the only entry point into selection mode,
                            // which makes it discoverable and consistent with Android norms.
                            onTogglePhotoSelection(photo.id)
                        }
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

    if (exportDialogOpen) {
        val defaultName = state.displayName.orEmpty().ifBlank {
            stringResource(R.string.cluster_default_name_format, state.clusterId)
        }
        AlbumNameDialog(
            initial = defaultName,
            onDismiss = { exportDialogOpen = false },
            onConfirm = { name ->
                onExport(name)
                exportDialogOpen = false
            }
        )
    }

    if (mergeDialogOpen) {
        MergePicker(
            candidates = mergeCandidates,
            onDismiss = { mergeDialogOpen = false },
            onPick = { intoId ->
                onMerge(intoId)
                mergeDialogOpen = false
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

@Composable
private fun ClusterHero(
    coverPhoto: PhotoEntity?,
    displayName: String,
    photoCount: Int,
    selectedCount: Int,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onMerge: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.md)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
        ) {
            if (coverPhoto != null) {
                AsyncImage(
                    model = coverPhoto.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }
            // Gradient so name text reads cleanly against any photo.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.15f),
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.75f)
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(Spacing.lg)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = Color.White
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.18f),
                        modifier = Modifier.clip(CircleShape)
                    ) {
                        IconButton(
                            onClick = onRename,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = stringResource(R.string.cluster_detail_rename),
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.people_face_count_format, photoCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            FilledTonalButton(
                onClick = onExport,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(20.dp)
            ) {
                Icon(
                    Icons.Outlined.FileDownload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    if (selectedCount > 0)
                        stringResource(R.string.cluster_detail_export_selected, selectedCount)
                    else
                        stringResource(R.string.cluster_detail_export)
                )
            }
            OutlinedButton(
                onClick = onMerge,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(20.dp)
            ) {
                Icon(
                    Icons.Outlined.MergeType,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(stringResource(R.string.cluster_detail_merge))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoCell(
    photo: PhotoEntity,
    isSelected: Boolean,
    isSelecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        AsyncImage(
            model = photo.uri,
            contentDescription = photo.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (isSelecting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (isSelected) Color.Black.copy(alpha = 0.35f)
                        else Color.Transparent
                    )
            )
            Icon(
                imageVector = if (isSelected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
            )
        }
    }
}

@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cluster_detail_rename)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.cluster_detail_rename_hint)) }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(stringResource(R.string.cluster_detail_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun AlbumNameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cluster_detail_export)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.album_name_hint)) }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.export)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun MergePicker(
    candidates: List<ClusterSummary>,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cluster_detail_merge)) },
        text = {
            if (candidates.isEmpty()) {
                Text(stringResource(R.string.cluster_detail_merge_empty))
            } else {
                Column {
                    candidates.forEach { c ->
                        ListItem(
                            headlineContent = {
                                Text(c.displayName
                                    ?: stringResource(R.string.people_unnamed))
                            },
                            supportingContent = {
                                Text(stringResource(R.string.people_face_count_format, c.faceCount))
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPick(c.id) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun ReassignPicker(
    candidates: List<ClusterSummary>,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cluster_detail_move_face_title)) },
        text = {
            if (candidates.isEmpty()) {
                Text(stringResource(R.string.cluster_detail_merge_empty))
            } else {
                Column {
                    candidates.forEach { c ->
                        ListItem(
                            headlineContent = {
                                Text(c.displayName
                                    ?: stringResource(R.string.people_unnamed))
                            },
                            supportingContent = {
                                Text(stringResource(R.string.people_face_count_format, c.faceCount))
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPick(c.id) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
