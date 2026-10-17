# Account Sources: Handover Document

**For:** Developer picking up this feature  
**Date:** 2026-10-17  
**Companion:** `docs/ACCOUNT-SOURCES-PLAN.md` (architecture decisions)

---

## Quick Start

You're adding streaming and auto-sync from music service accounts to Soiboi. The user signs into Apple Music, Spotify, YouTube Music, or SoundCloud; the app discovers new releases, syncs playlists to local downloads, and (for Apple Music) streams tracks in-app.

**Read first:**
- `HANDOVER.md` — overall architecture (Kotlin engine + Python pipeline)
- `AGENTS.md` — project conventions
- `docs/ACCOUNT-SOURCES-PLAN.md` — why decisions were made

**Directories to know:**
```
engine/src/main/kotlin/app/soiboi/engine/
├── accounts/          # NEW — MusicSession, sync
├── addons/            # Existing addon system (register session providers here)
├── discovery/         # AppleLibrary.kt lives here
├── download/          # DownloadQueue, Archive, Pipeline
├── player/            # Player.kt (resolveOverride)
├── servers/           # LocalRangeServer (range-HTTP serving)
└── spotify/           # Existing SpotifyApi, SpotifyBackend

pipeline/soiboi_pipeline/
├── apple_auth.py      # open_api() → gamdl AppleMusicApi
├── apple_library.py   # list_playlists(), list_tracks()
├── stream.py          # handle_youtube_stream (existing)
├── download.py        # handle_download (existing)
└── __main__.py        # Handler registration
```

---

## Task 1: Apple Music Streaming

**Goal:** Play any Apple Music track in-app at ~256 kbps without downloading.

### How it works

Apple's Web Playback API returns a `flavorMap` with multiple quality tiers. The `28:ctrp256` flavor is Widevine CENC (not FairPlay), which pywidevine can handle.

### Files to create

**`pipeline/soiboi_pipeline/apple_stream.py`:**

```python
"""Apple Music stream resolution — decrypts aac-web via Widevine."""

import asyncio
import tempfile
from pathlib import Path
from base64 import b64encode, b64decode

from pywidevine.cdm import Cdm
from pywidevine.device import Device
from pywidevine.pssh import PSSH

from .apple_auth import open_api
from .protocol import emit_progress, emit_done, emit_error


# You'll need a WVD file — place at pipeline/cdm/device.wvd
CDM_PATH = Path(__file__).parent / "cdm" / "device.wvd"


async def resolve_stream(api, track_id: str, cache_dir: Path) -> dict:
    """
    Resolve track_id to a playable decrypted file.
    Returns {"path": str, "format": "m4a", "bitrate": 256}
    """
    # 1. Get webplayback
    wp = await api.get_webplayback(track_id)
    song = wp["songList"][0]
    assets = song.get("assets", [])

    # 2. Find aac-web asset (flavor 28:ctrp256)
    asset = next((a for a in assets if a.get("flavor") == "28:ctrp256"), None)
    if not asset:
        raise ValueError(f"No aac-web flavor for track {track_id}")

    uri = asset["URL"]
    key_uri = asset.get("keyURI") or asset.get("keyURL")

    # 3. Parse PSSH from key_uri
    pssh_b64 = extract_pssh_from_key_uri(key_uri)
    pssh = PSSH(pssh_b64)

    # 4. Load CDM, open session
    device = Device.load(CDM_PATH)
    cdm = Cdm.from_device(device)
    session_id = cdm.open()

    try:
        # 5. Generate challenge
        challenge = cdm.get_license_challenge(session_id, pssh)

        # 6. Exchange for license
        license_data = await api.get_license_exchange(
            track_id=track_id,
            uri=key_uri,
            challenge=b64encode(challenge).decode(),
            key_system="widevine",
        )

        # 7. Parse license, get keys
        cdm.parse_license(session_id, b64decode(license_data["license"]))
        keys = list(cdm.get_keys(session_id))
        content_key = next((k for k in keys if k.type == "CONTENT"), None)
        if not content_key:
            raise ValueError("No content key in license")

        # 8. Download encrypted segments
        encrypted_path = cache_dir / f"{track_id}.enc.m4a"
        await download_hls_audio(uri, encrypted_path)

        # 9. Decrypt
        decrypted_path = cache_dir / f"{track_id}.m4a"
        decrypt_cenc_file(encrypted_path, decrypted_path, content_key.key)

        return {"path": str(decrypted_path), "format": "m4a", "bitrate": 256}

    finally:
        cdm.close(session_id)


def extract_pssh_from_key_uri(key_uri: str) -> str:
    """Extract base64 PSSH from Apple's key URI."""
    # Apple's key URI contains the PSSH in a query param or the path
    # Format varies — implement based on observed responses
    # For now, placeholder:
    from urllib.parse import urlparse, parse_qs
    parsed = urlparse(key_uri)
    params = parse_qs(parsed.query)
    return params.get("pssh", [None])[0]


async def download_hls_audio(hls_url: str, output_path: Path):
    """Download HLS audio segments to a single file."""
    import subprocess
    # Use ffmpeg for HLS → single file (segments stay encrypted)
    subprocess.run([
        "ffmpeg", "-y", "-i", hls_url,
        "-c", "copy", str(output_path)
    ], check=True, capture_output=True)


def decrypt_cenc_file(encrypted: Path, decrypted: Path, key: bytes):
    """Decrypt CENC-encrypted MP4."""
    from Crypto.Cipher import AES
    from pymp4.parser import Box
    # Full implementation: parse boxes, find encrypted samples,
    # decrypt each with AES-CTR using IV from senc box
    # For brevity, this is the skeleton — see pywidevine examples
    pass


# Handler for pipeline
def handle_apple_stream(payload: dict, emit):
    """Pipeline handler for apple_stream command."""
    import asyncio

    track_id = payload["track_id"]
    cookies_path = payload.get("cookies_path")
    wrapper_url = payload.get("wrapper_url")
    cache_dir = Path(payload.get("cache_dir", "/tmp/apple_stream"))
    cache_dir.mkdir(parents=True, exist_ok=True)

    async def run():
        api = await open_api(cookies_path, wrapper_url)
        result = await resolve_stream(api, track_id, cache_dir)
        emit_done(emit, result)

    try:
        asyncio.run(run())
    except Exception as e:
        emit_error(emit, "stream_failed", str(e))
```

