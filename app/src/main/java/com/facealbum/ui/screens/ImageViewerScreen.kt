@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.facealbum.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImage
import com.facealbum.R
import com.facealbum.data.db.PhotoEntity
import kotlin.math.abs

/**
 * Immersive photo viewer — edge-to-edge black surface, horizontal-pager
 * navigation, pinch-to-zoom + pan on each page, and a swipe-down-to-dismiss
 * gesture that only fires when the current page isn't zoomed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageViewerScreen(
    photos: List<PhotoEntity>,
    initialIndex: Int,
    onClose: () -> Unit
) {
    if (photos.isEmpty()) {
        onClose()
        return
    }
    val safeInitial = initialIndex.coerceIn(0, photos.lastIndex)
    val pagerState = rememberPagerState(initialPage = safeInitial) { photos.size }
    var chromeVisible by remember { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            ZoomablePhoto(
                photo = photos[page],
                onDismiss = onClose,
                onToggleChrome = { chromeVisible = !chromeVisible }
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.35f),
                    navigationIconContentColor = Color.White,
                    titleContentColor = Color.White
                ),
                title = {
                    Text(
                        text = stringResource(
                            R.string.viewer_position,
                            pagerState.currentPage + 1,
                            photos.size
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.viewer_close),
                            tint = Color.White
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun ZoomablePhoto(
    photo: PhotoEntity,
    onDismiss: () -> Unit,
    onToggleChrome: () -> Unit
) {
    // Keyed on the photo so zoom/pan reset when the Pager recycles this
    // composable for a neighbouring page — otherwise zoom state bleeds over.
    var scale by remember(photo.id) { mutableStateOf(1f) }
    var offsetX by remember(photo.id) { mutableStateOf(0f) }
    var offsetY by remember(photo.id) { mutableStateOf(0f) }

    val isZoomed = scale > 1.02f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(photo.id) {
                detectTapGestures(
                    onTap = { onToggleChrome() },
                    onDoubleTap = {
                        if (isZoomed) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = 2.5f
                        }
                    }
                )
            }
            .pointerInput(photo.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 6f)
                    if (newScale <= 1.001f) {
                        // Fully zoomed out — treat vertical pan as dismiss intent.
                        if (abs(pan.y) > 8f && abs(pan.y) > abs(pan.x)) {
                            offsetY += pan.y
                            if (abs(offsetY) > 220f) onDismiss()
                        } else {
                            offsetY = 0f
                            offsetX = 0f
                        }
                        scale = 1f
                    } else {
                        scale = newScale
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = photo.uri,
            contentDescription = photo.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                    // Fade the whole photo as it drags off screen at scale 1.
                    alpha = if (!isZoomed) {
                        (1f - (abs(offsetY) / 400f)).coerceIn(0.3f, 1f)
                    } else 1f
                )
        )
    }
}
