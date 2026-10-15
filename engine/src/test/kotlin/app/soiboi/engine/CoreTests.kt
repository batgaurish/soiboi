package app.soiboi.engine

import app.soiboi.engine.core.bareSongTitle
import app.soiboi.engine.core.normaliseForMatch
import app.soiboi.engine.core.ownedSongKey
import app.soiboi.engine.download.FailureFix
import app.soiboi.engine.download.catalogIds
import app.soiboi.engine.download.describeFailure
import app.soiboi.engine.download.explainFailure
import app.soiboi.engine.library.QualityInfo
import app.soiboi.engine.library.QualityTier
import app.soiboi.engine.library.Song
import app.soiboi.engine.library.SongSort
import app.soiboi.engine.library.splitArtists
import app.soiboi.engine.lyrics.hasTimestamps
import app.soiboi.engine.lyrics.parseLrc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchTest {
    @Test fun normaliseIgnoresCaseAndPunctuation() {
        assertEquals("rockandroll", normaliseForMatch("Rock & Roll!"))
        assertEquals("rockandroll", normaliseForMatch("rock AND roll"))
    }

    @Test fun bareTitleDropsCreditsButKeepsVersions() {
        assertEquals("Phir Mohabbat", bareSongTitle("Phir Mohabbat (From \"Murder 2\")"))
        assertEquals("Song", bareSongTitle("Song (feat. Someone)"))
        assertEquals("Song (Live From New York)", bareSongTitle("Song (Live From New York)"))
        assertEquals("Song (Remix)", bareSongTitle("Song (Remix)"))
    }

    @Test fun ownedKeyAgreesAcrossSpellings() {
        assertEquals(ownedSongKey("Arijit Singh", "Tum Hi Ho"), ownedSongKey("arijit singh", "Tum Hi Ho (From \"Aashiqui 2\")"))
    }

    @Test fun nonLatinNamesAreKept() {
        assertTrue(ownedSongKey("周杰伦", "晴天").startsWith("周杰伦|"))
    }
}

class ErrorCatalogTest {
    @Test fun everyEntryIsReachable() {
        val samples = mapOf(
            "cancelled" to ("Download stopped" to "cancelled"), "sign_in_unconfirmed" to ("x" to "wrapper_unconfirmed"), "not_signed_in" to ("No Apple Music cookies" to ""),
            "no_subscription" to ("No active Apple Music subscription" to ""), "downloader_missing" to ("Bundled Python runtime not found" to ""),
            "device_unsupported" to ("x" to "no_multiprocessing"), "app_bug" to ("x" to "bad_request"), "wrapper_outdated" to ("Unsupported wrapper-v2 API version" to ""),
            "playback_refused" to ("playback_dispatch_failed" to ""), "wrapper_down" to ("wrapper did not come up" to ""), "signed_out" to ("Status code: 401" to ""),
            "rate_limited" to ("Too Many Requests" to ""), "apple_unavailable" to ("Status code: 503" to ""), "disk_full" to ("[Errno 28] No space left on device" to ""),
            "folder_not_writable" to ("Permission denied" to ""), "quality_unavailable" to ("Requested format is not available" to ""),
            "needs_wrapper" to ("Decryption is not available" to ""), "not_streamable" to ("Media is not streamable" to ""), "unsupported_link" to ("URL is not valid or supported" to ""),
            "unsupported_media" to ("Media type is disallowed" to ""), "not_found" to ("Status code: 404" to ""), "apple_changed" to ("Error finding index.js URI" to ""),
            "network" to ("Connection reset" to ""), "widevine_rejected" to ("pywidevine" to ""), "tool_missing" to ("Required dependency not found" to ""),
            "downloader_crashed" to ("Downloader exited with status 1" to ""),
        )
        assertEquals(catalogIds.toSet(), samples.keys, "every catalog id has a sample")
        for ((id, sample) in samples) assertEquals(id, explainFailure(sample.first, sample.second)?.id, "sample for $id")
    }

