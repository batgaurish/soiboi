package com.music.bitchord.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.playlistimport.*
import com.music.bitchord.data.accounts.SessionPlaylist
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class AndroidImportPhase {
    ENTRY,
    REVIEW,
    MANUAL_RESOLUTION,
}

/**
 * Android playlist import alert connecting external providers, JSON payloads, and
 * pasted tracklists to candidate resolution, manual review, and BitChord library persistence.
 *
 * Implemented within BitChord's established Material 3 UI:
 * ENERGY 1 (matte surfaceVariant cards, high contrast, low saturation)
 * RHYTHM 2 (orderly track indices, consistent vertical spacing)
 * MOTION 1 (subtle transitions, honest loading/empty/error states)
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun PlaylistImportAlert(
    hazeState: HazeState,
    signedIn: Boolean,
    appleMusic: Boolean = false,
    loadAppleLibrary: (suspend () -> List<SessionPlaylist>)? = null,
    loadAppleLibraryPlaylist: (suspend (SessionPlaylist) -> ImportedPlaylist)? = null,
    onImported: (title: String, privacy: PlaylistPrivacy, songs: List<Song>) -> Unit,
    onImportedWithDetails: ((source: ImportedPlaylist, rows: List<ImportRowState>, title: String, privacy: PlaylistPrivacy, songs: List<Song>) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(AndroidImportPhase.ENTRY) }
    var input by remember { mutableStateOf("") }
    var titleOverride by remember { mutableStateOf("") }
    var credential by remember { mutableStateOf("") }
    var privacy by remember { mutableStateOf(PlaylistPrivacy.PRIVATE) }
    var working by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var libraryPlaylists by remember { mutableStateOf<List<SessionPlaylist>?>(null) }
    var libraryLoading by remember { mutableStateOf(false) }

    var importedPlaylist by remember { mutableStateOf<ImportedPlaylist?>(null) }
    var rows by remember { mutableStateOf<List<ImportRowState>>(emptyList()) }
    var selectedRowIndex by remember { mutableStateOf(-1) }

    // Manual resolution state
    var manualSearchQuery by remember { mutableStateOf("") }
    var manualCandidates by remember { mutableStateOf<List<Song>>(emptyList()) }
    var searchingManualCandidates by remember { mutableStateOf(false) }

    val handleDismiss: () -> Unit = {
        credential = ""
        onDismiss()
    }

    DisposableEffect(Unit) {
        onDispose {
            credential = ""
        }
    }

    LaunchedEffect(input) {
        if (PlaylistImportService.detectAuthCredentialKind(input) == null && credential.isNotEmpty()) {
            credential = ""
        }
    }

    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("bitchord_playlist_import_overrides", android.content.Context.MODE_PRIVATE)
        PlaylistImportOverrides.installStore(object : PlaylistImportStore {
            override fun getString(key: String): String? = prefs.getString(key, null)
            override fun putString(key: String, value: String) {
                prefs.edit().putString(key, value).apply()
            }
            override fun remove(key: String) {
                prefs.edit().remove(key).apply()
            }
        })
    }

    BackHandler(enabled = !working) {
        when (phase) {
            AndroidImportPhase.MANUAL_RESOLUTION -> phase = AndroidImportPhase.REVIEW
            AndroidImportPhase.REVIEW -> phase = AndroidImportPhase.ENTRY
            AndroidImportPhase.ENTRY -> handleDismiss()
        }
    }

    fun startImport() {
        if (working || input.isBlank()) return
        working = true
        errorMessage = null
        val fetchingMessage = context.getString(R.string.spotify_import_fetching)
        statusMessage = if (appleMusic) fetchingMessage.replace("Spotify", "Apple Music", ignoreCase = true) else fetchingMessage
        val tokenToUse = credential.trim().takeIf(String::isNotBlank)

        scope.launch {
            try {
                val playlist = PlaylistImportService.loadPlaylist(
                    input = input,
                    customTitle = titleOverride.takeIf(String::isNotBlank),
                    authToken = tokenToUse,
                )
                credential = ""
                importedPlaylist = playlist
                if (playlist.entries.isEmpty()) {
                    errorMessage = context.getString(R.string.spotify_import_empty_playlist)
                    working = false
                    return@launch
                }

                statusMessage = context.getString(R.string.spotify_import_matching, 0, playlist.entries.size)

                val resolvedRows = PlaylistImportService.resolveEntries(playlist) { done, total ->
                    statusMessage = context.getString(R.string.spotify_import_matching, done, total)
                }

                rows = resolvedRows
                phase = AndroidImportPhase.REVIEW
            } catch (e: CancellationException) {
                credential = ""
                throw e
            } catch (e: Exception) {
                val sanitized = PlaylistImportService.sanitizeDiagnostics(e.message, tokenToUse)
                errorMessage = context.getString(R.string.spotify_import_failed, sanitized.orEmpty())
            } finally {
                working = false
                statusMessage = null
            }
        }
    }

    fun saveAndPersist() {
        if (working) return
        val currentPlaylist = importedPlaylist ?: return
        val playableSongs = rows.mapNotNull { it.candidateSong }
        if (playableSongs.isEmpty()) {
            errorMessage = context.getString(R.string.spotify_import_none)
            return
        }

        credential = ""
        val title = currentPlaylist.title.ifBlank { context.getString(R.string.new_playlist) }
        if (onImportedWithDetails != null) {
            onImportedWithDetails(currentPlaylist, rows, title, privacy, playableSongs)
        } else {
            onImported(title, privacy, playableSongs)
        }
        handleDismiss()
    }

    AlertScaffold(hazeState = hazeState, onDismiss = { if (!working) handleDismiss() }) {
        when (phase) {
            AndroidImportPhase.ENTRY -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 19.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = if (appleMusic) "Import Apple Music playlist" else stringResource(R.string.spotify_import_title),
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = statusMessage ?: errorMessage ?: if (appleMusic) {
                            "Paste an Apple Music playlist link. Review matched tracks before saving."
                        } else stringResource(
                            if (signedIn) R.string.spotify_import_description else R.string.spotify_import_description_local,
                        ),
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                        color = when {
                            errorMessage != null -> MaterialTheme.colorScheme.error
                            statusMessage != null -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                    )

                    if (appleMusic && loadAppleLibrary != null && loadAppleLibraryPlaylist != null) {
                        TextButton(
                            enabled = !working && !libraryLoading,
                            onClick = {
                                libraryLoading = true
                                errorMessage = null
                                scope.launch {
                                    try {
                                        libraryPlaylists = loadAppleLibrary()
                                        if (libraryPlaylists.isNullOrEmpty()) errorMessage = "No playlists found in your Apple Music library."
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        errorMessage = "Couldn't load your Apple Music library: ${e.message.orEmpty()}"
                                    } finally {
                                        libraryLoading = false
                                    }
                                }
                            },
                        ) {
                            if (libraryLoading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(if (libraryPlaylists == null) "Browse your Apple Music library" else "Refresh Apple Music library")
                        }
                        libraryPlaylists?.let { savedPlaylists ->
                            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                                itemsIndexed(savedPlaylists, key = { _, playlist -> playlist.id }) { _, playlist ->
                                    TextButton(
                                        enabled = !working,
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            working = true
                                            errorMessage = null
                                            statusMessage = "Loading ${playlist.name}…"
                                            scope.launch {
                                                try {
                                                    val source = loadAppleLibraryPlaylist(playlist)
                                                    if (source.entries.isEmpty()) error("This playlist has no readable tracks.")
                                                    importedPlaylist = source
                                                    statusMessage = "Matching 0 of ${source.entries.size} tracks…"
                                                    rows = PlaylistImportService.resolveEntries(source) { done, total ->
                                                        statusMessage = "Matching $done of $total tracks…"
                                                    }
                                                    phase = AndroidImportPhase.REVIEW
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    errorMessage = "Couldn't import ${playlist.name}: ${e.message.orEmpty()}"
                                                } finally {
                                                    working = false
                                                    statusMessage = null
                                                }
                                            }
                                        },
                                    ) {
                                        Column(Modifier.fillMaxWidth()) {
                                            Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(playlist.trackCount?.let { "$it tracks" } ?: "Apple Music playlist", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Multi-source input box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp, max = 130.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(10.dp),
                    ) {
                        if (input.isEmpty()) {
                            Text(
                                text = if (appleMusic) "Paste an Apple Music playlist link or tracklist" else "Paste link or tracklist\n(Spotify, Deezer, YouTube, Tidal, etc.)",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                        BasicTextField(
                            value = input,
                            onValueChange = {
                                input = it
                                errorMessage = null
                            },
                            enabled = !working,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Optional title override
                    PillTextField(
                        value = titleOverride,
                        onValueChange = { titleOverride = it },
                        placeholder = "Custom playlist title (optional)",
                        enabled = !working,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (input.isNotBlank() && !working) startImport() }),
                    )

                    val authKind = remember(input) { PlaylistImportService.detectAuthCredentialKind(input) }
                    if (authKind != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = authKind.label,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PillTextField(
                            value = credential,
                            onValueChange = {
                                credential = it
                                errorMessage = null
                            },
                            placeholder = authKind.placeholder,
                            isPassword = true,
                            enabled = !working,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = {
                                if (input.isNotBlank() && !working) startImport()
                            }),
                        )
                    }

                    // Privacy selector when signed in
                    if (signedIn) {
                        Text(
                            text = stringResource(R.string.who_can_see_it),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp, bottom = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            PlaylistPrivacy.entries.forEach { option ->
                                val selected = option == privacy
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(percent = 50))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                        )
                                        .clickable(enabled = !working) { privacy = option }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = option.label,
                                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp),
                                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                AlertRule()
                AlertAction(
                    label = if (working) "Matching…" else stringResource(R.string.spotify_import_button),
                    emphasised = true,
                    onClick = ::startImport,
                    enabled = input.isNotBlank() && !working,
                )
                AlertRule()
                AlertAction(
                    label = stringResource(R.string.cancel),
                    emphasised = false,
                    onClick = handleDismiss,
                    enabled = !working,
                )
            }

            AndroidImportPhase.REVIEW -> {
                val currentPlaylist = importedPlaylist
                val matchedCount = rows.count { it.isPlayable }
                val unmatchedCount = rows.size - matchedCount

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text(
                        text = currentPlaylist?.title ?: "Import Review",
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.W600),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = currentPlaylist?.provider?.name?.replace('_', ' ') ?: "IMPORT",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "·",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "$matchedCount / ${rows.size} matched",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (unmatchedCount > 0) {
                            Text(
                                text = "($unmatchedCount unresolved)",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    // Track list (ordered, preserving duplicates & source indices)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(4.dp),
                    ) {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            itemsIndexed(rows, key = { idx, r -> "${r.entry.sourceIndex}_${idx}" }) { idx, row ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (!row.isPlayable) MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
                                            else Color.Transparent,
                                        )
                                        .clickable {
                                            selectedRowIndex = idx
                                            val query = "${row.entry.title} ${row.entry.artist}".trim()
                                            manualSearchQuery = query
                                            manualCandidates = row.availableCandidates
                                            phase = AndroidImportPhase.MANUAL_RESOLUTION
                                            if (row.availableCandidates.size <= 1 && query.isNotBlank()) {
                                                searchingManualCandidates = true
                                                scope.launch {
                                                    val candidates = PlaylistImportService.searchCandidates(query)
                                                    manualCandidates = candidates
                                                    searchingManualCandidates = false
                                                }
                                            }
                                        }
                                        .padding(horizontal = 6.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "${row.entry.sourceIndex + 1}.",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(22.dp),
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = row.entry.title,
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = row.entry.artist,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        val statusText = when (val state = row.matchState) {
                                            is ImportMatchState.Matched -> "✓ Matched: ${row.candidateSong?.title ?: ""}"
                                            is ImportMatchState.ManualConfirmed -> "✓ Confirmed: ${row.candidateSong?.title ?: ""}"
                                            is ImportMatchState.Unmatched -> "⚠ Unmatched (tap to resolve)"
                                        }
                                        Text(
                                            text = statusText,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                            color = if (row.isPlayable) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (unmatchedCount > 0) {
                        Text(
                            text = "$unmatchedCount unresolved tracks will be skipped from playback.",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }

                AlertRule()
                AlertAction(
                    label = "Save Playlist ($matchedCount)",
                    emphasised = true,
                    onClick = ::saveAndPersist,
                    enabled = matchedCount > 0 && !working,
                )
                AlertRule()
                AlertAction(
                    label = "Back to Input",
                    emphasised = false,
                    onClick = { phase = AndroidImportPhase.ENTRY },
                    enabled = !working,
                )
                AlertRule()
                AlertAction(
                    label = stringResource(R.string.cancel),
                    emphasised = false,
                    onClick = handleDismiss,
                    enabled = !working,
                )
            }

            AndroidImportPhase.MANUAL_RESOLUTION -> {
                val selectedRow = rows.getOrNull(selectedRowIndex)
                if (selectedRow == null) {
                    phase = AndroidImportPhase.REVIEW
                    return@AlertScaffold
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text(
                        text = "Resolve Track #${selectedRow.entry.sourceIndex + 1}",
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.W600),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Source: ${selectedRow.entry.title} · ${selectedRow.entry.artist}",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    )

                    // Search box
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.weight(1f)) {
                            BasicTextField(
                                value = manualSearchQuery,
                                onValueChange = { manualSearchQuery = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = {
                                    if (manualSearchQuery.isNotBlank() && !searchingManualCandidates) {
                                        searchingManualCandidates = true
                                        scope.launch {
                                            manualCandidates = PlaylistImportService.searchCandidates(manualSearchQuery)
                                            searchingManualCandidates = false
                                        }
                                    }
                                }),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (searchingManualCandidates) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Text(
                                text = "Find",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable {
                                        if (manualSearchQuery.isNotBlank()) {
                                            searchingManualCandidates = true
                                            scope.launch {
                                                manualCandidates = PlaylistImportService.searchCandidates(manualSearchQuery)
                                                searchingManualCandidates = false
                                            }
                                        }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Candidates list
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(4.dp),
                    ) {
                        if (manualCandidates.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (searchingManualCandidates) "Searching catalogue…"
                                    else "No candidates found for query.",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                itemsIndexed(manualCandidates) { _, candidate ->
                                    val isCurrent = selectedRow.candidateSong?.videoId == candidate.videoId
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(
                                                if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                                else Color.Transparent,
                                            )
                                            .clickable {
                                                val updated = PlaylistImportService.applyManualCandidate(selectedRow, candidate)
                                                rows = rows.toMutableList().also { it[selectedRowIndex] = updated }
                                                phase = AndroidImportPhase.REVIEW
                                            }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = candidate.title,
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = candidate.artist + (candidate.durationText?.let { " · $it" } ?: ""),
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        if (isCurrent) {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = "Selected",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                AlertRule()
                AlertAction(
                    label = "Mark as Unmatched / Skip",
                    emphasised = false,
                    onClick = {
                        val updated = PlaylistImportService.applyManualCandidate(selectedRow, null)
                        rows = rows.toMutableList().also { it[selectedRowIndex] = updated }
                        phase = AndroidImportPhase.REVIEW
                    },
                )
                if (selectedRow.matchState is ImportMatchState.ManualConfirmed) {
                    AlertRule()
                    AlertAction(
                        label = "Reset to Automatic Match",
                        emphasised = false,
                        onClick = {
                            val updated = PlaylistImportService.clearManualOverride(selectedRow)
                            rows = rows.toMutableList().also { it[selectedRowIndex] = updated }
                            phase = AndroidImportPhase.REVIEW
                        },
                    )
                }
                AlertRule()
                AlertAction(
                    label = "Done",
                    emphasised = true,
                    onClick = { phase = AndroidImportPhase.REVIEW },
                )
            }
        }
    }
}
