package app.soiboi.engine.apple

import app.soiboi.engine.core.EngineLog
import app.soiboi.engine.core.bareSongTitle
import app.soiboi.engine.core.normaliseForMatch
import app.soiboi.engine.sources.Http
import app.soiboi.engine.sources.arr
import app.soiboi.engine.sources.get
import app.soiboi.engine.sources.int
import app.soiboi.engine.sources.long
import app.soiboi.engine.sources.obj
import app.soiboi.engine.sources.str
import app.soiboi.engine.sources.urlWith
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

private const val HOST = "https://itunes.apple.com"

data class AppleMatch(val url: String, val album: String? = null, val artwork: String? = null, val previewUrl: String? = null, val trackId: String? = null)

/** An album in Apple's catalog, with enough to describe it before download. */
data class AppleAlbum(
    val id: String, val title: String, val artist: String, val url: String? = null, val artwork: String? = null,
    val trackCount: Int? = null, val releaseYear: String? = null, val genre: String? = null,
)

/** One track of a catalog album. */
data class AppleTrack(val title: String, val url: String, val trackNumber: Int? = null, val durationMs: Long? = null, val previewUrl: String? = null)

/** One song row offered when the user picks a match by hand. */
data class AppleCandidate(
    val title: String, val artist: String, val url: String, val album: String? = null, val year: String? = null,
    val durationMs: Long? = null, val artwork: String? = null, val previewUrl: String? = null, val trackId: String? = null,
) {
    val match: AppleMatch get() = AppleMatch(url, album, artwork, previewUrl, trackId)
}

/** Joined on NUL because it cannot appear in a tag, so no two artist/title pairs collide. */
private fun key(artist: String, title: String) = "${artist.lowercase().trim()}\u0000${title.lowercase().trim()}"

/** Higher-resolution artwork than the API's default 100px thumbnail. */
private fun artworkAt(url: String?, size: Int) = url?.replace("100x100bb", "${size}x${size}bb")

private val creditSplit = Regex(",|&|\\band\\b|\\bfeat\\.?|\\bft\\.?|\\bx\\b|\\bwith\\b", RegexOption.IGNORE_CASE)
private fun artistsOf(credit: String) = credit.split(creditSplit).map(::normaliseForMatch).filter { it.isNotEmpty() }

/**
 * Whether two credits share an artist. Sources credit differently and spell differently, so any artist
 * of one may match any of the other, by containment or within a letter or two, while an unrelated
 * artist does not. A credit with no Latin letters says nothing either way, so it matches.
 */
fun sameArtist(want: String, candidate: String): Boolean {
    val wants = artistsOf(want)
    if (wants.isEmpty()) return true
    val haves = artistsOf(candidate)
    for (w in wants) for (h in haves) {
        if (w == h) return true
        if (w.length >= 4 && h.length >= 4 && (w.contains(h) || h.contains(w))) return true
        if (nearlySame(w, h)) return true
    }
    return false
}

/** Two letters apart for names of eight or more, one for five to seven, none below that ("Sia" is not "SZA"). */
private fun nearlySame(a: String, b: String): Boolean {
    val shorter = minOf(a.length, b.length)
    val allowed = if (shorter >= 8) 2 else if (shorter >= 5) 1 else 0
    if (allowed == 0 || kotlin.math.abs(a.length - b.length) > allowed) return false
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val current = IntArray(b.length + 1); current[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
        }
        previous = current
    }
    return previous[b.length] <= allowed
}

private val editionWords = Regex("deluxe|anniversary|expanded|remaster|special edition|bonus", RegexOption.IGNORE_CASE)

private fun releaseCost(row: JsonObject): Int {
    val collection = row["collectionName"].str() ?: ""
    val track = row["trackName"].str() ?: ""
    var cost = 0
    if ((row["collectionArtistName"].str() ?: "").lowercase() == "various artists") cost += 8
    if (bareSongTitle(track) != track.trim() && Regex("from\\s", RegexOption.IGNORE_CASE).containsMatchIn(track)) cost += 4
    if (Regex("\\s-\\s(Single|EP)$").containsMatchIn(collection)) cost += 2
    if (editionWords.containsMatchIn(collection)) cost += 1
    return cost
}

