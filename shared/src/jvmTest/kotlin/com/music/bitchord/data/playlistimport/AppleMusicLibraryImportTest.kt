package com.music.bitchord.data.playlistimport

import com.music.bitchord.data.accounts.AppleMusicSession
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals

import org.junit.Test
import java.util.Base64

class AppleMusicLibraryImportTest {
    @Test fun loadsAccountPlaylistsAndTracksWithoutAnyPlaylistUrl() = runBlocking {
        val token = listOf(
            "{\"alg\":\"ES256\",\"kid\":\"WebPlayKid\"}",
            "{\"iss\":\"AMPWebPlay\"}",
            "sig",
        ).joinToString(".") { Base64.getUrlEncoder().withoutPadding().encodeToString(it.encodeToByteArray()) }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val body = when {
                request.url.host == "music.apple.com" && path == "/" -> "<script src=\"/assets/index~x.js\"></script>"
                request.url.host == "music.apple.com" -> token
                path == "/v1/me/library/playlists" -> """{"data":[{"id":"p.123","attributes":{"name":"Road Mix","trackCount":2}}]}"""
                path == "/v1/me/library/playlists/p.123/tracks" -> """{"data":[{"id":"t1","attributes":{"name":"Song A","artistName":"Artist A","isrc":"USAAA1234567"}},{"id":"t2","attributes":{"name":"Song B","artistName":"Artist B"}}]}"""
                else -> error("Unexpected request: $path")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

        val session = AppleMusicSession({ "media-user-token=session" }, { "in" }, client)
        val playlists = AppleMusicLibraryImport.load(session)

        assertEquals(1, playlists.size)
        val playlist = playlists.single()
        assertEquals("Road Mix", playlist.title)
        assertEquals(PlaylistImportProvider.APPLE_MUSIC, playlist.provider)
        assertEquals(listOf("Song A", "Song B"), playlist.entries.map { it.title })
        assertEquals(listOf(0, 1), playlist.entries.map { it.sourceIndex })
        assertEquals("t1", playlist.entries.first().sourceId)
        assertEquals("USAAA1234567", playlist.entries.first().isrc)
        assertEquals("p.123", AppleMusicLibraryImport.playlistId(playlist))
        assertEquals(null, AppleMusicLibraryImport.playlistId("apple-library://playlist/../account"))
    }
}