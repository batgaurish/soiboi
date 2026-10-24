package com.music.bitchord.data.smartplaylist

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import kotlinx.serialization.Serializable
import kotlin.random.Random

enum class SmartFieldKind {
    Text,
    Number,
    Date,
    Flag,
}

/** What a smart-playlist rule inspects. */
enum class SmartField(val label: String, val kind: SmartFieldKind) {
    Title("Title", SmartFieldKind.Text),
    Artist("Artist", SmartFieldKind.Text),
    Album("Album", SmartFieldKind.Text),
    AlbumArtist("Album artist", SmartFieldKind.Text),
    Genre("Genre", SmartFieldKind.Text),
    Format("Format", SmartFieldKind.Text),
    Path("Path", SmartFieldKind.Text),

    Year("Year", SmartFieldKind.Number),
    PlayCount("Play count", SmartFieldKind.Number),
    Bitrate("Bitrate (kbps)", SmartFieldKind.Number),
    DurationSeconds("Length (seconds)", SmartFieldKind.Number),
    TrackNumber("Track number", SmartFieldKind.Number),
    Bpm("BPM", SmartFieldKind.Number),
    Energy("Energy", SmartFieldKind.Number),
    Danceable("Danceable", SmartFieldKind.Number),
    Relaxed("Relaxed", SmartFieldKind.Number),
    Aggressive("Aggressive", SmartFieldKind.Number),

    Mood("Mood", SmartFieldKind.Text),
    Valence("Positivity", SmartFieldKind.Text),
    VibeEnergy("Felt energy", SmartFieldKind.Text),
    Language("Language", SmartFieldKind.Text),

    LastPlayed("Last played", SmartFieldKind.Date),
    Added("Date added", SmartFieldKind.Date),
    Modified("Date modified", SmartFieldKind.Date),

    Favourite("Favourite", SmartFieldKind.Flag),
    Explicit("Explicit", SmartFieldKind.Flag),
    Video("Video", SmartFieldKind.Flag),
}

/** Which operators a field can take. */
enum class SmartOperator(val label: String) {
    Contains("contains"),
    NotContains("does not contain"),
    Equals("is"),
    NotEquals("is not"),
    GreaterThan("is more than"),
    LessThan("is less than"),
    InLastDays("in the last (days)"),
    NotInLastDays("not in the last (days)");

    companion object {
        fun forKind(kind: SmartFieldKind): List<SmartOperator> = when (kind) {
            SmartFieldKind.Text -> listOf(Contains, NotContains, Equals, NotEquals)
            SmartFieldKind.Number -> listOf(Equals, NotEquals, GreaterThan, LessThan)
            SmartFieldKind.Date -> listOf(InLastDays, NotInLastDays)
            SmartFieldKind.Flag -> listOf(Equals, NotEquals)
        }
    }
}

/**
 * Context for rule evaluation and sorting.
 *
 * Local metadata fields stored directly on [Song] are resolved without context.
 * Context callbacks supply external/secondary properties (play count, acoustics,
 * ratings, AI vibes) when present, degrading gracefully to null without synthesizing zeros.
 */
class SmartContext(
    val nowEpochSeconds: Long = 0L,
    val isFavorite: (Song) -> Boolean = { false },
    val playCountOf: (Song) -> Int? = { null },
    val lastPlayedEpochSeconds: (Song) -> Long? = { null },
    val yearOf: (Song) -> Int? = { null },
    val genreOf: (Song) -> String? = { null },
    val albumArtistOf: (Song) -> String? = { null },
    val bitrateKbpsOf: (Song) -> Int? = { null },
    val bpmOf: (Song) -> Double? = { null },
    val energyOf: (Song) -> Double? = { null },
    val danceableOf: (Song) -> Double? = { null },
    val relaxedOf: (Song) -> Double? = { null },
    val aggressiveOf: (Song) -> Double? = { null },
    val moodOf: (Song) -> String? = { null },
    val valenceOf: (Song) -> String? = { null },
    val vibeEnergyOf: (Song) -> String? = { null },
    val languageOf: (Song) -> String? = { null },
    /** Seed used to produce the same random order on every evaluation. */
    val randomSeed: Int = 0,
)

