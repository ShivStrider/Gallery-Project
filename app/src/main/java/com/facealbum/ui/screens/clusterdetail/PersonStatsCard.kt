package com.facealbum.ui.screens.clusterdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.facealbum.R
import com.facealbum.ui.theme.Spacing
import com.facealbum.util.formatBytes
import com.facealbum.util.formatFriendlyDate

@Composable
internal fun PersonStatsCard(
    photoCount: Int,
    firstAppearance: Long?,
    latestAppearance: Long?,
    /** Null while the MediaStore size read is still in flight. */
    totalSizeBytes: Long?
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
                StatTile(
                    value = totalSizeBytes?.let { formatBytes(it) }
                        ?: stringResource(R.string.person_detail_stats_calculating),
                    label = stringResource(R.string.person_detail_stats_size_label)
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
