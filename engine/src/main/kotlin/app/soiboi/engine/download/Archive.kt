package app.soiboi.engine.download

import app.soiboi.engine.core.AppPaths
import app.soiboi.engine.core.SettingsStore
import app.soiboi.engine.core.resolveDownloadDir
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Why a download failed: the pipeline's code, where it gave one, and its message. */
data class DownloadFailure(val message: String, val code: String = "") {
    override fun toString() = message
}

enum class TrackState { Downloading, Done, Skipped, Failed;
    companion object { fun parse(name: String?) = entries.firstOrNull { it.name.equals(name, true) } ?: Downloading }
}

/** Where one track of a playlist or album download is up to, from the pipeline's `track` events. */
data class TrackStatus(
    /** 1-based, in the playlist's order. */
    val index: Int,
    val title: String,
    val state: TrackState,
    /** Null when gamdl does not know (playlists). */
    val total: Int? = null,
    /** Why it was skipped or failed. */
    val detail: String = "",
    /** Skipped because the library already has the song. */
    val owned: Boolean = false,
) {
    companion object {
        fun fromEvent(raw: JsonObject) = TrackStatus(
            index = raw["index"]?.jsonPrimitive?.intOrNull ?: 0,
            total = raw["total"]?.jsonPrimitive?.intOrNull,
            title = raw["title"]?.jsonPrimitive?.contentOrNull ?: "",
            state = TrackState.parse(raw["state"]?.jsonPrimitive?.contentOrNull),
            detail = raw["detail"]?.jsonPrimitive?.contentOrNull ?: "",
            owned = raw["owned"]?.jsonPrimitive?.booleanOrNull == true,
        )
    }
}

/** What archiving needs from the rest of the app, so it is testable without a device. */
class ArchiveEnvironment(
    val runner: PipelineRunner,
    val settings: SettingsStore,
    val paths: AppPaths,
    val cookies: CookieStore,
    val wrapper: WrapperService,
    /** Artist + title keys for every local song, for the pipeline to skip. */
    val ownedKeys: () -> List<String>,
    /** On Android, asks for All files access when the chosen folder needs it. */
    val ensureFolderWritable: suspend () -> Unit = {},
)

/**
 * Archives [url]. Returns null on success, or what went wrong. Never throws: callers are UI code
 * streaming a batch, and an exception halfway through a fifty-track playlist would lose the
 * progress already made.
 *
 * Retrying is cheap by default: with [redownload] off the pipeline skips any track already on disk.
 */
suspend fun ArchiveEnvironment.archiveUrl(
    url: String,
    redownload: Boolean = false,
    onProgress: ((Int, String) -> Unit)? = null,
    onLog: ((String) -> Unit)? = null,
    onTrack: ((TrackStatus) -> Unit)? = null,
    logPath: String? = null,
): DownloadFailure? {
    fun log(line: String) = onLog?.invoke(line)
    log("Checking the download folder")
    ensureFolderWritable()
    val outDir = resolveDownloadDir(settings.value, paths)
    log("Download folder: $outDir")

    val s = settings.value
    val payload = LinkedHashMap<String, Any?>()
    payload["url"] = url
    payload["cookies_path"] = cookies.path
    payload["output_dir"] = outDir.path
    payload["temp_dir"] = paths.downloadTempDir.path
    payload["overwrite"] = redownload
    payload["codec"] = s.downloadCodec
    // The pipeline skips these whatever their codec or file name.
    if (!redownload) payload["owned"] = ownedKeys()
    if (logPath != null) payload["log_path"] = logPath
    log("Codec: ${s.downloadCodec}")

    // The bundled wrapper takes every download it can: it is needed for ALAC, and once signed in it
    // is the account's own session, so no cookies file is involved.
    if (wrapper.supported) log("Starting the lossless wrapper (can take up to 30 seconds)")
    val bundled: Map<String, Any>? = if (!wrapper.supported) null
    else if (s.downloadCodec == "alac") bundledWrapperPayload() else wrapper.ensureReady()
    if (wrapper.supported) {
        val st = wrapper.state.value
        log("Wrapper: ${st.stage.name.lowercase()}${st.message?.let { " ($it)" } ?: ""}")
    }
    // The wrapper holds the only sign-in but Apple did not confirm it (no connection, usually).
    if (bundled == null && wrapper.signedIn.value && wrapper.state.value.stage == WrapperStage.SignedOut &&
        !cookies.signedIn.value && !s.useWrapper
    ) {
        val message = "Apple did not confirm the lossless sign-in. Check the connection."
        log(message)
        return DownloadFailure(message, "wrapper_unconfirmed")
    }
    when {
        bundled != null -> { payload.putAll(bundled); log("Signed in through the wrapper") }
        s.useWrapper -> {
            payload["use_wrapper"] = true
            val wrapperUrl = s.wrapperUrl.trim()
            if (wrapperUrl.isNotEmpty()) payload["wrapper_url"] = wrapperUrl
            log("Using the external wrapper at $wrapperUrl")
        }
        else -> {
            if (!s.wvdPath.isNullOrEmpty()) payload["wvd_path"] = s.wvdPath
            log("Signed in with cookies")
        }
    }
    val isSession = url.startsWith("session://")
    val command = if (isSession) "session_download" else "download"
    if (isSession) {
        val uri = java.net.URI(url)
        payload["session"] = uri.host ?: ""
        payload["track_id"] = uri.path?.removePrefix("/") ?: ""
        val query = uri.rawQuery?.split("&")?.mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) parts[0] to java.net.URLDecoder.decode(parts[1], "UTF-8") else null
        }?.toMap() ?: emptyMap()
        query["isrc"]?.takeIf { it.isNotEmpty() }?.let { payload["isrc"] = it }
        query["artist"]?.takeIf { it.isNotEmpty() }?.let { payload["artist"] = it }
        query["title"]?.takeIf { it.isNotEmpty() }?.let { payload["title"] = it }
        payload["apple_cookies_path"] = cookies.path
        if (s.useWrapper && s.wrapperUrl.trim().isNotEmpty()) {
            payload["apple_wrapper_url"] = s.wrapperUrl.trim()
        }
    }
    log("Handing over to the downloader")

    var error: DownloadFailure? = null
    var finished = false
    runner.run(command, payload.toJson()).collect { event ->
        when {
            event.isProgress -> onProgress?.invoke(event.progress, event.status)
            event.isError -> {
                error = DownloadFailure(event.message, event.code)
                log("Error${if (event.code.isEmpty()) "" else " (${event.code})"}: ${event.message}")
            }
            event.isDone -> finished = true
            event.event == "track" -> onTrack?.invoke(TrackStatus.fromEvent(event.raw))
        }
    }
    // A pipeline that dies mid-download closes the flow without a terminal event: a failure, not a success.
    if (error == null && !finished) {
        return DownloadFailure("The downloader stopped without finishing. See the log for details.", "no_result")
    }
    return error
}

private suspend fun ArchiveEnvironment.bundledWrapperPayload(): Map<String, Any>? {
    wrapper.refresh()
    if (!wrapper.librariesInstalled) return null
    wrapper.startForDownload()
    return wrapper.downloadPayload
}

internal fun Map<String, Any?>.toJson(): JsonObject = buildJsonObject {
    for ((key, value) in this@toJson) {
        when (value) {
            null -> {}
            is Boolean -> put(key, value)
            is Number -> put(key, value)
            is String -> put(key, value)
            is Iterable<*> -> put(key, JsonArray(value.map { JsonPrimitive(it.toString()) }))
            else -> put(key, value.toString())
        }
    }
}
