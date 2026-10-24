package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ArchivePipelineTest {
    @Test fun parsesStructuredResultAndRedactsSecrets() {
        val result = ArchiveProcessProtocol.parseResult("""{"jobId":"j1","state":"DONE","path":"/music/a.m4a","message":"ok"}""")
        assertEquals(ArchiveJobState.DONE, result.state)
        assertEquals("/music/a.m4a", result.path)
        assertEquals("Authorization: Bearer [REDACTED] token=[REDACTED]", ArchiveProcessProtocol.redact("Authorization: Bearer abc token=xyz"))
    }
    @Test fun rejectsInvalidResultAndBoundsOutput() {
        assertFailsWith<IllegalArgumentException> { ArchiveProcessProtocol.parseResult("not json") }
        assertEquals("123", ArchiveProcessProtocol.bounded("123456", 3))
    }
    @Test fun missingRestartStateIsRepresentedAsMissing() { assertNull(ArchiveProcessProtocol.parseOptionalResult("")) }

    @Test fun drainsLargeChildOutputWithoutExceedingConfiguredCaptureLimit() {
        val dir = java.nio.file.Files.createTempDirectory("archive-process-test").toFile()
        try {
            val executable = java.io.File(dir, "fake-child").apply {
                writeText("#!/bin/sh\nprintf 'token=hidden ' >&2\nhead -c 200000 /dev/zero | tr '\\\\000' x >&2\nexit 7\n")
                setExecutable(true)
            }
            val failure = assertFailsWith<IllegalStateException> {
                DesktopArchiveProcessAdapter(executable, dir, timeoutSeconds = 3, maxOutputChars = 128).status("job1")
            }
            assertEquals(true, failure.message!!.length <= 128)
            assertEquals(true, failure.message!!.contains("token=[REDACTED]"))
        } finally { dir.deleteRecursively() }
    }
}
