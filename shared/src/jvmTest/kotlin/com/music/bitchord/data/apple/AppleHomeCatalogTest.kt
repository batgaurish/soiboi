package com.music.bitchord.data.apple

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
import kotlin.test.assertNotNull
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

        val ready = assertIs<AppleHomeResult.Ready>(result, result.toString())
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

        val ready = assertIs<AppleHomeResult.Ready>(result, result.toString())
        val expansion = requests.single { it.url.encodedPath == "/v1/me/recommendations/42" }
        assertEquals("10", expansion.url.queryParameter("limit"))
        assertEquals("recent", expansion.url.queryParameter("seed"))
        assertEquals("albums,playlists,stations", expansion.url.queryParameter("types"))
        assertEquals("user-session", expansion.header("Music-User-Token"))
        assertTrue(ready.shelves.any { it.title == "Made for Gaurish" && it.items.single().title == "Recommended Album" })
    }

    @Test fun authenticatedHomeFallsBackToChartsWhenRecommendationExpansionIsNotFound() = runBlocking {
        val token = jwt("{\"alg\":\"ES256\",\"kid\":\"WebPlayKid\"}", "{\"iss\":\"AMPWebPlay\"}")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (code, body) = when {
                request.url.host == "music.apple.com" && request.url.encodedPath == "/" ->
                    200 to "<script src=\"/assets/index~abc.js\"></script>"
                request.url.encodedPath.endsWith("index~abc.js") -> 200 to token
                request.url.encodedPath == "/v1/me/recommendations" -> 200 to """{"data":[{"id":"42","type":"personal-recommendation","attributes":{"title":{"stringForDisplay":"Made for Gaurish"}},"href":"/v1/me/recommendations/42"}]}"""
                request.url.encodedPath == "/v1/me/recommendations/42" -> 404 to "not found"
                request.url.encodedPath == "/v1/me/recent/played" || request.url.encodedPath == "/v1/me/history/heavy-rotation" -> 200 to """{"data":[]}"""
                request.url.encodedPath.endsWith("/charts") -> 200 to """{"results":{"albums":[{"name":"Top Albums","data":[{"id":"a1","type":"albums","attributes":{"name":"Album One","artistName":"Artist One"}}]}]}}"""
                else -> error("Unexpected Apple Music request: ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Status")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

        val result = AppleHomeCatalog(
            cookieHeader = { "media-user-token=user-session" },
            storefront = { "us" },
            client = client,
        ).home()

        val ready = assertIs<AppleHomeResult.Ready>(result, result.toString())
        assertEquals(false, ready.personalized)
        assertEquals("Top Albums", ready.shelves.single().title)
    }

    @Test fun httpNon2xxWithPayloadCode3077MapsToService3077Failure() = runBlocking {
        val token = jwt("{\"alg\":\"ES256\",\"kid\":\"WebPlayKid\"}", "{\"iss\":\"AMPWebPlay\"}")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (code, body) = when {
                request.url.host == "music.apple.com" && request.url.encodedPath == "/" -> 200 to "<script src=\"/assets/index~abc.js\"></script>"
                request.url.encodedPath.endsWith("index~abc.js") -> 200 to token
                request.url.encodedPath == "/v1/me/recommendations" -> 403 to """{"errors":[{"code":"3077","title":"Session Error"}]}"""
                request.url.encodedPath == "/v1/me/recent/played" || request.url.encodedPath == "/v1/me/history/heavy-rotation" -> 200 to """{"data":[]}"""
                else -> error("Unexpected Apple Music request: ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Error")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

        val result = AppleHomeCatalog(
            cookieHeader = { "media-user-token=user-session" },
            storefront = { "us" },
            client = client,
        ).home()

        val failed = assertIs<AppleHomeResult.Failed>(result)
        assertEquals(Failure.SERVICE_3077, failed.kind)
    }

    @Test fun recommendationExpansionHttpNon2xxWithCode3077MapsToService3077Failure() = runBlocking {
        val token = jwt("{\"alg\":\"ES256\",\"kid\":\"WebPlayKid\"}", "{\"iss\":\"AMPWebPlay\"}")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (code, body) = when {
                request.url.host == "music.apple.com" && request.url.encodedPath == "/" -> 200 to "<script src=\"/assets/index~abc.js\"></script>"
                request.url.encodedPath.endsWith("index~abc.js") -> 200 to token
                request.url.encodedPath == "/v1/me/recommendations" -> 200 to """{"data":[{"id":"42","type":"personal-recommendation","attributes":{"title":{"stringForDisplay":"Made for Gaurish"}},"href":"/v1/me/recommendations/42"}]}"""
                request.url.encodedPath == "/v1/me/recommendations/42" -> 400 to """{"errors":[{"id":"err-1","code":"3077","status":"400"}]}"""
                request.url.encodedPath == "/v1/me/recent/played" || request.url.encodedPath == "/v1/me/history/heavy-rotation" -> 200 to """{"data":[]}"""
                else -> error("Unexpected Apple Music request: ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Error")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

        val result = AppleHomeCatalog(
            cookieHeader = { "media-user-token=user-session" },
            storefront = { "us" },
            client = client,
        ).home()

        val failed = assertIs<AppleHomeResult.Failed>(result)
        assertEquals(Failure.SERVICE_3077, failed.kind)
    }

    @Test fun discoverWebPlayerAssetExtractsDynamicHashesAndRejectsMissing() {
        assertEquals("/assets/index~abc123.js", discoverWebPlayerAsset("<script src=\"/assets/index~abc123.js\"></script>"))
        assertEquals("/assets/index-def456.js", discoverWebPlayerAsset("<script src=\"/assets/index-def456.js\"></script>"))
        assertEquals(null, discoverWebPlayerAsset("<script src=\"/assets/other.js\"></script>"))
        assertEquals(null, discoverWebPlayerAsset("<html><body>No scripts here</body></html>"))
    }

    @Test fun absentWebPlayerAssetFailsWithTypedHttpFailure(): Unit = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("<html><head></head><body>No asset scripts</body></html>".toResponseBody("text/html".toMediaType()))
                .build()
        }.build()

        val result = AppleHomeCatalog(
            cookieHeader = { null },
            storefront = { "us" },
            client = client,
        ).home()

        val failed = assertIs<AppleHomeResult.Failed>(result)
        assertEquals(Failure.HTTP, failed.kind)
    }

    @Test fun webPlayerAssetHttpNon2xxFailsWithTypedHttpFailure(): Unit = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (code, body) = when {
                request.url.host == "music.apple.com" && request.url.encodedPath == "/" -> 200 to "<script src=\"/assets/index~abc.js\"></script>"
                request.url.encodedPath.endsWith("index~abc.js") -> 404 to "Asset not found"
                else -> error("Unexpected: ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message(if (code == 200) "OK" else "Not Found")
                .body(body.toResponseBody("text/plain".toMediaType())).build()
        }.build()

        val result = AppleHomeCatalog(
            cookieHeader = { null },
            storefront = { "us" },
            client = client,
        ).home()

        val failed = assertIs<AppleHomeResult.Failed>(result)
        assertEquals(Failure.HTTP, failed.kind)
    }

    @Test fun selectWebPlayerTokenSelectsWebPlayKidAndRejectsUnrelatedTokens() {
        val validToken = jwt("{\"alg\":\"ES256\",\"kid\":\"WebPlayKid\"}", "{\"iss\":\"AMPWebPlay\"}")
        val wrongKid = jwt("{\"alg\":\"ES256\",\"kid\":\"OtherKid\"}", "{\"iss\":\"AMPWebPlay\"}")
        val wrongIssuer = jwt("{\"alg\":\"ES256\",\"kid\":\"WebPlayKid\"}", "{\"iss\":\"Catalog\"}")
        val malformedToken = "eyJhbGciOiJFUzI1NiJ9.not-valid-base64.signature"

        assertEquals(null, selectWebPlayerToken("bundle without tokens"))
        assertEquals(null, selectWebPlayerToken("noise $wrongKid $wrongIssuer $malformedToken"))

        val bundle = "someCode; $wrongKid; otherCode; $wrongIssuer; $validToken; end;"
        assertEquals(validToken, selectWebPlayerToken(bundle))
    }

    @Test fun developerTokenIsCachedInMemoryWithTtlAndRefreshesAfterTtl(): Unit = runBlocking {
        var currentTime = 1000L
        var homeScrapeCount = 0
        var assetScrapeCount = 0
        val token1 = jwt("{\"alg\":\"ES256\",\"kid\":\"WebPlayKid\"}", "{\"iss\":\"AMPWebPlay\"}")
        val token2 = jwt("{\"alg\":\"ES256\",\"kid\":\"WebPlayKid\"}", "{\"iss\":\"AMPWebPlay\"}")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = when {
                request.url.host == "music.apple.com" && request.url.encodedPath == "/" -> {
                    homeScrapeCount++
                    "<script src=\"/assets/index~abc.js\"></script>"
                }
                request.url.encodedPath.endsWith("index~abc.js") -> {
                    assetScrapeCount++
                    if (assetScrapeCount == 1) token1 else token2
                }
                request.url.encodedPath.endsWith("/charts") -> """{"results":{"albums":{"data":[{"id":"1","type":"albums","attributes":{"name":"A","artistName":"B"}}]}}}"""
                else -> error("Unexpected: ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

        val catalog = AppleHomeCatalog(
            cookieHeader = { null },
            storefront = { "us" },
            client = client,
            clock = { currentTime },
        )

        catalog.home()
        assertEquals(1, homeScrapeCount)
        assertEquals(1, assetScrapeCount)

        currentTime += 10 * 60 * 1000L
        catalog.invalidate()
        catalog.home()
        assertEquals(1, homeScrapeCount)
        assertEquals(1, assetScrapeCount)

        currentTime += 25 * 60 * 1000L
        catalog.invalidate()
        catalog.home()
        assertEquals(2, homeScrapeCount)
        assertEquals(2, assetScrapeCount)
    }

    @Test fun signedInRequestsIncludeExactOriginAuthorizationAndMusicUserToken(): Unit = runBlocking {
        val requests = java.util.concurrent.CopyOnWriteArrayList<Request>()
        val token = jwt("{\"alg\":\"ES256\",\"kid\":\"WebPlayKid\"}", "{\"iss\":\"AMPWebPlay\"}")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val body = when {
                request.url.host == "music.apple.com" && request.url.encodedPath == "/" -> "<script src=\"/assets/index~abc.js\"></script>"
                request.url.encodedPath.endsWith("index~abc.js") -> token
                request.url.encodedPath == "/v1/me/recommendations" ||
                    request.url.encodedPath == "/v1/me/recent/played" ||
                    request.url.encodedPath == "/v1/me/history/heavy-rotation" -> """{"data":[]}"""
                else -> error("Unexpected: ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

        AppleHomeCatalog(
            cookieHeader = { "other=123; media-user-token=my-secret-token; path=/" },
            storefront = { "us" },
            client = client,
        ).home()

        val meRequests = requests.filter { it.url.encodedPath.startsWith("/v1/me/") }
        assertEquals(3, meRequests.size)
        meRequests.forEach { req ->
            assertEquals("https://music.apple.com", req.header("Origin"))
            assertEquals("Bearer $token", req.header("Authorization"))
            assertEquals("my-secret-token", req.header("Music-User-Token"))
        }
    }

    @Test fun signedOutMissingUserTokenPreservesPublicFallbackInsteadOfError(): Unit = runBlocking {
        val requests = mutableListOf<Request>()
        val token = jwt("{\"alg\":\"ES256\",\"kid\":\"WebPlayKid\"}", "{\"iss\":\"AMPWebPlay\"}")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val body = when {
                request.url.host == "music.apple.com" && request.url.encodedPath == "/" -> "<script src=\"/assets/index~abc.js\"></script>"
                request.url.encodedPath.endsWith("index~abc.js") -> token
                request.url.encodedPath.endsWith("/charts") -> """{"results":{"albums":{"data":[{"id":"1","type":"albums","attributes":{"name":"A","artistName":"B"}}]}}}"""
                else -> error("Unexpected: ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

        val result = AppleHomeCatalog(
            cookieHeader = { "session_id=no-media-token" },
            storefront = { "jp" },
            client = client,
        ).home()

        val ready = assertIs<AppleHomeResult.Ready>(result, result.toString())
        assertEquals(false, ready.personalized)
        assertTrue(requests.any { it.url.encodedPath.startsWith("/v1/catalog/jp/charts") })
    }

    @Test fun recommendationExpansionPathMergesExistingParamsAndRejectsForeignHosts() {
        val relative = recommendationExpansionPath("/v1/me/recommendations/123?limit=25&seed=recent&types=songs&offset=5")
        assertNotNull(relative)
        val relativeUrl = "https://api.music.apple.com$relative".toHttpUrlOrNull()!!
        assertEquals("25", relativeUrl.queryParameter("limit"))
        assertEquals("recent", relativeUrl.queryParameter("seed"))
        assertEquals("5", relativeUrl.queryParameter("offset"))
        assertEquals("albums,playlists,stations", relativeUrl.queryParameter("types"))

        val absolute = recommendationExpansionPath("https://api.music.apple.com/v1/me/recommendations/99?tag=custom")
        assertNotNull(absolute)
        val absoluteUrl = "https://api.music.apple.com$absolute".toHttpUrlOrNull()!!
        assertEquals("custom", absoluteUrl.queryParameter("tag"))
        assertEquals("albums,playlists,stations", absoluteUrl.queryParameter("types"))

        assertEquals(null, recommendationExpansionPath("https://attacker.com/v1/me/recommendations/123"))
        assertEquals(null, recommendationExpansionPath("http://api.music.apple.com/v1/me/recommendations/123"))
    }

    @Test fun personalizedRequestsExecuteConcurrently(): Unit = runBlocking {
        val activeConcurrent = java.util.concurrent.atomic.AtomicInteger(0)
        val maxConcurrent = java.util.concurrent.atomic.AtomicInteger(0)
        val token = jwt("{\"alg\":\"ES256\",\"kid\":\"WebPlayKid\"}", "{\"iss\":\"AMPWebPlay\"}")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath.startsWith("/v1/me/")) {
                val current = activeConcurrent.incrementAndGet()
                maxConcurrent.updateAndGet { maxOf(it, current) }
                Thread.sleep(60)
                activeConcurrent.decrementAndGet()
            }
            val body = when {
                request.url.host == "music.apple.com" && request.url.encodedPath == "/" -> "<script src=\"/assets/index~abc.js\"></script>"
                request.url.encodedPath.endsWith("index~abc.js") -> token
                request.url.encodedPath == "/v1/me/recommendations" -> """{"data":[{"id":"r1","type":"personal-recommendation","attributes":{"title":{"stringForDisplay":"Recs"}},"relationships":{"contents":{"data":[{"id":"1","type":"albums","attributes":{"name":"A","artistName":"B"}}]}}}]}"""
                request.url.encodedPath == "/v1/me/recent/played" -> """{"data":[{"id":"2","type":"albums","attributes":{"name":"Recent","artistName":"Artist"}}]}"""
                request.url.encodedPath == "/v1/me/history/heavy-rotation" -> """{"data":[{"id":"3","type":"albums","attributes":{"name":"Rotation","artistName":"Artist"}}]}"""
                else -> error("Unexpected: ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

        val result = AppleHomeCatalog(
            cookieHeader = { "media-user-token=user-session" },
            storefront = { "us" },
            client = client,
        ).home()

        val ready = assertIs<AppleHomeResult.Ready>(result, result.toString())
        assertEquals(true, ready.personalized)
        assertEquals(3, ready.shelves.size)
        assertTrue(maxConcurrent.get() >= 2, "Expected concurrent requests but max was ${maxConcurrent.get()}")
    }

    @Test fun personalized401RefreshesTokenOnceAndRetriesSuccessfully(): Unit = runBlocking {
        var tokenScrapeCount = 0
        var recommendationsCallCount = 0
        val token1 = jwt("{\"alg\":\"ES256\",\"kid\":\"WebPlayKid\"}", "{\"iss\":\"AMPWebPlay\"}")
        val token2 = jwt("{\"alg\":\"ES256\",\"kid\":\"WebPlayKid\"}", "{\"iss\":\"AMPWebPlay\"}")

        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (code, body) = when {
                request.url.host == "music.apple.com" && request.url.encodedPath == "/" -> {
                    tokenScrapeCount++
                    200 to "<script src=\"/assets/index~abc.js\"></script>"
                }
                request.url.encodedPath.endsWith("index~abc.js") -> {
                    200 to (if (tokenScrapeCount == 1) token1 else token2)
                }
                request.url.encodedPath == "/v1/me/recommendations" -> {
                    recommendationsCallCount++
                    if (recommendationsCallCount == 1) {
                        401 to """{"errors":[{"code":"40100","title":"Unauthorized"}]}"""
                    } else {
                        200 to """{"data":[]}"""
                    }
                }
                request.url.encodedPath.endsWith("/charts") -> 200 to """{"results":{"albums":{"data":[{"id":"1","type":"albums","attributes":{"name":"Chart","artistName":"Artist"}}]}}}"""
                request.url.encodedPath == "/v1/me/recent/played" ||
                    request.url.encodedPath == "/v1/me/history/heavy-rotation" -> 200 to """{"data":[]}"""
                else -> error("Unexpected: ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message(if (code == 200) "OK" else "Unauthorized")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

        val result = AppleHomeCatalog(
            cookieHeader = { "media-user-token=session" },
            storefront = { "us" },
            client = client,
        ).home()

        assertEquals(false, assertIs<AppleHomeResult.Ready>(result).personalized)
        assertEquals(2, tokenScrapeCount, "Scraped token initially and refreshed once")
        assertEquals(2, recommendationsCallCount, "Called recommendations initially and retried once")
    }

    @Test fun personalizedSecond401FailsWithTypedHttpFailureWithoutLooping(): Unit = runBlocking {
        var tokenScrapeCount = 0
        var recommendationsCallCount = 0
        val token1 = jwt("{\"alg\":\"ES256\",\"kid\":\"WebPlayKid\"}", "{\"iss\":\"AMPWebPlay\"}")
        val token2 = jwt("{\"alg\":\"ES256\",\"kid\":\"WebPlayKid\"}", "{\"iss\":\"AMPWebPlay\"}")

        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (code, body) = when {
                request.url.host == "music.apple.com" && request.url.encodedPath == "/" -> {
                    tokenScrapeCount++
                    200 to "<script src=\"/assets/index~abc.js\"></script>"
                }
                request.url.encodedPath.endsWith("index~abc.js") -> {
                    200 to (if (tokenScrapeCount == 1) token1 else token2)
                }
                request.url.encodedPath == "/v1/me/recommendations" -> {
                    recommendationsCallCount++
                    401 to """{"errors":[{"code":"40100","title":"Unauthorized"}]}"""
                }
                request.url.encodedPath == "/v1/me/recent/played" ||
                    request.url.encodedPath == "/v1/me/history/heavy-rotation" -> 200 to """{"data":[]}"""
                else -> error("Unexpected: ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Unauthorized")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

        val result = AppleHomeCatalog(
            cookieHeader = { "media-user-token=session" },
            storefront = { "us" },
            client = client,
        ).home()

        val failed = assertIs<AppleHomeResult.Failed>(result)
        assertEquals(Failure.HTTP, failed.kind)
        assertEquals(2, recommendationsCallCount, "Must not retry more than once")
    }

    @Test fun cacheReusesResultWithinTtlAndExpiresAfterTtl(): Unit = runBlocking {
        var currentTime = 5000L
        var networkHits = 0
        val token = jwt("{\"alg\":\"ES256\",\"kid\":\"WebPlayKid\"}", "{\"iss\":\"AMPWebPlay\"}")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath.endsWith("/charts")) networkHits++
            val body = when {
                request.url.host == "music.apple.com" && request.url.encodedPath == "/" -> "<script src=\"/assets/index~abc.js\"></script>"
                request.url.encodedPath.endsWith("index~abc.js") -> token
                request.url.encodedPath.endsWith("/charts") -> """{"results":{"albums":{"data":[{"id":"$networkHits","type":"albums","attributes":{"name":"Album $networkHits","artistName":"Artist"}}]}}}"""
                else -> error("Unexpected: ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

        val catalog = AppleHomeCatalog(
            cookieHeader = { null },
            storefront = { "us" },
            client = client,
            clock = { currentTime },
        )

        val result1 = catalog.home()
        assertIs<AppleHomeResult.Ready>(result1)
        assertEquals(1, networkHits)

        currentTime += 2 * 60 * 1000L
        val result2 = catalog.home()
        assertIs<AppleHomeResult.Ready>(result2)
        assertEquals(1, networkHits)

        currentTime += 4 * 60 * 1000L
        val result3 = catalog.home()
        assertIs<AppleHomeResult.Ready>(result3)
        assertEquals(2, networkHits)
    }

    @Test fun cacheInvalidatesOnStorefrontOrSessionChangeOrExplicitInvalidate(): Unit = runBlocking {
        var currentStorefront = "us"
        var currentCookie: String? = null
        val chartRequests = mutableListOf<String>()
        val token = jwt("{\"alg\":\"ES256\",\"kid\":\"WebPlayKid\"}", "{\"iss\":\"AMPWebPlay\"}")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath.contains("/charts")) chartRequests += request.url.encodedPath
            val body = when {
                request.url.host == "music.apple.com" && request.url.encodedPath == "/" -> "<script src=\"/assets/index~abc.js\"></script>"
                request.url.encodedPath.endsWith("index~abc.js") -> token
                request.url.encodedPath.contains("/charts") -> """{"results":{"albums":{"data":[{"id":"1","type":"albums","attributes":{"name":"A","artistName":"B"}}]}}}"""
                request.url.encodedPath.startsWith("/v1/me/") -> """{"data":[]}"""
                else -> error("Unexpected: ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

        val catalog = AppleHomeCatalog(
            cookieHeader = { currentCookie },
            storefront = { currentStorefront },
            client = client,
        )

        catalog.home()
        assertEquals(listOf("/v1/catalog/us/charts"), chartRequests)

        currentStorefront = "gb"
        catalog.home()
        assertEquals(listOf("/v1/catalog/us/charts", "/v1/catalog/gb/charts"), chartRequests)

        currentCookie = "media-user-token=user1"
        val personalizedResult = catalog.home()
        assertEquals(false, assertIs<AppleHomeResult.Ready>(personalizedResult).personalized)

        catalog.invalidate()
        val reloaded = catalog.home()
        assertEquals(false, assertIs<AppleHomeResult.Ready>(reloaded).personalized)
    }

    @Test fun failedResultIsNotCachedSoSubsequentCallRetries(): Unit = runBlocking {
        var attempts = 0
        val token = jwt("{\"alg\":\"ES256\",\"kid\":\"WebPlayKid\"}", "{\"iss\":\"AMPWebPlay\"}")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (code, body) = when {
                request.url.host == "music.apple.com" && request.url.encodedPath == "/" -> 200 to "<script src=\"/assets/index~abc.js\"></script>"
                request.url.encodedPath.endsWith("index~abc.js") -> 200 to token
                request.url.encodedPath.endsWith("/charts") -> {
                    attempts++
                    if (attempts == 1) {
                        500 to "Server Error"
                    } else {
                        200 to """{"results":{"albums":{"data":[{"id":"1","type":"albums","attributes":{"name":"A","artistName":"B"}}]}}}"""
                    }
                }
                else -> error("Unexpected: ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Status")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

        val catalog = AppleHomeCatalog(
            cookieHeader = { null },
            storefront = { "us" },
            client = client,
        )

        val firstResult = catalog.home()
        val failed = assertIs<AppleHomeResult.Failed>(firstResult)
        assertEquals(Failure.HTTP, failed.kind)

        val secondResult = catalog.home()
        assertIs<AppleHomeResult.Ready>(secondResult)
        assertEquals(2, attempts)
    }

    @Test fun publicChartsParsesCurrentAppleChartArrayResponse() = runBlocking {
        val requests = mutableListOf<Request>()
        val token = jwt("{\"alg\":\"ES256\",\"kid\":\"WebPlayKid\"}", "{\"iss\":\"AMPWebPlay\"}")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val body = when {
                request.url.host == "music.apple.com" && request.url.encodedPath == "/" ->
                    "<script src=\"/assets/index~abc.js\"></script>"
                request.url.encodedPath.endsWith("index~abc.js") -> token
                request.url.encodedPath.endsWith("/charts") -> """{"results":{"albums":[{"chart":"most-played","name":"Top Albums","data":[{"id":"a1","type":"albums","attributes":{"name":"Album One","artistName":"Artist One"}}]}],"playlists":[{"chart":"most-played","name":"Top Playlists","data":[{"id":"p1","type":"playlists","attributes":{"name":"Playlist One","artistName":"Artist Two"}}]}]}}"""
                else -> error("Unexpected Apple Music request: ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

        val result = AppleHomeCatalog(cookieHeader = { null }, storefront = { "in" }, client = client).home()

        val ready = assertIs<AppleHomeResult.Ready>(result, result.toString())
        assertEquals(listOf("Top Albums", "Top Playlists"), ready.shelves.map { it.title })
        assertEquals(listOf("Album One", "Playlist One"), ready.shelves.map { it.items.single().title })
        assertTrue(ready.shelves.all { it.public })
        val chartRequest = requests.single { it.url.encodedPath.endsWith("/charts") }
        assertEquals(listOf("albums,playlists,songs"), chartRequest.url.queryParameterValues("types"))
    }

    @Test fun authenticatedHomeFallsBackToChartsWhenPersonalizedEndpointsReturn404() = runBlocking {
        val token = jwt("{\"alg\":\"ES256\",\"kid\":\"WebPlayKid\"}", "{\"iss\":\"AMPWebPlay\"}")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (code, body) = when {
                request.url.host == "music.apple.com" && request.url.encodedPath == "/" ->
                    200 to "<script src=\"/assets/index~abc.js\"></script>"
                request.url.encodedPath.endsWith("index~abc.js") -> 200 to token
                request.url.encodedPath.startsWith("/v1/me/") ->
                    404 to """{"errors":[{"code":"NOT_FOUND","title":"Endpoint not available"}]}"""
                request.url.encodedPath.endsWith("/charts") ->
                    200 to """{"results":{"albums":[{"name":"Top Albums","data":[{"id":"a1","type":"albums","attributes":{"name":"Album One","artistName":"Artist One"}}]}]}}"""
                else -> error("Unexpected Apple Music request: ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Status")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

        val result = AppleHomeCatalog(
            cookieHeader = { "media-user-token=session" },
            storefront = { "in" },
            client = client,
        ).home()

        val ready = assertIs<AppleHomeResult.Ready>(result, result.toString())
        assertEquals(false, ready.personalized)
        assertEquals("Top Albums", ready.shelves.single().title)
        assertEquals("Album One", ready.shelves.single().items.single().title)
    }

    @Test fun publicChartsUsesStorefrontBrowseWhenRootHomepageReturns404() = runBlocking {
        val requests = mutableListOf<Request>()
        val token = jwt("{\"alg\":\"ES256\",\"kid\":\"WebPlayKid\"}", "{\"iss\":\"AMPWebPlay\"}")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val (code, body) = when {
                request.url.host == "music.apple.com" && request.url.encodedPath == "/" -> 404 to "Not found"
                request.url.host == "music.apple.com" && request.url.encodedPath == "/in/browse" ->
                    200 to "<script src=\"/assets/index~abc.js\"></script>"
                request.url.encodedPath.endsWith("index~abc.js") -> 200 to token
                request.url.encodedPath.endsWith("/charts") ->
                    200 to """{"results":{"albums":[{"name":"Top Albums","data":[{"id":"a1","type":"albums","attributes":{"name":"Album One","artistName":"Artist One"}}]}]}}"""
                else -> error("Unexpected Apple Music request: ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Status")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

        val result = AppleHomeCatalog(cookieHeader = { null }, storefront = { "in" }, client = client).home()

        val ready = assertIs<AppleHomeResult.Ready>(result, result.toString())
        assertEquals("Top Albums", ready.shelves.single().title)
        assertTrue(requests.any { it.url.encodedPath == "/in/browse" })
    }

    private fun jwt(header: String, payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        fun encode(value: String) = encoder.encodeToString(value.encodeToByteArray())
        return "${encode(header)}.${encode(payload)}.signature"
    }
}
