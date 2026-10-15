package app.soiboi.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.soiboi.engine.Engine
import app.soiboi.engine.library.Song
import app.soiboi.engine.player.PlayMode
import app.soiboi.engine.smart.SmartPlaylist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the user is. Detail routes carry what they show, so back always returns to the same list. */
sealed interface Route {
    data object Today : Route
    data class Library(val tab: LibraryTab = LibraryTab.Songs) : Route
    data class Album(val name: String) : Route
    data class Artist(val name: String) : Route
    data class Playlist(val name: String) : Route
    data class Smart(val name: String) : Route
    data class Mood(val name: String) : Route
    data object Search : Route
    data object Discover : Route
    data object Downloads : Route
    data object Sources : Route
    data object Settings : Route
    data class Import(val link: String = "") : Route
}

enum class LibraryTab(val label: String) { Songs("Songs"), Albums("Albums"), Artists("Artists"), Playlists("Playlists") }

/** The five places the rail and the bottom bar offer. Detail screens belong to one of them. */
enum class Place(val label: String) { Today("Today"), Library("Library"), Discover("Discover"), Downloads("Downloads"), Sources("Sources"), Settings("Settings") }

fun Route.place(): Place = when (this) {
    Route.Today -> Place.Today
    is Route.Library, is Route.Album, is Route.Artist, is Route.Playlist, is Route.Smart, is Route.Mood, Route.Search -> Place.Library
    Route.Discover, is Route.Import -> Place.Discover
    Route.Downloads -> Place.Downloads
    Route.Sources -> Place.Sources
    Route.Settings -> Place.Settings
}

/** What a song's "more" menu is about, so one sheet serves every list. */
class SongMenu(val song: Song, val inPlaylist: String? = null)

/** Plain UI state and the actions screens call. Everything lasting lives in the [Engine]; this holds only where the user is and what is open. */
class AppController(val engine: Engine, val scope: CoroutineScope, val platform: PlatformUi) {
    val stack = mutableStateListOf<Route>(Route.Today)
    val route: Route get() = stack.last()

    var nowPlayingOpen by mutableStateOf(false)
    var queueOpen by mutableStateOf(false)
    var songMenu by mutableStateOf<SongMenu?>(null)
    var askAiOpen by mutableStateOf(false)
    var smartEditor by mutableStateOf<SmartPlaylist?>(null)
    var smartEditorOriginal by mutableStateOf<String?>(null)

    private val _message = MutableStateFlow<String?>(null)
    /** One line of feedback shown briefly at the bottom of the shell. */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun toast(text: String) { _message.value = text }
    fun clearToast() { _message.value = null }

    fun go(r: Route) {
        // Top-level places replace the stack so back does not wander through tabs.
        if (r.isTopLevel()) { stack.clear(); stack.add(r) } else if (stack.lastOrNull() != r) stack.add(r)
        nowPlayingOpen = false
    }

    fun goPlace(p: Place) = go(when (p) {
        Place.Today -> Route.Today; Place.Library -> Route.Library(); Place.Discover -> Route.Discover
        Place.Downloads -> Route.Downloads; Place.Sources -> Route.Sources; Place.Settings -> Route.Settings
    })

    fun back(): Boolean {
        if (nowPlayingOpen) { nowPlayingOpen = false; return true }
        if (stack.size > 1) { stack.removeAt(stack.lastIndex); return true }
        return false
    }

    private fun Route.isTopLevel() = this == Route.Today || this is Route.Library || this == Route.Discover || this == Route.Downloads || this == Route.Sources || this == Route.Settings

    // --- playback shortcuts ---

    fun play(songs: List<Song>, index: Int = 0, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        scope.launch {
            if (shuffle) engine.player.setQueue(songs, PlayMode.Shuffle) else engine.player.setQueue(songs, startAt = index)
        }
    }

    fun playNext(song: Song) { if (engine.player.insertNext(song)) toast("Plays next: ${song.displayTitle}") else toast("Already playing") }
    fun addToQueue(song: Song) { if (engine.player.addLast(song)) toast("Added to queue") else toast("Already in the queue") }
    fun toggleFavorite(song: Song) { scope.launch { engine.playlists.toggleFavorite(song.id) } }

    fun addToPlaylist(song: Song, playlist: String) { scope.launch { engine.playlists.add(playlist, listOf(song.id)); toast("Added to $playlist") } }

    fun newPlaylistWith(song: Song?, name: String) {
        scope.launch {
            val ok = engine.playlists.create(name.trim())
            if (!ok) { toast("A playlist called $name already exists"); return@launch }
            if (song != null) engine.playlists.add(name.trim(), listOf(song.id))
            toast("Created $name")
        }
    }
}
