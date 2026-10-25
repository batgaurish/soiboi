package com.music.bitchord.archive

import com.music.bitchord.desktop.ArchiveJobRequest
import com.music.bitchord.desktop.ArchiveJobResult
import com.music.bitchord.desktop.ArchiveJobState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AndroidArchivePipelineAdapterTest {

    @Test
    fun enqueuesValidJobAndReturnsResult() {
        val fakeRunner = ChaquopyBridgeRunner { action, jobId, args ->
            assertEquals("enqueue", action)
            assertEquals("job_123", jobId)
            assertEquals(listOf("song1", "track", "/tmp/music", "aac"), args)
            """{"jobId":"$jobId","state":"QUEUED","message":"queued"}"""
        }
        val adapter = AndroidArchivePipelineAdapter(fakeRunner)
        val result = adapter.enqueue(
            ArchiveJobRequest(
                jobId = "job_123",
                mediaId = "song1",
                mediaType = "track",
                outputDirectory = "/tmp/music",
                format = "aac"
            )
        )
        assertEquals("job_123", result.jobId)
        assertEquals(ArchiveJobState.QUEUED, result.state)
        assertEquals("queued", result.message)
    }

    @Test
    fun rejectsInvalidJobId() {
        val fakeRunner = ChaquopyBridgeRunner { _, _, _ -> """{"jobId":"bad","state":"DONE"}""" }
        val adapter = AndroidArchivePipelineAdapter(fakeRunner)
        try {
            adapter.status("invalid/job/id!")
            fail("Expected IllegalArgumentException for invalid job ID")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Invalid job id"))
        }
    }

    @Test
    fun redactsSecretsAndBoundsOutputOnBridgeError() {
        val longSecretMsg = "Failed with token=SUPER_SECRET_TOKEN and cookie=SECRET_COOKIE " + "x".repeat(500)
        val fakeRunner = ChaquopyBridgeRunner { _, _, _ ->
            throw RuntimeException(longSecretMsg)
        }
        val adapter = AndroidArchivePipelineAdapter(fakeRunner, maxOutputChars = 64)
        val result = adapter.enqueue(
            ArchiveJobRequest("job1", "media1", "track", "/tmp")
        )
        assertEquals("job1", result.jobId)
        assertEquals(ArchiveJobState.FAILED, result.state)
        assertTrue("Output should be bounded to 64 chars", result.message!!.length <= 64)
        assertTrue("Tokens should be redacted", result.message!!.contains("token=[REDACTED]"))
        assertTrue("Cookies should be redacted", !result.message!!.contains("SECRET_COOKIE"))
    }

    @Test
    fun returnsNullOnEmptyStatusOrResult() {
        val fakeRunner = ChaquopyBridgeRunner { action, _, _ ->
            if (action == "status" || action == "result") "" else """{"jobId":"j1","state":"DONE"}"""
        }
        val adapter = AndroidArchivePipelineAdapter(fakeRunner)
        assertNull(adapter.status("j1"))
        assertNull(adapter.result("j1"))
    }

    @Test
    fun handlesCancelAndResume() {
        val fakeRunner = ChaquopyBridgeRunner { action, jobId, _ ->
            when (action) {
                "cancel" -> """{"jobId":"$jobId","state":"CANCELLED","message":"stopped"}"""
                "resume" -> """{"jobId":"$jobId","state":"RUNNING","message":"resumed"}"""
                else -> error("unexpected")
            }
        }
        val adapter = AndroidArchivePipelineAdapter(fakeRunner)
        val cancelled = adapter.cancel("job_cancel")
        assertEquals(ArchiveJobState.CANCELLED, cancelled.state)
        assertEquals("stopped", cancelled.message)

        val resumed = adapter.resume("job_resume")
        assertEquals(ArchiveJobState.RUNNING, resumed.state)
        assertEquals("resumed", resumed.message)
    }
}
