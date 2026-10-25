package com.music.bitchord.desktop

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DesktopArchiveProcessAdapter(
    private val executable: File = defaultExecutable(),
    private val workDirectory: File = defaultWorkDirectory(),
    private val timeoutSeconds: Long = 30,
    private val maxOutputChars: Int = ArchiveProcessProtocol.MAX_OUTPUT_CHARS,
    private val credentialProvider: (() -> String?)? = { DesktopAppleAuth.netscapeJar() },
) : ArchivePipeline {
    private val processes = ConcurrentHashMap<String, Process>()

    override fun enqueue(request: ArchiveJobRequest): ArchiveJobResult {
        validateJobId(request.jobId)
        val args = mutableListOf(request.mediaId, request.mediaType, request.outputDirectory, request.format)
        val cookie = credentialProvider?.invoke()
        if (!cookie.isNullOrBlank()) {
            val cookieFile = File(workDirectory, ".apple_session_cookie.txt")
            runCatching {
                cookieFile.writeText(cookie)
                cookieFile.setReadable(false, false)
                cookieFile.setReadable(true, true)
                cookieFile.setWritable(false, false)
                cookieFile.setWritable(true, true)
            }
            args.add(cookieFile.absolutePath)
        }
        return invoke("enqueue", request.jobId, args)
            ?: ArchiveJobResult(request.jobId, ArchiveJobState.FAILED, message = "Failed to start archival job")
    }

    override fun status(jobId: String): ArchiveJobResult? {
        validateJobId(jobId)
        return invoke("status", jobId, optional = true)
    }

    override fun result(jobId: String): ArchiveJobResult? {
        validateJobId(jobId)
        return invoke("result", jobId, optional = true)
    }

    override fun resume(jobId: String): ArchiveJobResult {
        validateJobId(jobId)
        return invoke("resume", jobId)
            ?: ArchiveJobResult(jobId, ArchiveJobState.RUNNING, message = "resumed")
    }

    override fun cancel(jobId: String): ArchiveJobResult {
        validateJobId(jobId)
        processes[jobId]?.let { process ->
            runCatching { process.descendants().forEach { it.destroy() } }
            process.destroy()
        }
        return invoke("cancel", jobId)
            ?: ArchiveJobResult(jobId, ArchiveJobState.CANCELLED, message = "cancelled")
    }

    private fun validateJobId(id: String) {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid job id" }
    }

    private fun invoke(
        action: String,
        id: String,
        args: List<String> = emptyList(),
        optional: Boolean = false,
    ): ArchiveJobResult? {
        validateJobId(id)
        val process = ProcessBuilder(listOf(executable.absolutePath, action, id) + args)
            .directory(workDirectory)
            .redirectErrorStream(true)
            .start()
        processes[id] = process
        val output = StringBuilder()
        val drain = Thread {
            process.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    synchronized(output) {
                        if (output.length < maxOutputChars) {
                            output.append(buffer, 0, minOf(count, maxOutputChars - output.length))
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IllegalStateException("Archive process timed out")
            }
            drain.join()
            val captured = synchronized(output) { output.toString() }
            if (process.exitValue() != 0) {
                throw IllegalStateException(ArchiveProcessProtocol.bounded(ArchiveProcessProtocol.redact(captured), maxOutputChars))
            }
            val trimmed = captured.trim()
            if (optional && trimmed.isBlank()) return null
            val jsonLine = trimmed.lines().lastOrNull { line ->
                val l = line.trim()
                l.startsWith("{") && l.endsWith("}")
            } ?: trimmed
            val parsed = ArchiveProcessProtocol.parseResult(
                ArchiveProcessProtocol.bounded(ArchiveProcessProtocol.redact(jsonLine), maxOutputChars)
            )
            val msg = parsed.message
            return if (msg != null) {
                parsed.copy(message = ArchiveProcessProtocol.redact(msg))
            } else {
                parsed
            }
        } finally {
            processes.remove(id, process)
        }
    }

    companion object {
        fun defaultWorkDirectory(): File {
            val userDir = File(System.getProperty("user.dir", "."))
            val candidates = listOfNotNull(
                System.getenv("BITCHORD_PIPELINE")?.let { File(it) },
                System.getenv("SOIBOI_HOME")?.let { File(it, "pipeline") },
                File(userDir, "pipeline"),
                File("pipeline"),
                File("../pipeline"),
                userDir,
            )
            return candidates.firstOrNull { File(it, "soiboi_pipeline").isDirectory } ?: userDir
        }

        fun defaultExecutable(): File {
            val workDir = defaultWorkDirectory()
            val candidates = listOf(
                File(workDir, "archive_cli.py"),
                File(workDir, "pipeline/archive_cli.py"),
                File(System.getProperty("user.dir", "."), "pipeline/archive_cli.py"),
            )
            val found = candidates.firstOrNull { it.exists() } ?: File(workDir, "archive_cli.py")
            if (found.exists() && !found.canExecute()) {
                runCatching { found.setExecutable(true) }
            }
            return found
        }
    }
}