**`engine/src/main/kotlin/app/soiboi/engine/accounts/AppleSession.kt`:**

```kotlin
package app.soiboi.engine.accounts

import app.soiboi.engine.download.Pipeline
import app.soiboi.engine.discovery.AppleLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

class AppleSession(
    private val library: AppleLibrary,
    private val pipeline: Pipeline,
) : MusicSession {

    override val id = "apple"
    override val label = "Apple Music"

    private val _state = MutableStateFlow<SessionState>(SessionState.SignedOut)
    override val state: StateFlow<SessionState> = _state

    suspend fun signIn(cookiesPath: String?, wrapperUrl: String?) {
        // Validate by fetching storefront
        val storefront = library.storefront()
        _state.value = SessionState.SignedIn(
            account = library.accountEmail() ?: "Apple Music",
            market = storefront,
        )
    }

    override suspend fun libraryPlaylists(): List<SessionPlaylist> {
        return library.listPlaylists().map {
            SessionPlaylist(it.id, it.name, it.trackCount)
        }
    }

    override suspend fun playlistTracks(id: String): List<SessionTrack> {
        return library.playlistTracks(id).map {
            SessionTrack(
                id = it.id,
                title = it.title,
                artist = it.artist,
                album = it.album,
                isrc = it.isrc,
            )
        }
    }

    override suspend fun recentlyAdded(sinceMs: Long): List<SessionTrack> {
        return library.recentlyAdded(sinceMs).map {
            SessionTrack(it.id, it.title, it.artist, it.album, it.isrc)
        }
    }

    override suspend fun search(q: String): SessionSearchResult {
        val results = library.search(q)
        return SessionSearchResult(
            tracks = results.tracks.map { SessionTrack(it.id, it.title, it.artist, it.album, it.isrc) },
            albums = results.albums.map { SessionAlbum(it.id, it.title, it.artist) },
        )
    }

    override suspend fun streamUrl(trackId: String): StreamResult? {
        // Call pipeline to resolve stream
        val payload = buildJsonObject {
            put("track_id", trackId)
            library.cookiesPath?.let { put("cookies_path", it) }
            library.wrapperUrl?.let { put("wrapper_url", it) }
            put("cache_dir", cacheDir.absolutePath)
        }

        val result = pipeline.run("apple_stream", payload)
        return if (result.success) {
            StreamResult(
                url = "file://${result.data["path"]?.jsonPrimitive?.content}",
                format = "m4a",
                bitrate = 256,
            )
        } else {
            null
        }
    }

    override suspend fun downloadRef(trackId: String): DownloadRef {
        val track = library.trackDetails(trackId)
        return DownloadRef(
            session = id,
            trackId = trackId,
            label = "${track.artist} - ${track.title}",
            artist = track.artist,
            album = track.album,
            isrc = track.isrc,
        )
    }
}
```

