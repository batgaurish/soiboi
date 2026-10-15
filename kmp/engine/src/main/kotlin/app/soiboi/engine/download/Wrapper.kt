package app.soiboi.engine.download

import app.soiboi.engine.core.EngineLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/** The exact Apple Music build the wrapper's libraries must come from (pinned by SHA-256). */
const val APPLE_MUSIC_APK_URL =
    "https://www.apkmirror.com/apk/apple/apple-music/apple-music-3-6-0-beta-release/" +
        "apple-music-3-6-0-beta-4-android-apk-download/"
const val APPLE_MUSIC_APK_LABEL = "Apple Music 3.6.0-beta, build 1109 (arm64-v8a + x86_64)"

enum class WrapperStage { Unsupported, NeedsLibraries, Stopped, Starting, SignedOut, NeedsCode, Ready, Failed }

data class WrapperState(val stage: WrapperStage, val message: String? = null, val account: String? = null)

/** Where the platform keeps the wrapper's native pieces. Android fills in the native library dir. */
class WrapperPlatform(
    val isAndroid: Boolean,
    val abi: String,
    val nativeLibraryDir: File? = null,
    /** Where a packaged build put the wrapper: beside the installed app, or the dev tree's build/. */
    val bundleCandidates: List<File> = emptyList(),
)

/**
 * The bundled lossless wrapper (wrapper-v2), run by the app itself. gamdl fetches ALAC only through
 * it. On Linux it runs in an unprivileged user namespace (`unshare -rmpf`); on Android the two
 * programs ship as native libraries and the worker loads Apple's libraries from app storage.
 */
