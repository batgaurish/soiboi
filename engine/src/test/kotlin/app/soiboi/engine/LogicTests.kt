package app.soiboi.engine

import app.soiboi.engine.ai.BRIEF_SYSTEM_PROMPT
import app.soiboi.engine.ai.SongVibe
import app.soiboi.engine.ai.Track
import app.soiboi.engine.ai.VibeBrief
import app.soiboi.engine.ai.pickCandidates
import app.soiboi.engine.ai.picksSoFar
import app.soiboi.engine.ai.requestedCount
import app.soiboi.engine.ai.vibeScore
import app.soiboi.engine.ai.extractJsonObject
import app.soiboi.engine.ai.AiException
import app.soiboi.engine.library.Song
import app.soiboi.engine.player.PlayMode
import app.soiboi.engine.player.PlayQueueLogic
import app.soiboi.engine.smart.SmartContext
import app.soiboi.engine.smart.SmartField
import app.soiboi.engine.smart.SmartOperator
import app.soiboi.engine.smart.SmartPlaylist
import app.soiboi.engine.smart.SmartRule
import app.soiboi.engine.smart.SmartSort
import app.soiboi.engine.smart.autoMoodPlaylists
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmartPlaylistTest {
    private val now = Instant.parse("2026-10-15T00:00:00Z")
    private val ctx = SmartContext(now = now)
    private fun song(id: String, energy: Double? = null, plays: Int = 0, last: Instant? = null, genre: String? = null, year: Int? = null) =
        Song(id, title = id, genre = genre, energy = energy, playCount = plays, lastPlayed = last, year = year)

    @Test fun missingNumbersAreNotZero() {
        val lessThan = SmartRule(SmartField.Year, SmartOperator.LessThan, "2000")
        assertFalse(lessThan.matches(song("a"), ctx)); assertTrue(lessThan.matches(song("b", year = 1990), ctx))
        assertTrue(SmartRule(SmartField.Year, SmartOperator.NotEquals, "2000").matches(song("a"), ctx))
    }

    @Test fun neverPlayedMatchesNotInTheLastDays() {
        assertTrue(SmartRule(SmartField.LastPlayed, SmartOperator.NotInLastDays, "90").matches(song("a"), ctx))
        assertFalse(SmartRule(SmartField.LastPlayed, SmartOperator.InLastDays, "90").matches(song("a"), ctx))
        val recent = song("b", last = now.minus(10, ChronoUnit.DAYS))
        assertTrue(SmartRule(SmartField.LastPlayed, SmartOperator.InLastDays, "90").matches(recent, ctx))
    }

    @Test fun halfTypedRulesMatchNothingInsteadOfFailing() = assertFalse(SmartRule(SmartField.Energy, SmartOperator.GreaterThan, "abc").matches(song("a", energy = 0.9), ctx))

    @Test fun matchAllVersusAnyAndLimit() {
        val songs = listOf(song("a", 0.9, genre = "rock"), song("b", 0.9, genre = "pop"), song("c", 0.1, genre = "rock"))
        val rules = listOf(SmartRule(SmartField.Energy, SmartOperator.GreaterThan, "0.5"), SmartRule(SmartField.Genre, SmartOperator.Contains, "rock"))
        assertEquals(listOf("a"), SmartPlaylist("x", rules, matchAll = true, sort = SmartSort.Title, descending = false).evaluate(songs, ctx).map { it.id })
        assertEquals(setOf("a", "b", "c"), SmartPlaylist("x", rules, matchAll = false).evaluate(songs, ctx).map { it.id }.toSet())
        assertEquals(1, SmartPlaylist("x", emptyList(), limit = 1).evaluate(songs, ctx).size)
    }

    @Test fun noRulesMatchesEverything() = assertEquals(3, SmartPlaylist("x").evaluate(List(3) { song("$it") }, ctx).size)

    @Test fun moodShelfNeedsAtLeastFiveSongs() {
        val few = List(4) { song("$it", energy = 0.9) }
        assertTrue(autoMoodPlaylists(few, { null }).isEmpty())
    }

    @Test fun unanalysedSongsNeverReachMoodShelves() = assertTrue(autoMoodPlaylists(List(20) { song("$it") }, { null }).isEmpty())
}

class PlayQueueLogicTest {
    @Test fun insertNextMovesAnExistingSong() {
        val q = mutableListOf("a", "b", "c", "d")
        val r = PlayQueueLogic.insertNext(q, 1, "d")!!
        assertEquals(listOf("a", "b", "d", "c"), q); assertEquals(1, r.currentIndex); assertFalse(r.wasNewlyInserted)
    }