/** Value stays text whatever the field: half-typed rules degrade to non-matching without throwing. */
@Serializable
data class SmartRule(
    val field: SmartField,
    val operator: SmartOperator,
    val value: String,
) {
    fun matches(song: Song, ctx: SmartContext): Boolean = when (field.kind) {
        SmartFieldKind.Text -> matchesText(textOf(song, ctx))
        SmartFieldKind.Number -> matchesNumber(numberOf(song, ctx))
        SmartFieldKind.Date -> matchesDate(dateOf(song, ctx), ctx.nowEpochSeconds)
        SmartFieldKind.Flag -> matchesFlag(flagOf(song, ctx))
    }

    private fun textOf(s: Song, c: SmartContext): String? = when (field) {
        SmartField.Title -> s.title
        SmartField.Artist -> s.artist
        SmartField.Album -> s.albumName
        SmartField.AlbumArtist -> c.albumArtistOf(s)
        SmartField.Genre -> c.genreOf(s)
        SmartField.Format -> s.downloadFormat ?: s.sourceQuality
        SmartField.Path -> s.localPath ?: s.localUri
        SmartField.Mood -> c.moodOf(s)
        SmartField.Valence -> c.valenceOf(s)
        SmartField.VibeEnergy -> c.vibeEnergyOf(s)
        SmartField.Language -> c.languageOf(s)
        else -> null
    }

    private fun numberOf(s: Song, c: SmartContext): Double? = when (field) {
        SmartField.Year -> c.yearOf(s)?.toDouble()
        SmartField.PlayCount -> c.playCountOf(s)?.toDouble()
        SmartField.Bitrate -> c.bitrateKbpsOf(s)?.toDouble()
        SmartField.DurationSeconds -> s.durationText?.let { (it.durationMillis() / 1000.0) }
        SmartField.TrackNumber -> s.trackNumber?.toDouble()
        SmartField.Bpm -> c.bpmOf(s)
        SmartField.Energy -> c.energyOf(s)
        SmartField.Danceable -> c.danceableOf(s)
        SmartField.Relaxed -> c.relaxedOf(s)
        SmartField.Aggressive -> c.aggressiveOf(s)
        else -> null
    }

    private fun dateOf(s: Song, c: SmartContext): Long? = when (field) {
        SmartField.LastPlayed -> c.lastPlayedEpochSeconds(s)
        SmartField.Added -> s.localDateAddedSeconds
        SmartField.Modified -> s.localDateModifiedSeconds
        else -> null
    }

    private fun flagOf(s: Song, c: SmartContext): Boolean? = when (field) {
        SmartField.Favourite -> c.isFavorite(s)
        SmartField.Video -> s.isVideo
        SmartField.Explicit -> s.isExplicit
        else -> null
    }

    private fun matchesText(actual: String?): Boolean {
        val subject = actual.orEmpty()
        val target = value.trim()
        return when (operator) {
            SmartOperator.Contains -> subject.contains(target, ignoreCase = true)
            SmartOperator.NotContains -> !subject.contains(target, ignoreCase = true)
            SmartOperator.Equals -> subject.equals(target, ignoreCase = true)
            SmartOperator.NotEquals -> !subject.equals(target, ignoreCase = true)
            else -> false
        }
    }

    private fun matchesNumber(actual: Double?): Boolean {
        val target = value.trim().toDoubleOrNull() ?: return false
        // A missing value is not zero: a track with no value does not match "< 5" or "> 5".
        if (actual == null) return operator == SmartOperator.NotEquals
        return when (operator) {
            SmartOperator.Equals -> actual == target
            SmartOperator.NotEquals -> actual != target
            SmartOperator.GreaterThan -> actual > target
            SmartOperator.LessThan -> actual < target
            else -> false
        }
    }

    private fun matchesDate(actualEpochSeconds: Long?, nowEpochSeconds: Long): Boolean {
        val days = value.trim().toLongOrNull() ?: return false
        if (days < 0L) return false
        // Never played/added is the whole point of "not in the last 90 days".
        if (actualEpochSeconds == null) return operator == SmartOperator.NotInLastDays
        val diffSeconds = nowEpochSeconds - actualEpochSeconds
        val elapsedDays = if (diffSeconds <= 0L) 0L else diffSeconds / 86400L
        val within = elapsedDays <= days
        return if (operator == SmartOperator.InLastDays) within else !within
    }

    private fun matchesFlag(actual: Boolean?): Boolean {
        val targetBool = when (value.trim().lowercase()) {
            "true", "yes", "1" -> true
            "false", "no", "0" -> false
            else -> return false
        }
        val actualBool = actual ?: return false
        return when (operator) {
            SmartOperator.Equals -> actualBool == targetBool
            SmartOperator.NotEquals -> actualBool != targetBool
            else -> false
        }
    }
}

