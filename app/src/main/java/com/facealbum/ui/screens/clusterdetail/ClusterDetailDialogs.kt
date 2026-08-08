package com.facealbum.ui.screens.clusterdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.facealbum.R
import com.facealbum.data.db.ClusterSummary

@Composable
internal fun RenameDialog(
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
internal fun MergePicker(
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
                // Lazy and height-capped rather than a plain Column: the
                // candidate list is every other cluster, including the
                // below-threshold ones, so on a real library it can run to
                // hundreds of rows and an unbounded Column would push the
                // dialog's buttons off screen.
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(candidates, key = { it.id }) { c ->
                        ListItem(
                            // Show a face, not just a name. Every unnamed
                            // group reads "Unnamed", so two of them with the
                            // same photo count are otherwise identical rows
                            // with nothing to choose between them.
                            leadingContent = {
                                AsyncImage(
                                    model = c.coverPhotoUri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                )
                            },
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
internal fun ReassignPicker(
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
                // Lazy and height-capped rather than a plain Column: the
                // candidate list is every other cluster, including the
                // below-threshold ones, so on a real library it can run to
                // hundreds of rows and an unbounded Column would push the
                // dialog's buttons off screen.
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(candidates, key = { it.id }) { c ->
                        ListItem(
                            // Show a face, not just a name. Every unnamed
                            // group reads "Unnamed", so two of them with the
                            // same photo count are otherwise identical rows
                            // with nothing to choose between them.
                            leadingContent = {
                                AsyncImage(
                                    model = c.coverPhotoUri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                )
                            },
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
