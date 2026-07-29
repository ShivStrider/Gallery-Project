package com.facealbum.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.facealbum.R
import com.facealbum.domain.ExportPlanner
import com.facealbum.ui.theme.Spacing

/**
 * Confirmation step shown before any file is touched.
 *
 * The point of this screen is that the user sees the truth rather than a
 * promise: the exact number of files, where they come from, where they are
 * going, how much space is needed, and — for a move — that originals will be
 * deleted only after each copy is verified and they approve the system
 * prompt. Everything shown comes from a [ExportPlanner.Plan] that was
 * computed before the operation was committed.
 */
@Composable
fun ExportPreviewSheet(
    plan: ExportPlanner.Plan,
    moveAvailable: Boolean,
    initialAlbumName: String,
    onDismiss: () -> Unit,
    onConfirm: (albumName: String, mode: ExportPlanner.Mode) -> Unit
) {
    var albumName by remember { mutableStateOf(initialAlbumName) }
    var mode by remember { mutableStateOf(ExportPlanner.Mode.COPY) }
    val withOthers = plan.items.count { it.containsOtherPeople }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_preview_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                OutlinedTextField(
                    value = albumName,
                    onValueChange = { albumName = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.album_name_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )

                SummaryRow(
                    label = stringResource(R.string.export_preview_files_label),
                    value = pluralStringResource(
                        R.plurals.export_preview_file_count,
                        plan.fileCount,
                        plan.fileCount
                    )
                )
                SummaryRow(
                    label = stringResource(R.string.export_preview_size_label),
                    value = formatBytes(plan.totalBytes)
                )
                SummaryRow(
                    label = stringResource(R.string.export_preview_destination_label),
                    value = plan.destRelativePath
                )
                if (plan.sourceFolders.isNotEmpty()) {
                    SummaryRow(
                        label = stringResource(R.string.export_preview_sources_label),
                        value = plan.sourceFolders.joinToString(", ")
                    )
                }

                if (withOthers > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.export_preview_other_people,
                            withOthers,
                            withOthers
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (plan.rejectedCount > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.export_preview_skipped,
                            plan.rejectedCount,
                            plan.rejectedCount
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (moveAvailable) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        FilterChip(
                            selected = mode == ExportPlanner.Mode.COPY,
                            onClick = { mode = ExportPlanner.Mode.COPY },
                            label = { Text(stringResource(R.string.export_mode_copy)) }
                        )
                        FilterChip(
                            selected = mode == ExportPlanner.Mode.MOVE,
                            onClick = { mode = ExportPlanner.Mode.MOVE },
                            label = { Text(stringResource(R.string.export_mode_move)) }
                        )
                    }
                }

                Text(
                    text = if (mode == ExportPlanner.Mode.MOVE) {
                        stringResource(R.string.export_preview_move_explainer)
                    } else {
                        stringResource(R.string.export_preview_copy_explainer)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(albumName, mode) },
                enabled = plan.fileCount > 0
            ) {
                Text(stringResource(R.string.export))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Deliberately decimal (1 kB = 1000 B) to match how Android surfaces storage
 * elsewhere, so the estimate reads consistently with the system's own numbers.
 */
internal fun formatBytes(bytes: Long): String = when {
    bytes < 1_000L -> "$bytes B"
    bytes < 1_000_000L -> "%.0f kB".format(bytes / 1_000.0)
    bytes < 1_000_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
    else -> "%.2f GB".format(bytes / 1_000_000_000.0)
}
