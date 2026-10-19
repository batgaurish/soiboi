package app.soiboi.engine.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class DownloadJobState { Queued, Running, Done, Failed, Cancelled }

/** One URL waiting to be, or being, archived. */
class DownloadJob(
    val id: String,
    val url: String,
    /** What to call this in the queue; never blank, because a queue of unnamed rows is useless. */
    val label: String,
    val subtitle: String? = null,
    /** The playlist or album this job is one track of, when a caller queued a list as separate jobs. */
    val group: String? = null,
) {
    @Volatile var state: DownloadJobState = DownloadJobState.Queued
    @Volatile var progress: Int = 0
    @Volatile var status: String = ""

    /** The failure as the pipeline reported it, kept for the log; people see [failure] instead. */
    @Volatile var error: String? = null
    @Volatile var errorCode: String = ""
    val failure: FailureExplanation? get() = error?.let { describeFailure(it, errorCode) }

    private val trackMap = java.util.TreeMap<Int, TrackStatus>()
    val tracks: List<TrackStatus> get() = synchronized(trackMap) { trackMap.values.toList() }
    fun updateTrack(track: TrackStatus) = synchronized(trackMap) { trackMap[track.index] = track }

    /** What the app did for this job, timestamped, including the steps before the downloader starts. */
    private val logLines = ArrayList<String>()
    val log: List<String> get() = synchronized(logLines) { logLines.toList() }
    var downloaderLogPath: String? = null

    fun addLog(line: String) = synchronized(logLines) {
        logLines += "${LocalTime.now().format(timeFormat)}  $line"
        // Bounded: a long download reports many stages, and this is kept for every finished job.
        if (logLines.size > 500) logLines.subList(0, logLines.size - 500).clear()
    }

    val isTerminal get() = state == DownloadJobState.Done || state == DownloadJobState.Failed || state == DownloadJobState.Cancelled
    val isActive get() = state == DownloadJobState.Queued || state == DownloadJobState.Running

    private companion object { val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss") }
}

class DownloadRequest(val url: String, val label: String, val subtitle: String? = null, val group: String? = null)

/** The jobs one `enqueue` produced, so a screen can follow its own work without filtering the global queue. */
class DownloadBatch internal constructor(val jobs: List<DownloadJob>) {
    private val _completed = MutableStateFlow(0)
    val completed: StateFlow<Int> = _completed.asStateFlow()
    private val doneSignal = CompletableDeferred<Unit>()

    /** Completes once every job is terminal. Never throws: check [errors] instead. */
    suspend fun await() = doneSignal.await()

    val errors: List<String> get() = jobs.mapNotNull { it.error }
    val failures: List<FailureExplanation> get() = jobs.mapNotNull { it.failure }
    val succeeded: List<DownloadJob> get() = jobs.filter { it.state == DownloadJobState.Done }

    internal fun refresh() {
        _completed.value = jobs.count { it.isTerminal }
        if (_completed.value == jobs.size) doneSignal.complete(Unit)
    }
    internal val isDone get() = doneSignal.isCompleted
}

/** How many finished jobs are kept for review before the oldest are dropped. */
private const val HISTORY_LIMIT = 60

/**
 * One queue for every download the app starts. Screens only enqueue; the work outlives the widget
 * that asked for it. Deliberately sequential: Apple rate-limits, and the pipeline's cooldowns exist
 * for a reason.
 */
class DownloadQueue(
    private val scope: CoroutineScope,
    private val archive: suspend (
        url: String, onProgress: (Int, String) -> Unit, onLog: (String) -> Unit,
        onTrack: (TrackStatus) -> Unit, logPath: String?,
    ) -> DownloadFailure?,
    private val syncLibrary: suspend () -> Unit,
    private val stopActive: suspend () -> Unit,
    private val logDir: File,
) {
    private val allJobs = ArrayList<DownloadJob>()
    private val batches = ArrayList<DownloadBatch>()
    private val lock = Any()

    /** The queue, newest-enqueued last. Replaced on every change so collectors see an update. */
    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    /** Set while a job is being downloaded, so screens can show one bar without scanning the list. */
    private val _active = MutableStateFlow<DownloadJob?>(null)
    val active: StateFlow<DownloadJob?> = _active.asStateFlow()

    /** Bumps on every progress tick so a bar can recompose without the list changing. */
    private val _tick = MutableStateFlow(0)
    val tick: StateFlow<Int> = _tick.asStateFlow()

    /** Pausing stops the job in flight and puts it back at the front; finished tracks are skipped on resume. */
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private var afterStop: DownloadJobState? = null
    private var pumping = false
    private var dirty = false
    private var nextId = 0

    val activeCount: Int get() = synchronized(lock) { allJobs.count { it.isActive } }

    fun enqueue(requests: Iterable<DownloadRequest>): DownloadBatch {
        val batch: DownloadBatch
        synchronized(lock) {
            batch = DownloadBatch(requests.map {
                DownloadJob("${nextId++}", it.url, it.label.trim().ifEmpty { it.url }, it.subtitle, it.group)
            })
            batches += batch
            allJobs += batch.jobs
            trim()
        }
        notifyChanged()
        // An empty batch must still complete, or its caller waits forever.
        batch.refresh()
        pump()
        return batch
    }

    fun retry(job: DownloadJob) {
        if (job.isActive) return
        job.state = DownloadJobState.Queued; job.error = null; job.errorCode = ""; job.progress = 0; job.status = ""
        notifyChanged(); pump()
    }

    fun cancel(job: DownloadJob) {
        if (job.state == DownloadJobState.Running) { stopRunning(DownloadJobState.Cancelled); return }
        if (job.state != DownloadJobState.Queued) return
        job.state = DownloadJobState.Cancelled
        notifyChanged(); refreshBatches()
    }

    fun stopAll() { cancelPending(); stopRunning(DownloadJobState.Cancelled) }

    private fun stopRunning(then: DownloadJobState) {
        val job = _active.value ?: return
        afterStop = then
        job.status = "Stopping"
        job.addLog(if (then == DownloadJobState.Queued) "Pause requested" else "Stop requested")
        notifyChanged()
        scope.launch { stopActive() }
    }

    fun cancelPending() {
        synchronized(lock) { allJobs.filter { it.state == DownloadJobState.Queued }.forEach { it.state = DownloadJobState.Cancelled } }
        notifyChanged(); refreshBatches()
    }

    fun setPaused(value: Boolean) {
        _paused.value = value
        if (value) stopRunning(DownloadJobState.Queued) else pump()
    }

    fun dismiss(job: DownloadJob) {
        if (!job.isTerminal) return
        synchronized(lock) { allJobs.remove(job) }
        notifyChanged()
    }

    fun clearFinished() {
        synchronized(lock) { allJobs.removeAll { it.isTerminal } }
        notifyChanged()
    }

    private fun pump() {
        synchronized(lock) { if (pumping) return; pumping = true }
        scope.launch {
            try {
                while (!_paused.value) {
                    val job = synchronized(lock) { allJobs.firstOrNull { it.state == DownloadJobState.Queued } } ?: break
                    run(job)
                }
            } finally {
                synchronized(lock) { pumping = false }
            }
            // Once, when the queue goes quiet: a library sync walks every folder, and doing that
            // between tracks would dominate a fifty-track run.
            if (dirty && activeCount == 0) { dirty = false; syncLibrary() }
        }
    }

    private suspend fun run(job: DownloadJob) {
        job.state = DownloadJobState.Running; job.progress = 0; job.status = "Starting"
        _active.value = job
        notifyChanged()

        pruneLogs()
        // Unique per job and kept across a pause or retry; job ids restart at 0 every launch.
        if (job.downloaderLogPath == null) {
            job.downloaderLogPath = File(logDir, "${System.currentTimeMillis()}-${job.id}.log").path
        }
        job.addLog("Starting ${job.url}")

        var error: DownloadFailure? = try {
            archive(
                job.url,
                { progress, status ->
                    // A stop pressed before the downloader started reached a pipeline that clears the
                    // flag when a download begins; say it again now that one is running.
                    if (afterStop != null && _active.value === job) scope.launch { stopActive() }
                    else {
                        if (status != job.status) job.addLog("$progress%  $status")
                        job.progress = progress; job.status = status
                        _tick.update { it + 1 }
                    }
                },
                job::addLog, job::updateTrack, job.downloaderLogPath,
            )
        } catch (e: Exception) {
            // archiveUrl never throws, but a queue that dies on a broken promise would strand every job behind it.
            DownloadFailure(e.toString())
        }

        val stoppedAs = afterStop
        afterStop = null
        when {
            stoppedAs != null -> job.addLog(if (stoppedAs == DownloadJobState.Queued) "Paused" else "Stopped")
            error == null -> job.addLog("Finished")
            else -> {
                job.addLog("Failed: ${error.message}")
                val explained = explainFailure(error.message, error.code)
                job.addLog(if (explained == null) "Not in the error catalog" else "Catalog: ${explained.id} (${explained.title})")
            }
        }
        when {
            stoppedAs != null -> {
                // Stopped on purpose: a pause requeues the job, a cancel drops it.
                job.state = stoppedAs
                job.status = if (stoppedAs == DownloadJobState.Queued) "Paused" else "Stopped"
                job.error = null; job.errorCode = ""
                dirty = true // tracks finished before the stop still need syncing
            }
            error != null -> { job.state = DownloadJobState.Failed; job.error = error.message; job.errorCode = error.code; job.status = "Failed" }
            else -> { job.state = DownloadJobState.Done; job.progress = 100; job.status = "Done"; dirty = true }
        }
        _active.value = null
        notifyChanged(); refreshBatches()
    }

    /** Drops downloader logs older than a week, so they cannot pile up. */
    private fun pruneLogs() {
        val cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        logDir.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    private fun refreshBatches() {
        val snapshot = synchronized(lock) { batches.toList() }
        snapshot.forEach { it.refresh() }
        synchronized(lock) { batches.removeAll { it.isDone } }
    }

    /** Keeps finished history bounded, oldest first, without ever dropping work that has not run. */
    private fun trim() {
        val finished = allJobs.filter { it.isTerminal }
        if (finished.size <= HISTORY_LIMIT) return
        allJobs.removeAll(finished.take(finished.size - HISTORY_LIMIT).toSet())
    }

    private fun notifyChanged() { _jobs.value = synchronized(lock) { allJobs.toList() } }
}
