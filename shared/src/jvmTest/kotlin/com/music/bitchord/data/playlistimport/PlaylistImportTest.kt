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
