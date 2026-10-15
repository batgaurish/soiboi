package app.soiboi.desktop

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.soiboi.engine.Engine
import app.soiboi.engine.Platform
import app.soiboi.engine.core.AppPaths
import app.soiboi.engine.core.FileSecretStore
import app.soiboi.engine.download.DesktopPipelineRunner
import app.soiboi.engine.download.WrapperPlatform
import app.soiboi.engine.player.MpvBackend
import app.soiboi.ui.PlatformUi
import app.soiboi.ui.SoiboiApp
import app.soiboi.ui.theme.AppFonts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

private fun xdg(name: String, fallback: String): File = File(System.getenv(name)?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/" + fallback))

/** Where Soiboi keeps things on Linux, following the XDG folders rather than dotfiles in home. */
private fun desktopPaths(): AppPaths {
    val support = File(xdg("XDG_DATA_HOME", ".local/share"), "soiboi")
    val cache = File(xdg("XDG_CACHE_HOME", ".cache"), "soiboi")
    return AppPaths(support, cache, File(System.getProperty("user.home"), "Music/Soiboi"))
}

private fun loadFont(name: String, weight: FontWeight): Font {
    val bytes = object {}.javaClass.getResourceAsStream("/fonts/$name.ttf")!!.use { it.readBytes() }
    return androidx.compose.ui.text.platform.Font(identity = name, data = bytes, weight = weight)
}

private fun fonts() = AppFonts(
    zine = FontFamily(loadFont("BricolageGrotesque-400", FontWeight.W400), loadFont("BricolageGrotesque-600", FontWeight.W600), loadFont("BricolageGrotesque-800", FontWeight.W800)),
    linerDisplay = FontFamily(loadFont("Fraunces-400", FontWeight.W400), loadFont("Fraunces-600", FontWeight.W600), loadFont("Fraunces-700", FontWeight.W700)),
    linerBody = FontFamily(loadFont("IBMPlexSans-400", FontWeight.W400), loadFont("IBMPlexSans-600", FontWeight.W600)),
)

private object DesktopUi : PlatformUi {
    override val isAndroid = false
    override val web = DesktopWeb(File(desktopPaths().supportDir, "browser"))
    override fun decodeImage(bytes: ByteArray): ImageBitmap? = runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

    private suspend fun <T> onSwing(block: () -> T): T = withContext(Dispatchers.IO) {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        result!!.getOrThrow()
    }

    override suspend fun pickFolder(): String? = onSwing {
        JFileChooser(System.getProperty("user.home")).apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; dialogTitle = "Choose a music folder" }
            .let { if (it.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) it.selectedFile.path else null }
    }

    override suspend fun pickFile(extensions: List<String>): String? = onSwing {
        val dialog = FileDialog(null as Frame?, "Choose a file", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> extensions.isEmpty() || extensions.any { name.endsWith(".$it", true) } }
        dialog.isVisible = true
        dialog.file?.let { File(dialog.directory, it).path }
    }

    override suspend fun pickSavePath(suggestedName: String): String? = onSwing {
        val dialog = FileDialog(null as Frame?, "Save as", FileDialog.SAVE).apply { file = suggestedName }
        dialog.isVisible = true
        dialog.file?.let { File(dialog.directory, it).path }
    }

    override fun openUrl(url: String) {
        runCatching { if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) Desktop.getDesktop().browse(java.net.URI(url)) else ProcessBuilder("xdg-open", url).start() }
            .onFailure { runCatching { ProcessBuilder("xdg-open", url).start() } }
    }

    override fun copyToClipboard(text: String) { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
}

