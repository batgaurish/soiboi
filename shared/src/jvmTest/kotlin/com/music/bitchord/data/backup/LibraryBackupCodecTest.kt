package com.music.bitchord.data.backup

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryBackupCodecTest {

    @Test
    fun versionedExportImportRoundTripPreservesLibraryOrganization() {
        val tracks1 = listOf(
            BackupTrack(
                id = "vid_1",
                title = "Starboy",
                artist = "The Weeknd",
                album = "Starboy",
                durationText = "3:50",
            ),
            BackupTrack(
                id = "vid_2",
                title = "Blinding Lights",
                artist = "The Weeknd",
                album = "After Hours",
                durationText = "3:20",
            ),
        )
        val tracks2 = listOf(
            BackupTrack(
                id = "vid_3",
                title = "Levitating",
                artist = "Dua Lipa",
                album = "Future Nostalgia",
                durationText = "3:23",
            ),
        )

        val playlists = listOf(
            BackupPlaylist(name = "Weekend Favorites", pinned = true, songs = tracks1),
            BackupPlaylist(name = "Pop Hits", pinned = false, songs = tracks2),
        )

        val safeSettings = mapOf(
            "theme_mode" to "dark",
            "equalizer_enabled" to "true",
            "equalizer_bands" to "0.0,1.5,3.0,1.5,0.0,-1.0,2.0",
            "crossfade_seconds" to "4",
            "shuffle_enabled" to "true",
            "repeat_mode" to "ALL",
            "automix_performance_mode" to "BALANCED",
        )

        val original = LibraryBackup(
            version = LibraryBackupCodec.SCHEMA_VERSION,
            app = LibraryBackupCodec.APP_TAG,
            exportedAt = "2026-10-25T00:00:00Z",
            playlists = playlists,
            settings = safeSettings,
        )

        val encodedJson = LibraryBackupCodec.encode(original)
        assertTrue(encodedJson.isNotBlank())

        val restored = LibraryBackupCodec.decode(encodedJson)
        assertEquals(LibraryBackupCodec.SCHEMA_VERSION, restored.version)
        assertEquals(LibraryBackupCodec.APP_TAG, restored.app)
        assertEquals("2026-10-25T00:00:00Z", restored.exportedAt)
        assertEquals(2, restored.playlists.size)

        assertEquals("Weekend Favorites", restored.playlists[0].name)
        assertTrue(restored.playlists[0].pinned)
        assertEquals(2, restored.playlists[0].songs.size)
        assertEquals("Starboy", restored.playlists[0].songs[0].title)
        assertEquals("The Weeknd", restored.playlists[0].songs[0].artist)
        assertEquals("Starboy", restored.playlists[0].songs[0].album)
        assertEquals("3:50", restored.playlists[0].songs[0].durationText)

        assertEquals("Pop Hits", restored.playlists[1].name)
        assertFalse(restored.playlists[1].pinned)
        assertEquals(1, restored.playlists[1].songs.size)
        assertEquals("Levitating", restored.playlists[1].songs[0].title)

        assertEquals("dark", restored.settings["theme_mode"])
        assertEquals("true", restored.settings["equalizer_enabled"])
        assertEquals("4", restored.settings["crossfade_seconds"])
    }

    @Test
    fun unknownVersionRejection() {
        val futureVersionJson = """
            {
                "version": 999,
                "app": "bitchord",
                "exportedAt": "2026-10-25T00:00:00Z",
                "playlists": [],
                "settings": {}
            }
        """.trimIndent()

        assertFailsWith<UnknownBackupVersionException> {
            LibraryBackupCodec.decode(futureVersionJson)
        }

        val zeroVersionJson = """
            {
                "version": 0,
                "app": "bitchord",
                "exportedAt": "2026-10-25T00:00:00Z",
                "playlists": [],
                "settings": {}
            }
        """.trimIndent()

        assertFailsWith<UnknownBackupVersionException> {
            LibraryBackupCodec.decode(zeroVersionJson)
        }

        val negativeVersionJson = """
            {
                "version": -1,
                "app": "bitchord",
                "exportedAt": "2026-10-25T00:00:00Z",
                "playlists": [],
                "settings": {}
            }
        """.trimIndent()

        assertFailsWith<UnknownBackupVersionException> {
            LibraryBackupCodec.decode(negativeVersionJson)
        }
    }

    @Test
    fun ensuringCredentialAuthTokenFieldsAreAbsentFromExportedPayload() {
        val dangerousSettings = mapOf(
            "theme_mode" to "amoled",
            "auth_token" to "super_secret_bearer_token_12345",
            "lastfm_session_key" to "lastfm_session_secret_999",
            "lastfm_api_key" to "lastfm_api_key_888",
            "lastfm_secret" to "lastfm_secret_777",
            "listenbrainz_token" to "listenbrainz_token_666",
            "spotify_spdc_token" to "spotify_spdc_token_555",
            "paxsenix_api_key" to "paxsenix_api_key_444",
            "webdav_password" to "webdav_secret_password_333",
            "smb_password" to "smb_secret_password_222",
            "google_client_secret" to "google_secret_111",
            "user_cookie" to "SAPISID=cookie_content_000",
            "oauth_credential" to "oauth_cred_value",
            "crossfade_seconds" to "3",
        )

        val playlists = listOf(
            BackupPlaylist(
                name = "My Safe Playlist",
                pinned = true,
                songs = listOf(
                    BackupTrack(id = "safe_vid", title = "Safe Song", artist = "Safe Artist"),
                ),
            ),
        )

        val json = LibraryBackupCodec.export(
            playlists = playlists,
            settings = dangerousSettings,
            exportedAt = "2026-10-25T00:00:00Z",
        )

        // Safe settings must remain
        assertTrue(json.contains("\"theme_mode\""))
        assertTrue(json.contains("\"crossfade_seconds\""))

        // None of the secret keys or secret values may appear anywhere in the serialized payload
        assertFalse(json.contains("super_secret_bearer_token_12345"))
        assertFalse(json.contains("lastfm_session_secret_999"))
        assertFalse(json.contains("lastfm_api_key_888"))
        assertFalse(json.contains("lastfm_secret_777"))
        assertFalse(json.contains("listenbrainz_token_666"))
        assertFalse(json.contains("spotify_spdc_token_555"))
        assertFalse(json.contains("paxsenix_api_key_444"))
        assertFalse(json.contains("webdav_secret_password_333"))
        assertFalse(json.contains("smb_secret_password_222"))
        assertFalse(json.contains("google_secret_111"))
        assertFalse(json.contains("cookie_content_000"))
        assertFalse(json.contains("oauth_cred_value"))

        assertFalse(json.contains("auth_token"))
        assertFalse(json.contains("lastfm_session_key"))
        assertFalse(json.contains("webdav_password"))
        assertFalse(json.contains("smb_password"))
        assertFalse(json.contains("client_secret"))
        assertFalse(json.contains("user_cookie"))
        assertFalse(json.contains("oauth_credential"))
    }

    @Test
    fun ensuringAudioBytesAndPathsAreAbsentFromExportedPayload() {
        val songWithLocalPaths = Song(
            videoId = "vid_local_1",
            title = "Local Acoustic",
            artist = "Acoustic Artist",
            thumbnailUrl = null,
            durationText = "4:15",
            albumName = "Local Album",
            localUri = "file:///home/user/Music/Artist/Album/01-acoustic.flac",
            localPath = "/home/user/Music/Artist/Album/01-acoustic.flac",
            downloadFormat = "LOSSLESS_FLAC",
        )

        val playlist = BackupPlaylist.fromSongs(
            name = "Offline Collection",
            songs = listOf(songWithLocalPaths),
            pinned = false,
        )

        val settingsWithPaths = mapOf(
            "equalizer_enabled" to "true",
            "audio_cache_folder" to "/var/tmp/bitchord/audio_cache",
            "download_path" to "/home/user/Music/Downloads",
            "local_music_folder_uri" to "content://com.android.externalstorage.documents/tree/primary%3AMusic",
        )

        val json = LibraryBackupCodec.export(
            playlists = listOf(playlist),
            settings = settingsWithPaths,
            exportedAt = "2026-10-25T00:00:00Z",
        )

        // Metadata needed for library organization must be preserved
        assertTrue(json.contains("Local Acoustic"))
        assertTrue(json.contains("Acoustic Artist"))
        assertTrue(json.contains("Local Album"))
        assertTrue(json.contains("4:15"))

        // Absolute paths, local URIs, and audio file references must NEVER appear in exported payload
        assertFalse(json.contains("/home/user/Music"))
        assertFalse(json.contains("/var/tmp/bitchord"))
        assertFalse(json.contains("file:///home"))
        assertFalse(json.contains("content://"))
        assertFalse(json.contains("localPath"))
        assertFalse(json.contains("localUri"))
        assertFalse(json.contains("01-acoustic.flac"))
        assertFalse(json.contains("audio_cache_folder"))
        assertFalse(json.contains("download_path"))
        assertFalse(json.contains("local_music_folder_uri"))

        // When restored into Song, audio paths are absent
        val restoredBackup = LibraryBackupCodec.decode(json)
        val restoredSong = restoredBackup.playlists.first().songs.first().toSong()
        assertEquals("Local Acoustic", restoredSong.title)
        assertEquals("Acoustic Artist", restoredSong.artist)
        assertNull(restoredSong.localPath)
        assertNull(restoredSong.localUri)
    }

    @Test
    fun corruptedJsonIsRejected() {
        val corruptedJson = "{ not valid json }"
        val result = LibraryBackupCodec.decodeResult(corruptedJson)
        assertTrue(result.isFailure)

        assertFailsWith<Exception> {
            LibraryBackupCodec.decode(corruptedJson)
        }
    }

    @Test
    fun arbitrarySettingsAndPlaylistTextCannotLeakSuspiciousValues() {
        val secret = "ghp_abcdefghijklmnopqrstuvwxyz123456"
        val encoded = LibraryBackupCodec.export(
            playlists = listOf(BackupPlaylist(
                name = "Bearer abcdefghijklmnop",
                songs = listOf(BackupTrack(
                    id = "opaque_id",
                    title = secret,
                    artist = "file:///home/alice/Music/song.flac",
                    album = "content://private/audio",
                    durationText = "token=secret-value",
                )),
            )),
            settings = mapOf(
                "custom_feature" to secret,
                "harmless_label" to "/home/alice/Music/private.mp3",
                "ordinary_flag" to "true",
            ),
        )
        assertFalse(encoded.contains(secret))
        assertFalse(encoded.contains("Bearer abcdefghijklmnop"))
        assertFalse(encoded.contains("file:///home"))
        assertFalse(encoded.contains("content://"))
        assertFalse(encoded.contains("token=secret-value"))
        assertFalse(encoded.contains("ordinary_flag"))
    }

    @Test
    fun decodeFiltersForbiddenFieldsAndEnforcesVersionPolicy() {
        val imported = """{"version":1,"app":"bitchord","exportedAt":"now","playlists":[{"name":"safe","pinned":false,"songs":[{"id":"id","title":"ghp_abcdefghijklmnopqrstuvwxyz123456","artist":"/home/a/song.mp3","album":"Bearer abcdefghijklmnop","durationText":"3:00"}]}],"settings":{"theme_mode":"dark","custom":"token=hidden","auth_token":"hidden"},"futureField":"ignored"}"""
        val decoded = LibraryBackupCodec.decode(imported)
        assertEquals("dark", decoded.settings["theme_mode"])
        assertFalse(decoded.settings.containsKey("custom"))
        assertFalse(decoded.settings.containsKey("auth_token"))
        assertFalse(LibraryBackupCodec.encode(decoded).contains("hidden"))
        assertTrue(LibraryBackupCodec.decode("""{"playlists":[],"settings":{}}""").version == LibraryBackupCodec.SCHEMA_VERSION)
        assertFailsWith<UnknownBackupVersionException> {
            LibraryBackupCodec.decode("""{"version":0,"playlists":[],"settings":{}}""")
        }
    }
}
