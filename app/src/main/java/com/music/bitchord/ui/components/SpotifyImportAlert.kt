package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.Song
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi

/**
 * Paste a Spotify playlist link, match its songs on YouTube Music, keep the result.
 *
 * The same frosted card as the addon editor, rather than a Material dialog.
 * The "who can see it" row exists only for [signedIn]: it is a YouTube Music
 * setting, and a playlist kept on this device has no audience to choose.
 * Songs that matched nothing are listed once the playlist is made, instead of
 * being dropped without a word.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun SpotifyImportAlert(
    hazeState: HazeState,
    signedIn: Boolean,
    /** Called once the songs are matched; [privacy] is meaningful only when signed in. */
    onImported: (title: String, privacy: PlaylistPrivacy, songs: List<Song>) -> Unit,
    onDismiss: () -> Unit,
) {
    PlaylistImportAlert(
        hazeState = hazeState,
        signedIn = signedIn,
        onImported = onImported,
        onDismiss = onDismiss,
    )
}