/**
 * The row for the song's own release among iTunes Search [rows]. Apple sells a song on its original
 * album and again on every compilation, and search often ranks a compilation first. Prefer, in order:
 * not Various Artists, a title without a compilation's `(From "Film")` credit, an album over a single
 * or EP, and the plain edition over a deluxe one. Ties keep Apple's order.
 *
 * With [artist] and [title], rows for other songs are left out first, and if none are left there is
 * no match: Apple's top hit is then usually someone else's cover, and "not in the catalog" is honest.
 */
fun pickOriginalRelease(rows: List<JsonObject>, artist: String? = null, title: String? = null): JsonObject? {
    var pool = rows
    if (artist != null && title != null) {
        val wantTitle = normaliseForMatch(bareSongTitle(title))
        val same = rows.filter {
            normaliseForMatch(bareSongTitle(it["trackName"].str() ?: "")) == wantTitle && sameArtist(artist, it["artistName"].str() ?: "")
        }
        if (same.isEmpty()) return null
        pool = same
    }
    return pool.minByOrNull { releaseCost(it) } // minBy keeps the first of equal costs
}

private fun albumFrom(json: JsonObject) = AppleAlbum(
    id = json["collectionId"].long().toString(),
    title = json["collectionName"].str() ?: "",
    artist = json["artistName"].str() ?: "",
    url = json["collectionViewUrl"].str(),
    artwork = artworkAt(json["artworkUrl100"].str(), 600),
    trackCount = json["trackCount"].int(),
    // Only the year: the full ISO timestamp is noise in a subtitle.
    releaseYear = json["releaseDate"].str()?.substringBefore('-'),
    genre = json["primaryGenreName"].str(),
)

/**
 * Resolves tracks against Apple's catalog through the public iTunes Search API (no key, no account),
 * so it works standalone on any device. Results are cached per session, since resolution is a network
 * round trip per track. Transient failures are never cached: a timeout would make "not found"
 * permanent when the track reappears seconds later on a retry.
 */
class AppleCatalog(private val storefront: () -> String) {
    private val matches = ConcurrentHashMap<String, AppleMatch?>()
    private val nullMatch = AppleMatch("")
    private val albums = ConcurrentHashMap<String, AppleAlbum?>()
    private val albumTracks = ConcurrentHashMap<String, List<AppleTrack>>()
    private val artistAlbums = ConcurrentHashMap<String, List<AppleAlbum>>()

    private suspend fun getJson(url: String, timeoutMs: Long = 12_000): JsonObject? = try {
        val response = kotlinx.coroutines.withTimeout(timeoutMs) { Http.client.get(url) }
        if (response.status == HttpStatusCode.OK) Http.json.parseToJsonElement(response.bodyAsText()).obj() else null
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        null
    } catch (e: Exception) {
        EngineLog.output("apple: $e"); null
    }

    private fun results(body: JsonObject?) = body?.get("results").arr()?.mapNotNull { it.obj() }

    /** Song rows for [term], up to 25. Empty when none; null when the search failed even after one retry. */
    private suspend fun searchRows(term: String, storefront: String): List<JsonObject>? {
        val url = urlWith("$HOST/search", mapOf("term" to term, "entity" to "song", "limit" to "25", "country" to storefront))
        for (attempt in 0..1) {
            val body = getJson(url)
            val rows = results(body)
            // A non-200, or an empty answer for a track that exists, is worth one retry before believing it.
            if (rows.isNullOrEmpty()) {
                if (attempt == 0) { delay(500); continue }
                return if (body == null) null else emptyList()
            }
            return rows
        }
        return null
    }

    private fun matchOf(row: JsonObject, url: String) = AppleMatch(
        url = url,
        album = row["collectionName"].str(),
        // The API returns a 100px thumbnail; asking for 300 keeps card artwork from looking soft on a phone.
        artwork = row["artworkUrl100"].str()?.replace("100x100bb", "300x300bb"),
        previewUrl = row["previewUrl"].str(),
        trackId = row["trackId"].long()?.toString(),
    )

