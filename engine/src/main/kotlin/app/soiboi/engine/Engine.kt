package app.soiboi.engine

import app.soiboi.engine.ai.AiConfig
import app.soiboi.engine.ai.AiFeatures
import app.soiboi.engine.ai.SongTags
import app.soiboi.engine.apple.AppleCatalog
import app.soiboi.engine.core.AppPaths
import app.soiboi.engine.core.EngineLog
import app.soiboi.engine.core.SecretStore
import app.soiboi.engine.core.SettingsStore
import app.soiboi.engine.core.resolveDownloadDir
import app.soiboi.engine.debrid.RealDebridResolver
import app.soiboi.engine.debrid.TorboxResolver
import app.soiboi.engine.discovery.AppleLibrary
import app.soiboi.engine.discovery.Discovery
import app.soiboi.engine.discovery.LinkedPlaylists
import app.soiboi.engine.discovery.ListenBrainz
import app.soiboi.engine.discovery.ListenBrainzPlaylistSource
import app.soiboi.engine.discovery.ManualResolution
import app.soiboi.engine.download.ArchiveEnvironment
import app.soiboi.engine.download.CookieStore
import app.soiboi.engine.download.DownloadQueue
import app.soiboi.engine.download.PipelineRunner
import app.soiboi.engine.download.WrapperPlatform
import app.soiboi.engine.download.WrapperService
import app.soiboi.engine.download.archiveUrl
import app.soiboi.engine.library.AcousticAnalysis
import app.soiboi.engine.library.AcousticSidecars
import app.soiboi.engine.library.Backup
import app.soiboi.engine.library.AcousticSummary
import app.soiboi.engine.library.Artwork
import app.soiboi.engine.library.Database
import app.soiboi.engine.library.FolderScanner
import app.soiboi.engine.library.Library
import app.soiboi.engine.library.PlaylistStore
import app.soiboi.engine.library.Song
import app.soiboi.engine.library.SongOrigin
import app.soiboi.engine.library.SongStore
import app.soiboi.engine.library.TagReader
import app.soiboi.engine.lyrics.Lrclib
import app.soiboi.engine.lyrics.LyricsProvider
import app.soiboi.engine.player.AudioBackend
import app.soiboi.engine.player.Player
import app.soiboi.engine.servers.GoogleDriveAuth
import app.soiboi.engine.servers.GoogleDriveServer
import app.soiboi.engine.servers.DriveTokens
import app.soiboi.engine.servers.JellyfinServer
import app.soiboi.engine.servers.MusicServer
import app.soiboi.engine.servers.PlexServer
import app.soiboi.engine.servers.SubsonicServer
import app.soiboi.engine.servers.WebDavServer
import app.soiboi.engine.servers.SmbServer
import app.soiboi.engine.smart.SmartContext
import app.soiboi.engine.smart.SmartPlaylist
import app.soiboi.engine.sources.PlaylistSources
import app.soiboi.engine.sources.YouTubePlaylistSource
import app.soiboi.engine.accounts.AccountSync
import app.soiboi.engine.accounts.AppleSession
import app.soiboi.engine.accounts.SessionRegistry
import app.soiboi.engine.accounts.SpotifySession
import app.soiboi.engine.accounts.YTMusicSession
import app.soiboi.engine.accounts.SoundCloudSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** What the platform shell supplies to the engine: where files live, how to run Python, how to make sound, where secrets go. */
class Platform(
    val paths: AppPaths,
    val pipeline: PipelineRunner,
    val audio: AudioBackend,
    val secrets: SecretStore,
    val wrapper: WrapperPlatform,
    val isAndroid: Boolean = false,
    /** Android asks for All-files access when the chosen download folder needs it. */
    val ensureFolderWritable: suspend () -> Unit = {},
    val appVersion: String = "2.6.8",
)

/** One saved connection. Passwords and tokens live in the [SecretStore], never here. */
@Serializable
data class ServerConfig(val kind: SongOrigin, val url: String = "", val username: String = "", val extra: String = "")

/** Which services' keys the user has entered for playback sources and what is turned on. */
data class SourceStatus(val kind: SongOrigin, val connected: Boolean, val songs: Int, val syncing: Boolean = false, val error: String? = null)

/**
 * The composition root: every service the app has, wired once. The UI holds a single [Engine] and reads
 * its state flows; it never constructs a service itself. Everything platform-specific arrives in [Platform].
 */
