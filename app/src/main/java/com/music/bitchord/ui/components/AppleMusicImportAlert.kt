package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.Song
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun AppleMusicImportAlert(
    hazeState: HazeState,
    signedIn: Boolean,
    onImported: (title: String, privacy: PlaylistPrivacy, songs: List<Song>) -> Unit,
    onDismiss: () -> Unit,
) {
    PlaylistImportAlert(
        hazeState = hazeState,
        signedIn = signedIn,
        appleMusic = true,
        onImported = onImported,
        onDismiss = onDismiss,
    )
}
