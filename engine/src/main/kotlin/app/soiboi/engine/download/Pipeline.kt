package app.soiboi.engine.download

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One JSON line from the pipeline: progress, or a terminal result. */
class PipelineEvent(val raw: JsonObject) {
    val event: String get() = raw["event"]?.jsonPrimitive?.contentOrNull ?: ""
    val isProgress get() = event == "progress"
    val isDone get() = event == "done"
    val isError get() = event == "error"
    val progress: Int get() = raw["progress"]?.jsonPrimitive?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() } ?: 0
    val status: String get() = raw["status"]?.jsonPrimitive?.contentOrNull ?: ""
    val message: String get() = raw["message"]?.jsonPrimitive?.contentOrNull ?: ""
    val code: String get() = raw["code"]?.jsonPrimitive?.contentOrNull ?: ""

    companion object {
        fun error(code: String, message: String) = PipelineEvent(buildJsonObject {
            put("event", "error"); put("code", code); put("message", message)
        })
    }
}

/** What the bundled environment can actually do, probed rather than assumed. */
data class PipelineCapabilities(
    val canDownload: Boolean,
    val pythonVersion: String,
    val missing: List<String>,
    val nativeMuxer: Boolean,
    val canAnalyze: Boolean? = null,
    val error: String? = null,
) {
    val summary: String
        get() = when {
            error != null -> error
            canDownload -> "Ready"
            !nativeMuxer -> "Media engine missing for this device"
            missing.isNotEmpty() -> "Missing: ${missing.joinToString(", ")}"
            else -> "Unavailable"
        }

    companion object {
        fun unavailable(reason: String) = PipelineCapabilities(false, "", emptyList(), false, false, reason)

        fun fromJson(json: JsonObject) = PipelineCapabilities(
            canDownload = json["can_download"]?.jsonPrimitive?.booleanOrNull ?: false,
            pythonVersion = json["python"]?.jsonPrimitive?.contentOrNull ?: "",
            missing = (json["missing"] as? kotlinx.serialization.json.JsonArray)
                ?.map { it.jsonPrimitive.content } ?: emptyList(),
            nativeMuxer = (json["native_muxer"] as? JsonObject)?.get("available")?.jsonPrimitive?.booleanOrNull ?: false,
            canAnalyze = (json["acoustic_analysis"] as? JsonObject)?.get("available")?.jsonPrimitive?.booleanOrNull,
        )
    }
}

/** Streams events until the pipeline produces a terminal result. */
abstract class PipelineRunner {
    abstract fun run(command: String, payload: JsonObject? = null): Flow<PipelineEvent>

    /** Stops the download in progress, if any. Its flow then ends with a `cancelled` error. */
    abstract suspend fun cancelDownload()

    suspend fun capabilities(): PipelineCapabilities = try {
        var result: PipelineCapabilities? = null
        run("capabilities").collect { e ->
            if (result == null) {
                if (e.isDone) result = PipelineCapabilities.fromJson(e.raw)
                else if (e.isError) result = PipelineCapabilities.unavailable(e.message)
            }
        }
        result ?: PipelineCapabilities.unavailable("No response from pipeline")
    } catch (e: Exception) {
        PipelineCapabilities.unavailable(e.toString())
    }

    /** Free and total bytes on the disk holding [path], or null if the pipeline could not tell. */
    suspend fun diskUsage(path: String): Pair<Long, Long>? = try {
        val done = run("disk_usage", buildJsonObject { put("path", path) })
            .firstOrNull { it.isDone || it.isError }
        val free = done?.raw?.get("free")?.jsonPrimitive?.longOrNull
        val total = done?.raw?.get("total")?.jsonPrimitive?.longOrNull
        if (done?.isDone == true && free != null && total != null) free to total else null
    } catch (_: Exception) {
        null
    }
}