### Integration points

1. **Register in AddonRegistry** — so search/stream routes through AppleSession
2. **Player.resolveOverride** — check if song.source == "apple" && session signed in → call `session.streamUrl()`
3. **LocalRangeServer** — if returning file paths, serve them via range server for Media3/mpv

### Testing

```bash
# In pipeline venv
cd pipeline
python -c "
from soiboi_pipeline.apple_stream import handle_apple_stream

def emit(msg):
    print(msg)

handle_apple_stream({
    'track_id': '1234567890',  # Real Apple Music track ID
    'cookies_path': '/path/to/cookies.txt',
}, emit)
"
```

---

## Task 2: Apple Playlist Auto-Sync

**Goal:** New songs added to your Apple Music playlists automatically download to local library.

### Files to create

**`engine/src/main/kotlin/app/soiboi/engine/accounts/AccountSync.kt`:**

```kotlin
package app.soiboi.engine.accounts

import app.soiboi.engine.download.DownloadQueue
import app.soiboi.engine.data.Database
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class AccountSync(
    private val sessions: SessionRegistry,
    private val queue: DownloadQueue,
    private val db: Database,
    private val ownedKeys: () -> Set<String>,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null

    fun startPeriodicSync(intervalMs: Long = 30 * 60 * 1000) {
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive) {
                syncAll()
                delay(intervalMs)
            }
        }
    }

    suspend fun syncAll() {
        for (session in sessions.all()) {
            if (session.state.value is SessionState.SignedIn) {
                syncSession(session)
            }
        }
    }

    suspend fun syncSession(session: MusicSession) {
        val playlists = session.libraryPlaylists()

        for (playlist in playlists) {
            val cursor = loadCursor(session.id, playlist.id)
            val tracks = session.playlistTracks(playlist.id)
            val currentIds = tracks.map { it.id }.toSet()

            // Find new tracks
            val newTracks = tracks.filter { it.id !in cursor.knownTrackIds }

            // Filter out already owned
            val toDownload = newTracks.filter { track ->
                val key = ownedSongKey(track.artist ?: "", track.title)
                key !in ownedKeys()
            }

            if (toDownload.isNotEmpty()) {
                // Enqueue downloads
                val refs = toDownload.mapNotNull { session.downloadRef(it.id) }
                queue.enqueue(
                    refs.map { ref ->
                        DownloadJob(
                            url = "session://${ref.session}/${ref.trackId}",
                            label = ref.label,
                            source = "Sync: ${playlist.name}",
                        )
                    }
                )
            }

            // Save cursor
            saveCursor(session.id, playlist.id, SyncCursor(
                playlistId = playlist.id,
                lastSyncMs = System.currentTimeMillis(),
                knownTrackIds = currentIds,
            ))
        }
    }

    // Cursor persistence
    data class SyncCursor(
        val playlistId: String,
        val lastSyncMs: Long,
        val knownTrackIds: Set<String>,
    )

    private fun loadCursor(sessionId: String, playlistId: String): SyncCursor {
        return db.syncCursorQueries
            .load(sessionId, playlistId)
            .executeAsOneOrNull()
            ?.let { SyncCursor(it.playlistId, it.lastSyncMs, it.knownTrackIds.split(",").toSet()) }
            ?: SyncCursor(playlistId, 0L, emptySet())
    }

    private fun saveCursor(sessionId: String, playlistId: String, cursor: SyncCursor) {
        db.syncCursorQueries.upsert(
            sessionId = sessionId,
            playlistId = playlistId,
            lastSyncMs = cursor.lastSyncMs,
            knownTrackIds = cursor.knownTrackIds.joinToString(","),
        )
    }

    private fun ownedSongKey(artist: String, title: String): String {
        return "${artist.lowercase().trim()}|${title.lowercase().trim()}"
    }
}
```

**Schema addition** (`engine/src/main/sqldelight/.../SyncCursor.sq`):

```sql
CREATE TABLE IF NOT EXISTS sync_cursor (
    session_id TEXT NOT NULL,
    playlist_id TEXT NOT NULL,
    last_sync_ms INTEGER NOT NULL,
    known_track_ids TEXT NOT NULL,
    PRIMARY KEY (session_id, playlist_id)
);

upsert:
INSERT OR REPLACE INTO sync_cursor(session_id, playlist_id, last_sync_ms, known_track_ids)
VALUES (?, ?, ?, ?);

load:
SELECT * FROM sync_cursor WHERE session_id = ? AND playlist_id = ?;
```

