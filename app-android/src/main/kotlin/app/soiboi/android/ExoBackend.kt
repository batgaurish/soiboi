package app.soiboi.android

import android.content.Context
import android.content.Intent
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.soiboi.engine.player.AudioBackend
import app.soiboi.engine.core.EngineLog
import app.soiboi.engine.player.BackendEvent
import app.soiboi.engine.player.RemoteControls
import app.soiboi.engine.player.TrackMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import kotlin.math.log10

/**
 * Android audio on Media3's ExoPlayer, which plays ALAC, FLAC, AAC and Opus natively. The player lives in
 * [PlaybackService] so it keeps playing in the background and shows system media controls; this class drives it from the
 * engine. The player belongs to the main thread, so every call hops to the main looper. The equalizer maps the app's ten
 * bands onto the device's own band set, whatever its size.
 */
class ExoBackend(private val context: Context) : AudioBackend {
    private val main = Handler(Looper.getMainLooper())
    private val _events = MutableSharedFlow<BackendEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<BackendEvent> = _events.asSharedFlow()
    @Volatile override var positionMs: Long = 0; private set
    @Volatile override var durationMs: Long = 0; private set
    private var equalizer: Equalizer? = null
    private var attachedTo: ExoPlayer? = null
    private var pendingVolume = 0.3
    private var pendingEq: List<Double>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** The last song opened, so a play after the system destroyed the service can rebuild the player and resume it. */
    private var lastOpen: Triple<String, Map<String, String>, TrackMeta?>? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            EngineLog.event("EXO", "is_playing_changed", "playing=$isPlaying position=${PlaybackHolder.player?.currentPosition ?: -1} state=${PlaybackHolder.player?.playbackState ?: -1}")
            _events.tryEmit(BackendEvent.Playing(isPlaying))
        }
        override fun onPlaybackStateChanged(state: Int) {
            EngineLog.event("EXO", "playback_state", "state=${stateName(state)} playWhenReady=${PlaybackHolder.player?.playWhenReady} position=${PlaybackHolder.player?.currentPosition ?: -1}")
            if (state == Player.STATE_ENDED) _events.tryEmit(BackendEvent.Completed)
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            EngineLog.event("EXO", "play_when_ready", "value=$playWhenReady reason=$reason")
        }
        override fun onMetadata(metadata: androidx.media3.common.Metadata) {
            for (i in 0 until metadata.length()) (metadata[i] as? androidx.media3.extractor.metadata.icy.IcyInfo)?.title?.let { _events.tryEmit(BackendEvent.StreamTitle(it.trim().takeIf { t -> t.isNotEmpty() })) }
        }
        override fun onPlayerError(error: PlaybackException) {
            EngineLog.failure("EXO", "player_error", error, "code=${error.errorCodeName} cause=${error.cause?.javaClass?.name}")
            _events.tryEmit(BackendEvent.Error(error.message ?: "playback error"))
        }
    }

    private fun stateName(state: Int) = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($state)"
    }

    private val ticker = object : Runnable {
        override fun run() {
            PlaybackHolder.player?.let { p -> positionMs = p.currentPosition; durationMs = p.duration.takeIf { it > 0 } ?: durationMs; _events.tryEmit(BackendEvent.Position(positionMs, durationMs)) }
            main.postDelayed(this, 500)
        }
    }

    private fun onMain(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }

    /** Waits for the service to have built its player (it starts moments after launch). */
    private suspend fun player(shouldPlay: Boolean): ExoPlayer? {
        PlaybackHolder.player?.let { return it }
        EngineLog.event("EXO", "start_playback_service", "foreground=$shouldPlay")
        if (shouldPlay) ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
        else context.startService(Intent(context, PlaybackService::class.java))
        repeat(50) { PlaybackHolder.player?.let { return it }; delay(100) }
        EngineLog.event("EXO", "playback_service_timeout", "foreground=$shouldPlay")
        return null
    }

    private fun attach(p: ExoPlayer) {
        if (attachedTo === p) return
        attachedTo?.removeListener(listener)
        p.addListener(listener); attachedTo = p
        main.removeCallbacks(ticker); main.post(ticker)
        runCatching { equalizer?.release(); equalizer = Equalizer(0, p.audioSessionId).apply { enabled = true } }
        p.volume = (log10(pendingVolume * 9 + 1)).toFloat()
        pendingEq?.let { setEqualizer(it) }
    }

    override suspend fun open(source: String, headers: Map<String, String>, startMs: Long, play: Boolean, meta: TrackMeta?) {
        lastOpen = Triple(source, headers, meta)
        val p = player(play) ?: return
        PlaybackHolder.headers = headers
        EngineLog.event("EXO", "open_begin", "source=${if (source.startsWith("http")) "http" else "local"} startMs=$startMs play=$play headers=${headers.keys.sorted().joinToString(",")}")
        onMain {
            attach(p)
            val uri = if (source.startsWith("/")) Uri.fromFile(File(source)) else Uri.parse(source)
            val item = MediaItem.Builder().setUri(uri).apply {
                meta?.let { m ->
                    setMediaMetadata(MediaMetadata.Builder().setTitle(m.title).setArtist(m.artist).setAlbumTitle(m.album)
                        .apply { m.artworkPath?.let { setArtworkUri(Uri.fromFile(File(it))) } }.build())
                }
            }.build()
            p.setMediaItem(item, startMs); p.prepare(); p.playWhenReady = play
        }
    }

    override fun play() = onMain {
        val p = PlaybackHolder.player
        if (p != null) { p.play(); return@onMain }
        val (source, headers, meta) = lastOpen ?: return@onMain
        EngineLog.event("EXO", "reopen_after_service_loss", "positionMs=$positionMs")
        scope.launch { open(source, headers, positionMs, true, meta) }
    }
    override fun pause() = onMain { PlaybackHolder.player?.pause() }
    override fun stop() = onMain { PlaybackHolder.player?.let { it.stop(); positionMs = 0 } }
    override suspend fun seek(positionMs: Long) { onMain { PlaybackHolder.player?.seekTo(positionMs) }; this.positionMs = positionMs }

    override fun setVolume(volume: Double) { pendingVolume = volume.coerceIn(0.0, 1.0); onMain { PlaybackHolder.player?.volume = (log10(pendingVolume * 9 + 1)).toFloat() } }

    override fun setEqualizer(gainsDb: List<Double>) { pendingEq = gainsDb; onMain {
        val eq = equalizer ?: return@onMain
        val bands = eq.numberOfBands.toInt(); val (lo, hi) = eq.bandLevelRange.let { it[0] to it[1] }
        // Map each device band to the nearest of the app's ten, by centre frequency.
        val freqs = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        for (b in 0 until bands) {
            val centre = eq.getCenterFreq(b.toShort()) / 1000
            val i = freqs.indices.minByOrNull { kotlin.math.abs(freqs[it] - centre) } ?: 0
            eq.setBandLevel(b.toShort(), (gainsDb[i] * 100).toInt().coerceIn(lo.toInt(), hi.toInt()).toShort())
        }
    } }

    override fun attachRemote(controls: RemoteControls) { PlaybackHolder.controls = controls }

    override fun release() = onMain { main.removeCallbacks(ticker); attachedTo?.removeListener(listener); equalizer?.release(); context.stopService(Intent(context, PlaybackService::class.java)) }
}
