package app.soiboi.android

import android.content.Context
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.soiboi.engine.player.AudioBackend
import app.soiboi.engine.player.BackendEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.log10

/**
 * Android audio on Media3's ExoPlayer, which plays ALAC, FLAC, AAC and Opus natively. The player lives on the main
 * thread; the engine calls in from anywhere, so every call hops to the main looper. The equalizer maps the app's ten bands
 * onto the device's own band set, whatever its size.
 */
class ExoBackend(private val context: Context) : AudioBackend {
    private val main = Handler(Looper.getMainLooper())
    private val _events = MutableSharedFlow<BackendEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<BackendEvent> = _events.asSharedFlow()
    @Volatile override var positionMs: Long = 0; private set
    @Volatile override var durationMs: Long = 0; private set
    private var equalizer: Equalizer? = null
    private lateinit var player: ExoPlayer
    private var lastHeaders: Map<String, String> = emptyMap()

    private val ticker = object : Runnable {
        override fun run() {
            if (::player.isInitialized) {
                positionMs = player.currentPosition; durationMs = player.duration.takeIf { it > 0 } ?: durationMs
                _events.tryEmit(BackendEvent.Position(positionMs, durationMs))
            }
            main.postDelayed(this, 500)
        }
    }

    private fun build(headers: Map<String, String>): ExoPlayer {
        val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers).setAllowCrossProtocolRedirects(true)
        return ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build().also { p ->
            p.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            p.setHandleAudioBecomingNoisy(true)
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { _events.tryEmit(BackendEvent.Playing(isPlaying)) }
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) _events.tryEmit(BackendEvent.Completed) }
                override fun onPlayerError(error: PlaybackException) { _events.tryEmit(BackendEvent.Error(error.message ?: "playback error")) }
            })
            runCatching { equalizer?.release(); equalizer = Equalizer(0, p.audioSessionId).apply { enabled = true } }
        }
    }

    private fun onMain(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }

    override suspend fun open(source: String, headers: Map<String, String>, startMs: Long, play: Boolean) {
        onMain {
            // Request headers belong to the player's data source, so a different set means a new player.
            if (!::player.isInitialized || headers != lastHeaders) { if (::player.isInitialized) player.release(); player = build(headers); lastHeaders = headers; main.removeCallbacks(ticker); main.post(ticker) }
            player.setMediaItem(MediaItem.fromUri(if (source.startsWith("/")) android.net.Uri.fromFile(java.io.File(source)) else android.net.Uri.parse(source)), startMs)
            player.prepare(); player.playWhenReady = play
        }
    }

    override fun play() = onMain { if (::player.isInitialized) player.play() }
    override fun pause() = onMain { if (::player.isInitialized) player.pause() }
    override fun stop() = onMain { if (::player.isInitialized) { player.stop(); positionMs = 0 } }
    override suspend fun seek(positionMs: Long) { onMain { if (::player.isInitialized) player.seekTo(positionMs) }; this.positionMs = positionMs }
    override fun setVolume(volume: Double) = onMain { if (::player.isInitialized) player.volume = (log10(volume.coerceIn(0.0, 1.0) * 9 + 1)).toFloat() }

    override fun setEqualizer(gainsDb: List<Double>) = onMain {
        val eq = equalizer ?: return@onMain
        val bands = eq.numberOfBands.toInt(); val (lo, hi) = eq.bandLevelRange.let { it[0] to it[1] }
        // Map each device band to the nearest of the app's ten, by centre frequency.
        val freqs = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        for (b in 0 until bands) {
            val centre = eq.getCenterFreq(b.toShort()) / 1000
            val i = freqs.indices.minByOrNull { kotlin.math.abs(freqs[it] - centre) } ?: 0
            eq.setBandLevel(b.toShort(), (gainsDb[i] * 100).toInt().coerceIn(lo.toInt(), hi.toInt()).toShort())
        }
    }

    override fun release() = onMain { main.removeCallbacks(ticker); if (::player.isInitialized) player.release(); equalizer?.release() }
}
