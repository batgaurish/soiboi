package app.soiboi.engine.library

import app.soiboi.engine.download.PipelineRunner
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File

/** Totals across every folder in one analysis run. */
data class AcousticSummary(val analysed: Int = 0, val skipped: Int = 0, val pending: Int = 0, val unavailable: Boolean = false, val error: String? = null) {
    operator fun plus(o: AcousticSummary) = AcousticSummary(analysed + o.analysed, skipped + o.skipped, pending + o.pending, unavailable || o.unavailable, error ?: o.error)
}

/**
 * Backfills acoustic features for music the app did not download itself. Smart playlists filter on energy, relaxed and
 * danceable from a per-file sidecar, and a missing number matches no threshold, so a library that arrived any other way
 * made every smart playlist empty. Analysis is per file and cached by the sidecar, so it is resumable and cheap to rerun.
 */
class AcousticAnalysis(private val runner: PipelineRunner, private val storeDir: File) {
    /** Analyses every folder. Never throws: a folder that has gone missing must not take the run down with it. */
    suspend fun run(folders: List<String>, onProgress: (String) -> Unit = {}): AcousticSummary {
        var total = AcousticSummary()
        for (folder in folders.filter { File(it).isDirectory }) {
            try {
                runner.run("analyze", buildJsonObject { put("directory", folder); put("store_dir", storeDir.path) }).collect { e ->
                    when {
                        e.isProgress -> onProgress(e.status)
                        e.isError -> total += AcousticSummary(unavailable = e.code == "no_analysis", error = e.message)
                        e.isDone -> {
                            fun n(k: String) = (e.raw[k] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 0
                            total += AcousticSummary(n("analysed"), n("skipped"), n("pending"))
                        }
                    }
                }
            } catch (e: Exception) { total += AcousticSummary(error = e.toString()) }
        }
        return total
    }
}