class Engine(val platform: Platform, val scope: CoroutineScope) {
    val paths = platform.paths.also { it.ensure(); EngineLog.attach(java.io.File(it.supportDir, "logs/soiboi.log")) }
    val settings = SettingsStore(paths.settingsFile)
    val spotifyApi = app.soiboi.engine.spotify.SpotifyApi(platform.secrets)
    private val spotifyBackendRouter = app.soiboi.engine.spotify.RoutingBackend(platform.audio, app.soiboi.engine.spotify.SpotifyBackend(spotifyApi, scope) { settings.value.spotifyDeviceId.ifBlank { null } })
    val db = Database(paths.databaseFile)

    // library
    val songStore = SongStore(db)
    val library = Library(songStore)
    val playlists = PlaylistStore(db)
    private val cookies = CookieStore(java.io.File(paths.supportDir, "apple_music_cookies.txt"))
    val tagReader = TagReader(platform.pipeline)
    val sidecars = AcousticSidecars(paths.acousticDir)
    val scanner = FolderScanner(songStore, db, tagReader, sidecars, { resolveDownloadDir(settings.value, paths).path }, { settings.value.recursiveScan })

    val artwork = Artwork(paths.picturesDir, platform.pipeline) { serverMap }

    // downloading
    val cookieStore get() = cookies
    val wrapper = WrapperService(paths.wrapperDir, platform.wrapper, platform.pipeline)
    private val archiveEnv = ArchiveEnvironment(
        platform.pipeline, settings, paths, cookies, wrapper, ownedKeys = { library.songs.value.filter { !it.title.isNullOrEmpty() }.map { app.soiboi.engine.core.ownedSongKey(it.artist ?: "", it.title!!) }.distinct() },
        ensureFolderWritable = platform.ensureFolderWritable,
    )
    val downloads = DownloadQueue(
        scope,
        archive = { url, onProgress, onLog, onTrack, logPath -> archiveEnv.archiveUrl(url, false, onProgress, onLog, onTrack, logPath) },
        syncLibrary = { syncArchive() },
        stopActive = { platform.pipeline.cancelDownload() },
        logDir = paths.downloadLogDir,
    )

    // discovery
    val apple = AppleCatalog { settings.value.storefront }
    val manualResolution = ManualResolution(db)
    val linked = LinkedPlaylists(db, library, playlists)
    val appleLibrary = AppleLibrary(platform.pipeline, cookies, wrapper, settings, downloads, linked)
    val listenBrainz = ListenBrainz({ settings.value.listenBrainzUser }, library)
    val youtube = YouTubePlaylistSource(platform.pipeline)
    val playlistSources = PlaylistSources.standard(youtube).also { it.register(ListenBrainzPlaylistSource(listenBrainz)) }
    val discovery = Discovery(playlistSources, apple, manualResolution) { appleLibrary.accountStorefront() }

    // account sessions
    val sessionRegistry = SessionRegistry()
    val appleSession = AppleSession(
        appleLibrary,
        platform.pipeline,
        java.io.File(paths.cacheDir, "streams"),
        cookiesPath = { cookies.path.takeIf { java.io.File(it).exists() } },
        wrapperUrl = { null }, // Wrapper URL is obtained dynamically by the pipeline when needed
    )
    val spotifySession = SpotifySession(spotifyApi)
    val ytMusicSession = YTMusicSession(
        platform.pipeline,
        cookiesPath = { platform.secrets.get("ytmusic.cookies")?.let { java.io.File(paths.supportDir, "ytmusic_cookies.txt").also { f -> if (!f.exists() && it.isNotBlank()) f.writeText(it) }.path } },
        saveCookies = { cookies ->
            val file = java.io.File(paths.supportDir, "ytmusic_cookies.txt")
            file.writeText(cookies)
            platform.secrets.put("ytmusic.cookies", file.path)
        }
    )
    val soundCloudSession = SoundCloudSession(
        platform.pipeline,
        saveCredential = { cookies -> platform.secrets.put("soundcloud.cookies", cookies) },
    )
    val accountSync = AccountSync(
        sessionRegistry,
        downloads,
        java.io.File(paths.supportDir, "sync_cursors"),
        ownedKeys = { library.songs.value.filter { !it.title.isNullOrEmpty() }.map { app.soiboi.engine.core.ownedSongKey(it.artist ?: "", it.title!!) }.toSet() },
        scope = scope,
    )

