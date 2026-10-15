package app.soiboi.engine.ai

import app.soiboi.engine.sources.Http
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentType
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.Serializable

enum class AiProvider { Gemini, OpenRouter, Anthropic, OpenAi, Groq, Ollama, Custom }

class AiProviderInfo(
    val label: String,
    val blurb: String,
    /** OpenAI-compatible providers: the base the `/chat/completions` and `/models` paths hang off. Anthropic: the API host. */
    val baseUrl: String,
    /** Where a key is created, opened by "Get a key". */
    val keyPage: String? = null,
    /** Used until the person picks one, and when listing models fails. */
    val defaultModel: String? = null,
    val free: Boolean = false,
    val needsKey: Boolean = true,
)

/** Two wire formats cover every provider: Anthropic's Messages API, and OpenAI-compatible chat completions for the rest. */
val AiProviders: Map<AiProvider, AiProviderInfo> = mapOf(
    AiProvider.Gemini to AiProviderInfo("Google Gemini", "Free tier, sign in with a Google account, no card",
        "https://generativelanguage.googleapis.com/v1beta/openai", "https://aistudio.google.com/apikey", "gemini-2.5-flash", free = true),
    AiProvider.OpenRouter to AiProviderInfo("OpenRouter", "Free models, plus hundreds of paid ones on one key",
        "https://openrouter.ai/api/v1", "https://openrouter.ai/keys", free = true),
    AiProvider.Anthropic to AiProviderInfo("Anthropic (Claude)", "Claude models, paid",
        "https://api.anthropic.com", "https://console.anthropic.com/settings/keys", "claude-opus-5"),
    AiProvider.OpenAi to AiProviderInfo("OpenAI", "GPT models, paid", "https://api.openai.com/v1", "https://platform.openai.com/api-keys"),
    AiProvider.Groq to AiProviderInfo("Groq", "Fast open models, free tier", "https://api.groq.com/openai/v1", "https://console.groq.com/keys", free = true),
    AiProvider.Ollama to AiProviderInfo("Ollama (local)", "Models on your own machine, nothing leaves your network", "http://localhost:11434/v1", needsKey = false),
    AiProvider.Custom to AiProviderInfo("Other (OpenAI-compatible)", "Any endpoint that speaks the OpenAI chat API", "", needsKey = false),
)

/** What the person configured. [key] is never logged or shown. */
@Serializable
data class AiConfig(val provider: AiProvider, val key: String = "", val model: String = "", val baseUrl: String = "") {
    val info: AiProviderInfo get() = AiProviders.getValue(provider)
    val effectiveBaseUrl: String get() = (baseUrl.trim().ifEmpty { info.baseUrl }).trimEnd('/')
    val effectiveModel: String get() = model.trim().ifEmpty { info.defaultModel ?: "" }
    val isUsable: Boolean get() = (!info.needsKey || key.trim().isNotEmpty()) && effectiveBaseUrl.isNotEmpty() && effectiveModel.isNotEmpty()
}

class AiException(message: String) : Exception(message)

private const val TIMEOUT_MS = 4L * 60 * 1000

private fun endpoint(c: AiConfig) = if (c.provider == AiProvider.Anthropic) "${c.effectiveBaseUrl}/v1/messages" else "${c.effectiveBaseUrl}/chat/completions"

private fun HttpRequestBuilder.applyHeaders(c: AiConfig) {
    contentType(ContentType.Application.Json)
    if (c.provider == AiProvider.Anthropic) {
        header("x-api-key", c.key.trim()); header("anthropic-version", "2023-06-01")
        // A declined request is retried on Anthropic's recommended model instead of coming back as a refusal.
        header("anthropic-beta", "server-side-fallback-2026-07-01")
    } else {
        if (c.key.trim().isNotEmpty()) header("authorization", "Bearer ${c.key.trim()}")
        // OpenRouter shows these on its activity page; harmless elsewhere.
        header("x-title", "Soiboi"); header("http-referer", "https://github.com/batgaurish/soiboi")
    }
}

