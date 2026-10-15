package app.soiboi.engine.discovery

import app.soiboi.engine.apple.AppleCatalog
import app.soiboi.engine.apple.AppleMatch
import app.soiboi.engine.core.ownedSongKey
import app.soiboi.engine.sources.ExternalPlaylist
import app.soiboi.engine.sources.ExternalTrack
import app.soiboi.engine.sources.PlaylistSources
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

/** A discovery track, with whatever Apple resolution found. */
data class DiscoveryTrack(
    val title: String, val artist: String, val album: String? = null, val artwork: String? = null, val previewUrl: String? = null,
    val appleUrl: String? = null,
    /** Set when Apple has no match: such a track cannot be archived, so the gap is surfaced rather than discovered at download time. */
    val warning: String? = null,
    /** The user said this track has no right match; it is not offered or flagged again. */
    val userSkipped: Boolean = false,
) {
    val isResolved: Boolean get() = !appleUrl.isNullOrEmpty()
}

/** How many Apple lookups run at once: a weekly playlist holds fifty, and one at a time took over a minute. Six is a few seconds without hammering a public API. */
private const val RESOLVE_CONCURRENCY = 6

/**
 * Discovery, end to end, with no server. A source supplies playlists and tracks; Apple's catalog
 * resolves each track to a URL, artwork and preview. Resolution is the expensive half, one network
 * call per track, so results are cached per session and resolved lazily: a card needs four covers,
 * the full list waits until a playlist is opened. Caches are keyed by `sourceId:playlistId`, never a
 * bare id, because a hex string is both a plausible MusicBrainz id and a plausible YouTube one.
 */
class Discovery(
    private val sources: PlaylistSources,
    private val apple: AppleCatalog,
    private val manual: ManualResolution,
    /** The signed-in account's storefront, asked of Apple once and remembered. */
    private val storefront: suspend () -> String,
) {
    private val rawTracks = ConcurrentHashMap<String, List<ExternalTrack>>()
    private val resolved = ConcurrentHashMap<String, List<DiscoveryTrack>>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<List<DiscoveryTrack>?>>()

    fun cached(playlist: ExternalPlaylist): List<DiscoveryTrack>? = resolved[playlist.key]

    private suspend fun sourceTracks(playlist: ExternalPlaylist): List<ExternalTrack> =
        sources.byId(playlist.sourceId)?.tracks(playlist.id).orEmpty()

    /** Drops resolved playlists so a pick or skip made by hand shows next time. Catalog lookups stay cached, so this is cheap. */
    fun forgetMatches() = resolved.clear()

    /** How many tracks a playlist has, without resolving any of them. */
    suspend fun trackCount(playlist: ExternalPlaylist): Int? {
        rawTracks[playlist.key]?.let { return it.size }
        val raw = sourceTracks(playlist)
        if (raw.isEmpty()) return null
        rawTracks[playlist.key] = raw
        return raw.size
    }

    /**
     * Playlist tracks with Apple resolution applied. [limit] bounds how many are resolved, because each costs
     * a request. [onProgress] is called as each batch lands with the tracks resolved so far and the playlist's
     * true length, so a long playlist does not look stalled.
     */
    suspend fun resolveTracks(
        playlist: ExternalPlaylist, limit: Int? = null, onProgress: ((List<DiscoveryTrack>, Int) -> Unit)? = null,
    ): List<DiscoveryTrack>? {
        val sf = storefront()
        val cached = resolved[playlist.key]
        val full = rawTracks[playlist.key]?.size
        // A card prefetches only four covers, so a four-entry cache is a partial result, not the playlist. The true
        // length must be known before a cache can be called complete.
        val complete = cached != null && full != null && cached.size >= full
        if (cached != null && (if (limit == null) complete else cached.size >= limit)) return cached

        // A second caller waits for that work, then re-checks with its own limit.
        inFlight[playlist.key]?.let { it.await(); return resolveTracks(playlist, limit, onProgress) }

        val deferred = CompletableDeferred<List<DiscoveryTrack>?>()
        if (inFlight.putIfAbsent(playlist.key, deferred) != null) return resolveTracks(playlist, limit, onProgress)
        try {
            return resolveAndCache(playlist, limit, sf, onProgress).also { deferred.complete(it) }
        } catch (e: Throwable) {
            deferred.completeExceptionally(e); throw e
        } finally {
            inFlight.remove(playlist.key)
        }
    }

    private suspend fun resolveAndCache(playlist: ExternalPlaylist, limit: Int?, sf: String, onProgress: ((List<DiscoveryTrack>, Int) -> Unit)?): List<DiscoveryTrack>? {
        val raw = rawTracks[playlist.key] ?: sourceTracks(playlist)
        if (raw.isEmpty()) return null
        rawTracks[playlist.key] = raw
        val slice = if (limit == null) raw else raw.take(limit)
        val out = ArrayList<DiscoveryTrack>()

        // Chunked rather than a worker pool: a chunk keeps playlist order without index bookkeeping, and order is what the user sees.
        for (chunk in slice.chunked(RESOLVE_CONCURRENCY)) {
            val matches: List<AppleMatch?> = coroutineScope {
                chunk.map { track ->
                    async {
                        val choice = manual.overrideFor(track.artist, track.title)
                        when {
                            // The user's own pick wins over every search, and a skip means there is nothing to look for.
                            choice != null -> if (choice == SKIP_OVERRIDE) null else AppleMatch(url = choice)
                            !track.isrc.isNullOrEmpty() -> apple.resolveTrackByIsrc(track.isrc, sf) ?: apple.resolveTrack(track.artist, track.title, sf)
                            else -> apple.resolveTrack(track.artist, track.title, sf)
                        }
                    }
                }.awaitAll()
            }
            chunk.forEachIndexed { i, track ->
                val match = matches[i]
                val skipped = match == null && manual.overrideFor(track.artist, track.title) == SKIP_OVERRIDE
                out += DiscoveryTrack(
                    track.title, track.artist, match?.album, match?.artwork, match?.previewUrl, match?.url, userSkipped = skipped,
                    warning = if (skipped) "Skipped: you chose no match" else if (match == null) "Could not match to Apple Music catalog" else null,
                )
            }
            onProgress?.invoke(out.toList(), slice.size)
        }
        // Keep the longer result: a later full resolve should not be replaced by an earlier four-track preview.
        val existing = resolved[playlist.key]
        if (existing == null || out.size > existing.size) resolved[playlist.key] = out
        return resolved[playlist.key]
    }
}
