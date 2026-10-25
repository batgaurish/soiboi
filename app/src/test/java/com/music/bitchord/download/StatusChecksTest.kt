package com.music.bitchord.download

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusChecksTest {
    private fun inputs(
        codec: String = "aac",
        cookies: Boolean = false,
        wrapperSigned: Boolean = false,
        wrapper: WrapperState = WrapperState(WrapperStage.Stopped),
        free: Long? = 50L shl 30,
        online: Boolean? = true,
        expiry: Instant? = null
    ) = StatusInputs(
        now = Instant.parse("2026-10-15T00:00:00Z"),
        codec = codec,
        cookiesSignedIn = cookies,
        cookieExpiry = expiry,
        wrapperSignedIn = wrapperSigned,
        wrapper = wrapper,
        capabilities = PipelineCapabilities(canDownload = true, pythonVersion = "3.12", availableCodecs = emptyList(), ready = true),
        chosenFolder = "",
        saveFolder = "/d",
        freeBytes = free,
        online = online,
    )

    @Test
    fun notSignedInIsTheOneProblem() {
        val checks = buildStatusChecks(inputs())
        assertEquals(StatusLevel.Problem, checks[0].level)
        assertEquals(StatusFix.SignIn, checks[0].fix)
    }

    @Test
    fun losslessWrapperOnlyMattersForAlac() {
        val missing = WrapperState(WrapperStage.NeedsLibraries)
        assertEquals(StatusLevel.Info, buildStatusChecks(inputs("aac", cookies = true, wrapper = missing))[1].level)
        assertEquals(StatusLevel.Problem, buildStatusChecks(inputs("alac", cookies = true, wrapper = missing))[1].level)
    }

    @Test
    fun expiringSessionsWarnAndExpiredOnesFail() {
        assertEquals(StatusLevel.Warning, buildStatusChecks(inputs(cookies = true, expiry = Instant.parse("2026-10-18T00:00:00Z")))[0].level)
        assertEquals(StatusLevel.Problem, buildStatusChecks(inputs(cookies = true, expiry = Instant.parse("2026-10-01T00:00:00Z")))[0].level)
    }

    @Test
    fun lowSpaceWarnsAndNoSpaceFails() {
        assertEquals(StatusLevel.Warning, buildStatusChecks(inputs(cookies = true, free = 500L shl 20))[3].level)
        assertEquals(StatusLevel.Problem, buildStatusChecks(inputs(cookies = true, free = 100L shl 20))[3].level)
    }

    @Test
    fun offlineIsAProblemAndUnknownIsInfo() {
        assertEquals(StatusLevel.Problem, buildStatusChecks(inputs(online = false))[4].level)
        assertEquals(StatusLevel.Info, buildStatusChecks(inputs(online = null))[4].level)
    }
}
