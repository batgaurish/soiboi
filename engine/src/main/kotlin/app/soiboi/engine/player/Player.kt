package app.soiboi.engine.player

import app.soiboi.engine.core.EngineLog
import app.soiboi.engine.library.Database
import app.soiboi.engine.library.Library
import app.soiboi.engine.library.Song
import app.soiboi.engine.library.SongOrigin
import app.soiboi.engine.library.SongStore
import app.soiboi.engine.servers.MusicServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/** The queue, shown to the UI. [currentIndex] is -1 when nothing is loaded. */
data class QueueState(val songs: List<Song> = emptyList(), val currentIndex: Int = -1) {
    val current: Song? get() = songs.getOrNull(currentIndex)
    val upNext: List<Song> get() = if (currentIndex < 0) emptyList() else songs.drop(currentIndex + 1)
}

data class PlaybackState(val playing: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0, val loading: Boolean = false, val error: String? = null, val streamTitle: String? = null)

@Serializable
private data class Saved(
    val queue: List<String> = emptyList(), val unshuffled: List<String> = emptyList(), val index: Int = -1,
    val mode: PlayMode = PlayMode.Order, val modeBeforeRepeat: PlayMode = PlayMode.Order, val volume: Double = 0.3, val positionMs: Long = 0,
    val eq: List<Double> = List(EqFrequencies.size) { 0.0 },
)

/**
 * The playback controller: one queue, three modes (in order, shuffle, repeat one), history that counts a
 * play only after more than half the song was heard, and persisted state so the app resumes where it left
 * off. The audio itself is a swappable [AudioBackend]. Connector songs stream from their server (or a
 * cached copy); local files play from disk.
 */
