package com.music.bitchord.data.playlistimport

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
data class ApplePlaylistMirrorEntry(
    val sourceKey: String,
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
)

/** Persisted source-to-target mapping for an Apple Music playlist import. */
@Serializable
data class ApplePlaylistMirror(
    val sourceUrl: String,
    val targetId: String,
    val targetIsLocal: Boolean,
    val title: String,
    val tracks: List<ApplePlaylistMirrorEntry>,
    val lastSyncedMs: Long = 0L,
)

fun PlaylistEntry.appleSourceKey(): String = when {
    !sourceId.isNullOrBlank() -> "id:${sourceId.trim()}"
    !isrc.isNullOrBlank() -> "isrc:${isrc.trim().uppercase()}"
    else -> "meta:${title.trim().lowercase()}|${artist.trim().lowercase()}"
}

fun applePlaylistMirrorFromImport(
    source: ImportedPlaylist,
    targetId: String,
    targetIsLocal: Boolean,
    rows: List<ImportRowState>,
    syncedAtMs: Long = System.currentTimeMillis(),
): ApplePlaylistMirror? {
    val sourceUrl = source.sourceUrl ?: return null
    if (source.provider != PlaylistImportProvider.APPLE_MUSIC || targetId.isBlank()) return null
    val tracks = rows.asSequence()
        .filter { it.isPlayable }
        .mapNotNull { row ->
            val song = row.candidateSong ?: return@mapNotNull null
            ApplePlaylistMirrorEntry(
                sourceKey = row.entry.appleSourceKey(),
                videoId = song.videoId,
                title = song.title,
                artist = song.artist,
                thumbnailUrl = song.thumbnailUrl,
            )
        }
        .distinctBy(ApplePlaylistMirrorEntry::sourceKey)
        .toList()
    return ApplePlaylistMirror(
        sourceUrl = sourceUrl,
        targetId = targetId,
        targetIsLocal = targetIsLocal,
        title = source.title,
        tracks = tracks,
        lastSyncedMs = syncedAtMs,
    )
}

/** Resolves only newly added Apple tracks; mapped tracks retain their target match. */
object ApplePlaylistMirrorSync {
    suspend fun refresh(
        mirror: ApplePlaylistMirror,
        loadSource: suspend (String) -> ImportedPlaylist = { PlaylistImportService.loadPlaylist(it) },
        resolveNewEntries: suspend (ImportedPlaylist) -> List<ImportRowState> = { PlaylistImportService.resolveEntries(it) },
        applyChanges: suspend (ApplePlaylistMirror, String, List<Song>) -> Result<Unit>,
        clock: () -> Long = System::currentTimeMillis,
    ): Result<ApplePlaylistMirror> {
        return try {
            val source = loadSource(mirror.sourceUrl)
            require(source.provider == PlaylistImportProvider.APPLE_MUSIC) {
                "The stored source no longer resolves to an Apple Music playlist."
            }

            val known = mirror.tracks.associateBy(ApplePlaylistMirrorEntry::sourceKey)
            val newEntries = source.entries.filter { it.appleSourceKey() !in known }
            val resolvedNew = if (newEntries.isEmpty()) {
                emptyList()
            } else {
                resolveNewEntries(source.copy(entries = newEntries))
            }
            val resolvedByKey = resolvedNew.asSequence()
                .filter(ImportRowState::isPlayable)
                .mapNotNull { row ->
                    val song = row.candidateSong ?: return@mapNotNull null
                    row.entry.appleSourceKey() to ApplePlaylistMirrorEntry(
                        sourceKey = row.entry.appleSourceKey(),
                        videoId = song.videoId,
                        title = song.title,
                        artist = song.artist,
                        thumbnailUrl = song.thumbnailUrl,
                    )
                }
                .toMap()

            val refreshedTracks = source.entries.mapNotNull { entry ->
                known[entry.appleSourceKey()] ?: resolvedByKey[entry.appleSourceKey()]
            }.distinctBy(ApplePlaylistMirrorEntry::sourceKey)
            val songs = source.entries.mapNotNull { entry ->
                (known[entry.appleSourceKey()] ?: resolvedByKey[entry.appleSourceKey()])?.toSong()
            }
            val updatedTitle = source.title.trim().takeIf(String::isNotEmpty) ?: mirror.title
            require(source.entries.isEmpty() || songs.isNotEmpty()) {
                "Apple Music tracks no longer resolve to playable entries; the target playlist was left unchanged."
            }
            applyChanges(mirror, updatedTitle, songs).getOrThrow()
            Result.success(
                mirror.copy(
                    title = updatedTitle,
                    tracks = refreshedTracks,
                    lastSyncedMs = clock(),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure(failure)
        }
    }

    private fun ApplePlaylistMirrorEntry.toSong() = Song(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
    )
}