private fun body(c: AiConfig, system: String, prompt: String, stream: Boolean): String = buildJsonObject {
    put("model", c.effectiveModel)
    if (stream) put("stream", true)
    if (c.provider == AiProvider.Anthropic) {
        put("max_tokens", 16000); put("fallbacks", "default"); put("system", system)
        put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", prompt) }) })
    } else {
        put("messages", buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", system) })
            add(buildJsonObject { put("role", "user"); put("content", prompt) })
        })
    }
}.toString()

/** One request, one text answer. */
suspend fun aiComplete(config: AiConfig, system: String, prompt: String): String {
    if (!config.isUsable) throw AiException("AI is not set up yet")
    val response = try {
        withTimeout(TIMEOUT_MS) { Http.client.post(endpoint(config)) { applyHeaders(config); setBody(body(config, system, prompt, false)) } }
    } catch (e: TimeoutCancellationException) {
        throw AiException("The model stopped answering. Try again, or pick a faster model.")
    }
    val decoded = decode(response.status, response.bodyAsText())
    return if (config.provider == AiProvider.Anthropic) anthropicText(decoded) else openAiText(decoded)
}

/**
 * Like [aiComplete], but reads the answer as it is written and reports the text so far through
 * [onText], so a slow model stops looking like a frozen one. Falls back to the whole body when the
 * provider ignores `stream` and answers with plain JSON.
 */
suspend fun aiCompleteStreaming(config: AiConfig, system: String, prompt: String, onText: (String) -> Unit = {}): String {
    if (!config.isUsable) throw AiException("AI is not set up yet")
    val anthropic = config.provider == AiProvider.Anthropic
    try {
        return withTimeout(TIMEOUT_MS) {
            val statement = Http.client.preparePost(endpoint(config)) { applyHeaders(config); setBody(body(config, system, prompt, true)) }
            statement.execute { response ->
                val isStream = response.status == HttpStatusCode.OK && (response.headers["content-type"] ?: "").contains("text/event-stream")
                if (!isStream) {
                    val decoded = decode(response.status, response.bodyAsText())
                    val text = if (anthropic) anthropicText(decoded) else openAiText(decoded)
                    onText(text); return@execute text
                }
                val text = StringBuilder()
                val channel = response.bodyAsChannel()
                while (true) {
                    val line = withTimeout(90_000) { channel.readUTF8Line() } ?: break
                    if (anthropic && sseRefused(line)) throw AiException("Claude declined this request")
                    val piece = sseText(line, anthropic)
                    if (piece.isNullOrEmpty()) continue
                    text.append(piece); onText(text.toString())
                }
                if (text.isBlank()) throw AiException("The model sent back nothing")
                text.toString()
            }
        }
    } catch (e: TimeoutCancellationException) {
        throw AiException("The model stopped answering. Try again, or pick a faster model.")
    }
}

private fun sseData(line: String): JsonObject? {
    if (!line.startsWith("data:")) return null
    val raw = line.substring(5).trim()
    if (raw.isEmpty() || raw == "[DONE]") return null
    return try { Http.json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null }
}

