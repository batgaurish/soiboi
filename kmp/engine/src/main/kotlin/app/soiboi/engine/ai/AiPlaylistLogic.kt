package app.soiboi.engine.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.min
import kotlin.random.Random

/** A song as playlist logic sees it: just what is worth sending. */
class Track(val title: String, val artist: String, val vibe: SongVibe? = null, val album: String = "")

/** How many songs a request asks for, and whether that is a floor ("at least 60" keeps every pick). */
class CountAsk(val count: Int, val atLeast: Boolean)

private val countPattern = Regex(
    "(?:(at\\s?least|minimum(?:\\s+of)?|min|over|more\\s+than)\\s+)?(\\d{1,3})\\s*(?:\\+\\s*)?(?:songs?|tracks?|tunes?)\\b", RegexOption.IGNORE_CASE,
)

/** The song count in [request], or null when it names none. */
fun requestedCount(request: String): CountAsk? {
    val m = countPattern.find(request) ?: return null
    val n = m.groupValues[2].toInt()
    if (n < 1) return null
    return CountAsk(min(n, 200), atLeast = m.groupValues[1].isNotEmpty() || '+' in m.value)
}

/** What a request asks for in terms the saved tags can answer, filled in by the model from the request alone. */
class VibeBrief(
    val moods: List<String> = emptyList(),
    /** Moods that rule a song out ("not sad"). */
    val avoid: List<String> = emptyList(),
    val valence: String = "any",
    val energy: String = "any",
    /** Languages wanted, lower case. Empty means any. */
    val languages: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = moods.isEmpty() && avoid.isEmpty() && valence == "any" && energy == "any" && languages.isEmpty()

    companion object {
        fun fromJson(json: JsonObject): VibeBrief {
            fun words(v: Any?, only: Collection<String>? = null): List<String> =
                (v as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase()?.trim() }.filter { only == null || it in only }
            fun level(v: Any?): String { val s = ((v as? JsonPrimitive)?.contentOrNull ?: "any").lowercase().trim(); return if (s in VibeLevels) s else "any" }
            return VibeBrief(words(json["moods"], VibeMoods), words(json["avoid"], VibeMoods), level(json["valence"]), level(json["energy"]), words(json["languages"]))
        }
    }
}

/**
 * How well [vibe] fits [brief]; zero or less means it does not. Language is a hard filter, because
 * a Hindi song in an English-only playlist is wrong however well it fits the mood. An unknown
 * language is let through.
 */
fun vibeScore(vibe: SongVibe, brief: VibeBrief): Double {
    if (brief.languages.isNotEmpty() && vibe.language != "unknown" && vibe.language !in brief.languages) return 0.0
    if (brief.avoid.any { it in vibe.moods }) return 0.0
    var score = 0.0
    for (mood in brief.moods) if (mood in vibe.moods) score += 2
    if (brief.valence != "any") score += levelFit(vibe.valence, brief.valence)
    if (brief.energy != "any") score += levelFit(vibe.energy, brief.energy)
    // A brief with nothing but languages still has to let matching songs in.
    if (brief.moods.isEmpty() && brief.valence == "any" && brief.energy == "any" && score == 0.0) return 0.5
    // A neighbouring level alone is not a fit: "calm" must not admit a party song because both sit at mid energy.
    return if (score >= 1) score else 0.0
}

/** 1 for an exact level, 0.4 for a neighbouring one, a penalty for opposite. */
private fun levelFit(have: String, want: String): Double =
    when (kotlin.math.abs(VibeLevels.indexOf(have) - VibeLevels.indexOf(want))) { 0 -> 1.0; 1 -> 0.4; else -> -1.0 }

/** A pick of at most [cap] indexes into [tracks] that keeps variety: songs are taken from each artist in turn. */
fun diverseSample(tracks: List<Track>, cap: Int, only: Iterable<Int>? = null, random: Random = Random): List<Int> {
    val byArtist = LinkedHashMap<String, MutableList<Int>>()
    for (i in only ?: tracks.indices) byArtist.getOrPut(tracks[i].artist.lowercase()) { ArrayList() } += i
    val queues = byArtist.values.toMutableList().apply { shuffle(random) }
    queues.forEach { it.shuffle(random) }
    val out = ArrayList<Int>()
    var round = 0
    while (out.size < cap) {
        var any = false
        for (q in queues) {
            if (round < q.size) { out += q[round]; any = true; if (out.size >= cap) break }
        }
        if (!any) break
        round++
    }
    return out
}

/**
 * The songs worth sending for [brief], as indexes into [tracks], best first. Tagged songs that fit
 * come first; when too few fit, the rest is topped up with a diverse sample of untagged songs, so a
 * half-tagged library still gets a full list.
 */
fun pickCandidates(tracks: List<Track>, brief: VibeBrief, cap: Int, random: Random = Random): List<Int> {
    val scored = ArrayList<Pair<Int, Double>>(); val untagged = ArrayList<Int>()
    for (i in tracks.indices) {
        val vibe = tracks[i].vibe
        if (vibe == null) { untagged += i; continue }
        val s = vibeScore(vibe, brief)
        if (s > 0) scored += i to s
    }
    scored.sortByDescending { it.second }
    // Equal scores are common; without this the same artists always lead.
    val best = ArrayList<Int>()
    var tier = 0
    while (tier < scored.size) {
        var end = tier
        while (end < scored.size && scored[end].second == scored[tier].second) end++
        val group = (tier until end).map { scored[it].first }
        best += diverseSample(tracks, group.size, group, random)
        tier = end
    }
    val picked = best.take(cap).toMutableList()
    if (picked.size < cap) picked += diverseSample(tracks, cap - picked.size, untagged, random)
    return picked
}

/** One catalogue line: number, title, artist and, when known, the moods. Album, genre and year cost tokens and say less about feel. */
fun compactLine(number: Int, t: Track): String {
    fun clean(v: String) = v.replace('|', '/').trim()
    val moods = t.vibe?.let { " | ${it.moods.joinToString(",")}" } ?: ""
    return "$number | ${clean(t.title)} | ${clean(t.artist)}$moods"
}

/** Prompt that turns a request into a [VibeBrief], with no library in it. */
const val BRIEF_SYSTEM_PROMPT =
    "You read a request for a playlist and describe what it needs. Reply with JSON only, no prose, in exactly this shape: " +
        "{\"moods\": [...], \"avoid\": [...], \"valence\": \"any\", \"energy\": \"any\", \"languages\": [...]}. " +
        "\"moods\" are the feelings wanted and \"avoid\" the feelings ruled out, each chosen only from: calm, warm, happy, uplifting, upbeat, party, romantic, " +
        "dreamy, nostalgic, melancholic, sad, dark, intense, focus. \"valence\" is how positive the songs should feel and \"energy\" how energetic, each one " +
        "of any, low, mid, high. \"languages\" lists the languages of the vocals in lower case, empty if the request does not care. \"Calm but not sad\" is " +
        "{\"moods\": [\"calm\"], \"avoid\": [\"sad\", \"melancholic\"], \"energy\": \"low\"}."

/** How many track numbers a half-written answer holds so far, for a progress readout. */
fun picksSoFar(partial: String): Int {
    val key = partial.indexOf("\"tracks\""); if (key < 0) return 0
    val open = partial.indexOf('[', key); if (open < 0) return 0
    val close = partial.indexOf(']', open)
    return Regex("\\d+").findAll(partial.substring(open + 1, if (close < 0) partial.length else close)).count()
}
