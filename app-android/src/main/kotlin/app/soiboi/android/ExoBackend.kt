package app.soiboi.android

import android.content.Context
import android.content.Intent
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.soiboi.engine.player.AudioBackend
import app.soiboi.engine.player.BackendEvent
import app.soiboi.engine.player.RemoteControls
import app.soiboi.engine.player.TrackMeta
import kotlinx.coroutines.delay
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

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { _events.tryEmit(BackendEvent.Playing(isPlaying)) }
        override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) _events.tryEmit(BackendEvent.Completed) }
        override fun onPlayerError(error: PlaybackException) { _events.tryEmit(BackendEvent.Error(error.message ?: "playback error")) }
    }

    private val ticker = object : Runnable {
        override fun run() {
            PlaybackHolder.player?.let { p -> positionMs = p.currentPosition; durationMs = p.duration.takeIf { it > 0 } ?: durationMs; _events.tryEmit(BackendEvent.Position(positionMs, durationMs)) }
            main.postDelayed(this, 500)
        }
    }

    private fun onMain(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }

    /** Waits for the service to have built its player (it starts moments after launch). */
    private suspend fun player(): ExoPlayer? {
        PlaybackHolder.player?.let { return it }
        context.startService(Intent(context, PlaybackService::class.java))
        repeat(50) { PlaybackHolder.player?.let { return it }; delay(100) }
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
        val p = player() ?: return
        PlaybackHolder.headers = headers
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

    override fun play() = onMain { PlaybackHolder.player?.play() }
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
