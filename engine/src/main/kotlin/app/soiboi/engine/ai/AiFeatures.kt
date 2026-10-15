package app.soiboi.engine.ai

import app.soiboi.engine.library.Library
import app.soiboi.engine.library.PlaylistStore
import app.soiboi.engine.library.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Most songs sent to the model in one playlist request. Past this the wait is mostly the model reading. */
private const val MAX_CANDIDATES = 400

class AiPlaylist(val name: String, val songs: List<Song>)

class AiAlbumPick(val artist: String, val album: String, val why: String)

/** The two things AI does here: build a playlist from the library, and recommend albums to archive next. */
class AiFeatures(
    private val library: Library,
    private val playlists: PlaylistStore,
    private val tags: SongTags,
    private val config: () -> AiConfig?,
    /** Names of the artists played most, for recommendations; ListenBrainz supplies them when connected. */
    private val topPlayedArtists: suspend () -> List<String> = { emptyList() },
) {
    private val _progress = MutableStateFlow<String?>(null)
    /** What the Ask AI sheet shows while a request runs, or null when idle. */
    val progress: StateFlow<String?> = _progress.asStateFlow()

    /**
     * Asks for a playlist matching [request] from the local library, then saves it. A big library is
     * narrowed before anything is sent: when songs carry vibe tags, a tiny first request turns the
     * wording into a brief and the tags pick the songs that fit; otherwise a varied sample goes.
     */
    suspend fun makePlaylist(request: String): AiPlaylist {
        val cfg = config() ?: throw AiException("AI is not set up yet")
        // The same recording often sits in a library twice (single and album).
        val seen = HashSet<String>()
        val songs = library.songs.value.filter { !it.title.isNullOrBlank() && seen.add("${it.title}|${it.artist}".lowercase()) }
        if (songs.isEmpty()) throw AiException("Your library is empty")
        val tracks = songs.map { Track(it.title!!, it.artist ?: "", tags.of(it)) }

        try {
            var narrowed = false
            var candidates = tracks.indices.toList()
            if (tracks.size > MAX_CANDIDATES) {
                var brief: VibeBrief? = null
                if (tags.coverage(songs) >= 0.3) { _progress.value = "Reading your request"; brief = briefFor(cfg, request) }
                candidates = pickCandidates(tracks, brief ?: VibeBrief(), MAX_CANDIDATES)
                narrowed = brief != null
            }
            val ask = requestedCount(request)
            // Ask for a few over: the model counts loosely and undershoots more often than it overshoots.
            val want = ask?.let { minOf(Math.ceil(it.count * 1.2).toInt(), candidates.size) }
            val catalogue = candidates.withIndex().joinToString("\n") { (n, i) -> compactLine(n, tracks[i]) }

            _progress.value = "Choosing from ${candidates.size} songs"
            val answer = aiCompleteStreaming(
                cfg,
                system = "You build playlists from a personal music library. You may only choose tracks from the numbered list given. Reply with JSON only, " +
                    "no prose, in exactly this shape: {\"name\": \"short playlist title\", \"tracks\": [track numbers in play order]}. " +
                    "${if (want == null) "Pick between 15 and 40 tracks." else "Pick $want tracks."} " +
                    "Honour what the request rules out as firmly as what it asks for. Where a mood is given after a song it is how the song feels. " +
                    "Order them so the playlist flows.",
                prompt = "Request: $request\n\nLibrary (number | title | artist | moods):\n$catalogue",
                onText = { text -> picksSoFar(text).takeIf { it > 0 }?.let { _progress.value = "Picked $it songs" } },
            )
            val json = extractJsonObject(answer)
            val order = ArrayList<Int>(); val seenPicks = HashSet<Int>()
            for (n in (json["tracks"] as? JsonArray).orEmpty()) {
                val p = n as? JsonPrimitive ?: continue
                val i = p.intOrNull ?: p.contentOrNull?.toIntOrNull() ?: continue
                if (i < 0 || i >= candidates.size || !seenPicks.add(i)) continue
                order += i
            }
            if (order.isEmpty()) throw AiException("No tracks in your library matched that")
            // The model counts loosely. A short answer is topped up from songs the tags already vouch for; a long one is cut back to what was asked.
            if (ask != null && order.size < ask.count && narrowed) {
                var i = 0
                while (i < candidates.size && order.size < ask.count) { if (tracks[candidates[i]].vibe != null && seenPicks.add(i)) order += i; i++ }
            }
            val keep = if (ask != null && !ask.atLeast) order.take(ask.count) else order
            val picked = keep.map { songs[candidates[it]] }

            val title = (json["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: request
            val name = playlists.freeName(title)
            if (!playlists.create(name)) throw AiException("Could not save the playlist")
            playlists.add(name, picked.map { it.id })
            return AiPlaylist(name, picked)
        } finally {
            _progress.value = null
        }
    }

    /** The request as a [VibeBrief], from a tiny call that carries no library. Null when it fails, so the playlist still gets made from a sample. */
    private suspend fun briefFor(cfg: AiConfig, request: String): VibeBrief? = try {
        VibeBrief.fromJson(extractJsonObject(aiComplete(cfg, BRIEF_SYSTEM_PROMPT, "Request: $request")))
    } catch (_: AiException) {
        null
    }

    /** Albums worth archiving next, from the library and listening history. [request] narrows it ("more like this but Hindi", "90s") and may be empty. */
    suspend fun recommendAlbums(request: String = ""): List<AiAlbumPick> {
        val cfg = config() ?: throw AiException("AI is not set up yet")
        val owned = library.artists.value.sortedByDescending { it.songs.size }.take(40).map { it.name }
        val played = topPlayedArtists().take(25)
        val ownedAlbums = library.albums.value.take(400).joinToString("; ") { it.name }
        val answer = aiComplete(
            cfg,
            system = "You recommend albums to someone building an offline music archive. Recommend real albums that exist on Apple Music and that they do " +
                "not already own. Reply with JSON only, no prose, in exactly this shape: {\"albums\": [{\"artist\": \"...\", \"album\": \"exact album title\", " +
                "\"why\": \"one short sentence\"}]}. Give 12 albums.",
            prompt = "${if (request.isBlank()) "Recommend what they would love next." else "Request: $request"}\n\n" +
                "Artists they own most: ${owned.joinToString(", ")}\n" +
                (if (played.isEmpty()) "" else "Artists they play most: ${played.joinToString(", ")}\n") +
                "Albums already owned (do not repeat): $ownedAlbums",
        )
        val picks = (extractJsonObject(answer)["albums"] as? JsonArray).orEmpty().mapNotNull { a ->
            val o = a as? JsonObject ?: return@mapNotNull null
            val artist = (o["artist"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val album = (o["album"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (artist.isEmpty() || album.isEmpty()) null else AiAlbumPick(artist, album, (o["why"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty())
        }
        if (picks.isEmpty()) throw AiException("No recommendations came back")
        return picks
    }
}
