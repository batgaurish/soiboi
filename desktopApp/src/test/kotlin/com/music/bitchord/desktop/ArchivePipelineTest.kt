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
}
