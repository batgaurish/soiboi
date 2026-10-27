package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InnertubePlaylistEditTest {
    @Test
    fun playlistAddActionCanExplicitlyAllowRepeatedVideoIds() {
        val action = Innertube.playlistAddAction("same-video", allowDuplicates = true)

        assertTrue(action.toString().contains("DEDUPE_OPTION_SKIP"))
        assertTrue(action.toString().contains("same-video"))
    }

    @Test
    fun failedPlaylistEditSummaryKeepsServerReasonButNotTrackIdentifiers() {
        val response = Json.parseToJsonElement(
            """
            {
              "status": "STATUS_FAILED",
              "playlistEditResults": [{
                "status": "STATUS_FAILED",
                "playlistEditVideoAddedResultData": {"videoId": "private-video-id"},
                "error": {"code": "PERMISSION_DENIED", "message": "The playlist is not editable"}
              }]
            }
            """.trimIndent(),
        ).jsonObject

        val summary = Innertube.summarizePlaylistEditFailure(response)

        assertTrue("STATUS_FAILED" in summary)
        assertTrue("The playlist is not editable" in summary)
        assertTrue("PERMISSION_DENIED" in summary)
        assertFalse("private-video-id" in summary)
    }
}
