package com.music.bitchord.desktop

import com.music.bitchord.data.settings.HomeSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopHomeSourceSettingsTest {
    private val persistence = DesktopPersistence()

    @AfterTest
    fun tearDown() {
        persistence.saveHomeSource(HomeSource.YOUTUBE)
    }

    @Test
    fun homeSourceDefaultsToYouTube() {
        assertEquals(HomeSource.YOUTUBE, persistence.homeSource())
    }

    @Test
    fun selectingAppleSurvivesRoundTripAndRestoresChoice() {
        persistence.saveHomeSource(HomeSource.APPLE)
        val freshInstance = DesktopPersistence()
        assertEquals(HomeSource.APPLE, freshInstance.homeSource())
    }
}
