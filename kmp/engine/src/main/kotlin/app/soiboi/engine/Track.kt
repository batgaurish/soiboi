package app.soiboi.engine

import kotlinx.serialization.Serializable

/** A track as any source describes it, before it is matched to something playable. */
@Serializable
data class TrackRef(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSeconds: Int? = null,
    val coverUrl: String? = null,
    val isrc: String? = null,
    val explicit: Boolean = false,
    val sourceId: String? = null,
)

/** A playlist read from a link, with how many tracks the source said it had. */
@Serializable
data class ImportedPlaylist(
    val name: String,
    val tracks: List<TrackRef>,
    val reportedTotal: Int = tracks.size,
) {
    val skipped: Int get() = (reportedTotal - tracks.size).coerceAtLeast(0)
}