    /** Best Apple catalog match for [artist] + [title], or null. The storefront matters: a URL from the wrong one 404s. */
    suspend fun resolveTrack(artist: String, title: String, storefront: String? = null): AppleMatch? {
        val sf = storefront ?: this.storefront()
        if (artist.isBlank() || title.isBlank()) return null
        val cacheKey = "$sf\u0000${key(artist, title)}"
        matches[cacheKey]?.let { return it.takeIf { m -> m !== nullMatch } }
        // The full credit first, then narrower searches: a long credit can pull in a different song entirely.
        val bare = bareSongTitle(title)
        val terms = linkedSetOf("$artist $title", "${artist.split(creditSplit).first().trim()} $bare", bare)
        for (term in terms) {
            val rows = searchRows(term, sf) ?: return null // failed: worth asking again later, so nothing is cached
            val first = pickOriginalRelease(rows, artist, title)
            val url = first?.get("trackViewUrl").str()
            if (first == null || url == null) continue
            return matchOf(first, url).also { matches[cacheKey] = it }
        }
        matches[cacheKey] = nullMatch
        return null
    }

    /**
     * Resolves a track by ISRC, the one identifier that bridges platforms: Spotify, YouTube Music,
     * ListenBrainz and Apple Music all tag tracks with it, and Apple's `/lookup` accepts it directly.
     */
    suspend fun resolveTrackByIsrc(isrc: String, storefront: String? = null): AppleMatch? {
        val sf = storefront ?: this.storefront()
        if (isrc.isBlank()) return null
        val cacheKey = "$sf\u0000isrc:${isrc.lowercase().trim()}"
        matches[cacheKey]?.let { return it.takeIf { m -> m !== nullMatch } }
        val body = getJson(urlWith("$HOST/lookup", mapOf("isrc" to isrc, "country" to sf))) ?: return null
        val tracks = results(body).orEmpty().filter { it["wrapperType"].str() == "track" }
        val row = if (tracks.isEmpty()) null else pickOriginalRelease(tracks)
        val url = row?.get("trackViewUrl").str()
        if (row == null || url == null) { matches[cacheKey] = nullMatch; return null }
        return matchOf(row, url).also { matches[cacheKey] = it }
    }

    /** Best catalog match for an album, or null. Retries once, like [resolveTrack]. */
    suspend fun resolveAlbum(artist: String, album: String, storefront: String? = null): AppleAlbum? {
        val sf = storefront ?: this.storefront()
        if (album.isBlank()) return null
        val cacheKey = "$sf\u0000${key(artist, album)}"
        if (albums.containsKey(cacheKey)) return albums[cacheKey]
        for (attempt in 0..1) {
            val body = getJson(urlWith("$HOST/search", mapOf("term" to "$artist $album".trim(), "entity" to "album", "limit" to "1", "country" to sf)))
                ?: if (attempt == 0) continue else return null
            val first = results(body)?.firstOrNull()
            val match = first?.takeIf { it["collectionId"] != null }?.let(::albumFrom)
            if (match == null && attempt == 0) { delay(500); continue }
            val found = match ?: albumViaSongs(artist, album, sf)
            albums[cacheKey] = found
            return found
        }
        return null
    }

    /** Album search misses some catalog albums outright, while song search finds their tracks. */
    private suspend fun albumViaSongs(artist: String, album: String, sf: String): AppleAlbum? {
        fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        val wantAlbum = norm(album); val wantArtist = norm(artist)
        val rows = results(getJson(urlWith("$HOST/search", mapOf("term" to album, "entity" to "song", "limit" to "50", "country" to sf)))).orEmpty()
        for (r in rows) {
            val by = norm(r["artistName"].str() ?: "")
            val artistOk = wantArtist.isEmpty() || by.contains(wantArtist) || wantArtist.contains(by)
            if (norm(r["collectionName"].str() ?: "") != wantAlbum || !artistOk || r["collectionId"] == null) continue
            val lookup = results(getJson(urlWith("$HOST/lookup", mapOf("id" to r["collectionId"].long().toString(), "country" to sf))))?.firstOrNull()
            return lookup?.let(::albumFrom)
        }
        return null
    }

