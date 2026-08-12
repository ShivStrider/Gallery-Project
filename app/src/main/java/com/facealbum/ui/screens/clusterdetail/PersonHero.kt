package com.facealbum.ui.screens.clusterdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.facealbum.R
import com.facealbum.data.db.PhotoEntity
import com.facealbum.ui.theme.Spacing

@Composable
internal fun PersonHero(
    coverPhoto: PhotoEntity?,
    displayName: String,
    gridScrollOffsetPx: Int,
    onRename: () -> Unit
) {
    // Subtle parallax on the hero image as the grid scrolls under it.
    val parallax = (gridScrollOffsetPx * 0.35f).coerceIn(-200f, 200f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
    ) {
        if (coverPhoto != null) {
            AsyncImage(
                model = coverPhoto.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = parallax }
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.35f),
                        0.4f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.75f)
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(Spacing.lg)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = Color.White
                )
                Spacer(Modifier.width(Spacing.sm))
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.20f),
                    modifier = Modifier.clip(CircleShape)
                ) {
                    IconButton(
                        onClick = onRename,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = stringResource(R.string.person_detail_rename),
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
