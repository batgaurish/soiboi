package app.soiboi.desktop

import app.soiboi.engine.Engine
import app.soiboi.engine.core.EngineLog
import app.soiboi.engine.player.PlayMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.freedesktop.dbus.DBusPath

@DBusInterfaceName("org.mpris.MediaPlayer2")
interface MprisRoot : DBusInterface { fun Raise(); fun Quit() }

@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
interface MprisPlayer : DBusInterface {
    fun Next(); fun Previous(); fun Pause(); fun PlayPause(); fun Stop(); fun Play()
    fun Seek(offset: Long); fun SetPosition(trackId: DBusPath, position: Long); fun OpenUri(uri: String)
}

private const val PATH = "/org/mpris/MediaPlayer2"
private const val ROOT = "org.mpris.MediaPlayer2"
private const val PLAYER = "org.mpris.MediaPlayer2.Player"

/**
 * The player on the Linux desktop's media bus. Media keys, the lock screen, KDE and GNOME media widgets and `playerctl`
 * all speak MPRIS, so exporting it is what makes Soiboi behave like a native player. Every command goes through the same
 * [app.soiboi.engine.player.RemoteControls] as the on-screen buttons. Without a session bus it simply does not start.
 */
class Mpris(private val engine: Engine, private val scope: CoroutineScope) : MprisRoot, MprisPlayer, Properties {
    private var connection: DBusConnection? = null
    @Volatile private var snapshot: Map<String, Variant<*>> = emptyMap()
    @Volatile private var rootProps: Map<String, Variant<*>> = emptyMap()

    override fun getObjectPath() = PATH
    override fun isRemote() = false

    fun start() {
        try {
            val c = DBusConnectionBuilder.forSessionBus().withShared(false).build()
            c.requestBusName("org.mpris.MediaPlayer2.soiboi")
            c.exportObject(PATH, this)
            connection = c
        } catch (e: Throwable) {
            EngineLog.output("mpris unavailable: $e"); return
        }
        rootProps = mapOf(
            "CanQuit" to Variant(false), "CanRaise" to Variant(false), "HasTrackList" to Variant(false), "Identity" to Variant("Soiboi"),
            "DesktopEntry" to Variant("soiboi"), "SupportedUriSchemes" to Variant(arrayListOf<String>(), "as"), "SupportedMimeTypes" to Variant(arrayListOf<String>(), "as"),
        )
        scope.launch {
            combine(engine.player.queue, engine.player.state, engine.player.mode, engine.player.volume) { q, s, m, v -> Quad(q, s, m, v) }.collect { (q, s, m, v) ->
                val song = q.current
                val meta = LinkedHashMap<String, Variant<*>>()
                if (song != null) {
                    meta["mpris:trackid"] = Variant(DBusPath("/org/soiboi/track/${(song.id.hashCode().toLong() and 0xffffffffL)}"))
                    song.durationMs?.let { meta["mpris:length"] = Variant(it * 1000) }
                    meta["xesam:title"] = Variant(song.displayTitle)
                    meta["xesam:artist"] = Variant(arrayListOf(song.displayArtist), "as")
                    meta["xesam:album"] = Variant(song.displayAlbum)
                    runCatching { engine.artwork.coverFor(song) }.getOrNull()?.let { meta["mpris:artUrl"] = Variant("file://" + it.path) }
                }
                val status = when { song == null -> "Stopped"; s.playing -> "Playing"; else -> "Paused" }
                val next = mapOf(
                    "PlaybackStatus" to Variant(status), "Metadata" to Variant(meta, "a{sv}"), "Volume" to Variant(v), "Shuffle" to Variant(m == PlayMode.Shuffle),
                    "LoopStatus" to Variant(if (m == PlayMode.Repeat) "Track" else "None"), "Rate" to Variant(1.0), "MinimumRate" to Variant(1.0), "MaximumRate" to Variant(1.0),
                    "Position" to Variant(s.positionMs * 1000), "CanGoNext" to Variant(song != null), "CanGoPrevious" to Variant(song != null), "CanPlay" to Variant(song != null),
                    "CanPause" to Variant(song != null), "CanSeek" to Variant(song != null), "CanControl" to Variant(true),
                )
                val changed = next.filter { (k, v) -> k != "Position" && snapshot[k]?.value != v.value }
                snapshot = next
                if (changed.isNotEmpty()) runCatching { connection?.sendMessage(Properties.PropertiesChanged(PATH, PLAYER, changed, emptyList())) }
            }
        }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private val remote get() = engine.player.remote

    override fun Raise() {}
    override fun Quit() {}
    override fun Next() = remote.onNext()
    override fun Previous() = remote.onPrevious()
    override fun Pause() = remote.onPause()
    override fun PlayPause() = remote.onToggle()
    override fun Stop() = remote.onStop()
    override fun Play() = remote.onPlay()
    override fun Seek(offset: Long) = remote.onSeekTo((engine.player.state.value.positionMs + offset / 1000).coerceAtLeast(0))
    override fun SetPosition(trackId: DBusPath, position: Long) = remote.onSeekTo(position / 1000)
    override fun OpenUri(uri: String) {}

    @Suppress("UNCHECKED_CAST")
    override fun <A> Get(iface: String, prop: String): A = (propsOf(iface)[prop]?.value ?: error("unknown property $prop")) as A
    override fun <A> Set(iface: String, prop: String, value: A) {
        if (iface == PLAYER && prop == "Volume") engine.player.setVolume((value as? Double) ?: return)
    }
    override fun GetAll(iface: String): Map<String, Variant<*>> = propsOf(iface)

    private fun propsOf(iface: String): Map<String, Variant<*>> = when (iface) { ROOT -> rootProps; PLAYER -> snapshot.ifEmpty { mapOf("PlaybackStatus" to Variant("Stopped"), "CanControl" to Variant(true)) }.let { it + ("Position" to Variant(engine.player.state.value.positionMs * 1000)) }; else -> emptyMap() }

    fun stop() { runCatching { connection?.disconnect() } }
}
