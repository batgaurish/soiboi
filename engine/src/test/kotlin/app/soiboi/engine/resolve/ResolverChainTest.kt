package app.soiboi.engine.resolve

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class Fake(override val id: String, val result: Playable?, val boom: Boolean = false) : Resolver {
    override suspend fun resolve(track: TrackRef): Playable? = if (boom) error("down") else result
}

class ResolverChainTest {
    private val track = TrackRef("Song", "Artist")
    private fun hit(id: String) = Playable("https://x/$id", providerId = id)

    @Test fun firstMatchWins() = runTest {
        val chain = ResolverChain(listOf(Fake("a", null), Fake("b", hit("b")), Fake("c", hit("c"))))
        assertEquals("b", chain.resolve(track)?.providerId)
    }

    @Test fun failingResolverIsSkipped() = runTest {
        val chain = ResolverChain(listOf(Fake("a", null, boom = true), Fake("b", hit("b"))))
        assertEquals("b", chain.resolve(track)?.providerId)
    }

    @Test fun noMatchGivesNull() = runTest {
        assertNull(ResolverChain(listOf(Fake("a", null))).resolve(track))
    }
}
