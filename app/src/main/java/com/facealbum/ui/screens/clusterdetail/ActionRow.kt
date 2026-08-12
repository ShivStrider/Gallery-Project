package com.facealbum.ui.screens.clusterdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MergeType
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.facealbum.R
import com.facealbum.ui.theme.Spacing

@Composable
internal fun ActionRow(
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
