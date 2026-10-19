package app.soiboi.engine

import app.soiboi.engine.apple.AppleCatalog
import app.soiboi.engine.apple.pickOriginalRelease
import app.soiboi.engine.apple.sameArtist
import app.soiboi.engine.sources.Http
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppleMatchingTest {
    private fun row(track: String, artist: String, album: String, collArtist: String = artist) =
        Http.json.parseToJsonElement("""{"trackName":"$track","artistName":"$artist","collectionName":"$album","collectionArtistName":"$collArtist"}""") as JsonObject

    @Test fun prefersTheOriginalAlbumOverCompilationsAndSingles() {
        val rows = listOf(row("Song", "Artist", "Now 99", "Various Artists"), row("Song", "Artist", "Song - Single"), row("Song", "Artist", "The Album"))
        assertEquals("The Album", pickOriginalRelease(rows, "Artist", "Song")!!["collectionName"].toString().trim('"'))
    }

    @Test fun someoneElsesCoverIsNotAMatch() = assertNull(pickOriginalRelease(listOf(row("Slave", "Jaydan Wolf", "Covers")), "Måneskin", "Slave"))

    @Test fun artistMatchingTolertatesSpellingButNotStrangers() {
        assertTrue(sameArtist("Mohammad Irfan", "Mohammed Irfan, Arijit & Saim Bhat")); assertFalse(sameArtist("Sia", "SZA")); assertFalse(sameArtist("Måneskin", "Jaydan Wolf"))
    }

    @Test fun songIdsComeFromBothLinkShapes() {
        assertEquals("123", AppleCatalog.songIdFromUrl("https://music.apple.com/us/album/x/999?i=123")); assertEquals("456", AppleCatalog.songIdFromUrl("https://music.apple.com/us/song/name/456")); assertNull(AppleCatalog.songIdFromUrl("https://example.com/?i=1"))
    }
}
