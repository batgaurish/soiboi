package app.soiboi.engine.spotify

import app.soiboi.engine.core.EngineLog
import app.soiboi.engine.player.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Plays Spotify tracks by driving a Spotify Connect device the person owns (the Spotify app on a phone or computer, a speaker,
 * a librespot or spotifyd process). Soiboi sends commands and mirrors the device's position; the audio comes from that device.
 */
class SpotifyBackend(private val api: SpotifyApi, private val scope: CoroutineScope, private val deviceId: () -> String?) : AudioBackend {
    private val _events = MutableSharedFlow<BackendEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<BackendEvent> = _events.asSharedFlow()
    @Volatile override var positionMs: Long = 0; private set
    @Volatile override var durationMs: Long = 0; private set
    private var poller: Job? = null
    @Volatile private var currentUri: String? = null
    @Volatile private var wasPlaying = false
    @Volatile private var openedAt = 0L

    private fun startPolling() {
        poller?.cancel()
        poller = scope.launch {
            while (isActive) {
                delay(1000)
                try {
                    val p = api.playback()
                    // Give the device a moment to switch tracks before a stale answer is read as the end.
                    if (System.currentTimeMillis() - openedAt < 4000) continue
                    if (p == null || p.uri != currentUri) { if (wasPlaying) { wasPlaying = false; _events.tryEmit(BackendEvent.Completed) }; poller?.cancel(); continue }
                    positionMs = p.positionMs; durationMs = p.durationMs
                    if (p.playing != wasPlaying) { wasPlaying = p.playing; _events.tryEmit(BackendEvent.Playing(p.playing)) }
                    _events.tryEmit(BackendEvent.Position(positionMs, durationMs))
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) { EngineLog.output("spotify poll: ${e.message}") }
            }
        }
    }

    override suspend fun open(source: String, headers: Map<String, String>, startMs: Long, play: Boolean, meta: TrackMeta?) {
        currentUri = source; positionMs = startMs; durationMs = meta?.durationMs ?: 0; openedAt = System.currentTimeMillis()
        try {
            api.play(source, deviceId(), startMs)
            if (!play) api.pause()
            wasPlaying = play
            _events.tryEmit(BackendEvent.Playing(play))
            startPolling()
        } catch (e: Exception) { _events.tryEmit(BackendEvent.Error(e.message ?: "Spotify did not play it")); throw e }
    }

    private fun fire(block: suspend () -> Unit) { scope.launch { try { block() } catch (e: Exception) { _events.tryEmit(BackendEvent.Error(e.message ?: "Spotify did not answer")) } } }

    override fun play() = fire { api.resume(deviceId()); wasPlaying = true; _events.tryEmit(BackendEvent.Playing(true)) }
    override fun pause() = fire { api.pause(); wasPlaying = false; _events.tryEmit(BackendEvent.Playing(false)) }
    override fun stop() { poller?.cancel(); fire { api.pause() }; wasPlaying = false; currentUri = null }
    override suspend fun seek(positionMs: Long) { this.positionMs = positionMs; api.seek(positionMs) }
    override fun setVolume(volume: Double) = fire { api.volume((volume * 100).toInt()) }
    override fun setEqualizer(gainsDb: List<Double>) {} // The device owns its own sound.
    override fun release() { poller?.cancel() }
}

/** Sends `spotify:` sources to the Spotify backend and everything else to the local one, and passes on only the active one's events. */
class RoutingBackend(private val local: AudioBackend, private val spotify: AudioBackend) : AudioBackend {
    @Volatile private var active: AudioBackend = local
    override val events: Flow<BackendEvent> = merge(local.events.filter { active === local }, spotify.events.filter { active === spotify })
    override val positionMs get() = active.positionMs
    override val durationMs get() = active.durationMs

    override suspend fun open(source: String, headers: Map<String, String>, startMs: Long, play: Boolean, meta: TrackMeta?) {
        val next = if (source.startsWith("spotify:")) spotify else local
        // Moving between the two must silence the one being left.
        if (next !== active) { active.stop() ; active = next }
        next.open(source, headers, startMs, play, meta)
    }
    override fun play() = active.play()
    override fun pause() = active.pause()
    override fun stop() = active.stop()
    override suspend fun seek(positionMs: Long) = active.seek(positionMs)
    override fun setVolume(volume: Double) { local.setVolume(volume); spotify.setVolume(volume) }
    override fun setEqualizer(gainsDb: List<Double>) { local.setEqualizer(gainsDb) }
    override fun attachRemote(controls: RemoteControls) { local.attachRemote(controls) }
    override fun release() { local.release(); spotify.release() }
}
