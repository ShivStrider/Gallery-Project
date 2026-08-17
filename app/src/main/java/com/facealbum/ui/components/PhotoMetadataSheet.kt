@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.facealbum.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.facealbum.R
import com.facealbum.data.PhotoRepository
import com.facealbum.data.db.PhotoEntity
import com.facealbum.ui.theme.Spacing
import com.facealbum.util.formatBytes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Self-contained detail sheet for one photo: file name, capture date,
 * dimensions, file size, folder, MIME type, and how many faces were found in
 * it. Fully independent of any screen — it loads its own data and can be
 * dropped into any caller that has a [PhotoEntity], e.g.:
 *
 * ```
 * PhotoMetadataSheet(photo = photos[pagerState.currentPage], onDismiss = { showMetadata = false })
 * ```
 *
 * [PhotoEntity] already carries [PhotoEntity.displayName], [PhotoEntity.dateTaken]
 * and [PhotoEntity.faceCount] from the last index pass, so those render
 * immediately. Everything else (size, dimensions, folder, MIME type) needs a
 * fresh MediaStore read, done off the main thread in a [LaunchedEffect] keyed
 * on [PhotoEntity.mediaStoreId] — the source may have changed size or moved
 * since it was indexed, or vanished entirely, so this is never cached.
 */
@Composable
fun PhotoMetadataSheet(photo: PhotoEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { PhotoRepository(context) }
    var loadState by remember(photo.mediaStoreId) {
        mutableStateOf<MetadataLoadState>(MetadataLoadState.Loading)
    }

    LaunchedEffect(photo.mediaStoreId) {
        loadState = MetadataLoadState.Loading
        val details = repository.queryPhotoDetails(photo.mediaStoreId)
        loadState = if (details != null) {
            MetadataLoadState.Loaded(details)
        } else {
            MetadataLoadState.Unavailable
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = stringResource(R.string.photo_metadata_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(Modifier.height(Spacing.md))

            when (val state = loadState) {
                is MetadataLoadState.Loading -> LoadingRow()
                is MetadataLoadState.Unavailable -> {
                    UnavailableNotice()
                    Spacer(Modifier.height(Spacing.md))
                    MetadataRow(
                        label = stringResource(R.string.photo_metadata_file_name),
                        value = photo.displayName
                    )
                    MetadataRow(
                        label = stringResource(R.string.photo_metadata_date_taken),
                        value = formatExactDateTime(photo.dateTaken)
                    )
                    FacesFoundRow(photo.faceCount)
                }
                is MetadataLoadState.Loaded -> {
                    val details = state.details
                    MetadataRow(
                        label = stringResource(R.string.photo_metadata_file_name),
                        value = details.displayName.ifBlank { photo.displayName }
                    )
                    MetadataRow(
                        label = stringResource(R.string.photo_metadata_date_taken),
                        value = formatExactDateTime(details.dateTakenMs.takeIf { it > 0L } ?: photo.dateTaken)
                    )
                    if (details.width > 0 && details.height > 0) {
                        MetadataRow(
                            label = stringResource(R.string.photo_metadata_dimensions),
                            value = stringResource(
                                R.string.photo_metadata_dimensions_format,
                                details.width,
                                details.height
                            )
                        )
                    }
                    MetadataRow(
                        label = stringResource(R.string.photo_metadata_file_size),
                        value = formatBytes(details.sizeBytes)
                    )
                    MetadataRow(
                        label = stringResource(R.string.photo_metadata_folder),
                        value = details.relativePath
                            ?: stringResource(R.string.photo_metadata_unknown_value)
                    )
                    MetadataRow(
                        label = stringResource(R.string.photo_metadata_type),
                        value = details.mimeType
                            ?: stringResource(R.string.photo_metadata_unknown_value)
                    )
                    FacesFoundRow(photo.faceCount)
                }
            }
        }
    }
}

private sealed interface MetadataLoadState {
    object Loading : MetadataLoadState
    data class Loaded(val details: PhotoRepository.PhotoDetails) : MetadataLoadState
    object Unavailable : MetadataLoadState
}

@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Text(
            text = stringResource(R.string.photo_metadata_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun UnavailableNotice() {
    Text(
        text = stringResource(R.string.photo_metadata_unavailable),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error
    )
}

/**
 * "Faces found" doesn't fit the label/value row shape the other fields use —
 * it reads more naturally as a single sentence ("3 faces found") than a
 * "Faces found: 3" pair, so it renders as one line rather than through
 * [MetadataRow].
 */
@Composable
private fun FacesFoundRow(faceCount: Int) {
    Text(
        text = pluralStringResource(R.plurals.photo_metadata_faces_found, faceCount, faceCount),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = Spacing.xs)
    )
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.6f)
        )
    }
}

/**
 * Renders an epoch-millis capture date with both date and time, since the
 * short "Today"/"Yesterday" labels used elsewhere in the app are too coarse
 * for a details sheet. `epochMillis <= 0` means MediaStore never recorded a
 * capture date for this photo.
 */
@Composable
private fun formatExactDateTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return stringResource(R.string.photo_metadata_unknown_value)
    val pattern = remember { SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault()) }
    return pattern.format(Date(epochMillis))
}
