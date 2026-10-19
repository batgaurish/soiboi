package app.soiboi.engine.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Desktop transport: spawns `python -m soiboi_pipeline` from the bundled virtual
 * environment and reads newline-delimited JSON from its stdout.
 *
 * Android runs the same Python in-process through Chaquopy and supplies its own
 * [PipelineRunner]; callers see the same event flow either way.
 */
class DesktopPipelineRunner(
    private val pythonOverride: String? = null,
    private val rootOverride: String? = null,
    private val log: (String) -> Unit = {},
) : PipelineRunner() {
    private val json = Json { ignoreUnknownKeys = true }
    private val download = AtomicReference<Process?>(null)
    @Volatile private var downloadKilled = false

    private val root: File?
        get() {
            rootOverride?.let { return File(it) }
            val here = File(System.getProperty("user.dir"))
            // An installed copy sets SOIBOI_HOME to the folder holding pipeline/ and the virtualenv beside it.
            val candidates = listOfNotNull(
                System.getenv("SOIBOI_HOME")?.takeIf { it.isNotBlank() }?.let { File(it, "pipeline") },
                File(here, "pipeline"),
                File(here.parentFile, "pipeline"),
                File(here.parentFile?.parentFile, "pipeline"),
            )
            return candidates.firstOrNull { File(it, "soiboi_pipeline").isDirectory }
        }

    private val python: String?
        get() {
            pythonOverride?.let { return it }
            val r = root ?: return null
            // The venv lives beside the package, never inside it: Chaquopy copies the whole
            // pipeline directory into the APK and a virtualenv in there breaks that build.
            return listOf(
                File(r.parentFile, ".pipeline-venv/bin/python"),
                File(r.parentFile, ".pipeline-venv/Scripts/python.exe"),
            ).firstOrNull { it.exists() }?.path
        }

    override suspend fun cancelDownload() = withContext(Dispatchers.IO) {
        val process = download.get() ?: return@withContext
        downloadKilled = true
        runCatching { process.descendants().forEach { runCatching { it.destroy() } } }
        process.destroy()
        // gamdl does not trap SIGTERM, but a stuck native call could ignore it.
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            runCatching { process.descendants().forEach { runCatching { it.destroyForcibly() } } }
            process.destroyForcibly()
        }
        Unit
    }

    override fun run(command: String, payload: JsonObject?): Flow<PipelineEvent> = channelFlow {
        val py = python
        val pipelineRoot = root
        if (py == null || pipelineRoot == null) {
            send(PipelineEvent.error("no_runtime", "Bundled Python runtime not found"))
            return@channelFlow
        }
        val args = buildList {
            add(py); add("-m"); add("soiboi_pipeline"); add(command)
            if (payload != null) add(payload.toString())
        }
        val builder = ProcessBuilder(args).directory(pipelineRoot)
        // PYTHONPATH so the package resolves without being installed into the venv;
        // PYTHONUNBUFFERED so progress arrives as it happens, not in one lump at exit.
        builder.environment()["PYTHONPATH"] = pipelineRoot.path
        builder.environment()["PYTHONUNBUFFERED"] = "1"

        val process = try {
            withContext(Dispatchers.IO) { builder.start() }
        } catch (e: Exception) {
            send(PipelineEvent.error("spawn_failed", e.toString()))
            return@channelFlow
        }
        val isDownload = command == "download" || command == "session_download"
        if (isDownload) {
            download.set(process)
            downloadKilled = false
        }
        launch(Dispatchers.IO) {
            process.errorStream.bufferedReader().forEachLine { if (it.isNotBlank()) log("pipeline stderr: $it") }
        }
        withContext(Dispatchers.IO) {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val decoded = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
                    // Non-JSON output is the pipeline's own logging noise, not a protocol violation.
                    if (decoded != null) trySend(PipelineEvent(decoded)) else log("pipeline: $line")
                }
            } catch (e: Exception) {
                trySend(PipelineEvent.error("io", e.toString()))
            }
        }
        process.waitFor()
        if (isDownload && download.compareAndSet(process, null) && downloadKilled) {
            // A killed download has no chance to report it, so say so here.
            send(PipelineEvent.error("cancelled", "Download stopped"))
        }
        if (!isActive) {
            runCatching { process.descendants().forEach { runCatching { it.destroyForcibly() } } }
            process.destroyForcibly()
        }
    }
}
