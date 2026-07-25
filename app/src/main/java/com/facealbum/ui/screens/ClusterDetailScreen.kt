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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MergeType
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.facealbum.util.formatFriendlyDate

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
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onExport: (albumName: String) -> Unit,
    onMerge: (intoClusterId: Long) -> Unit,
    onToggleFavorite: () -> Unit,
    onPhotoTap: (photoIndex: Int) -> Unit,
    onTogglePhotoSelection: (photoId: Long) -> Unit,
    onClearSelection: () -> Unit,
    onExportSelected: (albumName: String) -> Unit,
    onReassignPhoto: (photoId: Long, toClusterId: Long) -> Unit
) {
    var renameDialogOpen by remember { mutableStateOf(false) }
    var exportDialogOpen by remember { mutableStateOf(false) }
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
                        onExport = {
                            val defaultName = state.displayName.orEmpty()
                            onExportSelected(defaultName)
                        },
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
                        onExport = { exportDialogOpen = true },
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

    if (exportDialogOpen) {
        val defaultName = state.displayName.orEmpty().ifBlank {
            stringResource(R.string.person_default_name_format, state.clusterId)
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

@Composable
private fun SelectionBanner(
    count: Int,
    showMove: Boolean,
    onMove: () -> Unit,
    onExport: () -> Unit,
    onCancel: () -> Unit
) {
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
                text = stringResource(R.string.person_detail_select_mode, count),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f)
            )
            if (showMove) {
                TextButton(onClick = onMove) {
                    Text(
                        stringResource(R.string.person_detail_move_to),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            TextButton(onClick = onExport) {
                Text(
                    stringResource(R.string.person_detail_export_selected, count),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            TextButton(onClick = onCancel) {
                Text(
                    stringResource(R.string.person_detail_cancel_select),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun PersonHero(
    coverPhoto: PhotoEntity?,
    displayName: String,
    gridScrollOffsetPx: Int,
    onRename: () -> Unit
) {
    // Subtle parallax on the hero image as the grid scrolls under it.
    val parallax = (gridScrollOffsetPx * 0.35f).coerceIn(-200f, 200f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
    ) {
        if (coverPhoto != null) {
            AsyncImage(
                model = coverPhoto.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = parallax }
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.35f),
                        0.4f to Color.Transparent,
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
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = Color.White
                )
                Spacer(Modifier.width(Spacing.sm))
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.20f),
                    modifier = Modifier.clip(CircleShape)
                ) {
                    IconButton(
                        onClick = onRename,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = stringResource(R.string.person_detail_rename),
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonStatsCard(
    photoCount: Int,
    firstAppearance: Long?,
    latestAppearance: Long?
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Text(
                text = stringResource(R.string.person_detail_stats_title),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(Spacing.md))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                StatTile(
                    value = photoCount.toString(),
                    label = stringResource(R.string.export_complete_count_label)
                )
                if (firstAppearance != null) {
                    StatTile(
                        value = formatFriendlyDate(firstAppearance),
                        label = "First seen"
                    )
                }
                if (latestAppearance != null && latestAppearance != firstAppearance) {
                    StatTile(
                        value = formatFriendlyDate(latestAppearance),
                        label = "Latest"
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.StatTile(value: String, label: String) {
    Column(
        modifier = Modifier.weight(1f)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ActionRow(
    selectedCount: Int,
    onExport: () -> Unit,
    onMerge: () -> Unit,
    onRename: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        ActionButton(
            icon = Icons.Outlined.FileDownload,
            label = if (selectedCount > 0)
                stringResource(R.string.person_detail_export_selected, selectedCount)
            else stringResource(R.string.person_detail_export),
            onClick = onExport,
            modifier = Modifier.weight(1f)
        )
        ActionButton(
            icon = Icons.AutoMirrored.Outlined.MergeType,
            label = stringResource(R.string.person_detail_merge),
            onClick = onMerge,
            modifier = Modifier.weight(1f),
            filled = false
        )
        ActionButton(
            icon = Icons.Outlined.Edit,
            label = stringResource(R.string.person_detail_rename),
            onClick = onRename,
            modifier = Modifier.weight(1f),
            filled = false
        )
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true
) {
    if (filled) {
        FilledTonalButton(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(18.dp),
            contentPadding = PaddingValues(vertical = Spacing.sm, horizontal = Spacing.md)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text(label, maxLines = 1, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(18.dp),
            contentPadding = PaddingValues(vertical = Spacing.sm, horizontal = Spacing.md)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text(label, maxLines = 1, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = Spacing.md, end = Spacing.md,
            top = Spacing.lg, bottom = Spacing.sm
        )
    )
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
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "photoScale"
    )
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        AsyncImage(
            model = photo.uri,
            contentDescription = stringResource(R.string.person_detail_open_photo),
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
                contentDescription = stringResource(
                    if (isSelected) R.string.person_detail_photo_selected
                    else R.string.person_detail_photo_unselected
                ),
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
        title = { Text(stringResource(R.string.person_detail_rename)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.person_detail_rename_hint)) }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(stringResource(R.string.person_detail_save))
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
        title = { Text(stringResource(R.string.person_detail_export)) },
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
        title = { Text(stringResource(R.string.person_detail_merge)) },
        text = {
            if (candidates.isEmpty()) {
                Text(stringResource(R.string.person_detail_merge_empty))
            } else {
                Column {
                    candidates.forEach { c ->
                        ListItem(
                            headlineContent = {
                                Text(c.displayName ?: stringResource(R.string.people_unnamed))
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
        title = { Text(stringResource(R.string.person_detail_move_face_title)) },
        text = {
            if (candidates.isEmpty()) {
                Text(stringResource(R.string.person_detail_merge_empty))
            } else {
                Column {
                    candidates.forEach { c ->
                        ListItem(
                            headlineContent = {
                                Text(c.displayName ?: stringResource(R.string.people_unnamed))
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

