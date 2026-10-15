package app.soiboi.engine.smart

import app.soiboi.engine.ai.SongVibe
import app.soiboi.engine.library.Song
import java.time.LocalDateTime

/** One mood shown on Home, with the list and count already resolved. */
class MoodCardData(val icon: String, val name: String, val playlist: SmartPlaylist, val trackCount: Int)

private const val MIN_TRACKS = 5

/** Share of the library that must carry tags before the shelf trusts them. Below this it would be built from a sliver and look empty. */
private const val MIN_TAGGED_SHARE = 0.2

private fun anyMood(name: String, moods: List<String>) = SmartPlaylist(
    name, moods.map { SmartRule(SmartField.Mood, SmartOperator.Contains, it) }, matchAll = false, sort = SmartSort.Random,
)

private fun taggedPrimaryFor(hour: Int) = when (hour) {
    in 5..10 -> anyMood("Morning Light", listOf("uplifting", "warm", "happy"))
    in 11..16 -> anyMood("Upbeat Mix", listOf("upbeat", "party", "happy"))
    in 17..21 -> anyMood("Wind Down", listOf("calm", "warm", "romantic"))
    else -> anyMood("Late Night Drive", listOf("dreamy", "melancholic", "nostalgic", "calm"))
}

private fun r(f: SmartField, o: SmartOperator, v: String) = SmartRule(f, o, v)

private fun morning() = SmartPlaylist("Morning Light", listOf(r(SmartField.Energy, SmartOperator.GreaterThan, "0.2"), r(SmartField.Energy, SmartOperator.LessThan, "0.5")), sort = SmartSort.Energy, descending = false)
private fun upbeat() = SmartPlaylist("Upbeat Mix", listOf(r(SmartField.Energy, SmartOperator.GreaterThan, "0.5"), r(SmartField.Danceable, SmartOperator.GreaterThan, "0.4")), sort = SmartSort.Energy)
private fun evening() = SmartPlaylist("Wind Down", listOf(r(SmartField.Relaxed, SmartOperator.GreaterThan, "0.5"), r(SmartField.Energy, SmartOperator.LessThan, "0.5")), sort = SmartSort.Relaxed)
private fun lateNight() = SmartPlaylist("Late Night Drive", listOf(r(SmartField.Energy, SmartOperator.LessThan, "0.3"), r(SmartField.Relaxed, SmartOperator.GreaterThan, "0.5")), sort = SmartSort.Energy, descending = false)
private fun focus() = SmartPlaylist("Deep Focus", listOf(r(SmartField.Energy, SmartOperator.GreaterThan, "0.3"), r(SmartField.Danceable, SmartOperator.LessThan, "0.4")), sort = SmartSort.Energy, descending = false)

private fun primaryFor(hour: Int) = when (hour) { in 5..10 -> morning(); in 11..16 -> upbeat(); in 17..21 -> evening(); else -> lateNight() }
private fun iconFor(hour: Int) = when (hour) { in 5..10 -> "sunrise"; in 11..16 -> "sun"; in 17..21 -> "dusk"; else -> "moon" }

private fun coverage(songs: List<Song>, vibeOf: (Song) -> SongVibe?): Double =
    if (songs.isEmpty()) 0.0 else songs.count { vibeOf(it) != null }.toDouble() / songs.size

/** Whether mood playlists are missing only because nothing is analysed yet, so Home should offer to analyse. */
fun moodsNeedAnalysis(songs: List<Song>, vibeOf: (Song) -> SongVibe?): Boolean =
    songs.isNotEmpty() && coverage(songs, vibeOf) < MIN_TAGGED_SHARE && songs.none { it.energy != null || it.danceable != null || it.relaxed != null }

/**
 * Moods worth showing right now, empty if nothing passes the gate. Once the AI has tagged enough of
 * the library the shelf reads those tags, since a tag can say a song is happy or sad and nothing
 * measured from the audio can; until then it falls back to audio features, which only know loud and quiet.
 */
fun autoMoodPlaylists(songs: List<Song>, vibeOf: (Song) -> SongVibe?, now: LocalDateTime = LocalDateTime.now()): List<MoodCardData> {
    val hour = now.hour
    val tagged = coverage(songs, vibeOf) >= MIN_TAGGED_SHARE
    val ctx = SmartContext(vibeOf = vibeOf)
    val candidates = listOf(
        iconFor(hour) to if (tagged) taggedPrimaryFor(hour) else primaryFor(hour),
        "lotus" to if (tagged) anyMood("Deep Focus", listOf("focus", "calm")) else focus(),
    )
    return candidates.mapNotNull { (icon, playlist) ->
        val tracks = playlist.evaluate(songs, ctx)
        if (tracks.size < MIN_TRACKS) null else MoodCardData(icon, playlist.name, playlist, tracks.size)
    }
}
