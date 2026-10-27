package com.music.bitchord.data.accounts

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class AppleMusicSessionTest {
    @Test fun libraryPlaylistAndTrackRelationshipsFollowNextPages() = runBlocking {
        val token = jwt("{\"alg\":\"ES256\",\"kid\":\"WebPlayKid\"}", "{\"iss\":\"AMPWebPlay\"}")
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
            if (request.url.host == "music.apple.com" && request.url.encodedPath == "/") {
                return@addInterceptor response(request, 200, "<script src=\"/assets/index~abc.js\"></script>")
            }
            if (request.url.host == "music.apple.com" && request.url.encodedPath.endsWith("index~abc.js")) {
                return@addInterceptor response(request, 200, token)
            }
            check(request.header("Music-User-Token") == "session")
            check(request.header("Origin") == "https://music.apple.com")
            val body = when (request.url.encodedPath) {
                "/v1/me/library/playlists" -> if (request.url.queryParameter("offset") == null) {
                    """{"data":[{"id":"p1","attributes":{"name":"Mix 1","trackCount":2}}],"next":"https://api.music.apple.com/v1/me/library/playlists?limit=100&offset=100"}"""
                } else """{"data":[{"id":"p2","attributes":{"name":"Mix 2"}}]}"""
                "/v1/me/library/playlists/p1/tracks" -> if (request.url.queryParameter("offset") == null) {
                    """{"data":[{"id":"t1","attributes":{"name":"Song 1","artistName":"Artist","playParams":{"catalogId":"c1"}}}],"next":"https://api.music.apple.com/v1/me/library/playlists/p1/tracks?limit=100&offset=100"}"""
                } else """{"data":[{"id":"t2","attributes":{"name":"Song 2","artistName":"Artist"}}]}"""
                else -> error("Unexpected Apple API path: ${request.url.encodedPath}")
            }
            response(request, 200, body)
        }.build()

        val session = AppleMusicSession(
            cookieHeader = { "media-user-token=session" },
            storefront = { "in" },
            client = client,
        )

        val playlists = session.libraryPlaylists()
        val tracks = session.playlistTracks("p1")
        assertEquals(listOf("p1", "p2"), playlists.map { it.id })
        assertEquals(listOf("t1", "t2"), tracks.map { it.id })
        assertTrue(requests.any { it.endsWith("offset=100") })
    }

    @Test fun libraryRequestFailureDoesNotMasqueradeAsAnEmptyLibrary() = runBlocking {
        val token = jwt("{\"alg\":\"ES256\",\"kid\":\"WebPlayKid\"}", "{\"iss\":\"AMPWebPlay\"}")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            when {
                request.url.host == "music.apple.com" && request.url.encodedPath == "/" -> response(request, 200, "<script src=\"/assets/index~abc.js\"></script>")
                request.url.host == "music.apple.com" -> response(request, 200, token)
                else -> response(request, 403, "{}")
            }
        }.build()
        val session = AppleMusicSession({ "media-user-token=session" }, { "in" }, client)
        val failure = runCatching { session.libraryPlaylists() }.exceptionOrNull()
        assertTrue("API failure must be surfaced", failure != null)
    }

    @Test fun libraryPaginationRejectsAContinuationToAnotherApiResource() = runBlocking {
        val token = jwt("{\"alg\":\"ES256\",\"kid\":\"WebPlayKid\"}", "{\"iss\":\"AMPWebPlay\"}")
        val requestedPaths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            requestedPaths += path
            val body = when {
                request.url.host == "music.apple.com" && path == "/" -> "<script src=\"/assets/index~abc.js\"></script>"
                request.url.host == "music.apple.com" -> token
                path == "/v1/me/library/playlists" -> """{"data":[],"next":"https://api.music.apple.com/v1/me/account"}"""
                else -> error("Credential-bearing request escaped the playlist resource: $path")
            }
            response(request, 200, body)
        }.build()
        val session = AppleMusicSession({ "media-user-token=session" }, { "in" }, client)
        val failure = runCatching { session.libraryPlaylists() }.exceptionOrNull()
        assertTrue("Cross-resource continuation must fail", failure != null)
        assertEquals(listOf("/v1/me/library/playlists"), requestedPaths.filter { it.startsWith("/v1/") })
    }

    private fun response(request: okhttp3.Request, code: Int, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("Status")
        .body(body.toResponseBody("application/json".toMediaType())).build()

    private fun jwt(header: String, payload: String): String = listOf(header, payload, "sig").joinToString(".") {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it.encodeToByteArray())
    }
}