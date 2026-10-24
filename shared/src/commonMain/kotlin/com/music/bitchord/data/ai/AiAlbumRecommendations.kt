package com.music.bitchord.data.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Signals a failure during AI payload parsing or response handling. */
class AiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A structured album recommendation pick suggested by an AI provider.
 *
 * Preserves the artist and album name along with an optional explanation of why the album was recommended.
 */
@Serializable
data class AiAlbumPick(
    val artist: String,
    val album: String,
    val why: String = "",
)

/**
 * Robust, provider-neutral response parser for AI album recommendations.
 *
 * Tolerates Markdown code fences, conversational preambles, and postambles produced by LLMs while
 * enforcing schema validation on the extracted payload.
 */
object AiAlbumRecommendationParser {

    const val MAX_RESPONSE_CHARS = 65_536
    const val MAX_ALBUMS = 50

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    /**
     * Extracts exactly one balanced JSON object, tolerating code fences and conversational chatter.
     */
    fun extractJsonObject(text: String): JsonObject {
        if (text.length > MAX_RESPONSE_CHARS) throw AiException("The model response was too large")
        val objects = mutableListOf<JsonObject>()
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val char = text[i]
            if (start < 0) {
                if (char == '{') {
                    start = i
                    depth = 1
                }
                continue
            }
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        val candidate = text.substring(start, i + 1)
                        try {
                            (json.parseToJsonElement(candidate) as? JsonObject)?.let(objects::add)
                        } catch (_: Exception) {
                            // Ignore braces in surrounding prose; a malformed sole payload is rejected below.
                        }
                        start = -1
                    }
                }
            }
        }
        if (objects.size != 1) throw AiException("The model did not answer in the expected format")
        return objects.single()
    }

    /**
     * Parses album recommendations from [response]. Entries may omit artist/album or leave them blank;
     * malformed field types and non-object entries reject the payload. Responses are bounded to 64 KiB
     * and 50 album entries.
     */
    fun parse(response: String, requireNonEmpty: Boolean = false): List<AiAlbumPick> {
        val root = extractJsonObject(response)
        val albumsElement = root["albums"] ?: throw AiException("The model did not answer in the expected format")
        val albumsArray = albumsElement as? JsonArray ?: throw AiException("The model did not answer in the expected format")
        if (albumsArray.size > MAX_ALBUMS) throw AiException("The model returned too many recommendations")

        val picks = albumsArray.map { element ->
            val obj = element as? JsonObject ?: throw AiException("The model did not answer in the expected format")
            fun stringField(name: String, required: Boolean): String {
                val value = obj[name] ?: return if (required) "" else ""
                val primitive = value as? JsonPrimitive
                if (primitive == null || !primitive.isString) throw AiException("The model did not answer in the expected format")
                return primitive.content.trim()
            }
            val artist = stringField("artist", required = true)
            val album = stringField("album", required = true)
            val why = stringField("why", required = false)
            if (artist.isEmpty() || album.isEmpty()) null else AiAlbumPick(artist, album, why)
        }.filterNotNull()

        if (requireNonEmpty && picks.isEmpty()) throw AiException("No recommendations came back")
        return picks
    }
}
