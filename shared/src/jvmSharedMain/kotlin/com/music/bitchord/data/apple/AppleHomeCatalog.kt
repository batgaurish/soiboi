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

internal fun extractMusicUserToken(cookieHeader: String?): String? =
    cookieHeader?.split(';')
        ?.map(String::trim)
        ?.firstOrNull { it.substringBefore('=', "").equals("media-user-token", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim()
        ?.takeIf(String::isNotBlank)

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
    data class Failed(val kind: Failure, val message: String) : AppleHomeResult
}
enum class Failure { MISSING_USER_TOKEN, SERVICE_3077, HTTP, PARSE }

/** Apple Music web catalogue. The scraped web-player token is memory-only. */
class AppleHomeCatalog(
    private val cookieHeader: () -> String?,
    private val storefront: () -> String,
    private val client: okhttp3.OkHttpClient = Http.client,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var developerToken: String? = null
    private var tokenFetchedAt = 0L
    private var cacheKey: String? = null
    private var cachedAt = 0L
    private var cached: AppleHomeResult? = null
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun home(): AppleHomeResult {
        val mut = extractMusicUserToken(cookieHeader())
        val key = "${storefront()}|${mut.orEmpty()}"
        if (cacheKey == key && clock() - cachedAt < CACHE_TTL && cached != null) return cached!!
        val result = if (mut == null) publicCharts() else personalized(mut)
        if (result !is AppleHomeResult.Failed || result.kind != Failure.MISSING_USER_TOKEN) {
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
            val paths = listOf("/v1/me/recommendations?limit=10", "/v1/me/recent/played?limit=20", "/v1/me/history/heavy-rotation?limit=20")
            var responses = paths.map { path -> async { request(path, requestToken, mut) } }.map { it.await() }
            if (responses.any { it.first == 401 }) {
                developerToken = null
                requestToken = token()
                responses = paths.mapIndexed { index, path ->
                    async { if (responses[index].first == 401) request(path, requestToken, mut) else responses[index] }
                }.map { it.await() }
            }
            parsePersonalized(responses, requestToken, mut)
        } catch (e: AppleFailure) { AppleHomeResult.Failed(e.kind, e.message ?: "Apple Music request failed") }
        catch (e: Exception) { AppleHomeResult.Failed(Failure.HTTP, e.message ?: "Apple Music request failed") }
    }

    private suspend fun parsePersonalized(
        responses: List<Pair<Int, String>>,
        token: String,
        mut: String,
    ): AppleHomeResult = coroutineScope {
        if (responses.any { it.first == 401 }) return@coroutineScope AppleHomeResult.Failed(Failure.HTTP, "Apple Music rejected the account session. Sign in again.")
        if (responses.any { it.first !in 200..299 }) return@coroutineScope AppleHomeResult.Failed(Failure.HTTP, "Apple Music returned HTTP ${responses.first { it.first !in 200..299 }.first}.")
        try {
            val roots = responses.map { json.parseToJsonElement(it.second).jsonObject }
            if (roots.any { root -> root["errors"]?.jsonArray.orEmpty().any { it.toString().contains("3077") } }) {
                throw AppleFailure(Failure.SERVICE_3077, "Apple Music could not load this account's home. Check the Apple Music session.")
            }
            val recommendationRows = roots[0]["data"]?.jsonArray.orEmpty()
            val recommendationShelves = recommendationRows.map { recommendation ->
                async { recommendationShelf(recommendation.jsonObject, token, mut) }
            }.mapNotNull { it.await() }
            val recent = catalogShelf("Recently Played", roots[1])
            val rotation = catalogShelf("Heavy Rotation", roots[2])
            AppleHomeResult.Ready(recommendationShelves + listOfNotNull(recent, rotation), true)
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
            val (code, body) = request(path, token, mut)
            if (code !in 200..299) throw AppleFailure(Failure.HTTP, "Apple Music recommendation expansion returned HTTP $code.")
            val expanded = json.parseToJsonElement(body).jsonObject
            if (expanded["errors"]?.jsonArray.orEmpty().any { it.toString().contains("3077") }) {
                throw AppleFailure(Failure.SERVICE_3077, "Apple Music could not expand this recommendation.")
            }
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
        val path = "/v1/catalog/${storefront()}/charts?types=albums,playlists,artists&limit=25"
        val (code, body) = request(path, token(), null)
        if (code !in 200..299) AppleHomeResult.Failed(Failure.HTTP, "Apple Music charts returned HTTP $code.")
        else {
            val data = json.parseToJsonElement(body).jsonObject["results"]?.jsonObject?.values?.firstOrNull()?.jsonObject?.get("data")?.jsonArray.orEmpty()
            AppleHomeResult.Ready(listOf(AppleHomeShelf("Popular on Apple Music", data.mapNotNull(::item), public = true)).filter { it.items.isNotEmpty() }, false)
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
            client.newCall(request).execute().use { response -> response.code to (response.body?.string().orEmpty()) }
        }
    }

    private suspend fun token(): String {
        if (developerToken != null && clock() - tokenFetchedAt < TOKEN_TTL) return developerToken!!
        val home = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { client.newCall(Request.Builder().url("https://music.apple.com/").build()).execute().use { it.body?.string().orEmpty() } }
        val asset = discoverWebPlayerAsset(home)
            ?: throw AppleFailure(Failure.HTTP, "Apple Music web-player token asset was not found.")
        val bundle = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            client.newCall(Request.Builder().url("https://music.apple.com$asset").build()).execute().use { it.body?.string().orEmpty() }
        }
        val jwt = selectWebPlayerToken(bundle)
            ?: throw AppleFailure(Failure.HTTP, "Apple Music web-player token was not found.")
        developerToken = jwt; tokenFetchedAt = clock(); return jwt
    }

    private data class AppleFailure(val kind: Failure, override val message: String?) : Exception(message)
    companion object { const val CACHE_TTL = 5 * 60 * 1000L; private const val TOKEN_TTL = 30 * 60 * 1000L }
}
