package app.soiboi.engine.smart

import app.soiboi.engine.ai.SongVibe
import app.soiboi.engine.library.Song
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

enum class SmartFieldKind { Text, Number, Date, Flag }

/** What a rule looks at. */
enum class SmartField(val label: String, val kind: SmartFieldKind) {
    Title("Title", SmartFieldKind.Text), Artist("Artist", SmartFieldKind.Text), Album("Album", SmartFieldKind.Text),
    AlbumArtist("Album artist", SmartFieldKind.Text), Genre("Genre", SmartFieldKind.Text), Format("Format", SmartFieldKind.Text),
    Year("Year", SmartFieldKind.Number), PlayCount("Play count", SmartFieldKind.Number), Bitrate("Bitrate (kbps)", SmartFieldKind.Number),
    DurationSeconds("Length (seconds)", SmartFieldKind.Number), Bpm("BPM", SmartFieldKind.Number), Energy("Energy", SmartFieldKind.Number),
    Danceable("Danceable", SmartFieldKind.Number), Relaxed("Relaxed", SmartFieldKind.Number), Aggressive("Aggressive", SmartFieldKind.Number),
    // From the AI's saved tags, not the audio. Empty until the library has been tagged, so a rule on these matches nothing rather than everything.
    Mood("Mood", SmartFieldKind.Text), Valence("Positivity", SmartFieldKind.Text), VibeEnergy("Felt energy", SmartFieldKind.Text), Language("Language", SmartFieldKind.Text),
    LastPlayed("Last played", SmartFieldKind.Date), Added("Date added", SmartFieldKind.Date), Favourite("Favourite", SmartFieldKind.Flag),
}

/** Which operators a field can take. Offering "contains" on a play count would only produce confusing playlists. */
enum class SmartOperator(val label: String) {
    Contains("contains"), NotContains("does not contain"), Equals("is"), NotEquals("is not"),
    GreaterThan("is more than"), LessThan("is less than"), InLastDays("in the last (days)"), NotInLastDays("not in the last (days)");

    companion object {
        fun forKind(kind: SmartFieldKind): List<SmartOperator> = when (kind) {
            SmartFieldKind.Text -> listOf(Contains, NotContains, Equals, NotEquals)
            SmartFieldKind.Number -> listOf(Equals, NotEquals, GreaterThan, LessThan)
            // A date is only ever interesting as a window, never as an exact value.
            SmartFieldKind.Date -> listOf(InLastDays, NotInLastDays)
            SmartFieldKind.Flag -> listOf(Equals)
        }
    }
}

/** What a rule needs to know about a song beyond its tags. */
class SmartContext(
    val vibeOf: (Song) -> SongVibe? = { null },
    val isFavorite: (Song) -> Boolean = { false },
    val now: Instant = Instant.now(),
)

/** Value stays text whatever the field: parsing at match time means a half-typed rule degrades to "matches nothing" instead of failing to save. */
@Serializable
data class SmartRule(val field: SmartField, val operator: SmartOperator, val value: String) {
    fun matches(song: Song, ctx: SmartContext): Boolean = when (field.kind) {
        SmartFieldKind.Text -> matchesText(textOf(song, ctx))
        SmartFieldKind.Number -> matchesNumber(numberOf(song))
        SmartFieldKind.Date -> matchesDate(dateOf(song), ctx.now)
        SmartFieldKind.Flag -> ctx.isFavorite(song) == (value.lowercase() != "no" && value.lowercase() != "false")
    }

    private fun textOf(s: Song, c: SmartContext): String? = when (field) {
        SmartField.Title -> s.title; SmartField.Artist -> s.artist; SmartField.Album -> s.album
        SmartField.AlbumArtist -> s.albumArtist; SmartField.Genre -> s.genre; SmartField.Format -> s.format
        SmartField.Mood -> c.vibeOf(s)?.moods?.joinToString(" ")
        SmartField.Valence -> c.vibeOf(s)?.valence; SmartField.VibeEnergy -> c.vibeOf(s)?.energy; SmartField.Language -> c.vibeOf(s)?.language
        else -> null
    }

    private fun numberOf(s: Song): Double? = when (field) {
        SmartField.Year -> s.year?.toDouble(); SmartField.PlayCount -> s.playCount.toDouble()
        // Stored in bits per second; rules are written in kbps because that is how the badge on the card reads.
        SmartField.Bitrate -> s.bitrate?.let { (it / 1000).toDouble() }
        SmartField.DurationSeconds -> s.durationMs?.let { (it / 1000).toDouble() }
        SmartField.Bpm -> s.bpm; SmartField.Energy -> s.energy; SmartField.Danceable -> s.danceable
        SmartField.Relaxed -> s.relaxed; SmartField.Aggressive -> s.aggressive
        else -> null
    }

    private fun dateOf(s: Song): Instant? = when (field) { SmartField.LastPlayed -> s.lastPlayed; SmartField.Added -> s.modified; else -> null }

    private fun matchesText(actual: String?): Boolean {
        val subject = (actual ?: "").lowercase(); val target = value.lowercase().trim()
        return when (operator) {
            SmartOperator.Contains -> target in subject; SmartOperator.NotContains -> target !in subject
            SmartOperator.Equals -> subject == target; SmartOperator.NotEquals -> subject != target
            else -> false
        }
    }

