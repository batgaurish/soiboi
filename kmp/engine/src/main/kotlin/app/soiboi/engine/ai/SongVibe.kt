package app.soiboi.engine.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Every mood a track can carry. Kept short on purpose: a small vocabulary is something a model applies consistently. */
val VibeMoods = listOf("calm", "warm", "happy", "uplifting", "upbeat", "party", "romantic", "dreamy", "nostalgic", "melancholic", "sad", "dark", "intense", "focus")
val VibeLevels = listOf("low", "mid", "high")

/** The shape of a song's feel: moods, how positive, how energetic, and the language it is sung in. */
data class SongVibe(
    /** Up to three of [VibeMoods]. */
    val moods: List<String>,
    /** How positive the track feels, from [VibeLevels]. The axis the acoustic analyser cannot measure. */
    val valence: String,
    val energy: String,
    /** Lower-case language of the vocals, or "instrumental" or "unknown". */
    val language: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("m", JsonArray(moods.map(::JsonPrimitive))); put("v", valence); put("e", energy); put("l", language)
    }

    companion object {
        /** A tag read from the model's answer or from disk, or null when too broken to use. Out-of-vocabulary values are dropped. */
        fun fromJson(raw: JsonElement?): SongVibe? {
            val o = raw as? JsonObject ?: return null
            val rawMoods = (o["m"] ?: o["moods"]) as? JsonArray ?: return null
            val moods = rawMoods.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase()?.trim() }.filter { it in VibeMoods }.take(3)
            if (moods.isEmpty()) return null
            fun level(v: JsonElement?): String { val s = (v as? JsonPrimitive)?.contentOrNull?.lowercase()?.trim() ?: ""; return if (s in VibeLevels) s else "mid" }
            val language = ((o["l"] ?: o["language"]) as? JsonPrimitive)?.contentOrNull?.lowercase()?.trim().orEmpty().ifEmpty { "unknown" }
            return SongVibe(moods, level(o["v"] ?: o["valence"]), level(o["e"] ?: o["energy"]), language)
        }
    }
}
