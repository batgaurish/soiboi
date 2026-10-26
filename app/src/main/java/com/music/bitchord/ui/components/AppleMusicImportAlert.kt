package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.playlistimport.ImportRowState
import com.music.bitchord.data.playlistimport.ImportedPlaylist
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun AppleMusicImportAlert(
    hazeState: HazeState,
    signedIn: Boolean,
    onImported: ((title: String, privacy: PlaylistPrivacy, songs: List<Song>) -> Unit)? = null,
    onImportedWithDetails: ((source: ImportedPlaylist, rows: List<ImportRowState>, title: String, privacy: PlaylistPrivacy, songs: List<Song>) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    PlaylistImportAlert(
        hazeState = hazeState,
        signedIn = signedIn,
        appleMusic = true,
        onImported = onImported ?: { _, _, _ -> },
        onImportedWithDetails = onImportedWithDetails,
        onDismiss = onDismiss,
    )
}
