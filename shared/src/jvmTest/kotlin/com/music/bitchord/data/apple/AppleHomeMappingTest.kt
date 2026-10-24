package com.music.bitchord.data.apple

import com.music.bitchord.data.model.UiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppleHomeMappingTest {

    @Test
    fun appleItemNeverCarriesPlayableVideoIdOrBrowseId() {
        val item = AppleHomeItem("123", "songs", "Song Title", "Artist Name", "https://artwork.jpg")
        val shelfItem = item.toShelfItem()

        assertEquals("Song Title", shelfItem.title)
        assertEquals("Artist Name", shelfItem.subtitle)
        assertEquals("https://artwork.jpg", shelfItem.thumbnailUrl)
        assertNull(shelfItem.videoId, "Apple cards must have null videoId to prevent streaming playback")
        assertNull(shelfItem.browseId, "Apple cards must have null browseId to prevent navigation to YouTube browse")
    }

    @Test
    fun publicChartShelfIsLabeledAccurately() {
        val publicShelf = AppleHomeShelf(
            title = "Popular on Apple Music",
            items = listOf(AppleHomeItem("1", "albums", "Top Album", "Top Artist", null)),
            public = true,
        )
        val homeShelf = publicShelf.toHomeShelf()

        assertEquals("Popular on Apple Music", homeShelf.title)
        assertEquals("Apple Music Charts", homeShelf.subtitle)
        assertEquals(1, homeShelf.items.size)
    }

    @Test
    fun personalizedShelfDoesNotCarryPublicLabel() {
        val personalizedShelf = AppleHomeShelf(
            title = "Heavy Rotation",
            items = listOf(AppleHomeItem("2", "albums", "Fav Album", "Fav Artist", null)),
            public = false,
        )
        val homeShelf = personalizedShelf.toHomeShelf()

        assertEquals("Heavy Rotation", homeShelf.title)
        assertEquals("", homeShelf.subtitle)
    }

    @Test
    fun resultMappingHandlesLoading() {
        val uiState = AppleHomeResult.Loading.toUiState()
        assertIs<UiState.Loading>(uiState)
    }

    @Test
    fun resultMappingHandlesReadyWithPersonalizedAndPublicShelves() {
        val result = AppleHomeResult.Ready(
            shelves = listOf(
                AppleHomeShelf("Recommendations", listOf(AppleHomeItem("1", "albums", "A", "B", null)), public = false),
                AppleHomeShelf("Popular on Apple Music", listOf(AppleHomeItem("2", "albums", "C", "D", null)), public = true),
            ),
            personalized = true,
        )
        val uiState = result.toUiState()
        val success = assertIs<UiState.Success<*>>(uiState)
        @Suppress("UNCHECKED_CAST")
        val shelves = success.data as List<com.music.bitchord.data.model.HomeShelf>
        assertEquals(2, shelves.size)
        assertEquals("", shelves[0].subtitle)
        assertEquals("Apple Music Charts", shelves[1].subtitle)
    }

    @Test
    fun resultMappingHandlesEmptyReadyResult() {
        val result = AppleHomeResult.Ready(shelves = emptyList(), personalized = true)
        val uiState = result.toUiState()
        val success = assertIs<UiState.Success<*>>(uiState)
        @Suppress("UNCHECKED_CAST")
        val shelves = success.data as List<com.music.bitchord.data.model.HomeShelf>
        assertTrue(shelves.isEmpty())
    }

    @Test
    fun resultMappingHandlesExplicitEmptyResult() {
        val result = AppleHomeResult.Empty("No shelves available")
        val uiState = result.toUiState()
        val success = assertIs<UiState.Success<*>>(uiState)
        @Suppress("UNCHECKED_CAST")
        val shelves = success.data as List<com.music.bitchord.data.model.HomeShelf>
        assertTrue(shelves.isEmpty())
    }

    @Test
    fun resultMappingHandlesMissingCookie() {
        val result = AppleHomeResult.Failed(Failure.MISSING_USER_TOKEN, "Missing token")
        val uiState = result.toUiState()
        val error = assertIs<UiState.Error>(uiState)
        assertTrue(error.message.contains("Missing token") || error.message.contains("session"))
    }

    @Test
    fun resultMappingHandlesService3077() {
        val result = AppleHomeResult.Failed(Failure.SERVICE_3077, "Apple Music error 3077")
        val uiState = result.toUiState()
        val error = assertIs<UiState.Error>(uiState)
        assertTrue(error.message.contains("3077"))
    }

    @Test
    fun resultMappingHandlesHttpAndParseFailures() {
        val httpError = AppleHomeResult.Failed(Failure.HTTP, "HTTP 500").toUiState()
        val parseError = AppleHomeResult.Failed(Failure.PARSE, "Corrupt response").toUiState()

        val http = assertIs<UiState.Error>(httpError)
        assertEquals("HTTP 500", http.message)

        val parse = assertIs<UiState.Error>(parseError)
        assertEquals("Corrupt response", parse.message)
    }
}
