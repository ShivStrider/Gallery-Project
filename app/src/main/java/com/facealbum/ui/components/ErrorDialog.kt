package com.facealbum.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.facealbum.R

/**
 * Reusable error dialog component.
 */
@Composable
fun ErrorDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    dismissButtonText: String = stringResource(R.string.ok)
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = { Text(text = message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissButtonText)
            }
        }
    )
}

/**
 * Dialog shown when no faces are detected in seed photos.
 */
@Composable
fun NoFacesDetectedDialog(
    onDismiss: () -> Unit
) {
    ErrorDialog(
        title = stringResource(R.string.no_faces_title),
        message = stringResource(R.string.no_faces_message),
        onDismiss = onDismiss
    )
}

/**
 * Dialog shown when the face recognition model fails to load.
 */
@Composable
fun ModelLoadErrorDialog(
    errorMessage: String,
    onDismiss: () -> Unit
) {
    ErrorDialog(
        title = stringResource(R.string.model_error_title),
        message = errorMessage,
        onDismiss = onDismiss
    )
}

/**
 * Generic scan error dialog.
 */
@Composable
fun ScanErrorDialog(
    errorMessage: String,
    onDismiss: () -> Unit
) {
    ErrorDialog(
        title = stringResource(R.string.scan_error_title),
        message = errorMessage,
        onDismiss = onDismiss
    )
}