### Integration

1. **Engine.kt** — instantiate `AccountSync`, call `startPeriodicSync()` on init
2. **DownloadQueue** — handle `session://` URLs by calling session.downloadRef() → pipeline

---

## Task 3: Spotify/YT/SoundCloud Download Sources

**Goal:** Friends sign into their service, app resolves tracks → downloads via Apple ISRC match or yt-dlp.

### Key insight

These services become *sources of truth* for what to download, not the audio source itself. Resolution priority:

1. ISRC match → Apple Music → gamdl download (lossless)
2. YouTube search → yt-dlp → opus/m4a
3. Fail with clear message

### Files to create

**`pipeline/soiboi_pipeline/session_download.py`:**

```python
"""Multi-service download resolution."""

from .download import handle_download
from .apple_auth import open_api


async def resolve_by_isrc(isrc: str, storefront: str, api) -> str | None:
    """Find Apple Music URL for an ISRC."""
    results = await api.get_search_results(
        term=isrc,
        types=["songs"],
        limit=1,
    )
    songs = results.get("results", {}).get("songs", {}).get("data", [])
    if songs:
        return songs[0]["attributes"]["url"]
    return None


def handle_session_download(payload: dict, emit):
    """
    Resolve a track from any session to a downloadable source.

    payload:
        session: "spotify" | "ytmusic" | "soundcloud"
        track_id: str
        isrc: str | None
        prefer_apple: bool (default True)
        apple_cookies_path: str | None
        apple_storefront: str (default "us")
    """
    import asyncio

    session = payload["session"]
    track_id = payload["track_id"]
    isrc = payload.get("isrc")
    prefer_apple = payload.get("prefer_apple", True)

    async def run():
        # Try Apple Music first
        if prefer_apple and isrc:
            try:
                api = await open_api(
                    payload.get("apple_cookies_path"),
                    payload.get("apple_wrapper_url"),
                )
                apple_url = await resolve_by_isrc(
                    isrc,
                    payload.get("apple_storefront", "us"),
                    api,
                )
                if apple_url:
                    # Delegate to standard download
                    return handle_download({"url": apple_url}, emit)
            except Exception:
                pass  # Fall through to yt-dlp

        # yt-dlp fallback
        search_query = payload.get("search_query") or f"{payload.get('artist', '')} {payload.get('title', '')}"
        return handle_download({
            "url": f"ytsearch1:{search_query}",
            "source": session,
        }, emit)

    asyncio.run(run())
```

**`engine/src/main/kotlin/app/soiboi/engine/accounts/SpotifySession.kt`:**

```kotlin
package app.soiboi.engine.accounts

import app.soiboi.engine.spotify.SpotifyApi
import kotlinx.coroutines.flow.*

class SpotifySession(
    private val api: SpotifyApi,
) : MusicSession {

    override val id = "spotify"
    override val label = "Spotify"

    private val _state = MutableStateFlow<SessionState>(SessionState.SignedOut)
    override val state: StateFlow<SessionState> = _state

    suspend fun signIn() {
        val me = api.me()
        _state.value = SessionState.SignedIn(
            account = me.email ?: me.displayName ?: "Spotify",
            market = me.country ?: "US",
        )
    }

    override suspend fun libraryPlaylists(): List<SessionPlaylist> {
        return api.myPlaylists().items.map {
            SessionPlaylist(it.id, it.name, it.tracks.total)
        }
    }

    override suspend fun playlistTracks(id: String): List<SessionTrack> {
        return api.playlistTracks(id).items.map { item ->
            val track = item.track
            SessionTrack(
                id = track.id,
                title = track.name,
                artist = track.artists.firstOrNull()?.name,
                album = track.album.name,
                isrc = track.externalIds?.isrc,
            )
        }
    }

    override suspend fun recentlyAdded(sinceMs: Long): List<SessionTrack> {
        // Spotify doesn't expose "recently added to library" directly
        // Use saved tracks endpoint sorted by added_at
        return api.savedTracks(limit = 50).items
            .filter { it.addedAt.toEpochMilli() > sinceMs }
            .map { SessionTrack(it.track.id, it.track.name, it.track.artists.first().name, it.track.album.name, it.track.externalIds?.isrc) }
    }

    override suspend fun search(q: String): SessionSearchResult {
        val results = api.search(q, types = listOf("track", "album"))
        return SessionSearchResult(
            tracks = results.tracks?.items?.map { SessionTrack(it.id, it.name, it.artists.first().name, it.album.name, it.externalIds?.isrc) } ?: emptyList(),
            albums = results.albums?.items?.map { SessionAlbum(it.id, it.name, it.artists.first().name) } ?: emptyList(),
        )
    }

    override suspend fun streamUrl(trackId: String): StreamResult? {
        // Spotify streaming requires Connect (plays on user's device)
        // Not in-app streaming for v1
        return null
    }

    override suspend fun downloadRef(trackId: String): DownloadRef {
        val track = api.track(trackId)
        return DownloadRef(
            session = id,
            trackId = trackId,
            label = "${track.artists.first().name} - ${track.name}",
            artist = track.artists.first().name,
            album = track.album.name,
            isrc = track.externalIds?.isrc,
        )
    }
}
```

