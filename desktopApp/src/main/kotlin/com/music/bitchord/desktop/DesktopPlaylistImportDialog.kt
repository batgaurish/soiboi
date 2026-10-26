package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.playlistimport.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class ImportPhase {
    ENTRY,
    REVIEW,
    MANUAL_RESOLUTION,
}

/**
 * Desktop playlist import dialog supporting all provider sources, pasted tracklists,
 * deterministic ISRC/artist-title matching, manual candidate resolution, and
 * library persistence.
 *
 * Adheres to BitChord consumer music app styling:
 * ENERGY 1 (matte surfaces, restrained saturation, high contrast)
 * RHYTHM 2 (tabular numbers, orderly row pacing, consistent metrics)
 * MOTION 1 (subtle, deterministic state transitions)
 */
@Composable
internal fun DesktopPlaylistImportDialog(
    signedIn: Boolean,
    onImported: suspend (title: String, songs: List<Song>) -> String?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(ImportPhase.ENTRY) }
    var rawInput by remember { mutableStateOf("") }
    var titleOverride by remember { mutableStateOf("") }
    var credential by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var progressStatus by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

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

    LaunchedEffect(rawInput) {
        if (PlaylistImportService.detectAuthCredentialKind(rawInput) == null && credential.isNotEmpty()) {
            credential = ""
        }
    }

    val persistence = remember { DesktopPersistence() }
    LaunchedEffect(Unit) {
        PlaylistImportOverrides.installStore(object : PlaylistImportStore {
            override fun getString(key: String): String? = persistence.string(key, "").takeIf { it.isNotEmpty() }
            override fun putString(key: String, value: String) = persistence.saveString(key, value)
            override fun remove(key: String) = persistence.saveString(key, "")
        })
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(phase) {
        if (phase == ImportPhase.ENTRY) {
            runCatching { focus.requestFocus() }
        }
    }

    fun startImport() {
        if (working || rawInput.isBlank()) return
        working = true
        errorMessage = null
        progressStatus = DesktopStrings["playlist_import_reading", "Reading playlist data…"]
        val tokenToUse = credential.trim().takeIf(String::isNotBlank)

        scope.launch {
            try {
                val playlist = PlaylistImportService.loadPlaylist(
                    input = rawInput,
                    customTitle = titleOverride.takeIf(String::isNotBlank),
                    authToken = tokenToUse,
                )
                credential = ""
                importedPlaylist = playlist
                if (playlist.entries.isEmpty()) {
                    errorMessage = DesktopStrings["playlist_import_empty", "No track entries found in the input."]
                    working = false
                    return@launch
                }

                progressStatus = DesktopStrings.format(
                    "playlist_import_matching",
                    0,
                    playlist.entries.size,
                    fallback = "Matching songs 0 of %2\$d…",
                )

                val resolvedRows = PlaylistImportService.resolveEntries(playlist) { done, total ->
                    progressStatus = DesktopStrings.format(
                        "playlist_import_matching",
                        done,
                        total,
                        fallback = "Matching songs %1\$d of %2\$d…",
                    )
                }

                rows = resolvedRows
                phase = ImportPhase.REVIEW
            } catch (cancelled: CancellationException) {
                credential = ""
                throw cancelled
            } catch (failure: Exception) {
                val sanitized = PlaylistImportService.sanitizeDiagnostics(failure.message, tokenToUse)
                DesktopTrackLog.log("playlist import failed: $sanitized")
                errorMessage = sanitized ?: DesktopStrings["playlist_import_error", "Failed to parse playlist."]
            } finally {
                working = false
                progressStatus = null
            }
        }
    }

    fun saveAndPersist() {
        if (working) return
        val currentPlaylist = importedPlaylist ?: return
        val playableSongs = rows.mapNotNull { it.candidateSong }
        if (playableSongs.isEmpty()) {
            errorMessage = DesktopStrings["playlist_import_none", "None of the tracks could be resolved to playable songs."]
            return
        }

        credential = ""
        working = true
        errorMessage = null
        progressStatus = DesktopStrings["playlist_import_saving", "Saving playlist to library…"]

        scope.launch {
            try {
                val title = currentPlaylist.title.ifBlank { "Imported playlist" }
                val note = onImported(title, playableSongs)
                if (note == null) {
                    handleDismiss()
                } else {
                    errorMessage = note
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                DesktopTrackLog.log("playlist save failed: ${failure.message}")
                errorMessage = failure.message ?: "Could not save playlist."
            } finally {
                working = false
                progressStatus = null
            }
        }
    }

    val panelWidth = when (phase) {
        ImportPhase.ENTRY -> 460
        ImportPhase.REVIEW -> 580
        ImportPhase.MANUAL_RESOLUTION -> 580
    }

    DesktopDialogPanel(onDismiss = { if (!working) handleDismiss() }, maxWidth = panelWidth, popup = true) {
        when (phase) {
            ImportPhase.ENTRY -> {
                DialogHeading(
                    title = DesktopStrings["playlist_import_title", "Import Playlist"],
                    message = if (signedIn) {
                        DesktopStrings[
                            "playlist_import_desc_signed_in",
                            "Paste a public link or tracklist. Matches are resolved against catalogue tracks and saved to your library.",
                        ]
                    } else {
                        DesktopStrings[
                            "playlist_import_desc_local",
                            "Paste a public link or tracklist. Matches are resolved against catalogue tracks and saved on this device.",
                        ]
                    },
                )

                // Input box for URL or pasted text
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = panelInset(22.dp), vertical = 8.dp),
                ) {
                    Text(
                        text = DesktopStrings["playlist_import_input_label", "Link, JSON payload, or tracklist"],
                        style = MaterialTheme.typography.labelSmall,
                        color = DesktopSecondary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 80.dp, max = 150.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = if (!working) 0.08f else 0.04f))
                            .padding(10.dp),
                    ) {
                        if (rawInput.isEmpty()) {
                            Text(
                                text = "music.apple.com/…\nopen.spotify.com/playlist/…\ndeezer.com/playlist/…\nor paste lines: Artist - Title",
                                style = MaterialTheme.typography.bodyMedium,
                                color = DesktopSecondary.copy(alpha = 0.6f),
                            )
                        }
                        BasicTextField(
                            value = rawInput,
                            onValueChange = {
                                rawInput = it
                                errorMessage = null
                            },
                            enabled = !working,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                            cursorBrush = SolidColor(DesktopAccent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focus),
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    Text(
                        text = DesktopStrings["playlist_import_title_override_label", "Custom playlist title (optional)"],
                        style = MaterialTheme.typography.labelSmall,
                        color = DesktopSecondary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = if (!working) 0.08f else 0.04f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            if (titleOverride.isEmpty()) {
                                Text(
                                    text = DesktopStrings["playlist_import_title_override_hint", "Use imported title"],
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = DesktopSecondary.copy(alpha = 0.6f),
                                )
                            }
                            BasicTextField(
                                value = titleOverride,
                                onValueChange = { titleOverride = it },
                                enabled = !working,
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                                cursorBrush = SolidColor(DesktopAccent),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    val authKind = remember(rawInput) { PlaylistImportService.detectAuthCredentialKind(rawInput) }
                    if (authKind != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = authKind.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = DesktopSecondary,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = if (!working) 0.08f else 0.04f))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.weight(1f)) {
                                if (credential.isEmpty()) {
                                    Text(
                                        text = authKind.placeholder,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = DesktopSecondary.copy(alpha = 0.6f),
                                    )
                                }
                                BasicTextField(
                                    value = credential,
                                    onValueChange = {
                                        credential = it
                                        errorMessage = null
                                    },
                                    enabled = !working,
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                                    cursorBrush = SolidColor(DesktopAccent),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = {
                                        if (rawInput.isNotBlank() && !working) startImport()
                                    }),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            if (credential.isNotEmpty() && !working) {
                                Box(
                                    Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .clickable { credential = "" },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        DesktopStrings["clear", "Clear"],
                                        tint = DesktopSecondary,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }

                    // Supported sources hint pills
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val providers = listOf("Apple Music", "Spotify", "Deezer", "YouTube", "Tidal", "SoundCloud", "Text")
                        providers.forEach { name ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.White.copy(alpha = 0.05f))
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = DesktopSecondary,
                                )
                            }
                        }
                    }
                }

                // Status or Error
                val currentStatus = progressStatus
                val currentError = errorMessage
                if (currentStatus != null) {
                    Text(
                        text = currentStatus,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = panelInset(22.dp), vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = DesktopAccent,
                        textAlign = TextAlign.Center,
                    )
                } else if (currentError != null) {
                    Text(
                        text = currentError,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = panelInset(22.dp), vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = DesktopDestructive,
                        textAlign = TextAlign.Center,
                    )
                }

                DialogActions(
                    confirm = DesktopStrings["playlist_import_button_inspect", "Inspect & Match"],
                    confirmEnabled = rawInput.isNotBlank() && !working,
                    busy = working,
                    onConfirm = ::startImport,
                    onDismiss = handleDismiss,
                )
            }

            ImportPhase.REVIEW -> {
                val playlist = importedPlaylist
                val matchedCount = rows.count { it.isPlayable }
                val unmatchedCount = rows.size - matchedCount

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = panelInset(22.dp), vertical = 12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = playlist?.title ?: "Import Review",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                modifier = Modifier.padding(top = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = playlist?.provider?.name?.replace('_', ' ') ?: "IMPORT",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    color = DesktopAccent,
                                )
                                Text(
                                    text = "·",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = DesktopSecondary,
                                )
                                Text(
                                    text = "$matchedCount / ${rows.size} matched",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    color = if (matchedCount == rows.size) Color(0xFF81C784) else DesktopSecondary,
                                )
                                if (unmatchedCount > 0) {
                                    Text(
                                        text = "($unmatchedCount unresolved)",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                        color = DesktopDestructive,
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // Ordered list of tracks
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.04f)),
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().padding(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            itemsIndexed(rows, key = { idx, row -> "${row.entry.sourceIndex}_${idx}" }) { idx, row ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (!row.isPlayable) Color.White.copy(alpha = 0.05f)
                                            else Color.Transparent,
                                        )
                                        .clickable {
                                            selectedRowIndex = idx
                                            val query = "${row.entry.title} ${row.entry.artist}".trim()
                                            manualSearchQuery = query
                                            manualCandidates = row.availableCandidates
                                            phase = ImportPhase.MANUAL_RESOLUTION
                                            if (row.availableCandidates.size <= 1 && query.isNotBlank()) {
                                                searchingManualCandidates = true
                                                scope.launch {
                                                    val candidates = PlaylistImportService.searchCandidates(query)
                                                    manualCandidates = candidates
                                                    searchingManualCandidates = false
                                                }
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // 1-based source index
                                    Text(
                                        text = "${row.entry.sourceIndex + 1}.",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                        color = DesktopSecondary,
                                        modifier = Modifier.width(28.dp),
                                    )

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = row.entry.title,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = row.entry.artist + (row.entry.isrc?.let { " · ISRC: $it" } ?: ""),
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                            color = DesktopSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )

                                        // Status match annotation
                                        val matchLabel = when (val state = row.matchState) {
                                            is ImportMatchState.Matched -> {
                                                val m = if (state.method == ImportMatchState.Method.ISRC) "ISRC" else "Title/Artist"
                                                "✓ Match ($m): ${row.candidateSong?.title ?: ""}"
                                            }
                                            is ImportMatchState.ManualConfirmed -> "✓ Confirmed: ${row.candidateSong?.title ?: ""}"
                                            is ImportMatchState.Unmatched -> "⚠ Unmatched - click to resolve"
                                        }
                                        Text(
                                            text = matchLabel,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = when (row.matchState) {
                                                is ImportMatchState.Matched -> Color(0xFF81C784)
                                                is ImportMatchState.ManualConfirmed -> DesktopAccent
                                                is ImportMatchState.Unmatched -> DesktopDestructive
                                            },
                                        )
                                    }

                                    TextButton(
                                        onClick = {
                                            selectedRowIndex = idx
                                            manualSearchQuery = "${row.entry.title} ${row.entry.artist}".trim()
                                            manualCandidates = row.availableCandidates
                                            phase = ImportPhase.MANUAL_RESOLUTION
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            text = if (row.isPlayable) "Change" else "Resolve",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                            color = DesktopAccent,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (errorMessage != null) {
                        Text(
                            text = errorMessage ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = DesktopDestructive,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }

                    if (unmatchedCount > 0) {
                        Text(
                            text = DesktopStrings.format(
                                "playlist_import_unmatched_notice",
                                unmatchedCount,
                                fallback = "%1\$d unmatched track(s) will be excluded from the saved playlist.",
                            ),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = DesktopSecondary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = panelInset(16.dp), vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { phase = ImportPhase.ENTRY }, enabled = !working) {
                        Text(DesktopStrings["back", "Back"], color = DesktopSecondary)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = handleDismiss, enabled = !working) {
                            Text(DesktopStrings["cancel", "Cancel"], color = DesktopSecondary)
                        }
                        Button(
                            onClick = ::saveAndPersist,
                            enabled = matchedCount > 0 && !working,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DesktopAccent,
                                contentColor = Color.Black,
                            ),
                        ) {
                            if (working) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.Black,
                                )
                            } else {
                                Text("Save Playlist ($matchedCount)")
                            }
                        }
                    }
                }
            }

            ImportPhase.MANUAL_RESOLUTION -> {
                val selectedRow = rows.getOrNull(selectedRowIndex)
                if (selectedRow == null) {
                    phase = ImportPhase.REVIEW
                    return@DesktopDialogPanel
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = panelInset(22.dp), vertical = 12.dp),
                ) {
                    Text(
                        text = "Resolve Track #${selectedRow.entry.sourceIndex + 1}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )

                    // Source track summary card
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .padding(10.dp),
                    ) {
                        Column {
                            Text(
                                text = "Source: ${selectedRow.entry.title}",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = Color.White,
                            )
                            Text(
                                text = "Artist: ${selectedRow.entry.artist}" + (selectedRow.entry.isrc?.let { " · ISRC: $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = DesktopSecondary,
                            )
                            selectedRow.candidateSong?.let { curr ->
                                Text(
                                    text = "Current Candidate: ${curr.title} · ${curr.artist}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = DesktopAccent,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }

                    // Candidate Search Field
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "Search",
                            tint = DesktopSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f)) {
                            BasicTextField(
                                value = manualSearchQuery,
                                onValueChange = { manualSearchQuery = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                                cursorBrush = SolidColor(DesktopAccent),
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
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = DesktopAccent,
                            )
                        } else {
                            TextButton(
                                onClick = {
                                    if (manualSearchQuery.isNotBlank()) {
                                        searchingManualCandidates = true
                                        scope.launch {
                                            manualCandidates = PlaylistImportService.searchCandidates(manualSearchQuery)
                                            searchingManualCandidates = false
                                        }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text("Search", style = MaterialTheme.typography.labelSmall, color = DesktopAccent)
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Candidates List
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.04f)),
                    ) {
                        if (manualCandidates.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (searchingManualCandidates) "Searching catalogue candidates…"
                                    else "No candidates found. Enter keywords and tap Search.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = DesktopSecondary,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().padding(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                itemsIndexed(manualCandidates) { _, candidate ->
                                    val isSelected = selectedRow.candidateSong?.videoId == candidate.videoId
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(
                                                if (isSelected) DesktopAccent.copy(alpha = 0.15f)
                                                else Color.White.copy(alpha = 0.03f),
                                            )
                                            .clickable {
                                                val updated = PlaylistImportService.applyManualCandidate(selectedRow, candidate)
                                                rows = rows.toMutableList().also { it[selectedRowIndex] = updated }
                                                phase = ImportPhase.REVIEW
                                            }
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = candidate.title,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = candidate.artist + (candidate.durationText?.let { " · $it" } ?: ""),
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                                color = DesktopSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }

                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = "Selected",
                                                tint = DesktopAccent,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        } else {
                                            Text(
                                                text = "Select",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = DesktopAccent,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = panelInset(16.dp), vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            val updated = PlaylistImportService.applyManualCandidate(selectedRow, null)
                            rows = rows.toMutableList().also { it[selectedRowIndex] = updated }
                            phase = ImportPhase.REVIEW
                        }) {
                            Text("Mark Unmatched / Skip", color = DesktopDestructive)
                        }

                        if (selectedRow.matchState is ImportMatchState.ManualConfirmed) {
                            TextButton(onClick = {
                                val updated = PlaylistImportService.clearManualOverride(selectedRow)
                                rows = rows.toMutableList().also { it[selectedRowIndex] = updated }
                                phase = ImportPhase.REVIEW
                            }) {
                                Text("Reset to Auto", color = DesktopSecondary)
                            }
                        }
                    }

                    TextButton(onClick = { phase = ImportPhase.REVIEW }) {
                        Text(DesktopStrings["done", "Done"], color = DesktopAccent)
                    }
                }
            }
        }
    }
}
