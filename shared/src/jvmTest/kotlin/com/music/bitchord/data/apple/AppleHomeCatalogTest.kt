package com.music.bitchord.data.apple

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AppleHomeCatalogTest {
    @Test fun cookieMatchIsExactAndMissingStateIsTyped() = runBlocking {
        val result = AppleHomeCatalog({ "not-media-user-token=wrong; media-user-token=" }, { "us" }).home()
        assertIs<AppleHomeResult.Failed>(result)
        assertEquals(Failure.MISSING_USER_TOKEN, result.kind)
    }
}
