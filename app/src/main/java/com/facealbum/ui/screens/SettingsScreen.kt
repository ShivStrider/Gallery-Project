@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.facealbum.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.facealbum.MainViewModel
import com.facealbum.R
import com.facealbum.data.prefs.UserPreferences
import com.facealbum.ui.theme.Spacing

@Composable
fun SettingsScreen(
    minClusterSize: Int,
    onMinClusterSizeChange: (Int) -> Unit,
    assignThreshold: Float,
    pendingAssignThreshold: Float?,
    reclusterProgress: MainViewModel.ReclusterProgress,
    onPreviewThreshold: (Float) -> Unit,
    onCommitThreshold: () -> Unit,
    onRecluster: () -> Unit,
    onRescanAll: () -> Unit,
    onDeleteIndex: () -> Unit,
    onBack: () -> Unit,
    themePreference: com.facealbum.data.prefs.ThemePreference,
    onThemeChange: (com.facealbum.data.prefs.ThemePreference) -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.cluster_detail_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = Spacing.sm)
        ) {
            SectionHeader(stringResource(R.string.settings_section_clustering))
            MinClusterSizeRow(
                value = minClusterSize,
                onValueChange = onMinClusterSizeChange
            )
            ThresholdRow(
                committed = assignThreshold,
                pending = pendingAssignThreshold,
                onPreview = onPreviewThreshold,
                onCommit = onCommitThreshold
            )
            ReclusterRow(
                progress = reclusterProgress,
                onClick = onRecluster
            )

            Spacer(Modifier.height(Spacing.md))

            SectionHeader(stringResource(R.string.settings_section_appearance))
            ThemeRow(
                current = themePreference,
                onSelect = onThemeChange
            )

            Spacer(Modifier.height(Spacing.md))

            SectionHeader(stringResource(R.string.settings_section_data))
            SettingRow(
                icon = Icons.Outlined.Refresh,
                title = stringResource(R.string.settings_rescan_all),
                subtitle = stringResource(R.string.settings_rescan_all_body),
                onClick = onRescanAll
            )
            SettingRow(
                icon = Icons.Outlined.DeleteOutline,
                title = stringResource(R.string.settings_delete_index),
                subtitle = stringResource(R.string.settings_delete_index_body),
                onClick = { confirmDelete = true },
                tintError = true
            )

            Spacer(Modifier.height(Spacing.md))

            SectionHeader(stringResource(R.string.settings_about))
            SettingRow(
                icon = Icons.Outlined.Info,
                title = stringResource(R.string.app_name),
                subtitle = stringResource(R.string.settings_about_body),
                onClick = null
            )
            AboutFooter()

            Spacer(Modifier.height(Spacing.xl))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.settings_delete_index)) },
            text = { Text(stringResource(R.string.settings_delete_index_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteIndex()
                    confirmDelete = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = Spacing.md,
            end = Spacing.md,
            top = Spacing.md,
            bottom = Spacing.xs
        )
    )
}

@Composable
private fun MinClusterSizeRow(value: Int, onValueChange: (Int) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_min_cluster_size),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        stringResource(R.string.settings_min_cluster_size_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = value.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
                    )
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            Slider(
                value = value.toFloat(),
                onValueChange = { onValueChange(it.toInt()) },
                valueRange = 1f..10f,
                steps = 8
            )
        }
    }
}

@Composable
private fun ThresholdRow(
    committed: Float,
    pending: Float?,
    onPreview: (Float) -> Unit,
    onCommit: () -> Unit
) {
    val current = pending ?: committed
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_threshold),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        stringResource(R.string.settings_threshold_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = stringResource(R.string.settings_threshold_value, current),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
                    )
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            Slider(
                value = current,
                onValueChange = onPreview,
                onValueChangeFinished = onCommit,
                valueRange = UserPreferences.MIN_ASSIGN..UserPreferences.MAX_ASSIGN
            )
        }
    }
}

@Composable
private fun ReclusterRow(
    progress: MainViewModel.ReclusterProgress,
    onClick: () -> Unit
) {
    val title = stringResource(R.string.settings_recluster)
    val subtitle = if (progress.running && progress.total > 0) {
        stringResource(R.string.settings_recluster_running, progress.done, progress.total)
    } else {
        stringResource(R.string.settings_recluster_body)
    }
    SettingRow(
        icon = Icons.Outlined.Refresh,
        title = title,
        subtitle = subtitle,
        onClick = if (progress.running) null else onClick
    )
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)?,
    tintError: Boolean = false
) {
    val titleColor =
        if (tintError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val iconColor =
        if (tintError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant

    val baseModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = Spacing.md, vertical = Spacing.xs)
    val rowModifier = if (onClick != null) baseModifier.clickable(onClick = onClick) else baseModifier

    Surface(
        modifier = rowModifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = titleColor)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AboutFooter() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (versionName.isNotEmpty()) {
            Text(
                text = stringResource(R.string.settings_version_label, versionName),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ThemeRow(
    current: com.facealbum.data.prefs.ThemePreference,
    onSelect: (com.facealbum.data.prefs.ThemePreference) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Text(
                text = stringResource(R.string.settings_theme),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = Spacing.sm)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                com.facealbum.data.prefs.ThemePreference.entries.forEach { pref ->
                    val label = stringResource(
                        when (pref) {
                            com.facealbum.data.prefs.ThemePreference.SYSTEM -> R.string.settings_theme_system
                            com.facealbum.data.prefs.ThemePreference.LIGHT -> R.string.settings_theme_light
                            com.facealbum.data.prefs.ThemePreference.DARK -> R.string.settings_theme_dark
                        }
                    )
                    FilterChip(
                        selected = current == pref,
                        onClick = { onSelect(pref) },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
