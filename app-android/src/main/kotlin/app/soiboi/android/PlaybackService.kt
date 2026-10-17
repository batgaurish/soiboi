package app.soiboi.android

import android.content.Intent
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.soiboi.engine.core.EngineLog
import app.soiboi.engine.player.RemoteControls

/** The pieces the app and the service share: the player the service owns, and where outside commands go. */
object PlaybackHolder {
    @Volatile var player: ExoPlayer? = null
    @Volatile var controls: RemoteControls? = null
    /** Request headers for the song now loading (WebDAV and Drive need auth that cannot ride on the URL). */
    @Volatile var headers: Map<String, String> = emptyMap()
}

/**
 * Hosts the ExoPlayer inside a media session, so playback survives the app leaving the screen and the system shows a
 * notification with lock-screen controls and honours headset buttons. The queue itself lives in the engine, so
 * next and previous are forwarded to it rather than handled by a one-item ExoPlayer playlist.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        EngineLog.event("SERVICE", "created")
        // The default factory reads files and content URIs as well as http; the resolver adds the per-song auth headers.
        val upstream = DefaultDataSource.Factory(this, DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true))
        val http = ResolvingDataSource.Factory(upstream) { spec -> spec.withAdditionalHeaders(PlaybackHolder.headers) }
        val exo = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build().apply {
            setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
        }
        PlaybackHolder.player = exo
        val forwarding = object : ForwardingPlayer(exo) {
            override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
                .addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build()
            override fun isCommandAvailable(command: Int) = command in setOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) || super.isCommandAvailable(command)
            override fun hasNextMediaItem() = true
            override fun hasPreviousMediaItem() = true
            override fun seekToNext() { PlaybackHolder.controls?.onNext?.invoke() }
            override fun seekToNextMediaItem() { PlaybackHolder.controls?.onNext?.invoke() }
            override fun seekToPrevious() { PlaybackHolder.controls?.onPrevious?.invoke() }
            override fun seekToPreviousMediaItem() { PlaybackHolder.controls?.onPrevious?.invoke() }
        }
        session = MediaSession.Builder(this, forwarding).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = PlaybackHolder.player
        EngineLog.event("SERVICE", "task_removed", "playWhenReady=${p?.playWhenReady} isPlaying=${p?.isPlaying} items=${p?.mediaItemCount} state=${p?.playbackState}")
        // Keep playing when the app is swiped away, as a music player should; stop only when nothing is playing.
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        EngineLog.event("SERVICE", "destroyed", "playing=${PlaybackHolder.player?.isPlaying} position=${PlaybackHolder.player?.currentPosition ?: -1}")
        session?.run { player.release(); release() }
        session = null; PlaybackHolder.player = null
        super.onDestroy()
    }
}