    val backup = Backup(db, library, playlists, settings) { _smart.value }

    // intelligence
    val tags = SongTags(db)
    private val _aiConfig = MutableStateFlow<AiConfig?>(null)
    val aiConfig: StateFlow<AiConfig?> = _aiConfig.asStateFlow()
    val ai = AiFeatures(library, playlists, tags, { _aiConfig.value }, { listenBrainz.topArtists(app.soiboi.engine.discovery.LbRange.Year)?.map { it.name }.orEmpty() })
    private val _smart = MutableStateFlow<List<SmartPlaylist>>(emptyList())
    val smartPlaylists: StateFlow<List<SmartPlaylist>> = _smart.asStateFlow()

    // lyrics
    val lrclib = Lrclib(java.io.File(paths.cacheDir, "lyrics"))
    val lyrics = LyricsProvider(lrclib, { settings.value.lrclibEnabled }, { song -> servers[song.origin]?.lyrics(song) }, {
        buildList {
            if (settings.value.musixmatchToken.isNotBlank()) add(app.soiboi.engine.lyrics.MusixmatchSource { settings.value.musixmatchToken })
            if (settings.value.paxsenixLyrics) add(app.soiboi.engine.lyrics.PaxsenixSource())
        }
    })

    // connectors and debrid
    val addons = app.soiboi.engine.addons.AddonRegistry(db, listOf(
        app.soiboi.engine.providers.youTubeMusicProvider { id -> youtubeStream(id) }, app.soiboi.engine.providers.SoundCloudProvider, app.soiboi.engine.providers.RadioProvider, app.soiboi.engine.spotify.spotifyProvider(spotifyApi),
    ))
    val updater = app.soiboi.engine.update.Updater("batgaurish/soiboi", platform.appVersion, platform.isAndroid, paths.cacheDir)
    private val serverMap = HashMap<SongOrigin, MusicServer>()
    val servers: Map<SongOrigin, MusicServer> get() = serverMap
    private val _serverStatus = MutableStateFlow<Map<SongOrigin, SourceStatus>>(emptyMap())
    val serverStatus: StateFlow<Map<SongOrigin, SourceStatus>> = _serverStatus.asStateFlow()
    private val _serverConfigs = MutableStateFlow<List<ServerConfig>>(emptyList())
    val serverConfigs: StateFlow<List<ServerConfig>> = _serverConfigs.asStateFlow()
    val torbox = TorboxResolver { platform.secrets.get("torbox.key").orEmpty() }
    val realDebrid = RealDebridResolver { platform.secrets.get("realdebrid.token").orEmpty() }
    val resolvers = ResolverChain(listOf(torbox, realDebrid))

