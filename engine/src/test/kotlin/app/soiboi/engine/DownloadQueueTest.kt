package app.soiboi.engine

import app.soiboi.engine.download.DownloadFailure
import app.soiboi.engine.download.DownloadJobState
import app.soiboi.engine.download.DownloadQueue
import app.soiboi.engine.download.DownloadRequest
import app.soiboi.engine.download.TrackStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadQueueTest {
    @Test fun jobsRunOneAtATimeInOrderAndTheBatchCompletes() = runTest {
        val order = mutableListOf<String>(); var running = 0; var maxRunning = 0; var synced = 0
        val queue = DownloadQueue(this,
            archive = { url, progress, log, track, _ -> running++; maxRunning = maxOf(maxRunning, running); order += url; progress(50, "half"); track(TrackStatus(1, "t", app.soiboi.engine.download.TrackState.Done)); running--; null },
            syncLibrary = { synced++ }, stopActive = {}, logDir = File(System.getProperty("java.io.tmpdir"), "soiboi-q"))
        val batch = queue.enqueue(listOf("a", "b", "c").map { DownloadRequest(it, it) })
        advanceUntilIdle(); batch.await()
        assertEquals(listOf("a", "b", "c"), order); assertEquals(1, maxRunning); assertEquals(1, synced, "one library sync when the queue goes quiet")
        assertTrue(batch.jobs.all { it.state == DownloadJobState.Done }); assertEquals(3, batch.completed.value)
    }

    @Test fun failuresAreExplainedAndRetryable() = runTest {
        var attempt = 0
        val queue = DownloadQueue(this, archive = { _, _, _, _, _ -> if (attempt++ == 0) DownloadFailure("Connection reset") else null }, syncLibrary = {}, stopActive = {}, logDir = File(System.getProperty("java.io.tmpdir"), "soiboi-q"))
        val batch = queue.enqueue(listOf(DownloadRequest("u", "Song"))); advanceUntilIdle()
        val job = batch.jobs.single(); assertEquals(DownloadJobState.Failed, job.state); assertEquals("network", job.failure!!.id); assertTrue(job.failure!!.temporary)
        queue.retry(job); advanceUntilIdle(); assertEquals(DownloadJobState.Done, job.state)
    }

    @Test fun emptyBatchesStillComplete() = runTest {
        val queue = DownloadQueue(this, { _, _, _, _, _ -> null }, {}, {}, File("x")); queue.enqueue(emptyList()).await()
    }

    @Test fun cancellingAQueuedJobSkipsIt() = runTest {
        val gate = CompletableDeferred<Unit>()
        val queue = DownloadQueue(this, archive = { _, _, _, _, _ -> gate.await(); null }, syncLibrary = {}, stopActive = { gate.complete(Unit) }, logDir = File(System.getProperty("java.io.tmpdir"), "soiboi-q"))
        val batch = queue.enqueue(listOf(DownloadRequest("1", "one"), DownloadRequest("2", "two")))
        advanceUntilIdle(); queue.cancel(batch.jobs[1]); gate.complete(Unit); advanceUntilIdle()
        assertEquals(DownloadJobState.Done, batch.jobs[0].state); assertEquals(DownloadJobState.Cancelled, batch.jobs[1].state)
    }
}