/** The text carried by one server-sent-events line, or null for any line that carries none (comments, keep-alives, `[DONE]`). */
fun sseText(line: String, anthropic: Boolean): String? {
    val data = sseData(line) ?: return null
    if (anthropic) {
        val delta = data["delta"] as? JsonObject
        return if (delta?.get("type")?.jsonPrimitive?.contentOrNull == "text_delta") delta["text"]?.jsonPrimitive?.contentOrNull else null
    }
    val first = (data["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
    return ((first["delta"] as? JsonObject)?.get("content"))?.jsonPrimitive?.contentOrNull
}

/** Whether a Claude stream line reports that the request was declined. */
fun sseRefused(line: String): Boolean =
    ((sseData(line)?.get("delta") as? JsonObject)?.get("stop_reason"))?.jsonPrimitive?.contentOrNull == "refusal"

private fun anthropicText(body: JsonObject): String {
    if (body["stop_reason"]?.jsonPrimitive?.contentOrNull == "refusal") throw AiException("Claude declined this request")
    val text = (body["content"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        .filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }.joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull ?: "" }
    if (text.isBlank()) throw AiException("The model sent back nothing")
    return text
}

private fun openAiText(body: JsonObject): String {
    val message = ((body["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
    val text = message?.get("content")?.jsonPrimitive?.contentOrNull
    if (text.isNullOrBlank()) throw AiException("The model sent back nothing")
    return text
}

private fun decode(status: HttpStatusCode, text: String): JsonObject {
    val body = try { Http.json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
    if (status.value in 200..299 && body != null) return body
    throw AiException(explain(status.value, body))
}

/** Provider errors in words a person can act on. */
private fun explain(status: Int, body: JsonObject?): String {
    val err = body?.get("error")
    val detail = when (err) { is JsonObject -> err["message"]?.jsonPrimitive?.contentOrNull; is kotlinx.serialization.json.JsonPrimitive -> err.contentOrNull; else -> null }
    val hint = when {
        status == 401 || status == 403 -> "The key was refused. Check it was copied whole."
        status == 404 -> "That model or address was not found."
        status == 429 -> "Rate limited or out of free quota. Wait a minute and try again."
        status >= 500 -> "The provider is having trouble. Try again shortly."
        else -> "The request failed ($status)."
    }
    return if (detail.isNullOrEmpty()) hint else "$hint\n$detail"
}

/** The provider's model ids, for the model picker. Empty when the provider has no listing endpoint or the request fails. */
suspend fun aiListModels(c: AiConfig): List<String> = try {
    val anthropic = c.provider == AiProvider.Anthropic
    val url = if (anthropic) "${c.effectiveBaseUrl}/v1/models?limit=100" else "${c.effectiveBaseUrl}/models"
    val response = withTimeout(20_000) {
        Http.client.get(url) {
            if (anthropic) { header("x-api-key", c.key.trim()); header("anthropic-version", "2023-06-01") }
            else if (c.key.trim().isNotEmpty()) header("authorization", "Bearer ${c.key.trim()}")
        }
    }
    (decode(response.status, response.bodyAsText())["data"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }
        .map { it.removePrefix("models/") }.filter { it.isNotEmpty() }
} catch (_: Exception) {
    emptyList()
}

/** A sensible model from [ids] when the provider has no fixed default: a free one on OpenRouter, a chat model elsewhere. */
fun pickDefaultModel(provider: AiProvider, ids: List<String>): String? {
    if (ids.isEmpty()) return null
    val notChat = Regex("embed|whisper|tts|audio|image|vision-only|guard|moderation|dall-e|transcribe|realtime", RegexOption.IGNORE_CASE)
    val usable = ids.filterNot { notChat.containsMatchIn(it) }
    if (usable.isEmpty()) return ids.first()
    return when (provider) {
        AiProvider.OpenRouter -> {
            val free = usable.filter { it.endsWith(":free") }
            listOf("deepseek", "llama", "gemini", "qwen", "mistral").firstNotNullOfOrNull { pref -> free.firstOrNull { pref in it } } ?: free.firstOrNull() ?: usable.first()
        }
        AiProvider.Gemini -> usable.firstOrNull { "flash" in it && "lite" !in it } ?: usable.first()
        AiProvider.Groq -> usable.firstOrNull { "versatile" in it } ?: usable.firstOrNull { "llama" in it } ?: usable.first()
        AiProvider.OpenAi -> usable.firstOrNull { it.startsWith("gpt-") } ?: usable.first()
        else -> usable.first()
    }
}

/** The first JSON object in [text], tolerating code fences and chatter around it, since not every model honours "reply with JSON only". */
fun extractJsonObject(text: String): JsonObject {
    val start = text.indexOf('{'); val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) throw AiException("The model did not answer in the expected format")
    return try { Http.json.parseToJsonElement(text.substring(start, end + 1)) as JsonObject } catch (_: Exception) {
        throw AiException("The model did not answer in the expected format")
    }
}
