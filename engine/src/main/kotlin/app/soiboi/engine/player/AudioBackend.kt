package app.soiboi.engine.player

import kotlinx.coroutines.flow.Flow

/** The 10 equalizer bands the app offers, in Hz. */
val EqFrequencies = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

/** What an audio engine reports. The shell supplies one: an mpv process on Linux, Media3's ExoPlayer on Android. */
interface AudioBackend {
    /** Emitted whenever the engine's playing, position or duration state changes, about once a second while playing. */
    val events: Flow<BackendEvent>

    suspend fun open(source: String, headers: Map<String, String> = emptyMap(), startMs: Long = 0, play: Boolean)
    fun play()
    fun pause()
    fun stop()
    suspend fun seek(positionMs: Long)

    /** 0..1 on a linear scale; the controller applies the perceptual curve. */
    fun setVolume(volume: Double)

    /** Gains in dB for [EqFrequencies]; all zero means no filter. */
    fun setEqualizer(gainsDb: List<Double>)

    val positionMs: Long
    val durationMs: Long
    fun release() {}
}

sealed interface BackendEvent {
    data class Position(val positionMs: Long, val durationMs: Long) : BackendEvent
    data class Playing(val playing: Boolean) : BackendEvent
    /** Playback reached the end of the source. */
    data object Completed : BackendEvent
    data class Error(val message: String) : BackendEvent
}
