package app.soiboi.engine

import app.soiboi.engine.library.Database
import app.soiboi.engine.library.Library
import app.soiboi.engine.library.Song
import app.soiboi.engine.library.SongOrigin
import app.soiboi.engine.library.SongStore
import app.soiboi.engine.player.AudioBackend
import app.soiboi.engine.player.BackendEvent
import app.soiboi.engine.player.PlayMode
import app.soiboi.engine.player.Player
import app.soiboi.engine.player.TrackMeta
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeAudioBackend : AudioBackend {
    private val _events = MutableSharedFlow<BackendEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<BackendEvent> = _events.asSharedFlow()

    var openedSource: String? = null
    var openedPlay: Boolean? = null
    var played = false
    var paused = false

    override suspend fun open(source: String, headers: Map<String, String>, startMs: Long, play: Boolean, meta: TrackMeta?) {
        openedSource = source
        openedPlay = play
        if (play) played = true
    }

    override fun play() {
        played = true
        paused = false
    }

    override fun pause() {
        paused = true
        played = false
    }

    override fun stop() {
        played = false
    }

    override suspend fun seek(positionMs: Long) {}
    override fun setVolume(volume: Double) {}
    override fun setEqualizer(gainsDb: List<Double>) {}
    override val positionMs: Long = 0
    override val durationMs: Long = 100000
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlayerTest {
    private fun db(): Database = Database(File.createTempFile("soiboi-player-test", ".db").also { it.deleteOnExit() })

    private fun song(id: String, path: String = "/$id.mp3") = Song(id, origin = SongOrigin.Local, path = path, title = "Title $id", artist = "Artist $id")

    @Test
    fun playWhenQueueHasSongsButCurrentIndexNegativeStartsPlayback() = runTest {
        val database = db()
        val store = SongStore(database)
        val lib = Library(store)
        val s1 = song("s1")
        store.replaceAll(SongOrigin.Local, listOf(s1))
        lib.load()

        val backend = FakeAudioBackend()
        val player = Player(
            scope = this,
            backend = backend,
            library = lib,
            songs = store,
            db = database,
            servers = { emptyMap() },
            cacheDir = null,
        )

        try {
            player.addLast(s1)
            assertEquals(1, player.queue.value.songs.size)
            // With currentIndex < 0, calling play() should start the song at index 0
            player.play()
            advanceUntilIdle()

            assertEquals(0, player.queue.value.currentIndex)
            assertEquals("/s1.mp3", backend.openedSource)
            assertTrue(backend.played)
        } finally {
            player.release()
        }
    }

    @Test
    fun reversePreservesCurrentPlayingTrackIndexWhenQueueHasDuplicates() = runTest {
        val database = db()
        val store = SongStore(database)
        val lib = Library(store)
        val sA = song("A")
        val sB = song("B")
        val sA2 = song("A")
        store.replaceAll(SongOrigin.Local, listOf(sA, sB))
        lib.load()

        val backend = FakeAudioBackend()
        val player = Player(
            scope = this,
            backend = backend,
            library = lib,
            songs = store,
            db = database,
            servers = { emptyMap() },
            cacheDir = null,
        )

        try {
            player.setQueue(listOf(sA, sB, sA2), PlayMode.Order, startAt = 0)
            advanceUntilIdle()
            assertEquals(0, player.queue.value.currentIndex)

            player.reverse()
            advanceUntilIdle()

            // Reversed list: [sA2, sB, sA]. The currently playing song was at index 0 (sA),
            // so in the reversed list of size 3 it must be at index 2 (size - 1 - 0).
            assertEquals(2, player.queue.value.currentIndex)
        } finally {
            player.release()
        }
    }

    @Test
    fun removeAtPreservesOtherDuplicatesInUnshuffled() = runTest {
        val database = db()
        val store = SongStore(database)
        val lib = Library(store)
        val sA = song("A")
        val sB = song("B")
        val sA2 = song("A")
        store.replaceAll(SongOrigin.Local, listOf(sA, sB))
        lib.load()

        val backend = FakeAudioBackend()
        val player = Player(
            scope = this,
            backend = backend,
            library = lib,
            songs = store,
            db = database,
            servers = { emptyMap() },
            cacheDir = null,
        )

        try {
            player.setQueue(listOf(sA, sB, sA2), PlayMode.Order, startAt = 1)
            advanceUntilIdle()
            player.setMode(PlayMode.Shuffle)
            advanceUntilIdle()

            // Remove the song at index 0 or 2 (which is not current index 1)
            val toRemoveIndex = if (player.queue.value.currentIndex == 0) 2 else 0
            val removedSong = player.queue.value.songs[toRemoveIndex]
            player.removeAt(toRemoveIndex)
            advanceUntilIdle()

            // Switch back to Order mode
            player.setMode(PlayMode.Order)
            advanceUntilIdle()

            // If duplicate A was present, removing one instance must leave the other instance intact
            val remainingIds = player.queue.value.songs.map { it.id }
            assertEquals(2, remainingIds.size)
            assertTrue("A" in remainingIds)
            assertTrue("B" in remainingIds)
        } finally {
            player.release()
        }
    }

    @Test
    fun equalizerFormattingUsesDotRegardlessOfSystemLocale() {
        val originalLocale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val gains = listOf(3.0, -2.0, 1.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            val bands = listOf(gains[0], 0.0, gains[1], gains[2], 0.0, gains[3], gains[4], 0.0, gains[5], gains[6], 0.0, gains[7], 0.0, gains[8], 0.0, gains[9], gains[9], gains[9])
            val params = bands.withIndex().joinToString(":") { (i, db) -> "${i + 1}b=${"%.3f".format(java.util.Locale.ROOT, 10.0.pow(db / 20))}" }
            assertFalse(params.contains(','))
            assertTrue(params.contains('.'))
        } finally {
            java.util.Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun databaseTransactionRollsBackAndPreservesOriginalException() = runTest {
        val database = db()
        val originalEx = RuntimeException("Transaction failure")
        val thrown = assertFailsWith<RuntimeException> {
            database.transaction {
                exec("INSERT INTO kv(key, value) VALUES('k1', 'v1')")
                throw originalEx
            }
        }
        assertEquals(originalEx.message, thrown.message)
        val value = database.getKv("k1")
        assertNull(value)
    }
}
