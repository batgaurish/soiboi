package com.music.bitchord.archive

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.music.bitchord.desktop.ArchiveJobRequest
import com.music.bitchord.desktop.ArchiveJobResult
import com.music.bitchord.desktop.ArchiveJobState
import com.music.bitchord.desktop.ArchivePipeline
import com.music.bitchord.desktop.ArchiveProcessProtocol
import java.io.File

/**
 * Functional interface abstracting Chaquopy Python invocation.
 * Allows pure offline unit testing without Android emulator or Chaquopy native binaries.
 */
fun interface ChaquopyBridgeRunner {
    fun invoke(action: String, jobId: String, args: List<String>): String?
}

/**
 * Production Chaquopy runner that initializes Python via AndroidPlatform and executes
 * `soiboi_pipeline.__main__.handle_archive`.
 */
class DefaultChaquopyBridgeRunner(
    private val context: Context,
    private val cookieProvider: (() -> String?)? = null
) : ChaquopyBridgeRunner {
    override fun invoke(action: String, jobId: String, args: List<String>): String? {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
        val py = Python.getInstance()
        val module = py.getModule("soiboi_pipeline.__main__")

        val effectiveArgs = if (action == "enqueue" && args.size == 4) {
            val cookie = cookieProvider?.invoke()
            if (!cookie.isNullOrBlank()) {
                val cookieFile = File(context.cacheDir, "apple_session_cookie.txt")
                runCatching {
                    cookieFile.writeText(cookie)
                }
                args + listOf(cookieFile.absolutePath)
            } else {
                args
            }
        } else {
            args
        }

        val result = module.callAttr("handle_archive", action, jobId, *effectiveArgs.toTypedArray())
        return result?.toString()
    }
}

/**
 * Android implementation of BitChord's ArchivePipeline contract.
 * Uses an embedded Python environment through Chaquopy for archive operations.
 */
class AndroidArchivePipelineAdapter(
    private val runner: ChaquopyBridgeRunner,
    private val maxOutputChars: Int = ArchiveProcessProtocol.MAX_OUTPUT_CHARS
) : ArchivePipeline {

    constructor(
        context: Context,
        cookieProvider: (() -> String?)? = null,
        maxOutputChars: Int = ArchiveProcessProtocol.MAX_OUTPUT_CHARS
    ) : this(DefaultChaquopyBridgeRunner(context, cookieProvider), maxOutputChars)

    private fun validateJobId(jobId: String) {
        require(jobId.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid job id" }
    }

    override fun enqueue(request: ArchiveJobRequest): ArchiveJobResult {
        validateJobId(request.jobId)
        val output = try {
            runner.invoke(
                "enqueue",
                request.jobId,
                listOf(request.mediaId, request.mediaType, request.outputDirectory, request.format)
            ) ?: throw IllegalStateException("Empty response from archive bridge")
        } catch (e: Throwable) {
            val safeMessage = ArchiveProcessProtocol.bounded(
                ArchiveProcessProtocol.redact(e.message ?: e.toString()),
                maxOutputChars
            )
            return ArchiveJobResult(request.jobId, ArchiveJobState.FAILED, message = safeMessage)
        }
        val bounded = ArchiveProcessProtocol.bounded(ArchiveProcessProtocol.redact(output), maxOutputChars)
        return ArchiveProcessProtocol.parseResult(bounded)
    }

    override fun status(jobId: String): ArchiveJobResult? {
        validateJobId(jobId)
        val output = try {
            runner.invoke("status", jobId, emptyList())
        } catch (e: Throwable) {
            val safeMessage = ArchiveProcessProtocol.bounded(
                ArchiveProcessProtocol.redact(e.message ?: e.toString()),
                maxOutputChars
            )
            return ArchiveJobResult(jobId, ArchiveJobState.FAILED, message = safeMessage)
        }
        if (output.isNullOrBlank()) return null
        val bounded = ArchiveProcessProtocol.bounded(ArchiveProcessProtocol.redact(output), maxOutputChars)
        return ArchiveProcessProtocol.parseOptionalResult(bounded)
    }

    override fun cancel(jobId: String): ArchiveJobResult {
        validateJobId(jobId)
        val output = try {
            runner.invoke("cancel", jobId, emptyList())
                ?: throw IllegalStateException("Empty response from archive bridge on cancel")
        } catch (e: Throwable) {
            val safeMessage = ArchiveProcessProtocol.bounded(
                ArchiveProcessProtocol.redact(e.message ?: e.toString()),
                maxOutputChars
            )
            return ArchiveJobResult(jobId, ArchiveJobState.FAILED, message = safeMessage)
        }
        val bounded = ArchiveProcessProtocol.bounded(ArchiveProcessProtocol.redact(output), maxOutputChars)
        return ArchiveProcessProtocol.parseResult(bounded)
    }

    override fun resume(jobId: String): ArchiveJobResult {
        validateJobId(jobId)
        val output = try {
            runner.invoke("resume", jobId, emptyList())
                ?: throw IllegalStateException("Empty response from archive bridge on resume")
        } catch (e: Throwable) {
            val safeMessage = ArchiveProcessProtocol.bounded(
                ArchiveProcessProtocol.redact(e.message ?: e.toString()),
                maxOutputChars
            )
            return ArchiveJobResult(jobId, ArchiveJobState.FAILED, message = safeMessage)
        }
        val bounded = ArchiveProcessProtocol.bounded(ArchiveProcessProtocol.redact(output), maxOutputChars)
        return ArchiveProcessProtocol.parseResult(bounded)
    }

    override fun result(jobId: String): ArchiveJobResult? {
        validateJobId(jobId)
        val output = try {
            runner.invoke("result", jobId, emptyList())
        } catch (e: Throwable) {
            val safeMessage = ArchiveProcessProtocol.bounded(
                ArchiveProcessProtocol.redact(e.message ?: e.toString()),
                maxOutputChars
            )
            return ArchiveJobResult(jobId, ArchiveJobState.FAILED, message = safeMessage)
        }
        if (output.isNullOrBlank()) return null
        val bounded = ArchiveProcessProtocol.bounded(ArchiveProcessProtocol.redact(output), maxOutputChars)
        return ArchiveProcessProtocol.parseOptionalResult(bounded)
    }
}