class Player(
    private val scope: CoroutineScope,
    private val backend: AudioBackend,
    private val library: Library,
    private val songs: SongStore,
    private val db: Database,
    private val servers: () -> Map<SongOrigin, MusicServer>,
    /** Where cached copies of remote songs live, keyed by song id; null disables caching. */
    private val cacheDir: File?,
    /** Debrid or other late-bound sources: a chance to turn a song into a direct URL before the origin server is asked. */
    private val resolveOverride: suspend (Song) -> Pair<String, Map<String, String>>? = { null },
    private val onPlayed: suspend (Song, Int) -> Unit = { _, _ -> },
    private val onSongChanged: suspend (Song) -> Unit = {},
    /** Title, artist and cover for the system's media controls. */
    private val metaFor: suspend (Song) -> TrackMeta = { TrackMeta(it.displayTitle, it.displayArtist, it.displayAlbum, null, it.durationMs) },
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _queue = MutableStateFlow(QueueState())
    val queue: StateFlow<QueueState> = _queue.asStateFlow()
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private val _mode = MutableStateFlow(PlayMode.Order)
    val mode: StateFlow<PlayMode> = _mode.asStateFlow()
    private val _volume = MutableStateFlow(0.3)
    val volume: StateFlow<Double> = _volume.asStateFlow()
    private val _eq = MutableStateFlow(List(EqFrequencies.size) { 0.0 })
    val equalizer: StateFlow<List<Double>> = _eq.asStateFlow()
    /** The sleep timer's remaining seconds, or null when off. */
    private val _sleepRemaining = MutableStateFlow<Int?>(null)
    val sleepRemaining: StateFlow<Int?> = _sleepRemaining.asStateFlow()
    private val _pauseAfterTrack = MutableStateFlow(false)
    val pauseAfterTrack: StateFlow<Boolean> = _pauseAfterTrack.asStateFlow()

    private var unshuffled: List<String> = emptyList()
    private var modeBeforeRepeat = PlayMode.Order
    private var lastSyncAt: Instant? = null
    private var played = java.time.Duration.ZERO
    private var sleepJob: Job? = null
    private var saveJob: Job? = null
    private var wantPlaying = false

    /** Commands from outside the app (notification buttons, media keys) go through the same paths as the on-screen controls. */
    val remote = RemoteControls(
        onNext = { scope.launch { next() } }, onPrevious = { scope.launch { previous() } }, onPlay = { play() }, onPause = { pause() },
        onToggle = { togglePlay() }, onStop = { scope.launch { stop() } }, onSeekTo = { ms -> scope.launch { seek(ms) } },
    )

    init {
        backend.attachRemote(remote)
        scope.launch {
            backend.events.collect { e ->
                when (e) {
                    is BackendEvent.Position -> _state.update { it.copy(positionMs = e.positionMs, durationMs = e.durationMs) }
                    is BackendEvent.Playing -> {
                        EngineLog.event("PLAYER", "backend_playing", "playing=${e.playing} song=${_queue.value.current?.id ?: "none"} position=${_state.value.positionMs}")
                        markPlaying(e.playing); _state.update { it.copy(playing = e.playing) }
                    }
                    is BackendEvent.StreamTitle -> _state.update { it.copy(streamTitle = e.title) }
                    BackendEvent.Completed -> { EngineLog.event("PLAYER", "backend_completed", "song=${_queue.value.current?.id ?: "none"}"); onCompleted() }
                    is BackendEvent.Error -> { EngineLog.output("player error: ${e.message}"); EngineLog.event("PLAYER", "backend_error", "song=${_queue.value.current?.id ?: "none"} message=${e.message}"); _state.update { it.copy(error = e.message, loading = false) } }
                }
            }
        }
    }

    private fun markPlaying(playing: Boolean) {
        if (playing) lastSyncAt = Instant.now()
        else lastSyncAt?.let { played += java.time.Duration.between(it, Instant.now()); lastSyncAt = null }
    }

    private suspend fun onCompleted() {
        if (_pauseAfterTrack.value) { _pauseAfterTrack.value = false; pause(); return }
        if (_mode.value == PlayMode.Repeat) load(0) else next()
    }

    // --- restore & persist -------------------------------------------------------------------------------------------------

    suspend fun restore() {
        val saved = db.getKv("player:state")?.let { runCatching { json.decodeFromString<Saved>(it) }.getOrNull() } ?: return
        val valid = library.songs.value.map { it.id }.toSet()
        val ids = PlayQueueLogic.remap(saved.queue, valid)
        _queue.value = QueueState(ids.mapNotNull { library.song(it) }, if (ids.isEmpty()) -1 else saved.index.coerceIn(0, ids.size - 1))
        unshuffled = PlayQueueLogic.remap(saved.unshuffled, valid)
        _mode.value = saved.mode; modeBeforeRepeat = saved.modeBeforeRepeat
        setVolume(saved.volume); setEqualizer(saved.eq)
        if (_queue.value.current != null) loadPaused(saved.positionMs)
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(800)
            val q = _queue.value
            runCatching {
                db.putKv("player:state", json.encodeToString(Saved.serializer(), Saved(q.songs.map { it.id }, unshuffled, q.currentIndex, _mode.value, modeBeforeRepeat, _volume.value, _state.value.positionMs, _eq.value)))
            }
        }
    }

    // --- queue ------------------------------------------------------------------------------------------------------------

    /** Replaces the queue with [source] and starts it. With [startAt] the mode is left alone; without it, [playMode] picks the start (a random song when shuffling). */
    suspend fun setQueue(source: List<Song>, playMode: PlayMode = _mode.value, startAt: Int? = null) {
        if (source.isEmpty()) return
        val index = startAt ?: PlayQueueLogic.startIndex(playMode, source.size)
        if (startAt == null) _mode.value = playMode
        _queue.value = QueueState(source.toList(), index)
        unshuffled = emptyList()
        if (PlayQueueLogic.isShuffled(_mode.value, modeBeforeRepeat)) shuffleQueue()
        wantPlaying = true
        load()
        scheduleSave()
    }

    private fun shuffleQueue() {
        val q = _queue.value
        if (q.songs.isEmpty() || q.currentIndex < 0) return
        unshuffled = q.songs.map { it.id }
        _queue.value = QueueState(PlayQueueLogic.shuffledAround(q.songs, q.currentIndex), 0)
    }

    suspend fun playSingle(song: Song) {
        if (insertNext(song)) next()
        play()
    }

    fun insertNext(song: Song): Boolean {
        val q = _queue.value; val list = q.songs.toMutableList()
        val r = PlayQueueLogic.insertNext(list, q.currentIndex, song) ?: return false
        _queue.value = QueueState(list, r.currentIndex)
        if (r.wasNewlyInserted && PlayQueueLogic.isShuffled(_mode.value, modeBeforeRepeat)) unshuffled = unshuffled + song.id
        scheduleSave(); return true
    }

    fun addLast(song: Song): Boolean {
        val q = _queue.value; val list = q.songs.toMutableList()
        val r = PlayQueueLogic.addLast(list, q.currentIndex, song) ?: return false
        _queue.value = QueueState(list, r.currentIndex)
        if (r.wasNewlyInserted && PlayQueueLogic.isShuffled(_mode.value, modeBeforeRepeat)) unshuffled = unshuffled + song.id
        scheduleSave(); return true
    }

    fun removeAt(index: Int) {
        val q = _queue.value; if (index !in q.songs.indices || index == q.currentIndex) return
        val removed = q.songs[index]
        val list = q.songs.toMutableList().also { it.removeAt(index) }
        unshuffled = unshuffled.filter { it != removed.id }
        _queue.value = QueueState(list, if (index < q.currentIndex) q.currentIndex - 1 else q.currentIndex)
        scheduleSave()
    }

    fun reverse() {
        val q = _queue.value; val cur = q.current ?: return
        val list = q.songs.reversed()
        _queue.value = QueueState(list, list.indexOf(cur)); scheduleSave()
    }

    suspend fun clear() {
        stop(); _queue.value = QueueState(); unshuffled = emptyList(); scheduleSave()
    }

    suspend fun setMode(new: PlayMode) {
        if (new == _mode.value) return
        when (new) {
            PlayMode.Order -> if (unshuffled.isNotEmpty()) {
                val byId = _queue.value.songs.associateBy { it.id }
                val restored = unshuffled.mapNotNull { byId[it] }
                val cur = _queue.value.current
                _queue.value = QueueState(restored, cur?.let { restored.indexOf(it) } ?: -1); unshuffled = emptyList()
            }
            PlayMode.Shuffle -> if (unshuffled.isEmpty()) shuffleQueue()
            PlayMode.Repeat -> {}
        }
        _mode.value = new; scheduleSave()
    }

    fun toggleRepeat() {
        if (_mode.value != PlayMode.Repeat) { modeBeforeRepeat = _mode.value; _mode.value = PlayMode.Repeat } else _mode.value = modeBeforeRepeat
        scheduleSave()
    }

    // --- transport --------------------------------------------------------------------------------------------------------

    suspend fun jumpTo(index: Int) {
        if (index !in _queue.value.songs.indices) return
        _queue.update { it.copy(currentIndex = index) }; wantPlaying = true; load(); scheduleSave()
    }

    suspend fun next() { val q = _queue.value; if (q.songs.isEmpty()) return; _queue.value = q.copy(currentIndex = PlayQueueLogic.nextIndex(q.currentIndex, q.songs.size)); load(); scheduleSave() }
    suspend fun previous() {
        val q = _queue.value; if (q.songs.isEmpty()) return
        // Like every player: a few seconds in, "previous" restarts the song instead of leaving it.
        if (_state.value.positionMs > 4000) { seek(0); return }
        _queue.value = q.copy(currentIndex = PlayQueueLogic.previousIndex(q.currentIndex, q.songs.size)); load(); scheduleSave()
    }

    fun play() { if (_queue.value.songs.isEmpty()) return; EngineLog.event("PLAYER", "command_play", "song=${_queue.value.current?.id ?: "none"}"); wantPlaying = true; backend.play() }
    fun pause() { EngineLog.event("PLAYER", "command_pause", "song=${_queue.value.current?.id ?: "none"} position=${_state.value.positionMs}"); wantPlaying = false; backend.pause() }
    fun togglePlay() { if (_state.value.playing) pause() else play() }
    suspend fun stop() { EngineLog.event("PLAYER", "command_stop", "song=${_queue.value.current?.id ?: "none"}"); wantPlaying = false; backend.stop(); _state.value = PlaybackState() }
    suspend fun seek(positionMs: Long) { EngineLog.event("PLAYER", "command_seek", "song=${_queue.value.current?.id ?: "none"} positionMs=$positionMs"); backend.seek(positionMs); _state.update { it.copy(positionMs = positionMs) } }

    fun setVolume(v: Double) { _volume.value = v.coerceIn(0.0, 1.0); backend.setVolume(_volume.value); scheduleSave() }
    fun setEqualizer(gains: List<Double>) { if (gains.size == EqFrequencies.size) { _eq.value = gains; backend.setEqualizer(gains); scheduleSave() } }

    fun setPauseAfterTrack(on: Boolean) { _pauseAfterTrack.value = on }

    /** Pauses after [minutes]; calling it again resets the timer, and null cancels. */
    fun sleepTimer(minutes: Int?) {
        sleepJob?.cancel(); _sleepRemaining.value = null
        if (minutes == null) return
        sleepJob = scope.launch {
            var left = minutes * 60
            while (left > 0) { _sleepRemaining.value = left; delay(1000); left-- }
            _sleepRemaining.value = null; pause()
        }
    }

    // --- loading ----------------------------------------------------------------------------------------------------------

    private suspend fun loadPaused(startMs: Long) { wantPlaying = false; load(startMs) }

    /** Counts the song just left as played when more than half of it was heard, as the original did. */
    private suspend fun settleHistory() {
        val prev = _lastLoaded ?: return
        lastSyncAt?.let { played += java.time.Duration.between(it, Instant.now()) }
        var seconds = (prev.durationMs ?: 0) / 1000
        if (seconds <= 0) {
            // Fix a missing duration from what the engine reported.
            seconds = backend.durationMs / 1000
            if (seconds > 0 && prev.origin == SongOrigin.Local) { songs.updateDuration(prev.id, backend.durationMs); library.replace(prev.copy(durationMs = backend.durationMs)) }
        }
        if (seconds > 0) {
            val times = played.seconds.toDouble() / seconds
            if (times > 0.5) {
                val updated = prev.copy(playCount = prev.playCount + Math.round(times).toInt().coerceAtLeast(1), lastPlayed = Instant.now())
                songs.setPlayCount(prev.id, updated.playCount, updated.lastPlayed)
                library.replace(updated)
                onPlayed(updated, Math.round(times).toInt().coerceAtLeast(1))
            }
        }
        lastSyncAt = null; played = java.time.Duration.ZERO
    }

    @Volatile private var _lastLoaded: Song? = null

    private suspend fun load(startMs: Long = 0) {
        settleHistory()
        val song = _queue.value.current ?: return
        _lastLoaded = library.song(song.id) ?: song
        EngineLog.event("PLAYER", "load_begin", "song=${song.id} origin=${song.origin} startMs=$startMs wantPlaying=$wantPlaying")
        _state.value = PlaybackState(playing = wantPlaying, loading = true)
        try {
            val (source, headers) = sourceFor(song)
            EngineLog.event("PLAYER", "source_resolved", "song=${song.id} kind=${sourceKind(source)} headers=${headers.keys.sorted().joinToString(",")}")
            backend.open(source, headers, startMs, wantPlaying, metaFor(song))
            EngineLog.event("PLAYER", "backend_open_returned", "song=${song.id} backend=${backend::class.simpleName} wantPlaying=$wantPlaying")
            if (wantPlaying) lastSyncAt = Instant.now()
            _state.update { it.copy(loading = false, playing = wantPlaying) }
            onSongChanged(song)
        } catch (e: Exception) {
            EngineLog.failure("PLAYER", "load_failed", e, "song=${song.id} origin=${song.origin}")
            // Stop what was already playing: leaving the old song audible while the
            // UI shows the failed one reads as "the wrong song played".
            runCatching { backend.stop() }
            _state.value = PlaybackState(error = e.message)
        }
    }

    private fun sourceKind(source: String): String = when {
        source.startsWith("file://") || source.startsWith("/") -> "file"
        source.startsWith("http://") || source.startsWith("https://") -> "http"
        else -> "other"
    }

    /** What to hand the engine for [song]: a cached copy, a debrid link, the origin server's stream, or the local file. */
    private suspend fun sourceFor(song: Song): Pair<String, Map<String, String>> {
        song.path?.takeIf { song.origin == SongOrigin.Local }?.let { return it to emptyMap() }
        cacheDir?.let { dir -> File(dir, cacheName(song)).takeIf { it.exists() }?.let { return it.path to emptyMap() } }
        resolveOverride(song)?.let { return it }
        val server = servers()[song.origin] ?: error("No connection to ${song.origin}")
        return server.streamUrl(song) to server.streamHeaders(song)
    }

    /** Caches a remote song in the background once it has been played enough to be worth keeping. */
    suspend fun cache(song: Song) {
        val dir = cacheDir ?: return; val server = servers()[song.origin] ?: return
        val target = File(dir, cacheName(song)); if (target.exists()) return
        delay(3000) // do not compete with the audio that is loading right now
        server.download(song, target)
    }

    private fun cacheName(song: Song) = java.security.MessageDigest.getInstance("MD5").digest("${song.origin}:${song.id}".toByteArray()).joinToString("") { "%02x".format(it) } + "." + (song.format ?: "audio")

    /** Swaps queue entries for their rescanned copies and drops songs the library no longer has. */
    fun syncWithLibrary() {
        val q = _queue.value
        // Addon songs live outside the library until saved; a rescan must not drop them from the queue.
        val list = q.songs.mapNotNull { library.song(it.id) ?: it.takeIf { s -> s.origin == SongOrigin.Addon } }
        val cur = q.current?.let { library.song(it.id) ?: it.takeIf { s -> s.origin == SongOrigin.Addon } }
        _queue.value = QueueState(list, cur?.let { list.indexOf(it) } ?: if (list.isEmpty()) -1 else 0)
        unshuffled = PlayQueueLogic.remap(unshuffled, list.map { it.id }.toSet())
    }

    fun release() { sleepJob?.cancel(); backend.release() }
}
