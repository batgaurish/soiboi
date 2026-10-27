package com.music.bitchord.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.playlistimport.ApplePlaylistMirrorReviewData
import com.music.bitchord.data.playlistimport.ApplePlaylistMirrorUnmatchedTrack
import com.music.bitchord.data.playlistimport.PlaylistImportService
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class MirrorTrackSearchState(
    val query: String,
    val isSearching: Boolean = false,
    val candidates: List<Song> = emptyList(),
    val error: String? = null,
    val searched: Boolean = false,
)

@Composable
fun ApplePlaylistMirrorReviewAlert(
    hazeState: HazeState,
    onLoadTracks: suspend () -> ApplePlaylistMirrorReviewData,
    onSearchCandidates: suspend (String) -> List<Song>,
    onSaveMatches: suspend (Map<String, Map<String, Song>>) -> Result<Unit>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    var reloadToken by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reviewData by remember { mutableStateOf<ApplePlaylistMirrorReviewData?>(null) }
    var searchStates by remember { mutableStateOf<Map<String, MirrorTrackSearchState>>(emptyMap()) }
    var selectedSongs by remember { mutableStateOf<Map<String, Song>>(emptyMap()) }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reloadToken) {
        isLoading = true
        loadError = null
        reviewData = null
        try {
            reviewData = onLoadTracks()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            loadError = safeMirrorReviewError(failure.message)
        } finally {
            isLoading = false
        }
    }

    BackHandler(enabled = true) {
        if (!isSaving) onDismiss()
    }

    val tracks = reviewData?.unmatchedTracks.orEmpty()
    val selectedCount = selectedSongs.size
    val canSave = selectedCount > 0 && !isSaving && !isLoading && loadError == null

    fun keyFor(track: ApplePlaylistMirrorUnmatchedTrack): String = "${track.targetId}\u0000${track.sourceKey}"

    fun runSearch(track: ApplePlaylistMirrorUnmatchedTrack) {
        val key = keyFor(track)
        val current = searchStates[key] ?: MirrorTrackSearchState(
            query = "${track.title} ${track.artist}".trim(),
        )
        val query = current.query.trim()
        if (query.isEmpty() || isSaving) return
        searchStates = searchStates + (key to current.copy(isSearching = true, candidates = emptyList(), error = null, searched = true))
        scope.launch {
            try {
                val candidates = onSearchCandidates(query).take(6)
                keyboardController?.hide()
                val latest = searchStates[key] ?: current
                searchStates = searchStates + (key to latest.copy(
                    isSearching = false,
                    candidates = candidates,
                    error = null,
                    searched = true,
                ))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                val latest = searchStates[key] ?: current
                searchStates = searchStates + (key to latest.copy(
                    isSearching = false,
                    candidates = emptyList(),
                    error = "Search failed. Check your connection and try again.",
                    searched = true,
                ))
            }
        }
    }

    fun saveMatches() {
        if (!canSave) return
        val matchesByTarget = tracks
            .filter { keyFor(it) in selectedSongs }
            .groupBy(ApplePlaylistMirrorUnmatchedTrack::targetId)
            .mapValues { (_, targetTracks) ->
                targetTracks.associate { track -> track.sourceKey to selectedSongs.getValue(keyFor(track)) }
            }
        scope.launch {
            isSaving = true
            saveError = null
            try {
                val result = onSaveMatches(matchesByTarget)
                result.fold(
                    onSuccess = { onDismiss() },
                    onFailure = { failure -> saveError = safeMirrorReviewError(failure.message) },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                saveError = safeMirrorReviewError(failure.message)
            } finally {
                isSaving = false
            }
        }
    }

    AlertScaffold(
        hazeState = hazeState,
        onDismiss = { if (!isSaving) onDismiss() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Text(
                text = "Review Apple Music matches",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "Choose a YouTube Music track for songs the automatic match could not resolve.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            when {
                isLoading -> ReviewMessage(
                    message = "Loading Apple Music playlists…",
                    showProgress = true,
                )
                loadError != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReviewMessage(
                        message = loadError ?: "Could not load Apple Music playlists.",
                        isError = true,
                    )
                    Text(
                        text = "Connect Apple Music or check your connection, then retry.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AlertAction(
                        label = "Retry",
                        emphasised = true,
                        onClick = { reloadToken++ },
                    )
                }
                tracks.isEmpty() -> ReviewMessage(
                    message = if ((reviewData?.mirrorCount ?: 0) == 0) {
                        "No Apple Music playlists are mirrored yet. Import a playlist to create one."
                    } else {
                        "No mirrored tracks need a manual match. Sync Apple playlists to check for updates."
                    },
                )
                else -> {
                    Text(
                        text = "${tracks.size} ${if (tracks.size == 1) "track needs" else "tracks need"} a match",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(tracks, key = ::keyFor) { track ->
                            val key = keyFor(track)
                            val state = searchStates[key] ?: MirrorTrackSearchState(
                                query = "${track.title} ${track.artist}".trim(),
                            )
                            val selectedSong = selectedSongs[key]
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(12.dp),
                            ) {
                                Text(
                                    text = track.mirrorTitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 3.dp),
                                )
                                Text(
                                    text = "${track.artist} · Track ${track.sourceIndex + 1}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (selectedSong != null) {
                                    Text(
                                        text = "Selected: ${selectedSong.title} by ${selectedSong.artist}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                                AlertAction(
                                    label = if (selectedSong != null) "Change match" else "Search YouTube Music",
                                    emphasised = false,
                                    onClick = {
                                        searchStates = searchStates + (key to state.copy(
                                            query = state.query.ifBlank { "${track.title} ${track.artist}".trim() },
                                        ))
                                    },
                                    enabled = !isSaving,
                                )
                                if (state.searched || searchStates[key] != null) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        OutlinedTextField(
                                            value = state.query,
                                            onValueChange = { query ->
                                                searchStates = searchStates + (key to state.copy(query = query, error = null))
                                            },
                                            label = { Text("Search YouTube Music") },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true,
                                            enabled = !isSaving,
                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                            keyboardActions = KeyboardActions(onSearch = { runSearch(track) }),
                                        )
                                        IconButton(
                                            onClick = { runSearch(track) },
                                            enabled = !isSaving && state.query.isNotBlank(),
                                            modifier = Modifier.size(48.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Search,
                                                contentDescription = "Search YouTube Music",
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                    when {
                                        state.isSearching -> ReviewMessage("Searching YouTube Music…", showProgress = true)
                                        state.error != null -> ReviewMessage(state.error, isError = true)
                                        state.searched && state.candidates.isEmpty() -> Text(
                                            text = "No results. Try a different title or artist.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 6.dp),
                                        )
                                        else -> state.candidates.forEach { candidate ->
                                            val isSelected = selectedSong?.videoId == candidate.videoId
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable(role = Role.RadioButton) {
                                                        selectedSongs = selectedSongs + (key to candidate)
                                                        saveError = null
                                                    }
                                                    .semantics { selected = isSelected }
                                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                RadioButton(selected = isSelected, onClick = null)
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = candidate.title,
                                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    Text(
                                                        text = candidate.artist,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (saveError != null) {
                Text(
                    text = "Could not save the matches. ${saveError}. Your selections are still here; retry when ready.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
        }
        AlertRule()
        AlertAction(
            label = when {
                isSaving -> "Saving matches…"
                selectedCount == 1 -> "Save match and sync playlist"
                else -> "Save $selectedCount matches and sync playlist"
            },
            emphasised = true,
            onClick = ::saveMatches,
            enabled = canSave,
        )
        AlertRule()
        AlertAction(
            label = "Cancel",
            emphasised = false,
            onClick = onDismiss,
            enabled = !isSaving,
        )
    }
}

@Composable
private fun ReviewMessage(
    message: String,
    showProgress: Boolean = false,
    isError: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun safeMirrorReviewError(message: String?): String =
    PlaylistImportService.sanitizeDiagnostics(message)
        ?.replace(Regex("[\\r\\n\\p{Cntrl}]"), " ")
        ?.take(180)
        ?.takeIf(String::isNotBlank)
        ?: "The playlist could not be loaded. Check your connection and try again."
