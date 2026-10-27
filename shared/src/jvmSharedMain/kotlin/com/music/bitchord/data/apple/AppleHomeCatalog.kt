package com.music.bitchord.data.apple

import com.music.bitchord.data.Http
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.util.Base64

fun extractMusicUserToken(cookieHeader: String?): String? {
    if (cookieHeader.isNullOrBlank()) return null
    val parsed = cookieHeader.split(';')
        .map(String::trim)
        .firstOrNull { it.substringBefore('=', "").equals("media-user-token", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim()
        ?.takeIf(String::isNotBlank)
    if (parsed != null) return parsed
    if (!cookieHeader.contains(';') && !cookieHeader.contains('=')) {
        return cookieHeader.trim()
    }
    return null
}

internal fun discoverWebPlayerAsset(homepage: String): String? =
    Regex("/assets/index[~-][^/\\\"]+\\.js").find(homepage)?.value

internal fun selectWebPlayerToken(bundle: String): String? {
    val tokenPattern = Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
    val json = Json { ignoreUnknownKeys = true }
    return tokenPattern.findAll(bundle).firstNotNullOfOrNull { match ->
        runCatching {
            val parts = match.value.split('.')
            val header = json.parseToJsonElement(Base64.getUrlDecoder().decode(parts[0]).decodeToString()).jsonObject
            val payload = json.parseToJsonElement(Base64.getUrlDecoder().decode(parts[1]).decodeToString()).jsonObject
            val kid = header["kid"]?.jsonPrimitive?.content
            val issuer = payload["iss"]?.jsonPrimitive?.content
            match.value.takeIf { kid == "WebPlayKid" && issuer == "AMPWebPlay" }
        }.getOrNull()
    }
}

internal fun recommendationExpansionPath(href: String): String? {
    val absoluteUrl = if (href.startsWith('/')) "https://api.music.apple.com$href" else href
    val url = absoluteUrl.toHttpUrlOrNull() ?: return null
    if (url.scheme != "https" || url.host != "api.music.apple.com") return null
    val expanded = url.newBuilder()
        .removeAllQueryParameters("types")
        .addQueryParameter("types", "albums,playlists,stations")
        .build()
    return buildString {
        append(expanded.encodedPath)
        expanded.encodedQuery?.let { append('?').append(it) }
    }
}

/** Catalogue data is descriptive only; it never carries a BitChord playback id. */
data class AppleHomeItem(val id: String, val type: String, val title: String, val subtitle: String, val artwork: String?)
data class AppleHomeShelf(val title: String, val items: List<AppleHomeItem>, val public: Boolean = false)
sealed interface AppleHomeResult {
    data object Loading : AppleHomeResult
    data class Ready(val shelves: List<AppleHomeShelf>, val personalized: Boolean) : AppleHomeResult
    data class Empty(val message: String = "No shelves available") : AppleHomeResult
    data class Failed(val kind: Failure, val message: String) : AppleHomeResult
}
enum class Failure { MISSING_USER_TOKEN, SERVICE_3077, PERSONALIZED_UNAVAILABLE, HTTP, PARSE }

private data class AppleHomeResponse(val path: String, val status: Int, val body: String)

/** Apple Music web catalogue. The scraped web-player token is memory-only. */
class AppleHomeCatalog(
    private val cookieHeader: () -> String?,
    private val storefront: () -> String,
    private val client: okhttp3.OkHttpClient = Http.client,
    private val clock: () -> Long = System::currentTimeMillis,
    private val diagnostic: (String) -> Unit = {},
) {
    private var developerToken: String? = null
    private var tokenFetchedAt = 0L
    private var cacheKey: String? = null
    private var cachedAt = 0L
    private var cached: AppleHomeResult? = null
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun home(): AppleHomeResult {
        val mut = extractMusicUserToken(cookieHeader())
        diagnostic("home start storefront=${storefront()} userTokenPresent=${mut != null}")
        val key = "${storefront()}|${mut.orEmpty()}"
        if (cacheKey == key && clock() - cachedAt < CACHE_TTL && cached != null) return cached!!
        val result = if (mut == null) publicCharts() else when (val personalized = personalized(mut)) {
            is AppleHomeResult.Failed -> if (personalized.kind == Failure.PERSONALIZED_UNAVAILABLE) {
                diagnostic("personalized rejected: ${personalized.message}; trying public charts")
                publicCharts()
            } else personalized
            else -> {
                diagnostic("personalized result=${personalized::class.simpleName} shelves=${(personalized as? AppleHomeResult.Ready)?.shelves?.size ?: 0}")
                personalized
            }
        }
        diagnostic("home result=${result::class.simpleName} personalized=${(result as? AppleHomeResult.Ready)?.personalized} shelves=${(result as? AppleHomeResult.Ready)?.shelves?.size ?: 0}")
        if (result is AppleHomeResult.Ready || result is AppleHomeResult.Empty) {
            cacheKey = key
            cachedAt = clock()
            cached = result
        }
        return result
    }

    fun invalidate() { cacheKey = null; cached = null }

    private suspend fun personalized(mut: String): AppleHomeResult = coroutineScope {
        try {
            var requestToken = token()
            val paths = listOf(
                "/v1/me/recommendations?limit=10",
                "/v1/me/recent/played?limit=10&types=albums,playlists,stations",
                "/v1/me/history/heavy-rotation?limit=10",
            )
            var responses = paths.map { path -> async {
                val (status, body) = request(path, requestToken, mut)
                AppleHomeResponse(path, status, body)
            } }.map { it.await() }
            responses.forEach { diagnostic("personalized ${it.path.substringBefore('?')} HTTP ${it.status}${if (it.status !in 200..299 && it.status != 404) ": ${safeAppleError(it.status, it.body)}" else ""}") }
            if (responses.any { it.status == 401 }) {
                developerToken = null
                requestToken = token()
                responses = responses.map { response ->
                    if (response.status == 401) async {
                        val (status, body) = request(response.path, requestToken, mut)
                        AppleHomeResponse(response.path, status, body)
                    } else async { response }
                }.map { it.await() }
            }
            parsePersonalized(responses, requestToken, mut)
        } catch (e: AppleFailure) { AppleHomeResult.Failed(e.kind, e.message ?: "Apple Music request failed") }
        catch (e: Exception) { AppleHomeResult.Failed(Failure.HTTP, e.message ?: "Apple Music request failed") }
    }

    private fun safeAppleError(status: Int, body: String): String {
        val details = runCatching {
            val errors = json.parseToJsonElement(body).jsonObject["errors"]?.jsonArray.orEmpty()
            errors.mapNotNull { row ->
                val obj = row as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val code = obj["code"]?.jsonPrimitive?.content?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,40}")) }
                val title = obj["title"]?.jsonPrimitive?.content?.replace(Regex("[\\r\\n\\p{Cntrl}]"), " ")?.take(120)
                listOfNotNull(code, title).joinToString(": ").takeIf(String::isNotBlank)
            }.take(3).joinToString("; ")
        }.getOrDefault("")
        return "Apple Music returned HTTP $status." + if (details.isBlank()) "" else " $details"
    }

    private fun containsService3077(body: String): Boolean = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        root["errors"]?.jsonArray.orEmpty().any { errorElement ->
            val code = (errorElement as? kotlinx.serialization.json.JsonObject)?.get("code")?.jsonPrimitive?.content
            code == "3077" || errorElement.toString().contains("3077")
        }
    }.getOrDefault(false)

    private suspend fun parsePersonalized(
        responses: List<AppleHomeResponse>,
        token: String,
        mut: String,
    ): AppleHomeResult = coroutineScope {
        if (responses.any { containsService3077(it.body) }) {
            return@coroutineScope AppleHomeResult.Failed(
                Failure.SERVICE_3077,
                "Apple Music could not load this account's home. Check the Apple Music session.",
            )
        }
        if (responses.any { it.status == 401 }) {
            return@coroutineScope AppleHomeResult.Failed(Failure.HTTP, "Apple Music rejected the account session. Sign in again.")
        }
        responses.firstOrNull { it.status !in 200..299 && it.status != 404 }?.let { response ->
            val endpoint = response.path.substringBefore('?')
            return@coroutineScope AppleHomeResult.Failed(
                Failure.PERSONALIZED_UNAVAILABLE,
                "$endpoint: ${safeAppleError(response.status, response.body)}",
            )
        }
        try {
            val roots = responses.filter { it.status in 200..299 }
                .associate { response -> response.path.substringBefore('?') to json.parseToJsonElement(response.body).jsonObject }
            val recommendationRows = roots["/v1/me/recommendations"]?.get("data")?.jsonArray.orEmpty()
            val recommendationShelves = recommendationRows.map { recommendation ->
                async { recommendationShelf(recommendation.jsonObject, token, mut) }
            }.mapNotNull { it.await() }
            val recent = roots["/v1/me/recent/played"]?.let { catalogShelf("Recently Played", it) }
            val rotation = roots["/v1/me/history/heavy-rotation"]?.let { catalogShelf("Heavy Rotation", it) }
            val allShelves = recommendationShelves + listOfNotNull(recent, rotation)
            if (allShelves.isEmpty()) {
                publicCharts()
            } else {
                AppleHomeResult.Ready(allShelves, true)
            }
        } catch (e: AppleFailure) { AppleHomeResult.Failed(e.kind, e.message ?: "Apple Music error") }
        catch (e: Exception) { AppleHomeResult.Failed(Failure.PARSE, "Apple Music returned unreadable home data.") }
    }

    private suspend fun recommendationShelf(
        recommendation: kotlinx.serialization.json.JsonObject,
        token: String,
        mut: String,
    ): AppleHomeShelf? {
        val attributes = recommendation["attributes"]?.jsonObject ?: return null
        val title = attributes["title"]?.jsonObject?.get("stringForDisplay")?.jsonPrimitive?.content
            ?: return null
        val contents = recommendation["relationships"]?.jsonObject?.get("contents")?.jsonObject
        var rows = contents?.get("data")?.jsonArray.orEmpty()
        if (rows.isEmpty()) {
            val href = recommendation["href"]?.jsonPrimitive?.content
                ?: contents?.get("href")?.jsonPrimitive?.content
                ?: contents?.get("links")?.jsonObject?.get("related")?.jsonPrimitive?.content
                ?: return null
            val path = recommendationExpansionPath(href) ?: return null
            var (code, body) = request(path, token, mut)
            if (code == 401) {
                developerToken = null
                val newToken = token()
                val retry = request(path, newToken, mut)
                code = retry.first
                body = retry.second
            }
            if (containsService3077(body)) {
                throw AppleFailure(Failure.SERVICE_3077, "Apple Music could not expand this recommendation.")
            }
            if (code == 404) return null
            if (code !in 200..299) throw AppleFailure(Failure.HTTP, "Apple Music recommendation expansion returned HTTP $code.")
            val expanded = json.parseToJsonElement(body).jsonObject
            rows = expanded["data"]?.jsonArray.orEmpty()
        }
        val items = rows.mapNotNull(::item)
        return items.takeIf(List<*>::isNotEmpty)?.let { AppleHomeShelf(title, it) }
    }

    private fun catalogShelf(title: String, root: kotlinx.serialization.json.JsonObject): AppleHomeShelf? {
        val rows = root["data"]?.jsonArray.orEmpty()
        val items = rows.mapNotNull(::item)
        return items.takeIf(List<*>::isNotEmpty)?.let { AppleHomeShelf(title, it) }
    }

    private suspend fun publicCharts(): AppleHomeResult = try {
        val path = "/v1/catalog/${storefront()}/charts?types=albums,playlists,songs&limit=25"
        var requestToken = token()
        var (code, body) = request(path, requestToken, null)
        if (code == 401) {
            developerToken = null
            requestToken = token()
            val retry = request(path, requestToken, null)
            code = retry.first
            body = retry.second
        }
        if (containsService3077(body)) {
            AppleHomeResult.Failed(Failure.SERVICE_3077, "Apple Music charts returned error 3077.")
        } else if (code !in 200..299) {
            AppleHomeResult.Failed(
                Failure.HTTP,
                "/v1/catalog/${storefront()}/charts: ${safeAppleError(code, body)}",
            )
        } else {
            val results = json.parseToJsonElement(body).jsonObject["results"]?.jsonObject.orEmpty()
            val shelves = results.flatMap { (_, chartGroup) ->
                when (chartGroup) {
                    is kotlinx.serialization.json.JsonArray -> chartGroup.mapNotNull { chartElement ->
                        val chart = chartElement as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                        val items = chart["data"]?.jsonArray.orEmpty().mapNotNull(::item)
                        val title = chart["name"]?.jsonPrimitive?.content
                            ?.takeIf(String::isNotBlank)
                            ?: "Popular on Apple Music"
                        items.takeIf(List<*>::isNotEmpty)?.let { AppleHomeShelf(title, it, public = true) }
                    }
                    is kotlinx.serialization.json.JsonObject -> {
                        // Retain support for the older flattened response/fixture shape.
                        val items = chartGroup["data"]?.jsonArray.orEmpty().mapNotNull(::item)
                        listOfNotNull(items.takeIf(List<*>::isNotEmpty)?.let {
                            AppleHomeShelf("Popular on Apple Music", it, public = true)
                        })
                    }
                    else -> emptyList()
                }
            }
            if (shelves.isEmpty()) {
                AppleHomeResult.Empty("No Apple Music charts available.")
            } else {
                AppleHomeResult.Ready(shelves, false)
            }
        }
    } catch (e: AppleFailure) { AppleHomeResult.Failed(e.kind, e.message ?: "Apple Music error") }
      catch (_: Exception) { AppleHomeResult.Failed(Failure.PARSE, "Apple Music charts could not be read.") }

    private fun item(element: kotlinx.serialization.json.JsonElement): AppleHomeItem? = runCatching {
        val obj = element.jsonObject; val attr = obj["attributes"]!!.jsonObject
        AppleHomeItem(obj["id"]!!.jsonPrimitive.content, obj["type"]!!.jsonPrimitive.content,
            attr["name"]!!.jsonPrimitive.content,
            attr["artistName"]?.jsonPrimitive?.content.orEmpty(),
            attr["artwork"]?.jsonObject?.get("url")?.jsonPrimitive?.content)
    }.getOrNull()

    private suspend fun request(path: String, token: String, mut: String?): Pair<Int, String> {
        val url = "https://api.music.apple.com$path"
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
            .header("Origin", "https://music.apple.com").apply { if (mut != null) header("Music-User-Token", mut) }.build()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            client.newCall(request).execute().use { response -> response.code to response.body.string() }
        }
    }

    private suspend fun token(): String {
        if (developerToken != null && clock() - tokenFetchedAt < TOKEN_TTL) return developerToken!!
        val pages = listOf(
            "https://music.apple.com/",
            "https://music.apple.com/${storefront()}/browse",
        )
        var asset: String? = null
        var lastHomepageStatus: Int? = null
        for (pageUrl in pages) {
            val (status, html) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                client.newCall(Request.Builder().url(pageUrl).build()).execute().use { response ->
                    response.code to response.body.string()
                }
            }
            if (status !in 200..299) {
                lastHomepageStatus = status
                continue
            }
            asset = discoverWebPlayerAsset(html)
            if (asset != null) break
        }
        val assetPath = asset ?: throw AppleFailure(
            Failure.HTTP,
            lastHomepageStatus?.let { "Apple Music home returned HTTP $it and its storefront page had no web-player asset." }
                ?: "Apple Music web-player token asset was not found on the home or storefront page.",
        )
        val bundle = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            client.newCall(Request.Builder().url("https://music.apple.com$assetPath").build()).execute().use { response ->
                if (!response.isSuccessful) throw AppleFailure(Failure.HTTP, "Apple Music web-player bundle returned HTTP ${response.code}.")
                response.body.string()
            }
        }
        val jwt = selectWebPlayerToken(bundle)
            ?: throw AppleFailure(Failure.HTTP, "Apple Music web-player token was not found.")
        developerToken = jwt; tokenFetchedAt = clock(); return jwt
    }

    private data class AppleFailure(val kind: Failure, override val message: String?) : Exception(message)
    companion object { const val CACHE_TTL = 5 * 60 * 1000L; private const val TOKEN_TTL = 30 * 60 * 1000L }
}