    /** The tracks of a catalog album, in running order. The lookup lists the album itself first, so that row is dropped. */
    suspend fun albumTracks(collectionId: String, storefront: String? = null): List<AppleTrack>? {
        albumTracks[collectionId]?.let { return it }
        val rows = results(getJson(urlWith("$HOST/lookup", mapOf("id" to collectionId, "entity" to "song", "country" to (storefront ?: this.storefront())))))
        if (rows.isNullOrEmpty()) return null
        val tracks = rows.filter { it["wrapperType"].str() == "track" }.mapNotNull { r ->
            val url = r["trackViewUrl"].str() ?: return@mapNotNull null
            AppleTrack(r["trackName"].str() ?: "", url, r["trackNumber"].int(), r["trackTimeMillis"].long(), r["previewUrl"].str())
        }
        albumTracks[collectionId] = tracks
        return tracks
    }

    /** An artist's albums, newest first. Searched by name: ListenBrainz supplies a MusicBrainz id Apple has never heard of. */
    suspend fun artistAlbums(artist: String, storefront: String? = null, limit: Int = 25): List<AppleAlbum>? {
        val sf = storefront ?: this.storefront()
        if (artist.isBlank()) return null
        val cacheKey = "$sf\u0000${key(artist, "albums")}"
        artistAlbums[cacheKey]?.let { return it }
        val found = results(getJson(urlWith("$HOST/search", mapOf("term" to artist, "entity" to "musicArtist", "limit" to "1", "country" to sf))))?.firstOrNull()
        val artistId = found?.get("artistId").long()?.toString() ?: return null
        val rows = results(getJson(urlWith("$HOST/lookup", mapOf("id" to artistId, "entity" to "album", "limit" to "$limit", "country" to sf)))) ?: return null
        // Newest first: an artist page opening on a decades-old debut reads as stale.
        val albums = rows.filter { it["wrapperType"].str() == "collection" }.map(::albumFrom).sortedByDescending { it.releaseYear ?: "" }
        artistAlbums[cacheKey] = albums
        return albums
    }

    /** Up to 25 songs for [term], in Apple's order and unfiltered: the user chooses, so covers and remixes are all on offer. */
    suspend fun searchSongs(term: String, storefront: String? = null): List<AppleCandidate>? {
        if (term.isBlank()) return emptyList()
        return searchRows(term.trim(), storefront ?: this.storefront())?.mapNotNull(::candidateFromRow)
    }

    /** What a pasted song link is, from the catalog; null when it is not a song link or the lookup failed. */
    suspend fun lookupSong(url: String, storefront: String? = null): AppleCandidate? {
        val id = songIdFromUrl(url) ?: return null
        return results(getJson(urlWith("$HOST/lookup", mapOf("id" to id, "country" to (storefront ?: this.storefront())))))
            ?.firstOrNull()?.let(::candidateFromRow)
    }

    companion object {
        /** An iTunes song row as a candidate; null for rows that are not songs or have no store link. */
        fun candidateFromRow(row: JsonObject): AppleCandidate? {
            val url = row["trackViewUrl"].str(); val title = row["trackName"].str()
            val kind = row["kind"].str()
            if (kind != null && kind != "song") return null
            if (url == null || title == null) return null
            return AppleCandidate(
                title, row["artistName"].str() ?: "", url, row["collectionName"].str(), row["releaseDate"].str()?.substringBefore('-'),
                row["trackTimeMillis"].long(), artworkAt(row["artworkUrl100"].str(), 300), row["previewUrl"].str(), row["trackId"].long()?.toString(),
            )
        }

        /** The song id in an Apple Music link: `?i=` on an album link, or the last path part of a `/song/` link. */
        fun songIdFromUrl(url: String): String? {
            val uri = try { java.net.URI(url.trim()) } catch (_: Exception) { return null }
            if (uri.host?.endsWith("music.apple.com") != true) return null
            (uri.rawQuery ?: "").split('&').map { it.split('=', limit = 2) }.firstOrNull { it[0] == "i" }?.getOrNull(1)
                ?.takeIf { Regex("^\\d+$").matches(it) }?.let { return it }
            val parts = uri.path.split('/').filter { it.isNotEmpty() }
            return if ("song" in parts && parts.isNotEmpty() && Regex("^\\d+$").matches(parts.last())) parts.last() else null
        }
    }
}
