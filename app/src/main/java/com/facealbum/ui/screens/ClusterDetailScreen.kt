@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.facealbum.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.MergeType
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

@Composable
fun ClusterDetailScreen(
    state: MainViewModel.ClusterDetailState,
    mergeCandidates: List<ClusterSummary>,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onExport: (albumName: String) -> Unit,
    onMerge: (intoClusterId: Long) -> Unit
) {
    var renameDialogOpen by remember { mutableStateOf(false) }
    var exportDialogOpen by remember { mutableStateOf(false) }
    var mergeDialogOpen by remember { mutableStateOf(false) }

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
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(bottom = Spacing.xl),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())
        ) {
            // Hero header spans the full grid width.
            item(span = { GridItemSpan(maxLineSpan) }) {
                ClusterHero(
                    coverPhoto = cover,
                    displayName = displayName,
                    photoCount = state.photos.size,
                    onRename = { renameDialogOpen = true },
                    onExport = { exportDialogOpen = true },
                    onMerge = { mergeDialogOpen = true }
                )
            }

            items(state.photos, key = { it.id }) { photo ->
                PhotoCell(photo)
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
}

@Composable
private fun ClusterHero(
    coverPhoto: PhotoEntity?,
    displayName: String,
    photoCount: Int,
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
                Text(stringResource(R.string.cluster_detail_export))
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

@Composable
private fun PhotoCell(photo: PhotoEntity) {
    AsyncImage(
        model = photo.uri,
        contentDescription = photo.displayName,
        contentScale = ContentScale.Crop,
        modifier = Modifier.aspectRatio(1f)
    )
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
