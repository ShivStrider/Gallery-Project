@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.facealbum.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.facealbum.MainViewModel
import com.facealbum.R
import com.facealbum.data.db.ClusterSummary
import com.facealbum.ui.theme.Spacing
import com.facealbum.util.PhotoAccess

/**
 * Hero "People" grid. Google Photos–style tiles with a large title, a live
 * scan-progress banner, a friendly empty state, and shimmer while the very
 * first scan finishes.
 */
@Composable
fun PeopleScreen(
    clusters: List<ClusterSummary>,
    indexProgress: MainViewModel.IndexProgress,
    snackbarHostState: SnackbarHostState,
    onClusterClick: (Long) -> Unit,
    onScanNow: () -> Unit,
    onOpenSettings: () -> Unit,
    limitedAccess: Boolean = false,
    reviewNeededFaceCount: Int = 0,
    onReviewNeededClick: () -> Unit = {}
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current

    // Ask for the notification permission in context — right when the user
    // starts a scan — so the foreground-service progress notification isn't
    // silently dropped on Android 13+. Denial never blocks the scan.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> onScanNow() }
    val startScan: () -> Unit = {
        val needsNotificationPermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onScanNow()
        }
    }

    // Re-launching the photo permission request under a partial grant shows
    // the system's "keep selection / select more" sheet — that's the official
    // reselect flow on Android 14+.
    val reselectLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (PhotoAccess.hasAnyAccess(context)) onScanNow()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.people_title),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.people_open_settings)
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = startScan,
                expanded = !indexProgress.running,
                icon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                text = { Text(stringResource(R.string.people_scan_now)) },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(top = innerPadding.calculateTopPadding()).fillMaxSize()) {
            if (limitedAccess) {
                LimitedAccessBanner(
                    onManage = { reselectLauncher.launch(PhotoAccess.requiredPermissions()) }
                )
            }

            AnimatedVisibility(
                visible = indexProgress.running || indexProgress.errorMessage != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                when {
                    indexProgress.errorMessage != null ->
                        ErrorBanner(message = indexProgress.errorMessage)
                    else -> IndexProgressBanner(progress = indexProgress)
                }
            }

            when {
                clusters.isEmpty() && reviewNeededFaceCount == 0 &&
                    indexProgress.running && indexProgress.total > 0 ->
                    SkeletonGrid(modifier = Modifier.weight(1f))
                clusters.isEmpty() && reviewNeededFaceCount == 0 ->
                    EmptyPeopleState(
                        isScanning = indexProgress.running,
                        onScanNow = startScan,
                        modifier = Modifier.weight(1f)
                    )
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(150.dp),
                        // Grid content is allowed to draw *behind* the system nav
                        // bar; only the inset is respected in contentPadding.
                        contentPadding = PaddingValues(
                            start = Spacing.md,
                            end = Spacing.md,
                            top = Spacing.sm,
                            bottom = innerPadding.calculateBottomPadding() + 96.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // Distinct affordance, not a person — kept out of the
                        // faceCount-desc ordering the DAO gives `clusters` so
                        // it doesn't jostle around as small groups grow.
                        if (reviewNeededFaceCount > 0) {
                            item(key = "review_needed") {
                                ReviewNeededTile(
                                    faceCount = reviewNeededFaceCount,
                                    onClick = onReviewNeededClick
                                )
                            }
                        }
                        items(clusters, key = { it.id }) { cluster ->
                            ClusterTile(
                                cluster = cluster,
                                onClick = { onClusterClick(cluster.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LimitedAccessBanner(onManage: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.people_limited_access_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onManage) {
                Text(stringResource(R.string.people_limited_access_manage))
            }
        }
    }
}

@Composable
private fun IndexProgressBanner(progress: MainViewModel.IndexProgress) {
    val total = progress.total.coerceAtLeast(1)
    val fraction = (progress.done.toFloat() / total).coerceIn(0f, 1f)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
        ) {
            Text(
                text = stringResource(
                    R.string.people_scanning_progress,
                    progress.done, progress.total, progress.faces
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.xs))
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(Spacing.md)
        )
    }
}

/** `internal` (module-visible) so [ReviewNeededScreen] can reuse the exact same tile. */
@Composable
internal fun ClusterTile(cluster: ClusterSummary, onClick: () -> Unit) {
    val nameOrPlaceholder = cluster.displayName?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.people_unnamed)

    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.aspectRatio(0.85f),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (cluster.coverPhotoUri != null) {
                AsyncImage(
                    model = cluster.coverPhotoUri,
                    contentDescription = nameOrPlaceholder,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Face,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            ) {
                Text(
                    text = nameOrPlaceholder,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1
                )
                Text(
                    text = stringResource(R.string.people_face_count_format, cluster.faceCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * The "Review needed" grid entry — same tile shape and bottom scrim as
 * [ClusterTile] so it reads as part of the same grid, but a flat tinted
 * surface and question-mark icon stand in for a cover photo so it can never
 * be mistaken for a person, and the subtitle counts faces, never "photos".
 */
@Composable
private fun ReviewNeededTile(faceCount: Int, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.aspectRatio(0.85f),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.HelpOutline,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            ) {
                Text(
                    text = stringResource(R.string.people_review_needed_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.people_review_needed_subtitle,
                        faceCount,
                        faceCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun EmptyPeopleState(
    isScanning: Boolean,
    onScanNow: () -> Unit,
    modifier: Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(width = 220.dp, height = 176.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_empty_people),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimaryContainer)
            )
        }
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = stringResource(R.string.people_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = if (isScanning) {
                stringResource(R.string.people_empty_scanning)
            } else {
                stringResource(R.string.people_empty_subtitle)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (!isScanning) {
            Spacer(Modifier.height(Spacing.lg))
            FilledTonalButton(
                onClick = onScanNow,
                shape = RoundedCornerShape(24.dp)
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(Spacing.sm))
                Text(stringResource(R.string.people_empty_cta))
            }
        }
    }
}

/**
 * Placeholder tiles that shimmer while the very first index runs — gives the
 * user a sense the grid is about to fill in rather than an empty screen.
 */
@Composable
private fun SkeletonGrid(modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonAlpha"
    )
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(
            start = Spacing.md,
            end = Spacing.md,
            top = Spacing.sm,
            bottom = 96.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        modifier = modifier.fillMaxSize()
    ) {
        items(count = 6) {
            Box(
                modifier = Modifier
                    .aspectRatio(0.85f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
                    )
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(Spacing.md)
                        .size(width = 80.dp, height = 12.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = alpha)
                        )
                )
            }
        }
    }
}
