package com.music.bitchord.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.discovery.ListenBrainzWeeklyClient
import com.music.bitchord.data.discovery.ListenBrainzWeeklyPlaylist
import com.music.bitchord.data.discovery.ListenBrainzWeeklyResult
import com.music.bitchord.data.discovery.ListenBrainzWeeklyTrack

/** Parent-owned playback boundary; resolve the recording with existing matching/playback logic. */
fun interface ListenBrainzTrackAction { fun onTrack(track: ListenBrainzWeeklyTrack) }

/** Shared Android/Linux shelf with bounded loading, empty, error, and retry states. */
@Composable
fun ListenBrainzWeeklyHome(token: String, onTrack: ListenBrainzTrackAction, modifier: Modifier = Modifier) {
    var attempt by remember(token) { mutableIntStateOf(0) }
    var result by remember(token) { mutableStateOf<ListenBrainzWeeklyResult>(ListenBrainzWeeklyResult.Loading) }
    LaunchedEffect(token, attempt) {
        result = ListenBrainzWeeklyResult.Loading
        result = ListenBrainzWeeklyClient(token = { token }).load()
    }
    Column(modifier = modifier.fillMaxWidth()) {
        when (val state = result) {
            ListenBrainzWeeklyResult.Loading -> Text("Loading ListenBrainz weekly discoveries…", modifier = Modifier.padding(16.dp))
            is ListenBrainzWeeklyResult.Failure -> {
                Text(state.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
                TextButton(onClick = { attempt++ }, modifier = Modifier.padding(start = 8.dp)) { Text("Retry") }
            }
            is ListenBrainzWeeklyResult.Empty -> Text("No weekly discovery playlists are available for ${state.username} yet.", modifier = Modifier.padding(16.dp))
            is ListenBrainzWeeklyResult.Success -> state.playlists.forEach { ListenBrainzWeeklyShelf(it, onTrack) }
        }
    }
}

@Composable
fun ListenBrainzWeeklyShelf(playlist: ListenBrainzWeeklyPlaylist, onTrack: ListenBrainzTrackAction, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(playlist.title, style = MaterialTheme.typography.titleMedium)
        if (playlist.description.isNotBlank()) Text(playlist.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        if (playlist.tracks.isEmpty()) {
            Text("No tracks in this playlist", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        } else LazyRow(modifier = Modifier.padding(top = 8.dp)) {
            itemsIndexed(playlist.tracks, key = { index, track -> "${track.recordingMbid.orEmpty()}:$index" }) { _, track ->
                Column(modifier = Modifier.padding(end = 12.dp).clickable { onTrack.onTrack(track) }.padding(8.dp)) {
                    Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    Text(track.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    track.release?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                }
            }
        }
    }
}