---

## Task 4: Session Registry and UI Wiring

**`engine/src/main/kotlin/app/soiboi/engine/accounts/SessionRegistry.kt`:**

```kotlin
package app.soiboi.engine.accounts

import kotlinx.coroutines.flow.*

class SessionRegistry {
    private val sessions = mutableMapOf<String, MusicSession>()

    fun register(session: MusicSession) {
        sessions[session.id] = session
    }

    fun get(id: String): MusicSession? = sessions[id]

    fun all(): List<MusicSession> = sessions.values.toList()

    fun allStates(): Flow<Map<String, SessionState>> {
        return combine(sessions.values.map { it.state }) { states ->
            sessions.keys.zip(states.toList()).toMap()
        }
    }
}
```

**Wire in Engine.kt:**

```kotlin
// In Engine initialization
val sessionRegistry = SessionRegistry()
val accountSync = AccountSync(sessionRegistry, downloadQueue, database, { ownedKeys })

// Register sessions
sessionRegistry.register(AppleSession(appleLibrary, pipeline))
sessionRegistry.register(SpotifySession(spotifyApi))
sessionRegistry.register(YTMusicSession(pipeline))
sessionRegistry.register(SoundCloudSession(pipeline))

// Start sync
accountSync.startPeriodicSync()
```

---

## Widevine CDM Note

The pipeline needs a `.wvd` device file for pywidevine. Options:

1. **User provides their own** (dumped from an Android device) — place at `pipeline/cdm/device.wvd`
2. **Extract from browser** (Firefox/Chrome) — Kopuz's `discover.rs` shows how

The CDM is **not** shipped with Soiboi. First run prompts user to provide one or attempts browser extraction.

---

## Checklist

- [ ] Create `engine/accounts/` package with interfaces
- [ ] Implement `apple_stream.py` with Widevine decrypt
- [ ] Create `AppleSession.kt` wrapping existing AppleLibrary
- [ ] Register Apple session as addon provider
- [ ] Test streaming a single track
- [ ] Implement `AccountSync.kt` with cursor persistence
- [ ] Add sync_cursor table migration
- [ ] Wire sync to DownloadQueue
- [ ] Test: add song to Apple playlist → downloads
- [ ] Implement `SpotifySession.kt` using existing SpotifyApi
- [ ] Implement `session_download.py` ISRC resolution
- [ ] Test: Spotify playlist → downloads via Apple match
- [ ] Add UI: Accounts section in Sources
- [ ] Add UI: "What's New" shelf on home

---

## Questions / Decisions Needed

1. **CDM sourcing:** Auto-extract from browser, or require user to provide `.wvd`?
2. **Sync frequency:** Default 30 min, configurable, or only manual?
3. **Download quality for synced tracks:** Match user's global setting, or separate?
4. **Spotify Connect:** Keep existing backend, or remove since not in-app streaming?

---

## Reference: Kopuz Code Locations

For implementation details, these Kopuz files show the exact mechanisms:

| Feature | Kopuz file |
|---------|-----------|
| Widevine challenge/license | `crates/server/src/applemusic/stream.rs` lines 241-354 |
| PSSH parsing | `crates/server/src/applemusic/cenc.rs` |
| CDM discovery | `crates/server/src/applemusic/widevine/discover.rs` |
| CDM download | `crates/server/src/applemusic/widevine/fetch.rs` |
| Spotify API | `crates/server/src/spotify/` |

Kopuz clone is at `/home/localindiesoyboy/.hermes/cache/scratch/kopuz`.
