package com.music.bitchord.data.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Extracts the first top-level JSON object from [text], tolerating code fences and conversational
     * chatter around it.
     */
    fun extractJsonObject(text: String): JsonObject {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw AiException("The model did not answer in the expected format")
        }
        val candidate = text.substring(start, end + 1)
        return try {
            json.parseToJsonElement(candidate) as? JsonObject
                ?: throw AiException("The model did not answer in the expected format")
        } catch (e: AiException) {
            throw e
        } catch (e: Exception) {
            throw AiException("The model did not answer in the expected format", e)
        }
    }

    /**
     * Parses album recommendations from [response].
     *
     * Validates that the payload contains an `albums` array. Entries lacking either a valid artist or album
     * title are discarded.
     *
     * @param response Raw response text from the model.
     * @param requireNonEmpty If true, throws [AiException] when no valid picks remain.
     */
    fun parse(response: String, requireNonEmpty: Boolean = false): List<AiAlbumPick> {
        val root = extractJsonObject(response)
        val albumsElement = root["albums"] ?: throw AiException("The model did not answer in the expected format")
        val albumsArray = albumsElement as? JsonArray ?: throw AiException("The model did not answer in the expected format")

        val picks = albumsArray.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val artist = (obj["artist"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val album = (obj["album"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val why = (obj["why"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (artist.isEmpty() || album.isEmpty()) null else AiAlbumPick(artist, album, why)
        }

        if (requireNonEmpty && picks.isEmpty()) {
            throw AiException("No recommendations came back")
        }
        return picks
    }
}
