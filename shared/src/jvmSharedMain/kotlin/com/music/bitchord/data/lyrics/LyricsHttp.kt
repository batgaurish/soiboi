package com.music.bitchord.data.lyrics

import com.music.bitchord.data.Http
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Shared plumbing for the lyric providers.
 *
 * They are all raced against each other by [LyricsRepository], so a provider
 * that hangs holds up the whole lookup. [LYRICS_TIMEOUT_SECONDS] is deliberately
 * far shorter than [Http]'s stream-oriented timeouts: a lyric that arrives
 * after the second chorus is of no use to anyone, and the fallbacks behind it
 * are the better answer.
 */
private const val LYRICS_TIMEOUT_SECONDS = 6L

internal const val LYRICS_AGENT = "BitChord (https://github.com/bitchord)"

val lyricsJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal val lyricsClient by lazy {
    // Derived from the shared client, so the connection pool and DNS stay
    // common — only the deadline differs.
    Http.client.newBuilder()
        .callTimeout(LYRICS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .build()
}

// PaxSenix's authenticated routes often have to query an upstream catalogue
// before answering. Keep the fast deadline for the ordinary providers, but
// match PaxSenix's own client timeout here so valid requests are not discarded
// while its backend is still resolving a track.
internal val authenticatedLyricsClient by lazy {
    lyricsClient.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
}

internal var lyricsGetOverride: ((String) -> String?)? = null
internal var lyricsGetBearerOverride: ((String, String) -> String?)? = null
internal var lyricsGetAuthorizedOverride: ((String, String) -> String?)? = null

/** Body of a successful GET, or null for any failure at all. */
internal fun lyricsGet(url: String): String? = runCatching {
    lyricsGetOverride?.let { return it(url) }
    val request = Request.Builder().url(url)
        .header("User-Agent", LYRICS_AGENT)
        .header("Accept", "application/json")
        .build()
    lyricsClient.newCall(request).execute().use { response ->
        if (response.isSuccessful) response.body?.string() else null
    }
}.getOrElse { error ->
    if (error is kotlinx.coroutines.CancellationException) throw error
    null
}

/** Body of an authenticated provider GET without service-specific browser headers. */
internal fun lyricsGetBearer(url: String, bearer: String): String? = runCatching {
    lyricsGetBearerOverride?.let { return it(url, bearer) }
    if (bearer.isBlank()) return null
    val request = Request.Builder().url(url)
        .header("User-Agent", LYRICS_AGENT)
        .header("Accept", "application/json, text/plain, */*")
        .header("Authorization", "Bearer $bearer")
        .build()
    authenticatedLyricsClient.newCall(request).execute().use { response ->
        if (response.isSuccessful) response.body?.string() else null
    }
}.getOrElse { error ->
    if (error is kotlinx.coroutines.CancellationException) throw error
    null
}

/**
 * [lyricsGet], with a bearer token and the headers Apple's own web player
 * sends alongside one — `amp-api.music.apple.com` answers a token with no
 * `Origin` at all the same way it answers a wrong one, with a 403.
 */
internal fun lyricsGetAuthorized(url: String, bearer: String): String? = runCatching {
    lyricsGetAuthorizedOverride?.let { return it(url, bearer) }
    val request = Request.Builder().url(url)
        .header("User-Agent", LYRICS_AGENT)
        .header("Accept", "application/json")
        .header("Authorization", "Bearer $bearer")
        .header("Origin", "https://music.apple.com")
        .header("Referer", "https://music.apple.com/")
        .build()
    lyricsClient.newCall(request).execute().use { response ->
        if (response.isSuccessful) response.body?.string() else null
    }
}.getOrElse { error ->
    if (error is kotlinx.coroutines.CancellationException) throw error
    null
}
