package app.soiboi.engine.resolve

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

/** Something that can be played or downloaded for a track. */
data class Playable(
    val url: String,
    val format: String? = null,
    val quality: String? = null,
    val expiresAtEpochSeconds: Long? = null,
    val providerId: String,
)

/** Matches a [TrackRef] to a [Playable]. ISRC first, then artist and title. */
interface Resolver {
    val id: String
    suspend fun resolve(track: TrackRef): Playable?
}

/** Tries resolvers in the user's order and returns the first match. */
class ResolverChain(private val resolvers: List<Resolver>) {
    suspend fun resolve(track: TrackRef): Playable? {
        for (resolver in resolvers) {
            val hit = runCatching { resolver.resolve(track) }.getOrNull()
            if (hit != null) return hit
        }
        return null
    }
}
