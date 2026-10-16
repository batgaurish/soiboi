package app.soiboi.android

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import app.soiboi.engine.Engine
import app.soiboi.engine.Platform
import app.soiboi.engine.core.AppPaths
import app.soiboi.engine.core.FileSecretStore
import app.soiboi.engine.download.WrapperPlatform
import app.soiboi.ui.PlatformUi
import app.soiboi.ui.SoiboiApp
import app.soiboi.ui.theme.AppFonts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class MainActivity : ComponentActivity() {
    private var pending: CompletableDeferred<String?>? = null

    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> pending?.complete(uri?.let(::treeToPath)); pending = null }
    private val pickDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pending?.complete(uri?.let(::copyToCache)); pending = null }
    private val saveDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> pending?.complete(uri?.let { copyTargetFor(it) }); pending = null }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    private var savedUri: Uri? = null

    /** A document-tree URI for primary storage ("primary:Music/Rips") as the plain path the scanner reads. Needs All files access. */
    private fun treeToPath(uri: Uri): String? {
        val id = android.provider.DocumentsContract.getTreeDocumentId(uri)
        val (volume, rel) = id.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val root = if (volume == "primary") Environment.getExternalStorageDirectory().path else "/storage/$volume"
        ensureAllFiles()
        return if (rel.isEmpty()) root else "$root/$rel"
    }

    private fun ensureAllFiles() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager())
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun copyToCache(uri: Uri): String? = runCatching {
        val out = File(cacheDir, "picked-" + (uri.lastPathSegment?.substringAfterLast('/')?.replace(Regex("[^A-Za-z0-9._-]"), "_") ?: "file"))
        contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
        out.path
    }.getOrNull()

    /** The save dialog yields a content URI; the engine writes a file, so hand it a cache file and copy it across on the next read. */
    private fun copyTargetFor(uri: Uri): String { savedUri = uri; return File(cacheDir, "backup-out.json").path }

    private fun font(name: String, weight: FontWeight) = Font("fonts/$name.ttf", assets, weight)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 33) permissions.launch(arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
        else permissions.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))

        val support = File(filesDir, "soiboi")
        val paths = AppPaths(support, File(cacheDir, "soiboi"), File(getExternalFilesDir(null) ?: filesDir, "Soiboi"))
        val platform = Platform(
            paths = paths, pipeline = AndroidPipelineRunner(applicationContext), audio = ExoBackend(applicationContext), secrets = FileSecretStore(support),
            wrapper = WrapperPlatform(isAndroid = true, abi = Build.SUPPORTED_ABIS[0], nativeLibraryDir = File(applicationInfo.nativeLibraryDir)), isAndroid = true,
            ensureFolderWritable = { ensureAllFiles() },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> app.soiboi.engine.core.EngineLog.output("uncaught in a background task: " + e.stackTraceToString()) })
        val engine = Engine(platform, scope)
        val fonts = AppFonts(
            zine = FontFamily(font("BricolageGrotesque-400", FontWeight.W400), font("BricolageGrotesque-600", FontWeight.W600), font("BricolageGrotesque-800", FontWeight.W800)),
            linerDisplay = FontFamily(font("Fraunces-400", FontWeight.W400), font("Fraunces-600", FontWeight.W600), font("Fraunces-700", FontWeight.W700)),
            linerBody = FontFamily(font("IBMPlexSans-400", FontWeight.W400), font("IBMPlexSans-600", FontWeight.W600)),
        )
        val ui = object : PlatformUi {
            override val isAndroid = true
            override val web = AndroidWeb
            override fun decodeImage(bytes: ByteArray): ImageBitmap? = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            private suspend fun ask(launch: () -> Unit): String? { val d = CompletableDeferred<String?>(); pending = d; launch(); return d.await() }
            override suspend fun pickFolder() = ask { pickTree.launch(null) }
            override suspend fun pickFile(extensions: List<String>) = ask { pickDoc.launch(arrayOf("*/*")) }
            override suspend fun pickSavePath(suggestedName: String) = ask { saveDoc.launch(suggestedName) }
            override fun openUrl(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            override fun copyToClipboard(text: String) { (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Soiboi", text)) }
            override fun setDownloading(active: Boolean) = KeepAliveService.set(applicationContext, active)
            override fun installApk(file: File): Boolean {
                if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    return false
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(applicationContext, "$packageName.updates", file)
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            }
        }
        setContent { SoiboiApp(engine, ui, fonts, rememberCoroutineScope()) }
    }
}
