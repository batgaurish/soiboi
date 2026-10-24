package com.music.bitchord.data.backup

import com.music.bitchord.data.model.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Thrown when a backup payload specifies an unsupported or unrecognised schema version. */
class UnknownBackupVersionException(message: String) : IllegalArgumentException(message)

/**
 * Portable representation of a track within a backup playlist.
 *
 * Explicitly excludes any local filesystem paths, content URIs, download caches,
 * or audio bytes so backups remain fully portable across devices and platforms.
 */
@Serializable
data class BackupTrack(
    val id: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    val durationText: String? = null,
) {
    companion object {
        /**
         * Converts a [Song] into a portable [BackupTrack], stripping out
         * local filesystem paths, content URIs, download formats, and device-specific state.
         */
        fun fromSong(song: Song): BackupTrack = BackupTrack(
            id = song.videoId.takeIf { it.isNotBlank() && !LibraryBackupCodec.isAudioPathValue(it) },
            title = song.title,
            artist = song.artist,
            album = song.albumName,
            durationText = song.durationText,
        )
    }

    /**
     * Converts back to a [Song] with null [Song.localPath] and [Song.localUri]
     * for portable matching on the destination device.
     */
    fun toSong(): Song = Song(
        videoId = id ?: "",
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = durationText,
        artistId = null,
        artists = emptyList(),
        albumId = null,
        albumName = album,
        isVideo = false,
        localUri = null,
        localPath = null,
        downloadFormat = null,
    )
}

/**
 * Portable representation of a user or system playlist.
 */
@Serializable
data class BackupPlaylist(
    val name: String = "",
    val pinned: Boolean = false,
    val songs: List<BackupTrack> = emptyList(),
) {
    companion object {
        fun fromSongs(name: String, songs: List<Song>, pinned: Boolean = false): BackupPlaylist =
            BackupPlaylist(
                name = name,
                pinned = pinned,
                songs = songs.map(BackupTrack::fromSong),
            )
    }
}

/**
 * Portable backup document containing library organization (playlists and safe settings).
 *
 * Audio data, local file paths, and authentication credentials/tokens are strictly excluded.
 */
@Serializable
data class LibraryBackup(
    val version: Int = LibraryBackupCodec.SCHEMA_VERSION,
    val app: String = LibraryBackupCodec.APP_TAG,
    val exportedAt: String = "",
    val playlists: List<BackupPlaylist> = emptyList(),
    val settings: Map<String, String> = emptyMap(),
)

/**
 * Codec for versioned export and import of library organization data.
 *
 * Implements strict security boundaries:
 * 1. Authentication tokens, session keys, passwords, cookies, and secrets are excluded.
 * 2. Local audio files, filesystem paths, content URIs, and audio bytes are excluded.
 * 3. Unsupported or unknown schema versions are rejected cleanly.
 */
object LibraryBackupCodec {

    const val SCHEMA_VERSION = 1
    const val APP_TAG = "bitchord"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val SECRET_KEY_PATTERNS = listOf(
        "token",
        "secret",
        "password",
        "cookie",
        "session",
        "auth",
        "credential",
        "key",
        "bearer",
        "apikey",
    )

    private val AUDIO_PATH_KEY_PATTERNS = listOf(
        "path",
        "uri",
        "cache_folder",
        "download",
        "downloaded",
        "local_music_folder",
        "audio_cache",
    )

    /** Returns true if [key] matches any known or heuristic credential/secret pattern. */
    fun isSecretKey(key: String): Boolean {
        val lower = key.lowercase()
        return SECRET_KEY_PATTERNS.any { lower.contains(it) }
    }

    /** Returns true if [key] indicates a device-specific audio path or storage setting. */
    fun isAudioPathKey(key: String): Boolean {
        val lower = key.lowercase()
        return AUDIO_PATH_KEY_PATTERNS.any { lower.contains(it) }
    }

    /** Returns true if [value] looks like a local filesystem path, file URI, or audio file reference. */
    fun isAudioPathValue(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.startsWith("/") ||
            trimmed.startsWith("file://") ||
            trimmed.startsWith("content://") ||
            trimmed.endsWith(".mp3", ignoreCase = true) ||
            trimmed.endsWith(".flac", ignoreCase = true) ||
            trimmed.endsWith(".m4a", ignoreCase = true) ||
            trimmed.endsWith(".ogg", ignoreCase = true) ||
            trimmed.endsWith(".opus", ignoreCase = true) ||
            trimmed.endsWith(".wav", ignoreCase = true) ||
            trimmed.endsWith(".alac", ignoreCase = true)
    }

    /** Returns true if [value] contains sensitive credential substrings. */
    fun isSecretValue(value: String): Boolean {
        val lower = value.lowercase()
        return lower.contains("bearer_token") ||
            lower.contains("sapisid=") ||
            lower.contains("secret") ||
            lower.contains("token")
    }

    /**
     * Sanitizes settings to ensure no secrets or device-local audio paths are serialized.
     */
    fun sanitizeSettings(rawSettings: Map<String, Any?>): Map<String, String> {
        return rawSettings.filterKeys { key ->
            !isSecretKey(key) && !isAudioPathKey(key)
        }.mapNotNull { (key, value) ->
            val stringValue = value?.toString() ?: return@mapNotNull null
            if (isAudioPathValue(stringValue) || isSecretValue(stringValue)) {
                null
            } else {
                key to stringValue
            }
        }.toMap()
    }

    /**
     * Serializes [backup] into a portable JSON document after applying security filtering.
     */
    fun encode(backup: LibraryBackup): String {
        val sanitizedSettings = sanitizeSettings(backup.settings)
        val sanitizedPlaylists = backup.playlists.map { playlist ->
            playlist.copy(
                songs = playlist.songs.map { track ->
                    track.copy(
                        id = track.id?.takeIf { !isAudioPathValue(it) },
                    )
                },
            )
        }
        val safeBackup = backup.copy(
            version = backup.version,
            app = backup.app,
            exportedAt = backup.exportedAt,
            playlists = sanitizedPlaylists,
            settings = sanitizedSettings,
        )
        return json.encodeToString(LibraryBackup.serializer(), safeBackup)
    }

    /**
     * Convenience method to build and encode a [LibraryBackup] with sanitized playlists and settings.
     */
    fun export(
        playlists: List<BackupPlaylist>,
        settings: Map<String, Any?> = emptyMap(),
        exportedAt: String = "",
    ): String {
        val backup = LibraryBackup(
            version = SCHEMA_VERSION,
            app = APP_TAG,
            exportedAt = exportedAt,
            playlists = playlists,
            settings = sanitizeSettings(settings),
        )
        return encode(backup)
    }

    /**
     * Deserializes and validates [jsonString]. Throws [UnknownBackupVersionException] if the version
     * is unsupported or invalid.
     */
    fun decode(jsonString: String): LibraryBackup {
        val backup = json.decodeFromString(LibraryBackup.serializer(), jsonString)
        if (backup.version <= 0 || backup.version > SCHEMA_VERSION) {
            throw UnknownBackupVersionException(
                "Unsupported backup version: ${backup.version} (current schema version is $SCHEMA_VERSION)",
            )
        }
        return backup
    }

    /**
     * Non-throwing variant of [decode] returning a [Result].
     */
    fun decodeResult(jsonString: String): Result<LibraryBackup> = runCatching {
        decode(jsonString)
    }
}
