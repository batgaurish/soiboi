# Account Sources: Implementation Plan

**Status:** Planning — no code changes yet  
**Date:** 2026-10-17  
**Scope:** Apple Music streaming/sync, Spotify/YT Music/SoundCloud as download sources

---

## 1. Goals

1. **Apple Music sync**: Discover new releases for your account (followed artists, recently added) and auto-download new songs added to your Apple Music playlists into the local library.

2. **Apple Music streaming**: Play Apple Music tracks in-app without downloading first, at web quality (~256 kbps AAC).

3. **Multi-service accounts**: Friends sign into Spotify/YouTube Music/SoundCloud and use those as sources for downloads (resolved via ISRC → Apple, or yt-dlp fallback).

4. **Standalone**: No server, no Docker. Sessions persist locally (tokens in SecretStore, cookies on disk).

---

## 2. Current State (Verified)

### What exists and this plan builds on:

| Component | Location | What it does |
|-----------|----------|--------------|
| **Player resolution** | `engine/player/Player.kt` | `resolveOverride` maps songs to `(source: String, headers: Map)` for the audio backend |
| **Addon system** | `engine/addons/AddonClient.kt` | `CatalogClient` interface: `search`, `stream`, `album`, `playlist`, `catalog` |
| **Built-in providers** | `engine/addons/AddonRegistry.kt` | YouTube Music, SoundCloud, Radio, Spotify already registered as `BuiltinProvider` |
| **Download queue** | `engine/download/DownloadQueue.kt` | Sequential jobs, progress/logs, `owned`-skip, batch tracking |
| **Archive environment** | `engine/download/Archive.kt` | `archiveUrl(url, ...)` runs pipeline `download` command |
| **Pipeline runner** | `engine/download/Pipeline.kt` | `PipelineRunner.run(command, payload)` works identically on desktop (subprocess) and Android (Chaquopy) |
| **Apple library** | `engine/discovery/AppleLibrary.kt` + `pipeline/apple_library.py` | Lists account playlists, tracks, storefront via gamdl |
| **Apple auth** | `pipeline/apple_auth.py` | `open_api(cookies, wrapper_url)` → gamdl `AppleMusicApi` |
| **Wrapper service** | `engine/download/Wrapper.kt` | wrapper-v2 sign-in, `/me` tokens, FPS decrypt port |
| **Spotify API** | `engine/spotify/SpotifyApi.kt` | OAuth PKCE, token refresh, Web API calls |
| **Spotify playback** | `engine/spotify/SpotifyBackend.kt` | Connect-device playback (drives user's Spotify app) |
| **Range server** | `engine/servers/RangeServer.kt` | `LocalRangeServer` serves bytes over HTTP with Range support |
| **YT stream** | `pipeline/stream.py` | `handle_youtube_stream` → yt-dlp `bestaudio` → URL |

### Pipeline dependencies (already in .pipeline-venv):

- `gamdl 3.8.5` — Apple Music API client, downloader
- `pywidevine 1.9.0` — Widevine CDM for license exchange
- `pycryptodome` — AES decryption
- `pymp4` — MP4 box parsing
- `yt-dlp` — YouTube/SoundCloud extraction

### Key gamdl API methods available:

```python
AppleMusicApi.create_from_wrapper(wrapper_url)
AppleMusicApi.create_from_netscape_cookies(path)
api.get_library_playlists(limit, offset)
api.get_library_songs(limit, offset, include, extend)
api.get_webplayback(track_id, is_library)  # Returns flavor list
api.get_license_exchange(track_id, uri, challenge, key_system)
api.get_search_results(term, types, limit)
```

---

## 3. Architecture

### 3.1 New Abstraction: `MusicSession`

A signed-in music service account. Not a server (no peer), not an addon (known protocol). First-class source with discovery, sync, and streaming.

```kotlin
// engine/accounts/MusicSession.kt
interface MusicSession {
    val id: String           // "apple", "spotify", "ytmusic", "soundcloud"
    val label: String        // "Apple Music", "Spotify", etc.
    val state: StateFlow<SessionState>

    suspend fun libraryPlaylists(): List<SessionPlaylist>
    suspend fun playlistTracks(id: String): List<SessionTrack>
    suspend fun recentlyAdded(sinceMs: Long): List<SessionTrack>
    suspend fun search(q: String): SessionSearchResult

    // For streaming (Apple only in v1)
    suspend fun streamUrl(trackId: String): StreamResult?

    // For downloads
    suspend fun downloadRef(trackId: String): DownloadRef?
}

sealed class SessionState {
    object SignedOut : SessionState()
    data class SignedIn(val account: String, val market: String) : SessionState()
    data class Error(val message: String) : SessionState()
}
```

### 3.2 Apple Music Streaming Path

The insight: Apple's Web Playback endpoint returns multiple flavors. The `aac-web` flavor (`28:ctrp256`) is Widevine CENC — **not FairPlay**. pywidevine (already in the venv) can exchange licenses for it.

**Resolution flow:**

```
1. track_id → api.get_webplayback(track_id)
2. Pick flavor "28:ctrp256" (aac-web, ~256 kbps)
3. Parse HLS playlist → extract PSSH box
4. pywidevine.generate_challenge(pssh) → challenge bytes
5. api.get_license_exchange(track_id, uri, challenge) → license
6. pywidevine.parse_license(license) → content key
7. Download encrypted fMP4 segments
8. Decrypt with AES-128-CTR using content key
9. Return decrypted m4a path (or serve via LocalRangeServer)
```

**New pipeline command:** `apple_stream`

```python
# pipeline/apple_stream.py
def handle_apple_stream(payload, emit):
    """Resolve a track to a playable URL or decrypted temp file."""
    track_id = payload["track_id"]
    storefront = payload.get("storefront", "us")
    quality = payload.get("quality", "aac")  # aac | lossless

    api = await open_api(payload["cookies_path"], payload.get("wrapper_url"))
    webplayback = await api.get_webplayback(track_id)

    # Pick the right flavor
    flavor = CODEC_FLAVOR_MAP.get(quality, "28:ctrp256")
    asset = find_asset(webplayback["songList"][0]["assets"], flavor)

    if quality == "aac":
        # Web AAC: Widevine CENC
        decrypted_path = await decrypt_widevine(api, track_id, asset)
        return done(path=decrypted_path, format="m4a", bitrate=256)
    else:
        # Lossless: requires wrapper decrypt port (existing path)
        # Delegate to existing download mechanism
        return error("lossless_stream", "Use download for lossless")
```

### 3.3 Auto-Sync Mechanism

**`AccountSync` service** (engine):

```kotlin
// engine/accounts/AccountSync.kt
class AccountSync(
    private val sessions: Map<String, MusicSession>,
    private val queue: DownloadQueue,
    private val db: Database,
    private val ownedKeys: () -> List<String>,
) {
    // Per-session, per-playlist cursor
    data class SyncCursor(
        val playlistId: String,
        val lastSyncMs: Long,
        val knownTrackIds: Set<String>,
    )

    suspend fun syncSession(session: MusicSession) {
        val playlists = session.libraryPlaylists()
        for (playlist in playlists) {
            val cursor = loadCursor(session.id, playlist.id)
            val tracks = session.playlistTracks(playlist.id)

            val newTracks = tracks.filter { it.id !in cursor.knownTrackIds }
            val toDownload = newTracks.filter { !isOwned(it) }

            if (toDownload.isNotEmpty()) {
                val refs = toDownload.mapNotNull { session.downloadRef(it.id) }
                enqueueDownloads(refs, "Sync: ${playlist.name}")
            }

            saveCursor(session.id, playlist.id, SyncCursor(
                playlistId = playlist.id,
                lastSyncMs = System.currentTimeMillis(),
                knownTrackIds = tracks.map { it.id }.toSet(),
            ))
        }
    }

    private fun isOwned(track: SessionTrack): Boolean {
        val key = ownedSongKey(track.artist, track.title)
        return key in ownedKeys()
    }
}
```

**Sync triggers:**
- App launch (once per day)
- Manual "Sync now" button
- Background timer (30 min default, configurable)

### 3.4 Download from Any Service

Each session provides `downloadRef(trackId)` → `DownloadRef`:

```kotlin
data class DownloadRef(
    val session: String,      // "apple", "spotify", etc.
    val trackId: String,
    val label: String,
    val artist: String?,
    val album: String?,
    val isrc: String?,        // For cross-service matching
)
```

**Resolution priority for Spotify/YT/SoundCloud:**

1. If user has Apple session: ISRC → Apple catalog → download via gamdl (best quality)
2. Else: yt-dlp fallback (YouTube audio match)
3. Else: service's own preview (30s, labeled honestly)

**New pipeline command:** `session_download`

```python
# pipeline/session_download.py
def handle_session_download(payload, emit):
    session = payload["session"]
    track_id = payload["track_id"]
    isrc = payload.get("isrc")
    prefer_apple = payload.get("prefer_apple", True)

    if prefer_apple and isrc:
        # Try Apple Music first
        apple_match = resolve_by_isrc(isrc, payload.get("storefront", "us"))
        if apple_match:
            return delegate_to_download(apple_match["url"], emit)

    # Fallback to yt-dlp
    if session in ("spotify", "ytmusic", "soundcloud"):
        return download_via_ytdlp(session, track_id, emit)

    return error("no_source", "Could not find a download source")
```

---

## 4. File Changes

### 4.1 New Kotlin Files

```
engine/src/main/kotlin/app/soiboi/engine/accounts/
├── MusicSession.kt          # Interface + SessionState
├── AppleSession.kt          # Wraps AppleLibrary + new streaming
├── SpotifySession.kt        # Wraps SpotifyApi
├── YTMusicSession.kt        # Cookie-based YT Music
├── SoundCloudSession.kt     # Public API + yt-dlp
├── AccountSync.kt           # Sync loop + cursor persistence
└── SessionRegistry.kt       # Manages all sessions
```

### 4.2 New Pipeline Files

```
pipeline/soiboi_pipeline/
├── apple_stream.py          # Widevine stream resolution
├── session_download.py      # Multi-service download dispatch
└── sync_diff.py             # Playlist diff helpers
```

### 4.3 Modified Files

| File | Changes |
|------|---------|
| `engine/Engine.kt` | Add `sessions: SessionRegistry`, `accountSync: AccountSync` |
| `engine/download/Archive.kt` | Accept `DownloadRef` alongside URL |
| `engine/addons/AddonRegistry.kt` | Register session-backed providers |
| `pipeline/__main__.py` | Register new handlers |
| `shared-ui/screens/Sources.kt` | Show account cards with sync status |
| `shared-ui/screens/Downloads.kt` | Show "Sync" section |

---

## 5. Quality Tiers

| Tier | Apple Flavor | Source | Notes |
|------|--------------|--------|-------|
| `aac` (default) | `28:ctrp256` | Web Playback + Widevine | ~256 kbps, in-app streaming |
| `lossless` | `alac` | Wrapper + FPS | Requires download, not streaming |
| `hi-res` | `alac` 24-bit | Wrapper + FPS | Requires download |

For streaming, only `aac` tier is available. Downloads support all tiers (existing path).

---

## 6. UI Changes

### 6.1 New "Accounts" Section in Sources

```
┌─────────────────────────────────────────┐
│ Your Accounts                           │
├─────────────────────────────────────────┤
│ 🍎 Apple Music                          │
│    Signed in as gaurish@... (US)        │
│    Last synced: 2 hours ago             │
│    [Sync Now] [Settings]                │
├─────────────────────────────────────────┤
│ 🎵 Spotify                              │
│    Connected (Premium)                  │
│    Downloads via Apple Music match      │
│    [Settings]                           │
├─────────────────────────────────────────┤
│ + Add Account                           │
└─────────────────────────────────────────┘
```

### 6.2 "What's New" on Home Screen

Shows union of `session.recentlyAdded()` across connected accounts, with download buttons.

---

## 7. Implementation Phases

### Phase 1: Apple Streaming (P1)

1. `apple_stream.py` — Widevine license + decrypt
2. `AppleSession.kt` — wraps existing AppleLibrary + new stream
3. Register as BuiltinProvider so search/stream works
4. Test: play any Apple Music track in-app

### Phase 2: Apple Sync (P1)

1. `AccountSync.kt` — diff loop + cursor persistence
2. `sync_diff.py` — playlist track diff helpers
3. Wire to DownloadQueue with "Sync: playlist" labels
4. Test: add song to Apple playlist → appears in local library

### Phase 3: Spotify/YT/SoundCloud Sources (P2)

1. `session_download.py` — ISRC resolution + yt-dlp fallback
2. `SpotifySession.kt`, `YTMusicSession.kt`, `SoundCloudSession.kt`
3. Test: friend's Spotify playlist → downloads via Apple/YT match

### Phase 4: Discovery Feeds (P3)

1. `session.recentlyAdded()` for each service
2. "What's New" home shelf
3. Per-artist new release notifications

---

## 8. Out of Scope (v1)

- Lossless streaming (requires FPS in-player; download instead)
- Spotify in-app audio (Connect only; SDK requires Widevine in WebView)
- Writing to cloud libraries (no add/remove on Apple/Spotify)
- Scrobbling
- Apple Music video

---

## 9. Testing Strategy

| Test | How |
|------|-----|
| Apple stream | Unit: mock webplayback response; Integration: real account |
| Widevine decrypt | Unit: known test vectors; Integration: real track |
| Sync diff | Unit: mock playlist states |
| ISRC resolution | Unit: mock Apple catalog; Integration: real ISRC |
| yt-dlp fallback | Integration: known YouTube track |

---

## 10. Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| pywidevine unavailable on Android arm64 | Host custom wheel in pip-repo/ |
| Apple rate limits | Existing cooldowns in downloader; sync is low-volume |
| Widevine license failures | Fall back to download, show clear error |
| Session expiry | Refresh tokens proactively; prompt re-auth when needed |
