@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.facealbum.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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

/**
 * Per-gesture arbitration decided on first movement past touch slop (see
 * [ZoomablePhoto]'s pointerInput). Once a mode other than [UNDECIDED] is
 * picked it is kept for the rest of that gesture.
 */
private enum class GestureMode { UNDECIDED, PAGER, DISMISS, PAN, ZOOM }

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
                // Manual gesture arbitration so we only consume pointer events
                // when we actually need them (pinch-zoom, pan-while-zoomed, or
                // vertical drag-to-dismiss). Plain horizontal drags at 1x zoom
                // are left untouched so the surrounding HorizontalPager's own
                // drag detector can claim them — detectTransformGestures used
                // to swallow every event unconditionally, which is what broke
                // page-swiping.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)

                    // Every single-pointer gesture starts UNDECIDED, zoomed or
                    // not, so that nothing is consumed before touch slop is
                    // crossed. Consuming earlier would cancel the sibling
                    // detectTapGestures block (it treats any consumed change as
                    // a cancellation), which would kill tap-to-toggle-chrome and
                    // double-tap-to-zoom-out precisely while zoomed in — the one
                    // state where double-tap is the way back out.
                    val startedZoomed = scale > 1.02f
                    var mode = GestureMode.UNDECIDED
                    var pendingPanX = 0f
                    var pendingPanY = 0f
                    var dismissed = false
                    val touchSlop = viewConfiguration.touchSlop

                    while (true) {
                        val event = awaitPointerEvent()
                        val pointerCount = event.changes.count { it.pressed }
                        val pan = event.calculatePan()
                        val zoom = event.calculateZoom()

                        if (pointerCount >= 2) {
                            mode = GestureMode.ZOOM
                        }

                        when (mode) {
                            GestureMode.ZOOM -> {
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                if (scale > 1.02f) {
                                    offsetX += pan.x
                                    offsetY += pan.y
                                } else {
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                                event.changes.forEach { it.consume() }
                            }

                            GestureMode.PAN -> {
                                offsetX += pan.x
                                offsetY += pan.y
                                event.changes.forEach { it.consume() }
                            }

                            GestureMode.DISMISS -> {
                                offsetY += pan.y
                                event.changes.forEach { it.consume() }
                                if (!dismissed && abs(offsetY) > 220f) {
                                    dismissed = true
                                    onDismiss()
                                }
                            }

                            GestureMode.UNDECIDED -> {
                                pendingPanX += pan.x
                                pendingPanY += pan.y
                                if (abs(pendingPanX) > touchSlop || abs(pendingPanY) > touchSlop) {
                                    mode = when {
                                        // Already zoomed in: the drag moves the
                                        // photo, in any direction. Neither the
                                        // pager nor dismiss competes for it.
                                        startedZoomed -> GestureMode.PAN
                                        abs(pendingPanY) > abs(pendingPanX) -> GestureMode.DISMISS
                                        else -> GestureMode.PAGER
                                    }
                                    when (mode) {
                                        GestureMode.PAN -> {
                                            offsetX += pendingPanX
                                            offsetY += pendingPanY
                                            event.changes.forEach { it.consume() }
                                        }

                                        GestureMode.DISMISS -> {
                                            offsetY += pendingPanY
                                            event.changes.forEach { it.consume() }
                                        }

                                        // PAGER: leave every change unconsumed so
                                        // the HorizontalPager's own detector can
                                        // pick up this and subsequent events once
                                        // we bail out.
                                        else -> Unit
                                    }
                                }
                                // Below touch slop: don't consume — we don't yet
                                // know whether the pager or dismiss wants this.
                            }

                            GestureMode.PAGER -> Unit // unreachable; loop exits below.
                        }

                        if (mode == GestureMode.PAGER) {
                            break
                        }
                        if (event.changes.none { it.pressed }) {
                            break
                        }
                    }

                    if (!dismissed && scale <= 1.02f) {
                        offsetX = 0f
                        offsetY = 0f
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