/** How a smart playlist sorts matches prior to capping. */
enum class SmartSort(val label: String) {
    Added("Recently added"),
    Modified("Recently modified"),
    PlayCount("Most played"),
    LastPlayed("Recently played"),
    Title("Title"),
    Artist("Artist"),
    Album("Album"),
    Year("Year"),
    TrackNumber("Track number"),
    Energy("Energy"),
    Danceable("Danceable"),
    Relaxed("Relaxed"),
    Bpm("BPM"),
    Random("Shuffled"),
}

/** A playlist defined by deterministic evaluation rules over local song metadata. */
@Serializable
data class SmartPlaylist(
    val name: String,
    val rules: List<SmartRule> = emptyList(),
    /** True for "all of these", false for "any of these". */
    val matchAll: Boolean = true,
    val sort: SmartSort = SmartSort.Added,
    val descending: Boolean = true,
    /** Null for everything that matches; a non-negative cap limits the result count. */
    val limit: Int? = null,
) {
    /** Evaluates rules over the given song collection in a pure, deterministic manner. */
    fun evaluate(library: List<Song>, ctx: SmartContext = SmartContext()): List<Song> {
        // Empty rules match all songs rather than none: a playlist under construction shows what it narrows.
        val matched = if (rules.isEmpty()) {
            library.toList()
        } else {
            library.filter { song ->
                if (matchAll) rules.all { it.matches(song, ctx) }
                else rules.any { it.matches(song, ctx) }
            }
        }
        val sorted = sortSongs(matched, ctx)
        return if (limit != null && limit >= 0 && sorted.size > limit) sorted.take(limit) else sorted
    }

    private fun sortSongs(songs: List<Song>, ctx: SmartContext): List<Song> {
        if (sort == SmartSort.Random) {
            return songs.shuffled(Random(ctx.randomSeed))
        }

        val primaryComparator: Comparator<Song> = when (sort) {
            SmartSort.Added -> comparatorNullsLast(descending) { it.localDateAddedSeconds }
            SmartSort.Modified -> comparatorNullsLast(descending) { it.localDateModifiedSeconds }
            SmartSort.LastPlayed -> comparatorNullsLast(descending) { ctx.lastPlayedEpochSeconds(it) }
            SmartSort.PlayCount -> comparatorNullsLast(descending) { ctx.playCountOf(it) }
            SmartSort.Year -> comparatorNullsLast(descending) { ctx.yearOf(it) }
            SmartSort.TrackNumber -> comparatorNullsLast(descending) { it.trackNumber }
            SmartSort.Energy -> comparatorNullsLast(descending) { ctx.energyOf(it) }
            SmartSort.Danceable -> comparatorNullsLast(descending) { ctx.danceableOf(it) }
            SmartSort.Relaxed -> comparatorNullsLast(descending) { ctx.relaxedOf(it) }
            SmartSort.Bpm -> comparatorNullsLast(descending) { ctx.bpmOf(it) }
            SmartSort.Title -> comparatorText(descending) { it.title }
            SmartSort.Artist -> comparatorText(descending) { it.artist }
            SmartSort.Album -> comparatorText(descending) { it.albumName }
            SmartSort.Random -> Comparator { _, _ -> 0 }
        }

        // Common-Kotlin comparators keep sorting portable across KMP targets. The source
        // index is the final tie-breaker, so exact duplicate metadata remains stable.
        return songs.withIndex()
            .sortedWith(Comparator { left, right ->
                val a = left.value
                val b = right.value
                primaryComparator.compare(a, b)
                    .takeIf { it != 0 }
                    ?: a.title.compareTo(b.title, ignoreCase = true).takeIf { it != 0 }
                    ?: a.artist.compareTo(b.artist, ignoreCase = true).takeIf { it != 0 }
                    ?: a.videoId.compareTo(b.videoId).takeIf { it != 0 }
                    ?: left.index.compareTo(right.index)
            })
            .map { it.value }
    }

    private fun <T : Comparable<T>> comparatorNullsLast(
        descending: Boolean,
        selector: (Song) -> T?,
    ): Comparator<Song> = Comparator { a, b ->
        val va = selector(a)
        val vb = selector(b)
        when {
            va == null && vb == null -> 0
            va == null -> 1 // Null values sort last in either direction
            vb == null -> -1
            descending -> vb.compareTo(va)
            else -> va.compareTo(vb)
        }
    }

    private fun comparatorText(
        descending: Boolean,
        selector: (Song) -> String?,
    ): Comparator<Song> = Comparator { a, b ->
        val sa = selector(a).orEmpty()
        val sb = selector(b).orEmpty()
        val res = sa.compareTo(sb, ignoreCase = true)
        if (descending) -res else res
    }
}

