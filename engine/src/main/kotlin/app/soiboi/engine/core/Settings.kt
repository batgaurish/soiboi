package app.soiboi.engine.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Which design flavour the UI wears. */
enum class Flavour { Zine, LinerNotes, Spotify }

enum class ThemeMode { System, Light, Dark }

@Serializable
data class Settings(
    val flavour: Flavour = Flavour.Zine,
    val theme: ThemeMode = ThemeMode.System,
    val autoPlayOnStartup: Boolean = false,
    /** On by default: nearly every library is Artist/Album folders, and a flat scan of one finds nothing. */
    val recursiveScan: Boolean = true,
    val exitOnClose: Boolean = false,
    val language: String = "",

    val downloadCodec: String = "aac",
    val downloadFolder: String = "",
    val appleStorefront: String = "",
    val setupWizardDone: Boolean = false,
    val wvdPath: String? = null,
    val useWrapper: Boolean = false,
    val wrapperUrl: String = "",
    val showDownloadLogs: Boolean = false,
    val notificationsEnabled: Boolean = true,

    val lrclibEnabled: Boolean = true,
    val listenBrainzUser: String = "",
    val lyricsFontSizeOffset: Double = 0.0,
    val reduceMotion: Boolean = false,
    val checkForUpdates: Boolean = true,
    /** The person's own Musixmatch user token; synced lyrics are asked of Musixmatch only when it is set. */
    val musixmatchToken: String = "",
    val paxsenixLyrics: Boolean = true,
    /** Desktop: show what is playing on Discord. Needs the person's own Discord application id. */
    val discordPresence: Boolean = false,
    val discordClientId: String = "",
    /** Desktop: closing the window hides it to the tray and keeps the music playing. */
    val minimizeToTray: Boolean = false,
    /** A version the person chose not to be asked about again. */
    val skippedVersion: String = "",
) {
    val storefront: String get() = appleStorefront.ifEmpty { "us" }
}

val DownloadCodecLabels = mapOf(
    "aac" to "AAC (256-320 kbps)",
    "alac" to "ALAC (Apple Lossless)",
)

/** Settings persisted to a JSON file and observable as a [StateFlow]. */
class SettingsStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state.asStateFlow()
    val value: Settings get() = _state.value

    private fun load(): Settings = try {
        if (file.exists()) json.decodeFromString(Settings.serializer(), file.readText()) else Settings()
    } catch (_: Exception) {
        Settings()
    }

    fun update(change: Settings.() -> Settings) {
        _state.update(change)
        file.parentFile?.mkdirs()
        // Write beside, then move: a crash mid-write must not leave half a settings file.
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(Settings.serializer(), _state.value))
        tmp.renameTo(file)
    }
}

/** Where the app keeps its files. The platform supplies the roots. */
class AppPaths(val supportDir: File, val cacheDir: File, val defaultDownloadDir: File) {
    val downloadTempDir: File get() = File(cacheDir, "download-temp")
    val downloadLogDir: File get() = File(supportDir, "download-logs")
    val wrapperDir: File get() = File(supportDir, "wrapper")
    val acousticDir: File get() = File(supportDir, "acoustic")
    val picturesDir: File get() = File(cacheDir, "pictures")
    val databaseFile: File get() = File(supportDir, "library.db")
    val settingsFile: File get() = File(supportDir, "setting.json")
    val configFile: File get() = File(supportDir, "config.json")

    fun ensure() {
        listOf(supportDir, cacheDir, defaultDownloadDir, downloadTempDir, downloadLogDir, picturesDir)
            .forEach { it.mkdirs() }
    }
}

/** Whether [dir] can actually be written to, tested by writing. */
fun isDirWritable(dir: File): Boolean = try {
    if (!dir.exists()) dir.mkdirs()
    val probe = File(dir, ".soiboi-write-test")
    probe.writeText("")
    probe.delete()
    true
} catch (_: Exception) {
    false
}

/** The folder downloads should actually go to: the chosen one if writable, else the app's own. */
fun resolveDownloadDir(settings: Settings, paths: AppPaths): File {
    val chosen = settings.downloadFolder.trim()
    if (chosen.isEmpty()) return paths.defaultDownloadDir
    return File(chosen).takeIf { isDirWritable(it) } ?: paths.defaultDownloadDir
}
