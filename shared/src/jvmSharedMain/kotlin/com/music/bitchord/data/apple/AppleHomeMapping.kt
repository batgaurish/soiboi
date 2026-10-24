package com.music.bitchord.data.apple

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState

/** Maps Apple catalogue items to descriptive shelf cards without playback or browse ids. */
fun AppleHomeItem.toShelfItem(): ShelfItem = ShelfItem(
    title = title,
    subtitle = subtitle,
    thumbnailUrl = artwork,
    videoId = null,
    browseId = null,
)

/** Maps an Apple shelf to a BitChord home shelf, clearly labelling public charts. */
fun AppleHomeShelf.toHomeShelf(): HomeShelf = HomeShelf(
    title = title,
    subtitle = if (public) "Apple Music Charts" else "",
    items = items.map { it.toShelfItem() },
)

/** Translates an AppleHomeResult into the standard UiState for Home. */
fun AppleHomeResult.toUiState(): UiState<List<HomeShelf>> = when (this) {
    is AppleHomeResult.Loading -> UiState.Loading
    is AppleHomeResult.Ready -> UiState.Success(shelves.map { it.toHomeShelf() })
    is AppleHomeResult.Failed -> when (kind) {
        Failure.MISSING_USER_TOKEN -> UiState.Error(
            message.ifBlank { "Apple Music session cookie is missing. Sign in to Apple Music." },
        )
        Failure.SERVICE_3077 -> UiState.Error(
            message.ifBlank { "Apple Music error 3077: Check the Apple Music session." },
        )
        Failure.HTTP, Failure.PARSE -> UiState.Error(
            message.ifBlank { "Apple Music request failed." },
        )
    }
}
