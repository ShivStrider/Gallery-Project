package com.facealbum.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.facealbum.R
import com.facealbum.domain.ExportReport
import com.facealbum.ui.theme.Spacing

/**
 * @param report per-state tallies read back from the export transaction log.
 *   When present the screen reports exactly what happened — including
 *   originals that were kept and files that failed — instead of only a
 *   success count. Null keeps the plain copy-mode summary.
 */
@Composable
fun ExportCompleteScreen(
    exportedCount: Int,
    albumName: String,
    onStartOver: () -> Unit,
    report: ExportReport? = null,
    onUndo: (() -> Unit)? = null
) {
    val safeAlbumName = albumName.ifBlank { stringResource(R.string.album_name_default) }
    val context = LocalContext.current

    // Spring-in for the check mark on first composition.
    var checkPopped by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { checkPopped = true }
    val checkScale by animateFloatAsState(
        targetValue = if (checkPopped) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 260f),
        label = "checkScale"
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(Modifier.height(Spacing.xl))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .graphicsLayer {
                            scaleX = checkScale
                            scaleY = checkScale
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = stringResource(R.string.export_success_icon),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(60.dp)
                    )
                }

                Spacer(Modifier.height(Spacing.lg))

                Text(
                    text = stringResource(R.string.export_complete_title),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(Modifier.height(Spacing.md))

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = Spacing.md)
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.lg),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = exportedCount.toString(),
                            style = MaterialTheme.typography.displayMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.export_complete_count_label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(Spacing.md))

                Text(
                    text = stringResource(R.string.export_complete_location, safeAlbumName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Spacing.md)
                )

                if (report != null) {
                    Spacer(Modifier.height(Spacing.md))
                    ExportOutcomeDetails(report)
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Button(
                    onClick = onStartOver,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Text(
                        stringResource(R.string.start_over),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(
                                Uri.parse("content://media/external/images/media"),
                                "image/*"
                            )
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        runCatching { context.startActivity(intent) }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(26.dp)
                ) {
                    Icon(
                        Icons.Outlined.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.export_complete_open_gallery))
                }

                if (report?.canUndo == true && onUndo != null) {
                    TextButton(
                        onClick = onUndo,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (report.sourcesDeletedCount > 0) {
                                stringResource(R.string.export_undo_move)
                            } else {
                                stringResource(R.string.export_undo_copy)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Per-state breakdown. Kept photos and failures are shown as prominently as
 * successes — a move that left originals behind is not a completed move, and
 * the user needs to know that without opening a file manager to check.
 */
@Composable
private fun ExportOutcomeDetails(report: ExportReport) {
    Column(
        modifier = Modifier.padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (report.sourcesDeletedCount > 0) {
            OutcomeLine(
                text = pluralStringResource(
                    R.plurals.export_outcome_originals_deleted,
                    report.sourcesDeletedCount,
                    report.sourcesDeletedCount
                ),
                emphasis = false
            )
        }
        if (report.sourcesKeptCount > 0) {
            OutcomeLine(
                text = pluralStringResource(
                    R.plurals.export_outcome_originals_kept,
                    report.sourcesKeptCount,
                    report.sourcesKeptCount
                ),
                emphasis = true
            )
        }
        if (report.failedCount > 0) {
            OutcomeLine(
                text = pluralStringResource(
                    R.plurals.export_outcome_failed,
                    report.failedCount,
                    report.failedCount
                ),
                emphasis = true
            )
        }
        if (report.restoredCount > 0) {
            OutcomeLine(
                text = pluralStringResource(
                    R.plurals.export_outcome_restored,
                    report.restoredCount,
                    report.restoredCount
                ),
                emphasis = false
            )
        }
    }
}

@Composable
private fun OutcomeLine(text: String, emphasis: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (emphasis) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        textAlign = TextAlign.Center
    )
}
