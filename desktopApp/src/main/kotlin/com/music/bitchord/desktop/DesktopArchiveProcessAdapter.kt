package com.music.bitchord.desktop

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DesktopArchiveProcessAdapter(private val executable: File, private val workDirectory: File, private val timeoutSeconds: Long = 5, private val maxOutputChars: Int = ArchiveProcessProtocol.MAX_OUTPUT_CHARS) : ArchivePipeline {
    private val processes = ConcurrentHashMap<String, Process>()
    override fun enqueue(request: ArchiveJobRequest) = invoke("enqueue", request.jobId, listOf(request.mediaId, request.mediaType, request.outputDirectory, request.format) )!!
    override fun status(jobId: String) = invoke("status", jobId, optional = true)
    override fun result(jobId: String) = invoke("result", jobId, optional = true)
    override fun resume(jobId: String) = invoke("resume", jobId)!!
    override fun cancel(jobId: String): ArchiveJobResult { processes[jobId]?.destroy(); return invoke("cancel", jobId)!! }
    private fun invoke(action: String, id: String, args: List<String> = emptyList(), optional: Boolean = false): ArchiveJobResult? {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid job id" }
        val process = ProcessBuilder(listOf(executable.absolutePath, action, id) + args).directory(workDirectory).redirectErrorStream(true).start()
        processes[id] = process
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { process.destroyForcibly(); throw IllegalStateException("Archive process timed out") }
            val output = ArchiveProcessProtocol.bounded(process.inputStream.bufferedReader().use { it.readText() }, maxOutputChars)
            if (process.exitValue() != 0) throw IllegalStateException(ArchiveProcessProtocol.redact(output))
            return if (optional && output.isBlank()) null else ArchiveProcessProtocol.parseResult(output)
        } finally { processes.remove(id, process) }
    }
}