    // playback
    val player: Player = Player(
        scope, spotifyBackendRouter, library, songStore, db, { serverMap },
        cacheDir = java.io.File(paths.cacheDir, "remote"),
        resolveOverride = { song ->
            if (song.origin == SongOrigin.Addon && song.id.startsWith("addon:apple:")) {
                val trackId = song.id.removePrefix("addon:apple:")
                val appleStream = runCatching { appleSession.streamUrl(trackId) }.getOrNull()
                if (appleStream != null) {
                    appleStream.url to appleStream.headers
                } else {
                    val fromResolver = resolvers.resolve(TrackRef(song.displayTitle, song.displayArtist, song.album))?.let { it.url to emptyMap<String, String>() }
                    if (fromResolver != null) {
                        fromResolver
                    } else {
                        val query = listOfNotNull(song.displayArtist.takeIf { it.isNotBlank() }, song.displayTitle.takeIf { it.isNotBlank() }).joinToString(" - ")
                        if (query.isNotBlank()) {
                            val yt = runCatching { youtubeStream("ytsearch1:$query") }.getOrNull()
                            yt?.let { it.url to it.headers }
                        } else null
                    }
                }
            } else if (song.origin == SongOrigin.Addon) {
                val stream = runCatching { addons.streamFor(song) }.getOrNull()
                if (stream != null) {
                    stream
                } else {
                    val fromResolver = resolvers.resolve(TrackRef(song.displayTitle, song.displayArtist, song.album))?.let { it.url to emptyMap<String, String>() }
                    if (fromResolver != null) {
                        fromResolver
                    } else {
                        val query = listOfNotNull(song.displayArtist.takeIf { it.isNotBlank() }, song.displayTitle.takeIf { it.isNotBlank() }).joinToString(" - ")
                        if (query.isNotBlank()) {
                            val yt = runCatching { youtubeStream("ytsearch1:$query") }.getOrNull()
                            yt?.let { it.url to it.headers }
                        } else null
                    }
                }
            } else if (song.origin == SongOrigin.Local) {
                null
            } else {
                resolvers.resolve(TrackRef(song.displayTitle, song.displayArtist, song.album))?.let { it.url to emptyMap() }
            }
        },
        onPlayed = { song, _ -> serverMap[song.origin]?.scrobble(song.id); if (song.origin != SongOrigin.Local && song.playCount > 1) player.cache(song) },
        onSongChanged = { song -> lyrics.parsedFor(song) },
        metaFor = { s -> app.soiboi.engine.player.TrackMeta(s.displayTitle, s.displayArtist, s.displayAlbum, runCatching { artwork.coverFor(s)?.path }.getOrNull(), s.durationMs) },
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Measures energy, tempo and danceability for every local song, then rereads the sidecars so smart playlists see them. */
    suspend fun analyse(onProgress: (String) -> Unit = {}): AcousticSummary {
        val summary = AcousticAnalysis(platform.pipeline, paths.acousticDir).run(scanner.folders(), onProgress)
        library.set(library.songs.value.map { sidecars.load(it) })
        songStore.upsert(library.songs.value.filter { it.origin == SongOrigin.Local })
        return summary
    }

    /** Keeps an addon song in the library so it can sit in a playlist or among favorites. Addon songs are otherwise only in the queue. */
    suspend fun keepAddonSong(song: app.soiboi.engine.library.Song) {
        if (song.origin != SongOrigin.Addon || library.song(song.id) != null) return
        songStore.upsert(listOf(song))
        library.set(library.songs.value + song)
    }

    suspend fun exportBackup(target: java.io.File) = backup.export(target)
    suspend fun importBackup(source: java.io.File): Boolean = backup.import(source).also { if (it) { playlists.load(); loadSmart() } }

    /** A direct audio URL for a YouTube video, read by yt-dlp in the pipeline. */
    private suspend fun youtubeStream(id: String): app.soiboi.engine.addons.AddonStream {
        var result: app.soiboi.engine.download.PipelineEvent? = null
        platform.pipeline.run("youtube_stream", kotlinx.serialization.json.buildJsonObject { put("id", kotlinx.serialization.json.JsonPrimitive(id)) }).collect { if (it.isDone || it.isError) result = it }
        val r = result ?: throw app.soiboi.engine.addons.AddonException("The downloader did not answer.")
        if (r.isError) throw app.soiboi.engine.addons.AddonException(if (r.code == "no_runtime" || r.code == "android_bridge") "YouTube Music needs the downloader, which is not available on this device yet." else r.message)
        val url = (r.raw["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: throw app.soiboi.engine.addons.AddonException("YouTube gave no stream for this track.")
        val headers = (r.raw["headers"] as? kotlinx.serialization.json.JsonObject)?.mapNotNull { (k, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { k to it } }?.toMap().orEmpty()
        return app.soiboi.engine.addons.AddonStream(url, (r.raw["ext"] as? kotlinx.serialization.json.JsonPrimitive)?.content, null, headers)
    }

    /** Loads everything persisted and brings the library up. Call once at startup. */
    suspend fun start() {
        EngineLog.sink = { println("[soiboi] $it"); System.err.println("[soiboi] $it") }
        cookies.refresh()
        playlists.load(); manualResolution.load(); linked.load(); tags.load(); addons.load()
        loadSmart(); loadAiConfig(); loadServers()
        library.set(songStore.all())
        wrapper.refresh()
        player.restore()
        scope.launch { runCatching { platform.pipeline.capabilities().also { _capabilities.value = it } } }
        scope.launch { runCatching { wrapper.probeSignIn() } }
        // Pick up anything added or changed while the app was closed.
        if (scanner.folders().isNotEmpty()) scope.launch { runCatching { rescan() } }
        // Initialize account sessions
        scope.launch { initSessions() }
    }

    /** Initialize and register account sessions. */
    private suspend fun initSessions() {
        sessionRegistry.register(appleSession)
        sessionRegistry.register(spotifySession)
        sessionRegistry.register(ytMusicSession)
        sessionRegistry.register(soundCloudSession)
        // Refresh session states
        runCatching { appleSession.refresh() }
        runCatching { spotifySession.refresh() }
        runCatching { ytMusicSession.refresh() }
        runCatching { soundCloudSession.refresh() }
        // Sync is disabled by default; user enables in settings via accountSync.setEnabled(true)
    }

    /** Trigger manual sync for all signed-in sessions. */
    suspend fun syncAccounts() {
        accountSync.syncAll()
    }

    private val _capabilities = MutableStateFlow<app.soiboi.engine.download.PipelineCapabilities?>(null)
    val capabilities: StateFlow<app.soiboi.engine.download.PipelineCapabilities?> = _capabilities.asStateFlow()

    // --- library ---------------------------------------------------------------------------------------------------------------

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val _scanProgress = MutableStateFlow(0)
    val scanProgress: StateFlow<Int> = _scanProgress.asStateFlow()

    /** Rescans the registered folders, then refreshes everything that reads the library. */
    suspend fun rescan() {
        if (_scanning.value) return
        _scanning.value = true
        try {
            scanner.sync { _scanProgress.value = it }
            reloadLibrary()
        } finally { _scanning.value = false }
    }

    private suspend fun reloadLibrary() {
        library.set(songStore.all())
        player.syncWithLibrary(); linked.refreshAll()
    }

    /** Registers the archive folder on first use, then rescans, so a finished download shows up in the library. */
    private suspend fun syncArchive() {
        scanner.addFolder(resolveDownloadDir(settings.value, paths).path)
        rescan()
    }

    // --- smart playlists -------------------------------------------------------------------------------------------------------

    val smartContext: SmartContext get() = SmartContext(vibeOf = tags::of, isFavorite = { it.id in playlists.favoriteIds })

    private suspend fun loadSmart() {
        _smart.value = db.getKv("smart:playlists")?.let { runCatching { json.decodeFromString(ListSerializer(SmartPlaylist.serializer()), it) }.getOrNull() }.orEmpty()
    }

    suspend fun saveSmart(playlist: SmartPlaylist, replacing: String? = null) {
        val target = replacing ?: playlist.name
        _smart.update { list -> if (list.any { it.name == target }) list.map { if (it.name == target) playlist else it } else list + playlist }
        db.putKv("smart:playlists", json.encodeToString(ListSerializer(SmartPlaylist.serializer()), _smart.value))
    }

    suspend fun removeSmart(name: String) {
        _smart.update { list -> list.filter { it.name != name } }
        db.putKv("smart:playlists", json.encodeToString(ListSerializer(SmartPlaylist.serializer()), _smart.value))
    }

    fun songsOf(smart: SmartPlaylist): List<Song> = smart.evaluate(library.songs.value, smartContext)

    // --- AI --------------------------------------------------------------------------------------------------------------------

    private suspend fun loadAiConfig() {
        platform.secrets.get("ai.config")?.let { _aiConfig.value = runCatching { json.decodeFromString(AiConfig.serializer(), it) }.getOrNull() }
    }

    fun saveAiConfig(config: AiConfig?) {
        _aiConfig.value = config
        platform.secrets.put("ai.config", config?.let { json.encodeToString(AiConfig.serializer(), it) })
    }

    // --- connectors ------------------------------------------------------------------------------------------------------------

    private suspend fun loadServers() {
        _serverConfigs.value = db.getKv("servers")?.let { runCatching { json.decodeFromString(ListSerializer(ServerConfig.serializer()), it) }.getOrNull() }.orEmpty()
        for (config in _serverConfigs.value) buildServer(config)?.let { serverMap[config.kind] = it }
        refreshServerStatus()
    }

    private fun buildServer(c: ServerConfig): MusicServer? {
        val secret = platform.secrets.get("server.${c.kind.name}")
        return when (c.kind) {
            SongOrigin.Navidrome, SongOrigin.OpenSubsonic -> SubsonicServer(c.url, c.username, secret.orEmpty(), c.kind)
            SongOrigin.Jellyfin, SongOrigin.Emby -> JellyfinServer(c.url, c.username, secret.orEmpty(), c.kind, platform.appVersion)
            SongOrigin.Plex -> PlexServer(c.url, secret.orEmpty())
            SongOrigin.WebDav -> WebDavServer(c.url, c.username, secret.orEmpty(), recursive = true)
            SongOrigin.Nextcloud -> {
                val folder = c.extra.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
                val user = java.net.URLEncoder.encode(c.username, "UTF-8").replace("+", "%20")
                WebDavServer(c.url.trim().trimEnd('/') + "/remote.php/dav/files/$user" + if (folder.isEmpty()) "" else "/$folder", c.username, secret.orEmpty(), recursive = true, origin = SongOrigin.Nextcloud, label = "Nextcloud")
            }
            SongOrigin.Smb -> SmbServer(c.url, c.username, secret.orEmpty())
            SongOrigin.GoogleDrive -> {
                val client = platform.secrets.get("drive.client")?.split('\n')?.takeIf { it.size == 2 } ?: return null
                val tokens = secret?.split('\n')?.takeIf { it.size == 3 }?.let { DriveTokens(it[0], it[1].ifEmpty { null }, it[2].toLongOrNull() ?: 0) } ?: return null
                GoogleDriveServer(tokens, GoogleDriveAuth(client[0], client[1]), c.extra.ifEmpty { null }) { t -> platform.secrets.put("server.GoogleDrive", "${t.accessToken}\n${t.refreshToken.orEmpty()}\n${t.expiresAtEpochMs}") }
            }
            SongOrigin.Local, SongOrigin.Addon -> null
        }
    }

    /** Saves a connection, tests it, and returns whether the server answered. [secret] is a password, a Plex token, or Drive's token triple. */
    suspend fun connectServer(config: ServerConfig, secret: String): Boolean {
        platform.secrets.put("server.${config.kind.name}", secret)
        val server = buildServer(config) ?: return false
        val ok = server.ping()
        if (!ok) return false
        serverMap[config.kind] = server
        _serverConfigs.update { list -> list.filter { it.kind != config.kind } + config }
        db.putKv("servers", json.encodeToString(ListSerializer(ServerConfig.serializer()), _serverConfigs.value))
        refreshServerStatus()
        return true
    }

    suspend fun disconnectServer(kind: SongOrigin) {
        serverMap.remove(kind); platform.secrets.put("server.${kind.name}", null)
        _serverConfigs.update { list -> list.filter { it.kind != kind } }
        db.putKv("servers", json.encodeToString(ListSerializer(ServerConfig.serializer()), _serverConfigs.value))
        songStore.replaceAll(kind, emptyList()); reloadLibrary(); refreshServerStatus()
    }

    /** Pulls every song from a connected server into the library next to the local ones. */
    suspend fun syncServer(kind: SongOrigin) {
        val server = serverMap[kind] ?: return
        setStatus(kind) { it.copy(syncing = true, error = null) }
        try {
            val remote = server.allSongs { n -> setStatus(kind) { s -> s.copy(songs = n) } }
            songStore.replaceAll(kind, remote)
            reloadLibrary()
        } catch (e: Exception) {
            setStatus(kind) { it.copy(error = e.message) }
        } finally { setStatus(kind) { it.copy(syncing = false) } }
        refreshServerStatus()
    }

    private fun setStatus(kind: SongOrigin, change: (SourceStatus) -> SourceStatus) =
        _serverStatus.update { m -> m + (kind to change(m[kind] ?: SourceStatus(kind, serverMap.containsKey(kind), 0))) }

    private suspend fun refreshServerStatus() {
        val counts = library.songs.value.groupingBy { it.origin }.eachCount()
        _serverStatus.update { old -> SongOrigin.entries.filter { it != SongOrigin.Local }.associateWith { k -> SourceStatus(k, serverMap.containsKey(k), counts[k] ?: old[k]?.songs ?: 0, old[k]?.syncing == true, old[k]?.error) } }
    }

    // --- debrid ----------------------------------------------------------------------------------------------------------------

    fun setDebridKeys(torboxKey: String?, realDebridToken: String?) {
        platform.secrets.put("torbox.key", torboxKey?.trim()?.ifEmpty { null }); platform.secrets.put("realdebrid.token", realDebridToken?.trim()?.ifEmpty { null })
        torbox.refresh(); realDebrid.refresh()
    }

    val hasTorbox: Boolean get() = !platform.secrets.get("torbox.key").isNullOrBlank()
    val hasRealDebrid: Boolean get() = !platform.secrets.get("realdebrid.token").isNullOrBlank()

    fun shutdown() { accountSync.stopSync(); player.release(); db.close() }
}