    private fun matchesNumber(actual: Double?): Boolean {
        val target = value.trim().toDoubleOrNull() ?: return false
        // A missing value is not zero: a track with no year should not match "year is less than 2000".
        if (actual == null) return operator == SmartOperator.NotEquals
        return when (operator) {
            SmartOperator.Equals -> actual == target; SmartOperator.NotEquals -> actual != target
            SmartOperator.GreaterThan -> actual > target; SmartOperator.LessThan -> actual < target
            else -> false
        }
    }

    private fun matchesDate(actual: Instant?, now: Instant): Boolean {
        val days = value.trim().toLongOrNull() ?: return false
        // Never played is the whole point of "not in the last 90 days".
        if (actual == null) return operator == SmartOperator.NotInLastDays
        val within = Duration.between(actual, now).toDays() <= days
        return if (operator == SmartOperator.InLastDays) within else !within
    }
}

/** How the result is ordered before [SmartPlaylist.limit] is applied. */
enum class SmartSort(val label: String) {
    Added("Recently added"), PlayCount("Most played"), LastPlayed("Recently played"), Title("Title"), Artist("Artist"), Year("Year"),
    Energy("Energy"), Danceable("Danceable"), Relaxed("Relaxed"), Random("Shuffled")
}

/** A playlist defined by rules rather than by a fixed list: it stays right as the library changes. Everything it needs is local. */
@Serializable
data class SmartPlaylist(
    val name: String,
    val rules: List<SmartRule> = emptyList(),
    /** True for "all of these", false for "any of these". */
    val matchAll: Boolean = true,
    val sort: SmartSort = SmartSort.Added,
    val descending: Boolean = true,
    /** Null for everything that matches; a cap is what makes "50 most played rock tracks" expressible. */
    val limit: Int? = null,
) {
    /** The songs this playlist currently means. */
    fun evaluate(library: List<Song>, ctx: SmartContext = SmartContext()): List<Song> {
        // No rules matches everything rather than nothing: a playlist being built should show the library it is narrowing.
        val matched = if (rules.isEmpty()) library.toMutableList()
        else library.filter { s -> if (matchAll) rules.all { it.matches(s, ctx) } else rules.any { it.matches(s, ctx) } }.toMutableList()
        val sorted = sorted(matched)
        return if (limit != null && sorted.size > limit) sorted.subList(0, limit) else sorted
    }

    private fun sorted(songs: List<Song>): List<Song> {
        if (sort == SmartSort.Random) return songs.shuffled()
        // Missing dates sort last in either direction: a never-played track is not "played in 1970".
        val cmp: Comparator<Song> = when (sort) {
            SmartSort.Added -> compareBy<Song, Instant?>(nullsFirst()) { it.modified }
            SmartSort.LastPlayed -> compareBy<Song, Instant?>(nullsFirst()) { it.lastPlayed }
            SmartSort.PlayCount -> compareBy { it.playCount }
            SmartSort.Title -> compareBy { (it.title ?: "").lowercase() }
            SmartSort.Artist -> compareBy { (it.artist ?: "").lowercase() }
            SmartSort.Year -> compareBy { it.year ?: 0 }
            SmartSort.Energy -> compareBy { it.energy ?: -1.0 }
            SmartSort.Danceable -> compareBy { it.danceable ?: -1.0 }
            SmartSort.Relaxed -> compareBy { it.relaxed ?: -1.0 }
            SmartSort.Random -> compareBy { 0 }
        }
        return songs.sortedWith(if (descending) cmp.reversed() else cmp)
    }
}

/** A ready-made smart playlist, one tap from the editor instead of a blank form. */
class SmartPlaylistTemplate(val name: String, val icon: String, val description: String, val playlist: SmartPlaylist)

private fun rule(f: SmartField, o: SmartOperator, v: String) = SmartRule(f, o, v)

/** The fixed template set. Shipping a constant list keeps this offline and deterministic. */
val SmartPlaylistTemplates = listOf(
    SmartPlaylistTemplate("Energy", "bolt", "Uptempo, high energy",
        SmartPlaylist("Energy", listOf(rule(SmartField.Energy, SmartOperator.GreaterThan, "0.6")), sort = SmartSort.Energy)),
    SmartPlaylistTemplate("Sleep", "moon", "Relaxed and quiet",
        SmartPlaylist("Sleep", listOf(rule(SmartField.Relaxed, SmartOperator.GreaterThan, "0.6"), rule(SmartField.Energy, SmartOperator.LessThan, "0.4")),
            sort = SmartSort.Energy, descending = false)),
    SmartPlaylistTemplate("Workout", "dumbbell", "Fast, high energy",
        SmartPlaylist("Workout", listOf(rule(SmartField.Energy, SmartOperator.GreaterThan, "0.6"), rule(SmartField.Bpm, SmartOperator.GreaterThan, "120")), sort = SmartSort.Energy)),
    SmartPlaylistTemplate("Dance party", "note", "Danceable and upbeat",
        SmartPlaylist("Dance party", listOf(rule(SmartField.Danceable, SmartOperator.GreaterThan, "0.6"), rule(SmartField.Energy, SmartOperator.GreaterThan, "0.5")), sort = SmartSort.Danceable)),
    SmartPlaylistTemplate("Chill", "lotus", "Laid-back, lower tempo",
        SmartPlaylist("Chill", listOf(rule(SmartField.Relaxed, SmartOperator.GreaterThan, "0.4"), rule(SmartField.Energy, SmartOperator.LessThan, "0.5")),
            sort = SmartSort.Energy, descending = false)),
)
