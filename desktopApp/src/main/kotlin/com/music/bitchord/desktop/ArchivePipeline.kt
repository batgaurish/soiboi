package com.music.bitchord.desktop

enum class ArchiveJobState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }
data class ArchiveJobRequest(val jobId: String, val mediaId: String, val mediaType: String, val outputDirectory: String, val format: String = "aac")
data class ArchiveJobResult(val jobId: String, val state: ArchiveJobState, val path: String? = null, val message: String? = null)

interface ArchivePipeline {
    fun enqueue(request: ArchiveJobRequest): ArchiveJobResult
    fun status(jobId: String): ArchiveJobResult?
    fun cancel(jobId: String): ArchiveJobResult
    fun resume(jobId: String): ArchiveJobResult
    fun result(jobId: String): ArchiveJobResult?
}

object ArchiveProcessProtocol {
    const val MAX_OUTPUT_CHARS = 64 * 1024
    fun bounded(text: String, maxChars: Int = MAX_OUTPUT_CHARS): String = text.take(maxChars)
    fun redact(text: String): String = text
        .replace(Regex("(?i)(authorization\\s*:\\s*bearer\\s+)\\S+"), "${'$'}1[REDACTED]")
        .replace(Regex("(?i)(token|password|cookie|secret)(\\s*[=:]\\s*)\\S+"), "${'$'}1${'$'}2[REDACTED]")

    fun parseOptionalResult(json: String): ArchiveJobResult? = json.trim().takeIf(String::isNotEmpty)?.let(::parseResult)
    fun parseResult(json: String): ArchiveJobResult {
        require(json.trim().startsWith("{") && json.trim().endsWith("}")) { "Invalid archive result" }
        fun field(name: String) = Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").find(json)?.groupValues?.get(1)
        val id = field("jobId") ?: throw IllegalArgumentException("Missing jobId")
        val state = field("state")?.let { runCatching { ArchiveJobState.valueOf(it) }.getOrNull() } ?: throw IllegalArgumentException("Invalid archive state")
        return ArchiveJobResult(id, state, field("path"), field("message"))
    }
}