/** Preset smart playlist template. */
class SmartPlaylistTemplate(
    val name: String,
    val icon: String,
    val description: String,
    val playlist: SmartPlaylist,
)

private fun rule(f: SmartField, o: SmartOperator, v: String) = SmartRule(f, o, v)

val SmartPlaylistTemplates = listOf(
    SmartPlaylistTemplate(
        name = "Energy",
        icon = "bolt",
        description = "Uptempo, high energy",
        playlist = SmartPlaylist(
            name = "Energy",
            rules = listOf(rule(SmartField.Energy, SmartOperator.GreaterThan, "0.6")),
            sort = SmartSort.Energy,
        ),
    ),
    SmartPlaylistTemplate(
        name = "Sleep",
        icon = "moon",
        description = "Relaxed and quiet",
        playlist = SmartPlaylist(
            name = "Sleep",
            rules = listOf(
                rule(SmartField.Relaxed, SmartOperator.GreaterThan, "0.6"),
                rule(SmartField.Energy, SmartOperator.LessThan, "0.4"),
            ),
            sort = SmartSort.Energy,
            descending = false,
        ),
    ),
    SmartPlaylistTemplate(
        name = "Workout",
        icon = "dumbbell",
        description = "Fast, high energy",
        playlist = SmartPlaylist(
            name = "Workout",
            rules = listOf(
                rule(SmartField.Energy, SmartOperator.GreaterThan, "0.6"),
                rule(SmartField.Bpm, SmartOperator.GreaterThan, "120"),
            ),
            sort = SmartSort.Energy,
        ),
    ),
    SmartPlaylistTemplate(
        name = "Dance party",
        icon = "note",
        description = "Danceable and upbeat",
        playlist = SmartPlaylist(
            name = "Dance party",
            rules = listOf(
                rule(SmartField.Danceable, SmartOperator.GreaterThan, "0.6"),
                rule(SmartField.Energy, SmartOperator.GreaterThan, "0.5"),
            ),
            sort = SmartSort.Danceable,
        ),
    ),
    SmartPlaylistTemplate(
        name = "Chill",
        icon = "lotus",
        description = "Laid-back, lower tempo",
        playlist = SmartPlaylist(
            name = "Chill",
            rules = listOf(
                rule(SmartField.Relaxed, SmartOperator.GreaterThan, "0.4"),
                rule(SmartField.Energy, SmartOperator.LessThan, "0.5"),
            ),
            sort = SmartSort.Energy,
            descending = false,
        ),
    ),
)
