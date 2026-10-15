package app.soiboi.android

import android.content.Context
import app.soiboi.engine.download.PipelineEvent
import app.soiboi.engine.download.PipelineRunner
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.Executors

/**
 * Android transport: the same `soiboi_pipeline` module the desktop spawns, run in-process through Chaquopy, because Android
 * cannot start a Python binary. Progress comes back through a Consumer that Python calls as events happen. Downloads and
 * analyses get their own threads so a long run never holds up a quick tag read, and a stop never queues behind a download.
 */
class AndroidPipelineRunner(private val context: Context) : PipelineRunner() {
    private val json = Json { ignoreUnknownKeys = true }
    private val downloads = Executors.newSingleThreadExecutor()
    private val analysis = Executors.newSingleThreadExecutor()
    private val background = Executors.newFixedThreadPool(2)
    private val control = Executors.newSingleThreadExecutor()

    private fun start() { if (!Python.isStarted()) Python.start(AndroidPlatform(context)) }

    override suspend fun cancelDownload() { run("cancel").collect { } }

    override fun run(command: String, payload: JsonObject?): Flow<PipelineEvent> = callbackFlow {
        val executor = when (command) { "download" -> downloads; "analyze" -> analysis; "cancel" -> control; else -> background }
        executor.execute {
            val terminal: String = try {
                start()
                val module = Python.getInstance().getModule("soiboi_pipeline.__main__")
                val emit = java.util.function.Consumer<String> { line -> parse(line)?.let { trySend(it) } }
                module.callAttr("handle_json", command, (payload ?: JsonObject(emptyMap())).toString(), emit).toString()
            } catch (e: Throwable) {
                // Surfaced as a normal pipeline error, so callers handle one failure shape.
                buildJsonObject { put("event", "error"); put("code", "android_bridge"); put("message", e.toString()) }.toString()
            }
            parse(terminal)?.let { trySend(it) }
            close()
        }
        awaitClose { }
    }.flowOn(Dispatchers.IO)

    private fun parse(line: String): PipelineEvent? = runCatching { (json.parseToJsonElement(line) as? JsonObject)?.let(::PipelineEvent) }.getOrNull()
}
