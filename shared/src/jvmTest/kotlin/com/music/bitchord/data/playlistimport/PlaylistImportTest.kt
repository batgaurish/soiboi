package com.music.bitchord.data.playlistimport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PlaylistImportTest {
    @Test fun pastedTracksKeepOrderAndDuplicates() {
        val result = PlaylistImportParser.parse("Title\tArtist\tISRC\nFirst\tA\tUSAAA0000001\nSecond - B\nFirst - A")
        assertEquals(listOf("First", "Second", "First"), result.entries.map { it.title })
        assertEquals(listOf(0, 1, 2), result.entries.map { it.sourceIndex })
    }

    @Test fun pastedTracklistParsesDeterministicSeparatorsAndLeadingNumbers() {
        val text = listOf(
            "# A pasted playlist tracklist with various separators and numbering",
            "1. Radiohead - Reckoner",
            "02 – Björk – Jóga",
            "3) Pink Floyd — Time",
            "4. Daft Punk\tOne More Time",
            "5 - Queen | Bohemian Rhapsody",
            "6 : Miles Davis : So What",
            "",
            "not a valid track line without separator",
            "- ",
            "   ",
            "# Duplicate entry to test preservation of order and duplicates",
            "7. Radiohead - Reckoner"
        ).joinToString("\n")
        val parsed = PlaylistImportParser.parseTracklist(text, title = "My Tracklist")
        assertEquals("My Tracklist", parsed.title)
        assertEquals(PlaylistImportProvider.PASTED, parsed.provider)
        assertEquals(listOf("Reckoner", "Jóga", "Time", "One More Time", "Bohemian Rhapsody", "So What", "Reckoner"), parsed.entries.map { it.title })
        assertEquals(listOf("Radiohead", "Björk", "Pink Floyd", "Daft Punk", "Queen", "Miles Davis", "Radiohead"), parsed.entries.map { it.artist })
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), parsed.entries.map { it.sourceIndex })
    }

    @Test fun pastedTracklistCsvWithIsrcAndQuotes() {
        val text = """
            Title,Artist,ISRC
            "Hello, Goodbye",The Beatles,GBAYE0601690
            Yellow,Coldplay,
            "Song, With, Commas",Artist Name,USAAA2400001
        """.trimIndent()
        val parsed = PlaylistImportParser.parseTracklist(text)
        assertEquals(PlaylistImportProvider.PASTED, parsed.provider)
        assertEquals(listOf("Hello, Goodbye", "Yellow", "Song, With, Commas"), parsed.entries.map { it.title })
        assertEquals(listOf("The Beatles", "Coldplay", "Artist Name"), parsed.entries.map { it.artist })
        assertEquals(listOf("GBAYE0601690", null, "USAAA2400001"), parsed.entries.map { it.isrc })
        assertEquals(listOf(0, 1, 2), parsed.entries.map { it.sourceIndex })
    }

    @Test fun pastedTracklistArtistTitleCsvHeader() {
        val text = """
            Artist,Title,ISRC
            The Beatles,Yesterday,GBAYE0601691
            "Simon & Garfunkel","The Sound of Silence",USSM16400001
        """.trimIndent()
        val parsed = PlaylistImportParser.parseTracklist(text)
        assertEquals(listOf("Yesterday", "The Sound of Silence"), parsed.entries.map { it.title })
        assertEquals(listOf("The Beatles", "Simon & Garfunkel"), parsed.entries.map { it.artist })
        assertEquals(listOf("GBAYE0601691", "USSM16400001"), parsed.entries.map { it.isrc })
    }

    @Test fun pastedTracklistSkipsMalformedAndBlankLines() {
        val text = listOf(
            "",
            "# leading comment",
            "",
            "Valid Artist - Valid Title",
            "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M",
            "onlyonepart",
            " - missing artist",
            "missing title - ",
            "Valid Artist - Valid Title"
        ).joinToString("\n")
        val parsed = PlaylistImportParser.parseTracklist(text)
        assertEquals(listOf("Valid Title", "Valid Title"), parsed.entries.map { it.title })
        assertEquals(listOf("Valid Artist", "Valid Artist"), parsed.entries.map { it.artist })
        assertEquals(listOf(0, 1), parsed.entries.map { it.sourceIndex })
    }

    @Test fun supportedProvidersNormalizeFixtureEntries() {
        PlaylistImportProvider.entries.filterNot { it == PlaylistImportProvider.PASTED }.forEach { provider ->
            val parsed = PlaylistImportParser.parseProviderFixture(provider, """{"name":"Mix","tracks":[{"title":"First","artist":"A","isrc":"USAAA0000001"},{"title":"First","artist":"A"}]}""")
            assertEquals(provider, parsed.provider)
            assertEquals(listOf(0, 1), parsed.entries.map { it.sourceIndex })
        }
    }

    @Test fun spotifyApiResponseExtractsTracksAndSkipsDeletedItems() {
        val fixture = this::class.java.getResource("/spotify-playlist-response.json")!!.readText()
        val parsed = PlaylistImportParser.parseSpotifyPlaylistResponse(fixture)
        assertEquals("Road Trip Mix", parsed.title)
        assertEquals(PlaylistImportProvider.SPOTIFY, parsed.provider)
        assertEquals(listOf("First Song", "Second Song", "First Song"), parsed.entries.map { it.title })
        assertEquals(listOf("Artist One, Guest Artist", "Artist Two", "Artist One, Guest Artist"), parsed.entries.map { it.artist })
        assertEquals(listOf("USAAA2400001", "GBBBB2400002", "USAAA2400001"), parsed.entries.map { it.isrc })
        assertEquals(listOf(0, 1, 2), parsed.entries.map { it.sourceIndex })
    }

    @Test fun deezerPlaylistResponseExtractsTracksAndKeepsDuplicatesInOrder() {
        val fixture = """{"title":"Night Drive","tracks":{"data":[{"title":"First Song","artist":{"name":"Artist One"},"isrc":"USAAA2400001"},{"title":"Second Song","artist":{"name":"Artist Two"},"isrc":"GBBBB2400002"},{"title":"First Song","artist":{"name":"Artist One"},"isrc":"USAAA2400001"}]}}"""
        val parsed = PlaylistImportParser.parseDeezerPlaylistResponse(fixture)
        assertEquals("Night Drive", parsed.title)
        assertEquals(PlaylistImportProvider.DEEZER, parsed.provider)
        assertEquals(listOf("First Song", "Second Song", "First Song"), parsed.entries.map { it.title })
        assertEquals(listOf("Artist One", "Artist Two", "Artist One"), parsed.entries.map { it.artist })
        assertEquals(listOf("USAAA2400001", "GBBBB2400002", "USAAA2400001"), parsed.entries.map { it.isrc })
        assertEquals(listOf(0, 1, 2), parsed.entries.map { it.sourceIndex })
    }

    @Test fun isrcWinsAndManualChoiceRequiresConfirmation() {
        val entry = PlaylistEntry("Song", "Artist", "USAAA0000001", sourceIndex = 0)
        val candidates = listOf(ImportCandidate("same-title", "Song", "Artist"), ImportCandidate("isrc", "Other", "Other", "USAAA0000001"))
        assertEquals("isrc", PlaylistImportMatcher.match(entry, candidates)?.candidate?.id)
        val unresolved = PlaylistImportMatcher.manualResolution(entry, "same-title", candidates, confirmed = false)
        assertIs<ImportMatchState.Unmatched>(unresolved)
        assertIs<ImportMatchState.ManualConfirmed>(PlaylistImportMatcher.manualResolution(entry, "same-title", candidates, confirmed = true))
    }
}
