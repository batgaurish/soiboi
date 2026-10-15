package app.soiboi.engine.sources

import app.soiboi.engine.core.EngineLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** A browser-like User-Agent. Several services serve a stripped page, or none, to anything else. */
const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36"

/** Shared by every source and client in the engine. */
object Http {
    val client: HttpClient by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) { requestTimeoutMillis = 20_000; connectTimeoutMillis = 10_000 }
            expectSuccess = false
        }
    }
    val json = Json { ignoreUnknownKeys = true; isLenient = true }
}

/** GETs [url] as text, or null on any failure. Sources treat an unreachable playlist as empty, not a crash. */
suspend fun fetchText(url: String, headers: Map<String, String> = emptyMap()): String? = try {
    val response = Http.client.get(url) {
        header("User-Agent", BROWSER_USER_AGENT)
        headers.forEach { (k, v) -> header(k, v) }
    }
    if (response.status == HttpStatusCode.OK) response.bodyAsText() else null
} catch (e: Exception) {
    EngineLog.output("fetch $url: $e")
    null
}

/** [fetchText], decoded as JSON, or null. */
suspend fun fetchJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement? {
    val body = fetchText(url, headers) ?: return null
    return try { Http.json.parseToJsonElement(body) } catch (_: Exception) { null }
}

/** Builds `base?k=v&...` with percent-encoding. */
fun urlWith(base: String, query: Map<String, String?>): String {
    val encoded = query.entries.filter { it.value != null }.joinToString("&") {
        "${java.net.URLEncoder.encode(it.key, "UTF-8")}=${java.net.URLEncoder.encode(it.value!!, "UTF-8")}"
    }
    return if (encoded.isEmpty()) base else "$base${if ('?' in base) '&' else '?'}$encoded"
}

// Lenient JSON accessors: source payloads change shape without notice and must cost an import, not the app.
fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray
fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.contentOrNull
fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

/** Whether [url] answers at all within eight seconds. Any status counts: the question is whether the network gets there. */
suspend fun reachable(url: String): Boolean = try {
    withTimeout(8000) { Http.client.get(url); true }
} catch (_: Exception) {
    false
}
