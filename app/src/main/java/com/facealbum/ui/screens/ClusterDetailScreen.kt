package com.facealbum.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.facealbum.MainViewModel
import com.facealbum.R
import com.facealbum.data.db.ClusterSummary
import com.facealbum.data.db.PhotoEntity

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                },
                title = {
                    Text(state.displayName ?: stringResource(R.string.people_unnamed))
                },
                actions = {
                    IconButton(onClick = { renameDialogOpen = true }) {
                        Icon(Icons.Default.Edit, contentDescription = null)
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar(
                actions = {
                    TextButton(onClick = { mergeDialogOpen = true }) {
                        Text(stringResource(R.string.cluster_detail_merge))
                    }
                },
                floatingActionButton = {
                    ExtendedFloatingActionButton(onClick = { exportDialogOpen = true }) {
                        Text(stringResource(R.string.cluster_detail_export))
                    }
                }
            )
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 100.dp),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
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
        AlbumNameDialog(
            initial = state.displayName.orEmpty().ifBlank { "Person_${state.clusterId}" },
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
private fun PhotoCell(photo: PhotoEntity) {
    AsyncImage(
        model = photo.uri,
        contentDescription = photo.displayName,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
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
                Text("Nothing to merge with yet.")
            } else {
                Column {
                    candidates.forEach { c ->
                        TextButton(
                            onClick = { onPick(c.id) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = (c.displayName
                                    ?: stringResource(R.string.people_unnamed)) +
                                    " (${c.faceCount})",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