    @Test fun insertNextBeforeCurrentKeepsTheCurrentSong() {
        val q = mutableListOf("a", "b", "c")
        // "c" is playing; "a" moves to right after it, so "c" slides to index 1.
        assertEquals(1, PlayQueueLogic.insertNext(q, 2, "a")!!.currentIndex); assertEquals(listOf("b", "c", "a"), q)
    }

    @Test fun nothingHappensForTheCurrentSong() { assertNull(PlayQueueLogic.insertNext(mutableListOf("a"), 0, "a")); assertNull(PlayQueueLogic.addLast(mutableListOf("a"), 0, "a")) }

    @Test fun addLastAppendsNewSongs() {
        val q = mutableListOf("a", "b"); val r = PlayQueueLogic.addLast(q, 0, "z")!!
        assertEquals(listOf("a", "b", "z"), q); assertTrue(r.wasNewlyInserted)
    }

    @Test fun shuffleKeepsTheCurrentSongFirst() {
        val out = PlayQueueLogic.shuffledAround(listOf("a", "b", "c", "d", "e"), 2, Random(1))
        assertEquals("c", out.first()); assertEquals(setOf("a", "b", "c", "d", "e"), out.toSet())
    }

    @Test fun indexesWrap() { assertEquals(0, PlayQueueLogic.nextIndex(2, 3)); assertEquals(2, PlayQueueLogic.previousIndex(0, 3)); assertEquals(0, PlayQueueLogic.startIndex(PlayMode.Order, 9)) }

    @Test fun repeatKeepsTheShuffleOfTheModeItReplaced() {
        assertTrue(PlayQueueLogic.isShuffled(PlayMode.Repeat, PlayMode.Shuffle)); assertFalse(PlayQueueLogic.isShuffled(PlayMode.Repeat, PlayMode.Order))
    }
}

class AiLogicTest {
    @Test fun readsHowManySongsWereAsked() {
        assertEquals(40, requestedCount("make me 40 songs for a drive")!!.count)
        val floor = requestedCount("at least 60 tracks")!!; assertEquals(60, floor.count); assertTrue(floor.atLeast)
        assertTrue(requestedCount("60+ songs")!!.atLeast); assertNull(requestedCount("something calm")); assertEquals(200, requestedCount("900 songs")!!.count)
    }

    private fun vibe(vararg moods: String, v: String = "mid", e: String = "mid", l: String = "english") = SongVibe(moods.toList(), v, e, l)

    @Test fun languageIsAHardFilterButUnknownPassesThrough() {
        val brief = VibeBrief(moods = listOf("calm"), languages = listOf("hindi"))
        assertEquals(0.0, vibeScore(vibe("calm"), brief)); assertTrue(vibeScore(vibe("calm", l = "unknown"), brief) > 0); assertTrue(vibeScore(vibe("calm", l = "hindi"), brief) > 0)
    }

    @Test fun avoidedMoodsRuleASongOut() = assertEquals(0.0, vibeScore(vibe("calm", "sad"), VibeBrief(moods = listOf("calm"), avoid = listOf("sad"))))

    @Test fun aNeighbouringLevelAloneIsNotAFit() = assertEquals(0.0, vibeScore(vibe("party", e = "mid"), VibeBrief(moods = listOf("calm"), energy = "low")))

    @Test fun candidatesPutFittingTaggedSongsFirstAndTopUpWithUntagged() {
        val tracks = listOf(Track("a", "x", vibe("calm")), Track("b", "y", vibe("party")), Track("c", "z"), Track("d", "w"))
        val picks = pickCandidates(tracks, VibeBrief(moods = listOf("calm")), cap = 3, random = Random(2))
        assertEquals(0, picks.first()); assertEquals(3, picks.size); assertFalse(1 in picks)
    }

    @Test fun diverseSampleSpreadsAcrossArtists() {
        val tracks = List(10) { Track("t$it", if (it < 8) "heavy" else "other$it") }
        val picks = app.soiboi.engine.ai.diverseSample(tracks, 3, random = Random(3))
        assertTrue(picks.count { tracks[it].artist == "heavy" } <= 1)
    }

    @Test fun countsPicksInAHalfWrittenAnswer() { assertEquals(0, picksSoFar("{\"name\":")); assertEquals(3, picksSoFar("{\"name\":\"x\",\"tracks\":[4, 8, 15")) }

    @Test fun extractsJsonFromChattyAnswers() {
        assertEquals("x", (extractJsonObject("Sure!\n```json\n{\"a\":\"x\"}\n```")["a"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertFailsWith<AiException> { extractJsonObject("no json here") }
    }

    @Test fun briefPromptNamesEveryMood() = app.soiboi.engine.ai.VibeMoods.forEach { assertTrue(it in BRIEF_SYSTEM_PROMPT, it) }
}