class WrapperService(
    private val installDir: File,
    private val platform: WrapperPlatform,
    private val runner: PipelineRunner,
) {
    private val http = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(WrapperState(WrapperStage.Stopped))
    val state: StateFlow<WrapperState> = _state.asStateFlow()

    /** Whether the wrapper holds a signed-in session; a marker file remembers it while stopped. */
    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private var process: Process? = null
    private var httpPort: Int? = null
    private var decryptPort: Int? = null

    val supported: Boolean get() = bundleDir() != null || platform.isAndroid

    private val libDir: File
        get() = if (platform.isAndroid) File(installDir, "lib") else File(installDir, "rootfs/system/lib64")
    private val baseUrl: String get() = "http://127.0.0.1:$httpPort"
    private val marker: File get() = File(installDir, "signed_in")

    val librariesInstalled: Boolean get() = File(libDir, "libandroidappmusic.so").exists()

    private fun bundleDir(): File? {
        if (platform.isAndroid) {
            val dir = platform.nativeLibraryDir
            return dir?.takeIf { File(it, "libwrapperd.so").exists() }
        }
        return platform.bundleCandidates.firstOrNull { File(it, "wrapperd").exists() }
    }

    private fun setSignedIn(value: Boolean) {
        _signedIn.value = value
        try {
            if (value) { marker.parentFile?.mkdirs(); marker.createNewFile() } else marker.delete()
        } catch (e: Exception) {
            EngineLog.output("wrapper sign-in marker: $e")
        }
    }

    /** Sets [state] from what is on disk, without starting anything. */
    fun refresh() {
        _signedIn.value = supported && librariesInstalled && marker.exists()
        when {
            !supported || bundleDir() == null -> _state.value = WrapperState(
                WrapperStage.Unsupported, "The lossless wrapper is not part of this build.",
            )
            !librariesInstalled -> _state.value = WrapperState(WrapperStage.NeedsLibraries)
            process == null -> _state.value = WrapperState(WrapperStage.Stopped)
        }
    }

    /** Copies the wrapper into app storage and installs Apple's libraries from [apk]. Returns an error, or null. */
    suspend fun installLibraries(apk: File): String? {
        val bundle = bundleDir() ?: return "The lossless wrapper is not part of this build."
        stop()
        if (!platform.isAndroid) {
            val copy = withContext(Dispatchers.IO) {
                ProcessBuilder("cp", "-a", "${bundle.path}/.", installDir.path).redirectErrorStream(true).start()
                    .let { p -> p.inputStream.bufferedReader().readText() to p.waitFor() }
            }
            if (copy.second != 0) return "Could not copy the wrapper: ${copy.first}"
        }
        var error: String? = null
        runner.run("wrapper_install", buildJsonObject {
            put("apk_path", apk.path); put("arch", platform.abi); put("out_dir", libDir.path)
        }).collect { if (it.isError) error = it.message }
        refresh()
        return error
    }

    /** Starts the wrapper if it is not running. Returns once it answers. */
    suspend fun start() {
        if (process != null) return
        refresh()
        if (_state.value.stage != WrapperStage.Stopped) return
        _state.value = WrapperState(WrapperStage.Starting)

        httpPort = freePort(); decryptPort = freePort()
        val ports = mapOf(
            "WRAPPER_HOST" to "127.0.0.1", "WRAPPER_PORT" to "$httpPort",
            "WRAPPER_DECRYPT_HOST" to "127.0.0.1", "WRAPPER_DECRYPT_PORT" to "$decryptPort",
        )
        val started = try {
            withContext(Dispatchers.IO) {
                val builder = if (platform.isAndroid) {
                    val native = platform.nativeLibraryDir!!
                    val baseDir = File(installDir, "base").also { it.mkdirs() }
                    val certs = File(libDir, "cacert.pem").path
                    ProcessBuilder(File(native, "libwrapperd.so").path).directory(installDir).apply {
                        environment().putAll(ports + mapOf(
                            "WRAPPER_LAUNCHER" to File(native, "libwrapperworker.so").path,
                            "WRAPPER_NATIVE_ANDROID" to "1", "WRAPPER_LIBS_DIR" to libDir.path,
                            "WRAPPER_BASE_DIR" to baseDir.path, "LD_LIBRARY_PATH" to libDir.path,
                            "SSL_CERT_FILE" to certs, "CURL_CA_BUNDLE" to certs,
                        ))
                    }
                } else {
                    File(installDir, "rootfs/data/data/com.apple.android.music/files").mkdirs()
                    killLeftovers()
                    // --kill-child: the wrapper is PID 1 in its namespace and ignores SIGTERM, so without
                    // this, stopping or losing the app left it running with nobody knowing its port.
                    ProcessBuilder("unshare", "-rmpf", "--kill-child", File(installDir, "wrapperd").path)
                        .directory(installDir).apply {
                            environment().putAll(ports + ("WRAPPER_LAUNCHER" to File(installDir, "wrapper").path))
                        }
                }
                builder.start()
            }
        } catch (e: Exception) {
            _state.value = WrapperState(WrapperStage.Failed, e.toString())
            return
        }
        process = started
        Thread { started.errorStream.bufferedReader().forEachLine { EngineLog.output("wrapper: ${it.trim()}") } }
            .apply { isDaemon = true }.start()
        Thread { started.inputStream.copyTo(java.io.OutputStream.nullOutputStream()) }
            .apply { isDaemon = true }.start()
        Thread {
            val code = started.waitFor()
            if (process === started) {
                process = null
                _state.value = WrapperState(WrapperStage.Failed, "The wrapper stopped (exit code $code).")
            }
        }.apply { isDaemon = true }.start()

        // Apple's runtime takes a few seconds to load.
        repeat(30) {
            if (refreshAccount()) return
            delay(1000)
        }
        _state.value = WrapperState(WrapperStage.Failed, "The wrapper did not come up within 30 seconds.")
    }

    /** Reads the sign-in state. Returns false when the wrapper is not answering. */
    private suspend fun refreshAccount(): Boolean = try {
        val body = withTimeout(3000) { http.get("$baseUrl/me").bodyAsText() }
        val auth = json.parseToJsonElement(body).jsonObject["auth"] as? JsonObject
        if (auth == null) false else {
            val loggedOut = auth["state"]?.jsonPrimitive?.contentOrNull == "logged_out"
            // Only a sign-in sets the marker, never a "logged out": offline, the wrapper cannot confirm
            // the session with Apple and reports logged out while the session itself is fine.
            if (!loggedOut) setSignedIn(true)
            _state.value = when {
                !loggedOut -> WrapperState(WrapperStage.Ready, account = auth["apple_id"]?.jsonPrimitive?.contentOrNull)
                _signedIn.value -> WrapperState(
                    WrapperStage.SignedOut,
                    "Apple did not confirm the sign-in. This happens without a connection; it is checked again at the next download.",
                )
                else -> WrapperState(WrapperStage.SignedOut)
            }
            true
        }
    } catch (_: Exception) {
        false
    }

    /** Signs the wrapper in. The password goes only to the local wrapper, which hands it to Apple. */
    suspend fun signIn(appleId: String, password: String) =
        post("/login", buildJsonObject { put("username", appleId); put("password", password) })

    suspend fun submitCode(code: String) = post("/login/2fa", buildJsonObject { put("code", code.trim()) })

    private suspend fun post(path: String, body: JsonObject) {
        try {
            val response = withTimeout(90_000) {
                http.post("$baseUrl$path") { header("content-type", "application/json"); setBody(body.toString()) }
            }
            when (response.status) {
                HttpStatusCode.Accepted -> _state.value = WrapperState(WrapperStage.NeedsCode)
                HttpStatusCode.OK -> refreshAccount()
                else -> _state.value = WrapperState(
                    WrapperStage.SignedOut,
                    if (response.status == HttpStatusCode.Unauthorized) "Apple did not accept that Apple ID or password."
                    else "Sign-in failed (${response.status.value}).",
                )
            }
        } catch (e: Exception) {
            _state.value = WrapperState(WrapperStage.SignedOut, e.toString())
        }
    }

    suspend fun signOut() {
        try { http.delete("$baseUrl/login") } catch (e: Exception) { EngineLog.output("wrapper sign-out: $e") }
        setSignedIn(false)
        refreshAccount()
    }

    suspend fun stop() {
        val p = process
        process = null
        if (p == null) return
        withContext(Dispatchers.IO) {
            p.destroy()
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        refresh()
    }

    /** What a download needs to go through the running wrapper, or null when it is not signed in. */
    val downloadPayload: Map<String, Any>?
        get() = if (_state.value.stage == WrapperStage.Ready) mapOf(
            "use_wrapper" to true, "wrapper_url" to baseUrl, "wrapper_decrypt_port" to decryptPort!!,
        ) else null

    /** Starts a signed-in wrapper and returns its [downloadPayload], or null when not set up or signed in. */
    suspend fun ensureReady(): Map<String, Any>? {
        refresh()
        if (!librariesInstalled || !_signedIn.value) return null
        startForDownload()
        return downloadPayload
    }

    /**
     * [start], for a download about to use it. A wrapper started without a connection stays
     * "logged out" after the connection returns, so while a sign-in is remembered, one fresh
     * start asks Apple again.
     */
    suspend fun startForDownload() {
        start()
        if (_state.value.stage == WrapperStage.SignedOut && _signedIn.value) { stop(); start() }
    }

    /** At startup: ask a wrapper set up before sign-ins were remembered, once, then stop it if it is signed out. */
    suspend fun probeSignIn() {
        refresh()
        if (!librariesInstalled || _signedIn.value) return
        start()
        if (!_signedIn.value) stop()
    }

    /** Force-stops wrappers from an earlier run, found by their executable in this app's own storage. Linux only. */
    private fun killLeftovers() {
        val exe = File(installDir, "wrapperd").path
        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toLongOrNull() ?: return@forEach
            try {
                val cmdline = File(entry, "cmdline").readText()
                if (exe in cmdline.split('\u0000')) ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
            } catch (_: Exception) {
                // Exited while we looked, or not ours to read.
            }
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
