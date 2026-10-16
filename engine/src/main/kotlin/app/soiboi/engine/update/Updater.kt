package app.soiboi.engine.update

import app.soiboi.engine.core.EngineLog
import app.soiboi.engine.sources.Http
import app.soiboi.engine.sources.downloadFile
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** A newer release and the file for this device. */
data class UpdateInfo(val version: String, val notes: String, val assetName: String, val assetUrl: String, val size: Long, val pageUrl: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val done: Long, val total: Long) : UpdateState
    /** Android: the APK is saved and waits for the system installer. */
    data class ReadyToInstall(val info: UpdateInfo, val file: File) : UpdateState
    /** Desktop: the new files are in place and take effect on the next start. */
    data class Installed(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}

fun versionParts(v: String): List<Int> = v.trim().removePrefix("v").substringBefore('-').substringBefore('+').split('.').map { it.toIntOrNull() ?: 0 }

fun isNewerVersion(candidate: String, current: String): Boolean {
    val a = versionParts(candidate); val b = versionParts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/** Looks for a newer GitHub release, downloads the file for this device, and on desktop swaps it in. Android hands the APK to the system installer. */
class Updater(private val repo: String, private val currentVersion: String, private val isAndroid: Boolean, private val cacheDir: File) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Returns the newer release if there is one. A failed check keeps quiet when [quiet], as a launch check should. */
    suspend fun check(quiet: Boolean = false): UpdateInfo? {
        _state.value = UpdateState.Checking
        return try {
            val response = withTimeout(20_000) { Http.client.get("https://api.github.com/repos/$repo/releases/latest") { header("accept", "application/vnd.github+json") } }
            if (!response.status.isSuccess()) throw IllegalStateException("GitHub answered ${response.status.value}.")
            val release = Http.json.parseToJsonElement(response.bodyAsText()) as JsonObject
            val info = pick(release)
            if (info != null && isNewerVersion(info.version, currentVersion)) { _state.value = UpdateState.Available(info); info }
            else { _state.value = UpdateState.UpToDate; null }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            EngineLog.output("update check failed: $e")
            _state.value = if (quiet) UpdateState.Idle else UpdateState.Failed("Could not reach GitHub. ${e.message ?: ""}".trim())
            null
        }
    }

    private fun pick(release: JsonObject): UpdateInfo? {
        val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        if (release["draft"]?.jsonPrimitive?.contentOrNull == "true") return null
        val assets = (release["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val wanted = assets.firstOrNull {
            val n = it["name"]?.jsonPrimitive?.contentOrNull ?: return@firstOrNull false
            if (isAndroid) n.endsWith(".apk") else n.endsWith("linux-x64.tar.gz")
        } ?: return null // A release without a file for this device is not an update for it.
        return UpdateInfo(
            version = tag.removePrefix("v"), notes = release["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            assetName = wanted["name"]!!.jsonPrimitive.content, assetUrl = wanted["browser_download_url"]!!.jsonPrimitive.content,
            size = wanted["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0, pageUrl = release["html_url"]?.jsonPrimitive?.contentOrNull ?: "https://github.com/$repo/releases",
        )
    }

    /** Downloads the release file. On desktop it then unpacks it over the install; [installDir] is the folder holding `app/`. */
    suspend fun download(info: UpdateInfo, installDir: File?) {
        val dest = File(cacheDir, "updates/${info.assetName}")
        _state.value = UpdateState.Downloading(info, 0, info.size)
        val failure = downloadFile(info.assetUrl, dest) { done, total -> _state.value = UpdateState.Downloading(info, done, if (total > 0) total else info.size) }
        if (failure != null) { _state.value = UpdateState.Failed("The download stopped. $failure"); return }
        if (isAndroid) { _state.value = UpdateState.ReadyToInstall(info, dest); return }
        if (installDir == null) { _state.value = UpdateState.Failed("This copy was not set up by the installer, so Soiboi cannot replace itself. Download it from ${info.pageUrl}"); return }
        val problem = withContext(Dispatchers.IO) { unpack(dest, installDir) }
        _state.value = if (problem == null) UpdateState.Installed(info) else UpdateState.Failed(problem)
    }

    /** Unpacks the tarball beside the live app, then swaps the folders so a failure leaves the old version in place. */
    internal fun unpack(tarball: File, installDir: File): String? = try {
        val staging = File(installDir, "update-staging").apply { deleteRecursively(); mkdirs() }
        val tar = ProcessBuilder("tar", "-xzf", tarball.path, "-C", staging.path).redirectErrorStream(true).start()
        val out = tar.inputStream.bufferedReader().readText()
        if (tar.waitFor() != 0) { staging.deleteRecursively(); "Could not unpack the update. ${out.take(200)}" }
        else {
            val fresh = File(staging, "soiboi")
            if (!File(fresh, "bin/soiboi").exists()) { staging.deleteRecursively(); "The update file did not look like Soiboi." }
            else {
                val live = File(installDir, "app"); val old = File(installDir, "app.old")
                old.deleteRecursively()
                if (live.exists() && !live.renameTo(old)) { staging.deleteRecursively(); "Could not move the current version aside." }
                else if (!fresh.renameTo(live)) { old.renameTo(live); staging.deleteRecursively(); "Could not put the new version in place." }
                else { staging.deleteRecursively(); old.deleteRecursively(); tarball.delete(); null }
            }
        }
    } catch (e: Exception) { EngineLog.output("update unpack failed: $e"); "Could not install the update. ${e.message ?: ""}" }

    fun dismiss() { _state.value = UpdateState.Idle }
}
