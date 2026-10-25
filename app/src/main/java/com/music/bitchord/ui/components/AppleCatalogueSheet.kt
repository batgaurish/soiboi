package com.music.bitchord.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.data.apple.AppleHomeItem
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.download.DownloadState
import com.music.bitchord.download.Downloads

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppleCatalogueSheet(
    item: AppleHomeItem,
    onArchive: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier,
    ) {
        AppleCatalogueSheetContent(
            item = item,
            onArchive = onArchive,
            onCancel = onCancel,
            onDelete = onDelete,
            onRetry = onRetry,
        )
    }
}

@Composable
fun AppleCatalogueSheetContent(
    item: AppleHomeItem,
    onArchive: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val trackId = "apple:${item.id}"
    val active by Downloads.active.collectAsStateWithLifecycle()
    val saved by Downloads.saved.collectAsStateWithLifecycle()

    val recorded = saved[trackId]
    val file by produceState(recorded?.let(Uri::parse), trackId, recorded) {
        value = Downloads.savedUri(context, trackId)
    }

    DisposableEffect(trackId) {
        onDispose { Downloads.dismissFailure(trackId) }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val shape = RoundedCornerShape(8.dp)
            AsyncImage(
                model = item.artwork.artworkAt(ROW_ART_PX),
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .clip(shape)
                    .thumbnailBorder(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.subtitle.ifBlank { item.type },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Apple Music",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Streaming playback is not supported for Apple Music. Archive this item to your local library for offline playback.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

        when (val state = active[trackId]) {
            is DownloadState.Queued -> ActionRow(
                icon = Icons.Rounded.Downloading,
                label = "Queued for archival",
                value = "Cancel",
                onClick = onCancel,
            )
            is DownloadState.Running -> ActionRow(
                icon = Icons.Rounded.Downloading,
                label = "Archiving...",
                value = if (state.fraction > 0f) "${(state.fraction * 100).toInt()}%" else "Cancel",
                tint = MaterialTheme.colorScheme.primary,
                onClick = onCancel,
            )
            is DownloadState.Failed -> ActionRow(
                icon = Icons.Rounded.ErrorOutline,
                label = state.reason.ifBlank { "Archival failed" },
                value = "Retry",
                tint = MaterialTheme.colorScheme.error,
                accent = MaterialTheme.colorScheme.error,
                onClick = onRetry,
            )
            null -> {
                if (file != null || trackId in saved) {
                    ActionRow(
                        icon = Icons.Rounded.DownloadDone,
                        label = "Saved to Library",
                        value = "Delete",
                        tint = MaterialTheme.colorScheme.primary,
                        onClick = onDelete,
                    )
                } else {
                    ActionRow(
                        icon = Icons.Rounded.Download,
                        label = "Archive to Library",
                        onClick = onArchive,
                    )
                }
            }
        }
    }
}
