package com.music.bitchord.data.apple

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppleHomeCatalogTest {
    @Test fun cookieMatchIsExactAndIgnoresEmptyValues() {
        assertEquals(null, extractMusicUserToken("not-media-user-token=wrong; media-user-token="))
        assertEquals("right", extractMusicUserToken("not-media-user-token=wrong; media-user-token=right"))
    }

    @Test fun signedOutHomeLoadsPublicChartsWithStorefrontAndExactOrigin() = runBlocking {
        val requests = mutableListOf<Request>()
        val token = jwt("{\"alg\":\"ES256\",\"kid\":\"WebPlayKid\"}", "{\"iss\":\"AMPWebPlay\"}")
        val unrelated = jwt("{\"alg\":\"ES256\",\"kid\":\"WebPlayKid\"}", "{\"iss\":\"Catalog\"}")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val body = when {
                request.url.host == "music.apple.com" && request.url.encodedPath == "/" -> "<script src=\"/assets/index~abc.js\"></script>"
                request.url.encodedPath.endsWith("index~abc.js") -> "$unrelated $token"
                request.url.encodedPath.endsWith("/charts") -> """{"results":{"albums":{"data":[{"id":"1","type":"albums","attributes":{"name":"Album","artistName":"Artist"}}]}}}"""
                else -> error("Unexpected Apple Music request: ${request.url}")
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

        val result = AppleHomeCatalog(
            cookieHeader = { null },
            storefront = { "gb" },
            client = client,
        ).home()

        val ready = assertIs<AppleHomeResult.Ready>(result)
        assertEquals(false, ready.personalized)
        assertEquals("Popular on Apple Music", ready.shelves.single().title)
        assertTrue(ready.shelves.single().public)
        assertEquals("Album", ready.shelves.single().items.single().title)
        assertEquals("/assets/index~abc.js", requests[1].url.encodedPath)
        assertTrue(requests[2].url.encodedPath.startsWith("/v1/catalog/gb/charts"))
        assertEquals("Bearer $token", requests[2].header("Authorization"))
        assertEquals("https://music.apple.com", requests[2].header("Origin"))
        assertEquals(null, requests[2].header("Music-User-Token"))
    }

    @Test fun emptyPersonalizedRecommendationMergesTypesIntoExistingHrefQuery() = runBlocking {
        val requests = java.util.concurrent.CopyOnWriteArrayList<Request>()
        val token = jwt("{\"alg\":\"ES256\",\"kid\":\"WebPlayKid\"}", "{\"iss\":\"AMPWebPlay\"}")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val body = when {
                request.url.host == "music.apple.com" && request.url.encodedPath == "/" -> "<script src=\"/assets/index~abc.js\"></script>"
                request.url.encodedPath.endsWith("index~abc.js") -> token
                request.url.encodedPath == "/v1/me/recommendations" -> """{"data":[{"id":"42","type":"personal-recommendation","attributes":{"title":{"stringForDisplay":"Made for Gaurish"}},"href":"/v1/me/recommendations/42?limit=10&seed=recent&types=albums"}]}"""
                request.url.encodedPath == "/v1/me/recommendations/42" -> """{"data":[{"id":"a1","type":"albums","attributes":{"name":"Recommended Album","artistName":"Artist"}}]}"""
                request.url.encodedPath == "/v1/me/recent/played" || request.url.encodedPath == "/v1/me/history/heavy-rotation" -> """{"data":[]}"""
                else -> error("Unexpected Apple Music request: ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

        val result = AppleHomeCatalog(
            cookieHeader = { "media-user-token=user-session" },
            storefront = { "us" },
            client = client,
        ).home()

        val ready = assertIs<AppleHomeResult.Ready>(result)
        val expansion = requests.single { it.url.encodedPath == "/v1/me/recommendations/42" }
        assertEquals("10", expansion.url.queryParameter("limit"))
        assertEquals("recent", expansion.url.queryParameter("seed"))
        assertEquals("albums,playlists,stations", expansion.url.queryParameter("types"))
        assertEquals("user-session", expansion.header("Music-User-Token"))
        assertTrue(ready.shelves.any { it.title == "Made for Gaurish" && it.items.single().title == "Recommended Album" })
    }

    private fun jwt(header: String, payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        fun encode(value: String) = encoder.encodeToString(value.encodeToByteArray())
        return "${encode(header)}.${encode(payload)}.signature"
    }
}