    @Test fun specificEntriesBeatBroadOnes() {
        // A refused wrapper connection must read as the wrapper, not as the network.
        assertEquals("wrapper_down", explainFailure("wrapper: Connection refused")?.id)
        assertEquals("playback_refused", explainFailure("Apple store error: playback_dispatch_failed")?.id)
    }

    @Test fun unknownKeepsItsMessage() {
        val d = describeFailure("something odd happened")
        assertFalse(d.known); assertEquals("something odd happened", d.detail); assertEquals(FailureFix.OpenLog, d.fix)
    }

    @Test fun onlyTransientFailuresAreMarkedTemporary() {
        assertTrue(describeFailure("Connection reset").temporary)
        assertFalse(describeFailure("Media is not streamable").temporary)
    }
}

class QualityTest {
    @Test fun mp4NeedsBitrateToSeparateAlacFromAac() {
        assertEquals("ALAC", QualityInfo.classify("MP4", 900_000).label)
        assertEquals(QualityTier.Lossless, QualityInfo.classify("MP4", 900_000).tier)
        assertEquals("AAC 256", QualityInfo.classify("MP4", 256_000).label)
        assertEquals("AAC 321", QualityInfo.classify("MP4", 321).label)
    }

    @Test fun losslessContainersAreLossless() {
        assertEquals(QualityTier.Lossless, QualityInfo.classify("FLAC", null).tier)
        assertEquals(QualityTier.Lossy, QualityInfo.classify("MP3", 320_000).tier)
        assertEquals(QualityTier.Unknown, QualityInfo.classify(null, null).tier)
    }

    @Test fun sampleRateBecomesDetail() = assertEquals("44.1 kHz", QualityInfo.classify("FLAC", null, 44100).detail)
}

class SongTest {
    @Test fun splitArtistsHandlesCollaborations() {
        assertEquals(listOf("A", "B", "C"), splitArtists("A & B, C"))
        assertEquals(listOf("A/"), splitArtists("A/"))
    }

    @Test fun sortKeepsAlbumOrderWithinAnArtist() {
        val songs = listOf(Song("1", title = "b", artist = "x", album = "al", track = 2, year = 2020), Song("2", title = "a", artist = "x", album = "al", track = 1, year = 2020))
        assertEquals(listOf("2", "1"), SongSort.ArtistAsc.sort(songs).map { it.id })
        assertEquals(listOf("2", "1"), SongSort.TitleAsc.sort(songs).map { it.id })
    }
}

class LyricsTest {
    @Test fun parsesLineSyncedLyrics() {
        val l = parseLrc(listOf("[ar:Someone]", "[00:12.30]First line", "[00:15.00]Second line"), songDurationMs = 20000)
        assertTrue(l.isSynced); assertEquals(2, l.lines.size); assertEquals(12300, l.lines[0].startMs)
        assertEquals(1, l.lineAt(16000)); assertEquals(-1, l.lineAt(1000))
    }

    @Test fun detectsKaraokeAndTranslations() {
        val k = parseLrc(listOf("[00:01.00]Hel<00:01.50>lo <00:02.00>world"))
        assertTrue(k.isKaraoke)
        val t = parseLrc(listOf("[00:05.00]Hello", "[00:05.00]Bonjour"))
        assertEquals(1, t.lines.size); assertEquals(listOf("Bonjour"), t.lines[0].translates)
    }

    @Test fun plainLyricsAreShownUnsyncedWithoutHeaders() {
        val p = parseLrc(listOf("[by:Someone]", "just words", "more words"))
        assertFalse(p.isSynced); assertEquals(listOf("just words", "more words"), p.lines.map { it.text })
    }

    @Test fun emptyGivesThePlaceholder() = assertEquals("No lyrics", parseLrc(emptyList()).lines.single().text)

    @Test fun timestampDetectionIgnoresPlainText() { assertTrue(hasTimestamps(listOf("[01:02.03]x"))); assertFalse(hasTimestamps(listOf("1:2 is not a stamp"))) }
}
