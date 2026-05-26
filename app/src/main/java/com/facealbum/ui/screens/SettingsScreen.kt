package com.facealbum.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.facealbum.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    minClusterSize: Int,
    onMinClusterSizeChange: (Int) -> Unit,
    onRescanAll: () -> Unit,
    onDeleteIndex: () -> Unit,
    onBack: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column {
                Text(
                    text = stringResource(R.string.settings_min_cluster_size),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = minClusterSize.toString(),
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = minClusterSize.toFloat(),
                    onValueChange = { onMinClusterSizeChange(it.toInt()) },
                    valueRange = 1f..10f,
                    steps = 8
                )
            }

            Divider()

            OutlinedButton(
                onClick = onRescanAll,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_rescan_all)) }

            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) { Text(stringResource(R.string.settings_delete_index)) }

            Divider()

            Text(
                text = stringResource(R.string.settings_about),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.settings_about_body),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.settings_delete_index)) },
            text = { Text("This removes all face data on this device. Exported albums remain.") },
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
