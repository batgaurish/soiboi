package com.music.bitchord.data.smartplaylist

import com.music.bitchord.data.model.Song
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmartPlaylistTest {
    private val nowEpochSeconds = 1760486400L // 2026-10-15T00:00:00Z
    private val ctx = SmartContext(
        nowEpochSeconds = nowEpochSeconds,
        random = Random(42),
    )

    private fun song(
        id: String,
        title: String = id,
        artist: String = "Test Artist",
        album: String? = null,
        durationText: String? = null,
        format: String? = null,
        trackNumber: Int? = null,
        addedSeconds: Long? = null,
        modifiedSeconds: Long? = null,
        isVideo: Boolean = false,
        isExplicit: Boolean? = null,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = durationText,
        albumName = album,
        downloadFormat = format,
        trackNumber = trackNumber,
        localDateAddedSeconds = addedSeconds,
        localDateModifiedSeconds = modifiedSeconds,
        isVideo = isVideo,
        isExplicit = isExplicit,
    )

    @Test
    fun missingNumbersAreNotZero() {
        val lessThan = SmartRule(SmartField.TrackNumber, SmartOperator.LessThan, "5")
        // Song without trackNumber must NOT match less than 5 (missing is not 0)
        assertFalse(lessThan.matches(song("a"), ctx))
        // Song with trackNumber 2 matches less than 5
        assertTrue(lessThan.matches(song("b", trackNumber = 2), ctx))
        // Missing trackNumber DOES match NotEquals
        assertTrue(SmartRule(SmartField.TrackNumber, SmartOperator.NotEquals, "5").matches(song("a"), ctx))
        // Missing trackNumber does NOT match Equals or GreaterThan
        assertFalse(SmartRule(SmartField.TrackNumber, SmartOperator.Equals, "5").matches(song("a"), ctx))
        assertFalse(SmartRule(SmartField.TrackNumber, SmartOperator.GreaterThan, "5").matches(song("a"), ctx))
    }

    @Test
    fun missingDurationIsNotZero() {
        val lessThan = SmartRule(SmartField.DurationSeconds, SmartOperator.LessThan, "180")
        assertFalse(lessThan.matches(song("a", durationText = null), ctx))
        assertTrue(lessThan.matches(song("b", durationText = "2:30"), ctx)) // 150s < 180s
        assertFalse(lessThan.matches(song("c", durationText = "3:30"), ctx)) // 210s < 180s is false
    }

    @Test
    fun missingContextNumberIsNotZero() {
        // Year provided via context
        val contextWithYears = SmartContext(
            nowEpochSeconds = nowEpochSeconds,
            yearOf = { s -> if (s.videoId == "with_year") 1995 else null },
        )
        val rule = SmartRule(SmartField.Year, SmartOperator.LessThan, "2000")
        assertFalse(rule.matches(song("no_year"), contextWithYears))
        assertTrue(rule.matches(song("with_year"), contextWithYears))
        assertTrue(SmartRule(SmartField.Year, SmartOperator.NotEquals, "2000").matches(song("no_year"), contextWithYears))
    }

    @Test
    fun neverAddedOrPlayedMatchesNotInLastDays() {
        // Missing date on Added matches NotInLastDays
        assertTrue(SmartRule(SmartField.Added, SmartOperator.NotInLastDays, "90").matches(song("a"), ctx))
        assertFalse(SmartRule(SmartField.Added, SmartOperator.InLastDays, "90").matches(song("a"), ctx))

        // Recent song (10 days ago) matches InLastDays(90)
        val tenDaysAgo = nowEpochSeconds - (10L * 86400L)
        val recent = song("recent", addedSeconds = tenDaysAgo)
        assertTrue(SmartRule(SmartField.Added, SmartOperator.InLastDays, "90").matches(recent, ctx))
        assertFalse(SmartRule(SmartField.Added, SmartOperator.NotInLastDays, "90").matches(recent, ctx))

        // Old song (100 days ago) matches NotInLastDays(90)
        val hundredDaysAgo = nowEpochSeconds - (100L * 86400L)
        val old = song("old", addedSeconds = hundredDaysAgo)
        assertFalse(SmartRule(SmartField.Added, SmartOperator.InLastDays, "90").matches(old, ctx))
        assertTrue(SmartRule(SmartField.Added, SmartOperator.NotInLastDays, "90").matches(old, ctx))
    }

    @Test
    fun contextDateLastPlayed() {
        val tenDaysAgo = nowEpochSeconds - (10L * 86400L)
        val ctxWithPlayed = SmartContext(
            nowEpochSeconds = nowEpochSeconds,
            lastPlayedEpochSeconds = { s -> if (s.videoId == "played") tenDaysAgo else null },
        )
        assertTrue(SmartRule(SmartField.LastPlayed, SmartOperator.NotInLastDays, "90").matches(song("unplayed"), ctxWithPlayed))
        assertTrue(SmartRule(SmartField.LastPlayed, SmartOperator.InLastDays, "90").matches(song("played"), ctxWithPlayed))
    }

    @Test
    fun halfTypedRulesMatchNothingInsteadOfFailing() {
        assertFalse(SmartRule(SmartField.TrackNumber, SmartOperator.GreaterThan, "abc").matches(song("a", trackNumber = 10), ctx))
        assertFalse(SmartRule(SmartField.Added, SmartOperator.InLastDays, "xyz").matches(song("a", addedSeconds = nowEpochSeconds), ctx))
        assertFalse(SmartRule(SmartField.DurationSeconds, SmartOperator.LessThan, "").matches(song("a", durationText = "3:00"), ctx))
    }

    @Test
    fun emptyRulesMatchesAllSongs() {
        val songs = listOf(song("1"), song("2"), song("3"))
        val evaluated = SmartPlaylist(name = "All", rules = emptyList()).evaluate(songs, ctx)
        assertEquals(3, evaluated.size)
        assertEquals(setOf("1", "2", "3"), evaluated.map { it.videoId }.toSet())
    }

    @Test
    fun matchAllVersusAnyAndLimit() {
        val songs = listOf(
            song("a", title = "Rock Anthem", format = "FLAC"),
            song("b", title = "Pop Anthem", format = "FLAC"),
            song("c", title = "Rock Ballad", format = "MP3"),
        )
        val rules = listOf(
            SmartRule(SmartField.Title, SmartOperator.Contains, "rock"),
            SmartRule(SmartField.Format, SmartOperator.Equals, "FLAC"),
        )

        // matchAll = true -> only "a"
        val allMatches = SmartPlaylist("MatchAll", rules, matchAll = true, sort = SmartSort.Title, descending = false).evaluate(songs, ctx)
        assertEquals(listOf("a"), allMatches.map { it.videoId })

        // matchAll = false -> "a", "b", "c"
        val anyMatches = SmartPlaylist("MatchAny", rules, matchAll = false).evaluate(songs, ctx)
        assertEquals(setOf("a", "b", "c"), anyMatches.map { it.videoId }.toSet())

        // limit
        val limited = SmartPlaylist("Limited", emptyList(), limit = 2).evaluate(songs, ctx)
        assertEquals(2, limited.size)

        val zeroLimit = SmartPlaylist("Zero", emptyList(), limit = 0).evaluate(songs, ctx)
        assertEquals(0, zeroLimit.size)
    }

    @Test
    fun textOperatorMatching() {
        val song = song("s1", title = "Bohemian Rhapsody", artist = "Queen", album = "A Night at the Opera")

        assertTrue(SmartRule(SmartField.Title, SmartOperator.Contains, "rhapsody").matches(song, ctx))
        assertFalse(SmartRule(SmartField.Title, SmartOperator.Contains, "pop").matches(song, ctx))

        assertTrue(SmartRule(SmartField.Artist, SmartOperator.Equals, "queen").matches(song, ctx))
        assertFalse(SmartRule(SmartField.Artist, SmartOperator.Equals, "king").matches(song, ctx))

        assertTrue(SmartRule(SmartField.Album, SmartOperator.NotContains, "disco").matches(song, ctx))
        assertFalse(SmartRule(SmartField.Album, SmartOperator.NotContains, "opera").matches(song, ctx))

        assertTrue(SmartRule(SmartField.Title, SmartOperator.NotEquals, "Other").matches(song, ctx))
        assertFalse(SmartRule(SmartField.Title, SmartOperator.NotEquals, "bohemian rhapsody").matches(song, ctx))
    }

    @Test
    fun flagRulesFavouriteAndVideo() {
        val ctxFav = SmartContext(isFavorite = { it.videoId == "fav" })
        val favSong = song("fav", isVideo = true)
        val normalSong = song("normal", isVideo = false)

        val favRule = SmartRule(SmartField.Favourite, SmartOperator.Equals, "true")
        assertTrue(favRule.matches(favSong, ctxFav))
        assertFalse(favRule.matches(normalSong, ctxFav))

        val notFavRule = SmartRule(SmartField.Favourite, SmartOperator.Equals, "false")
        assertFalse(notFavRule.matches(favSong, ctxFav))
        assertTrue(notFavRule.matches(normalSong, ctxFav))

        val videoRule = SmartRule(SmartField.Video, SmartOperator.Equals, "true")
        assertTrue(videoRule.matches(favSong, ctxFav))
        assertFalse(videoRule.matches(normalSong, ctxFav))
    }

    @Test
    fun stableDeterministicSortingAndTieBreaking() {
        val s1 = song("id1", title = "Alpha", artist = "Artist B")
        val s2 = song("id2", title = "Alpha", artist = "Artist A")
        val s3 = song("id3", title = "Beta", artist = "Artist A")

        // Sort by Title asc: both s1 and s2 have "Alpha". Tie-break by artist asc -> s2 then s1.
        val sortedAsc = SmartPlaylist("SortTitle", emptyList(), sort = SmartSort.Title, descending = false)
            .evaluate(listOf(s3, s1, s2), ctx)
        assertEquals(listOf("id2", "id1", "id3"), sortedAsc.map { it.videoId })

        // Sort by Title desc: "Beta" first (s3). For "Alpha", tie-break ensures determinism.
        val sortedDesc = SmartPlaylist("SortTitleDesc", emptyList(), sort = SmartSort.Title, descending = true)
            .evaluate(listOf(s3, s1, s2), ctx)
        assertEquals("id3", sortedDesc.first().videoId)
    }

    @Test
    fun missingDateSortsLastInBothDirections() {
        val withDate1 = song("d1", addedSeconds = 1000L)
        val withDate2 = song("d2", addedSeconds = 2000L)
        val noDate = song("nodate", addedSeconds = null)

        // Descending (newest first): d2 (2000), d1 (1000), noDate
        val desc = SmartPlaylist("DateDesc", emptyList(), sort = SmartSort.Added, descending = true)
            .evaluate(listOf(noDate, withDate1, withDate2), ctx)
        assertEquals(listOf("d2", "d1", "nodate"), desc.map { it.videoId })

        // Ascending (oldest first): d1 (1000), d2 (2000), noDate
        val asc = SmartPlaylist("DateAsc", emptyList(), sort = SmartSort.Added, descending = false)
            .evaluate(listOf(noDate, withDate2, withDate1), ctx)
        assertEquals(listOf("d1", "d2", "nodate"), asc.map { it.videoId })
    }

    @Test
    fun injectableSeededRandomSortIsDeterministic() {
        val songs = (1..20).map { song("s$it", title = "Track $it") }

        val playlist = SmartPlaylist("Random", emptyList(), sort = SmartSort.Random)
        val res1 = playlist.evaluate(songs, SmartContext(random = Random(12345)))
        val res2 = playlist.evaluate(songs, SmartContext(random = Random(12345)))
        val resDiffSeed = playlist.evaluate(songs, SmartContext(random = Random(99999)))

        assertEquals(res1.map { it.videoId }, res2.map { it.videoId })
        // Different seed produces different ordering
        assertFalse(res1.map { it.videoId } == resDiffSeed.map { it.videoId })
    }

    @Test
    fun acousticContextFeaturesFilter() {
        val songs = listOf(song("s1"), song("s2"), song("s3"))
        val acousticContext = SmartContext(
            bpmOf = { if (it.videoId == "s1") 128.0 else 90.0 },
            energyOf = { if (it.videoId == "s1") 0.85 else 0.4 },
        )

        val workoutRule = listOf(
            SmartRule(SmartField.Bpm, SmartOperator.GreaterThan, "120"),
            SmartRule(SmartField.Energy, SmartOperator.GreaterThan, "0.6"),
        )
        val result = SmartPlaylist("Workout", workoutRule, matchAll = true).evaluate(songs, acousticContext)
        assertEquals(listOf("s1"), result.map { it.videoId })
    }

    @Test
    fun serializationRoundTrip() {
        val original = SmartPlaylist(
            name = "My Dynamic Mix",
            rules = listOf(
                SmartRule(SmartField.Artist, SmartOperator.Contains, "Beatles"),
                SmartRule(SmartField.Added, SmartOperator.InLastDays, "30"),
                SmartRule(SmartField.TrackNumber, SmartOperator.GreaterThan, "3"),
            ),
            matchAll = false,
            sort = SmartSort.Title,
            descending = false,
            limit = 25,
        )

        val json = kotlinx.serialization.json.Json.encodeToString(SmartPlaylist.serializer(), original)
        val decoded = kotlinx.serialization.json.Json.decodeFromString(SmartPlaylist.serializer(), json)

        assertEquals(original, decoded)
    }

    @Test
    fun operatorForKindEnforcement() {
        assertEquals(
            listOf(SmartOperator.Contains, SmartOperator.NotContains, SmartOperator.Equals, SmartOperator.NotEquals),
            SmartOperator.forKind(SmartFieldKind.Text),
        )
        assertEquals(
            listOf(SmartOperator.Equals, SmartOperator.NotEquals, SmartOperator.GreaterThan, SmartOperator.LessThan),
            SmartOperator.forKind(SmartFieldKind.Number),
        )
        assertEquals(
            listOf(SmartOperator.InLastDays, SmartOperator.NotInLastDays),
            SmartOperator.forKind(SmartFieldKind.Date),
        )
        assertEquals(
            listOf(SmartOperator.Equals, SmartOperator.NotEquals),
            SmartOperator.forKind(SmartFieldKind.Flag),
        )
    }

    @Test
    fun templatesAreValidAndNonEmpty() {
        assertTrue(SmartPlaylistTemplates.isNotEmpty())
        for (template in SmartPlaylistTemplates) {
            assertTrue(template.name.isNotBlank())
            assertTrue(template.icon.isNotBlank())
            assertTrue(template.description.isNotBlank())
            assertEquals(template.name, template.playlist.name)
        }
    }

    @Test
    fun emptyLibraryEvaluatesToEmpty() {
        val emptySongs = emptyList<Song>()
        val result = SmartPlaylist("Empty", emptyList(), limit = 10).evaluate(emptySongs, ctx)
        assertTrue(result.isEmpty())
    }

    @Test
    fun negativeOrZeroDaysRule() {
        val recent = song("s1", addedSeconds = nowEpochSeconds)
        // Negative days is invalid -> matches nothing
        assertFalse(SmartRule(SmartField.Added, SmartOperator.InLastDays, "-5").matches(recent, ctx))
        assertFalse(SmartRule(SmartField.Added, SmartOperator.NotInLastDays, "-5").matches(recent, ctx))

        // 0 days: today
        assertTrue(SmartRule(SmartField.Added, SmartOperator.InLastDays, "0").matches(recent, ctx))
        assertFalse(SmartRule(SmartField.Added, SmartOperator.NotInLastDays, "0").matches(recent, ctx))
    }
}