fun main() {
    val paths = desktopPaths()
    val home = System.getProperty("user.dir")
    val repo = generateSequence(File(home)) { it.parentFile }.take(5).firstOrNull { File(it, "pipeline/soiboi_pipeline").isDirectory }
    val platform = Platform(
        paths = paths,
        pipeline = DesktopPipelineRunner(rootOverride = repo?.let { File(it, "pipeline").path }),
        audio = MpvBackend(),
        secrets = FileSecretStore(paths.supportDir),
        wrapper = WrapperPlatform(isAndroid = false, abi = "x86_64", bundleCandidates = listOfNotNull(repo?.let { File(it, "build/wrapper/x86_64") }, File(home, "build/wrapper/x86_64"))),
    )
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val engine = Engine(platform, scope)
    val fonts = fonts()
    val mpris = Mpris(engine, scope).also { it.start() }
    application {
        Window(onCloseRequest = { mpris.stop(); engine.shutdown(); exitApplication() }, title = "Soiboi", state = rememberWindowState(size = DpSize(1280.dp, 820.dp))) {
            SoiboiApp(engine, DesktopUi, fonts, rememberCoroutineScope()) { app -> DevCommands.attach(app) }
        }
    }
}

/**
 * A development aid, active only when SOIBOI_DEV_COMMANDS names a file: each line appended to it runs one UI action
 * (go, play, next, now, back, search, flavour, theme), so the real screens can be exercised and screenshotted without a
 * pointer. It never runs in a normal launch.
 */
private object DevCommands {
    fun attach(ui: app.soiboi.ui.AppController) {
        val path = System.getenv("SOIBOI_DEV_COMMANDS") ?: return
        val file = File(path)
        Thread {
            var done = 0
            while (true) {
                val lines = if (file.exists()) file.readLines() else emptyList()
                while (done < lines.size) { val line = lines[done++].trim(); runCatching { run(ui, line) }.onFailure { System.err.println("dev command failed: $line: $it") } }
                Thread.sleep(300)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun run(ui: app.soiboi.ui.AppController, line: String) {
        val (cmd, arg) = line.split(' ', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val e = ui.engine
        when (cmd) {
            "web" -> ui.web = when (arg) {
                "apple" -> app.soiboi.ui.web.WebSessions.appleSignIn(ui)
                "apk" -> app.soiboi.ui.web.WebSessions.wrapperApk(ui)
                else -> app.soiboi.ui.web.WebSessions.aiKey(app.soiboi.engine.ai.AiProvider.Gemini) { System.err.println("dev: key found") }
            }
            "webclose" -> ui.web = null
            "go" -> ui.goPlace(app.soiboi.ui.Place.entries.first { it.name.equals(arg, true) })
            "library" -> ui.go(app.soiboi.ui.Route.Library(app.soiboi.ui.LibraryTab.entries.first { it.name.equals(arg, true) }))
            "album" -> ui.go(app.soiboi.ui.Route.Album(arg))
            "artist" -> ui.go(app.soiboi.ui.Route.Artist(arg))
            "search" -> ui.go(app.soiboi.ui.Route.Search)
            "play" -> ui.play(e.library.songs.value.sortedBy { it.displayTitle }.drop(arg.toIntOrNull() ?: 0).take(60))
            "playlist" -> ui.play(e.library.songs.value.filter { it.displayArtist.contains(arg, true) })
            "toggle" -> e.player.togglePlay()
            "next" -> kotlinx.coroutines.runBlocking { e.player.next() }
            "now" -> ui.nowPlayingOpen = true
            "back" -> ui.back()
            "queue" -> ui.queueOpen = !ui.queueOpen
            "flavour" -> e.settings.update { copy(flavour = app.soiboi.engine.core.Flavour.entries.first { it.name.equals(arg, true) }) }
            "theme" -> e.settings.update { copy(theme = app.soiboi.engine.core.ThemeMode.entries.first { it.name.equals(arg, true) }) }
            "ask" -> ui.askAiOpen = true
            "menu" -> e.library.songs.value.firstOrNull()?.let { ui.songMenu = app.soiboi.ui.SongMenu(it) }
            "closemenu" -> { ui.songMenu = null; ui.askAiOpen = false; ui.smartEditor = null }
            "volume" -> e.player.setVolume(arg.toDouble())
            else -> System.err.println("unknown dev command: $line")
        }
    }
}
