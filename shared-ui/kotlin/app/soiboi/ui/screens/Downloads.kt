package app.soiboi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.soiboi.engine.core.resolveDownloadDir
import app.soiboi.engine.download.DownloadJob
import app.soiboi.engine.download.DownloadJobState
import app.soiboi.engine.download.FailureFix
import app.soiboi.engine.download.StatusFix
import app.soiboi.engine.download.StatusInputs
import app.soiboi.engine.download.StatusLevel
import app.soiboi.engine.download.TrackState
import app.soiboi.engine.download.buildStatusChecks
import app.soiboi.ui.AppController
import app.soiboi.ui.Route
import app.soiboi.ui.components.EmptyState
import app.soiboi.ui.components.ProgressBar
import app.soiboi.ui.components.SButton
import app.soiboi.ui.components.SDialog
import app.soiboi.ui.components.SIconButton
import app.soiboi.ui.components.SectionTitle
import app.soiboi.ui.components.Space
import app.soiboi.ui.components.Tone
import app.soiboi.ui.components.Txt
import app.soiboi.ui.components.leadShadow
import app.soiboi.ui.components.plural
import app.soiboi.ui.components.press
import app.soiboi.ui.theme.Glyph
import app.soiboi.ui.theme.GlyphIcon
import app.soiboi.ui.theme.Soiboi
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Everything being archived, in one queue that outlives whatever screen started it. The running job leads with a large
 * progress block; failures say what happened in plain words and offer the one fix; the checks panel answers
 * "why can't I download?" one line at a time.
 */
@Composable
fun DownloadsScreen(app: AppController) {
    val c = Soiboi.colors
    val engine = app.engine
    val jobs by engine.downloads.jobs.collectAsState()
    val active by engine.downloads.active.collectAsState()
    val tick by engine.downloads.tick.collectAsState()
    val paused by engine.downloads.paused.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var logJob by remember { mutableStateOf<DownloadJob?>(null) }
    val queued = jobs.filter { it.state == DownloadJobState.Queued }
    val finished = jobs.filter { it.isTerminal }.asReversed()

    LaunchedEffect(active) { app.platform.setDownloading(active != null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        item { Txt("Downloads", Modifier.padding(top = Space.l), style = Soiboi.type.display) }
        item { StatusChecksCard(app) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                SButton("Paste a link", { app.go(Route.Discover) }, tone = Tone.Primary, glyph = Glyph.Link)
                SButton(if (paused) "Resume" else "Pause", { engine.downloads.setPaused(!paused) }, glyph = if (paused) Glyph.Play else Glyph.Stop, enabled = jobs.any { it.isActive })
                SButton("Stop everything", { engine.downloads.stopAll() }, tone = Tone.Ghost, enabled = jobs.any { it.isActive })
            }
        }
        if (jobs.isEmpty()) item { EmptyState("Nothing is downloading", "Paste an Apple Music link, or a playlist from another service, and the songs land here. Finished files appear in your library.", glyph = Glyph.Download) }
        active?.let { j ->
            item {
                val shape = Soiboi.look.shape(22)
                Column(Modifier.fillMaxWidth().leadShadow(shape).background(c.raised, shape).padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Txt("Downloading now", style = Soiboi.type.label, color = c.muted)
                    Txt(j.label, style = Soiboi.type.headline, maxLines = 2)
                    j.subtitle?.let { Txt(it, style = Soiboi.type.body, color = c.soft, maxLines = 1) }
                    ProgressBar(j.progress / 100f + tick * 0f)
                    Row { Txt(j.status.ifEmpty { "Starting" }, Modifier.weight(1f), style = Soiboi.type.caption, color = c.soft); Txt("${j.progress} percent", style = Soiboi.type.caption, color = c.soft) }
                    TrackList(j)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) { SButton("Show the log", { logJob = j }, tone = Tone.Ghost, compact = true); SButton("Stop this one", { engine.downloads.cancel(j) }, tone = Tone.Secondary, compact = true) }
                }
            }
        }
        if (queued.isNotEmpty()) {
            item { SectionTitle("Up next, ${queued.size}") }
            items(queued.take(40)) { j -> JobRow(app, j, onLog = { logJob = it }) }
        }
        if (finished.isNotEmpty()) {
            item { SectionTitle("Finished") { SButton("Clear finished", { engine.downloads.clearFinished() }, tone = Tone.Ghost, compact = true) } }
            items(finished) { j -> JobRow(app, j, onLog = { logJob = it }) }
        }
    }
    logJob?.let { j -> LogDialog(j) { logJob = null } }
}

@Composable
private fun TrackList(job: DownloadJob) {
    val c = Soiboi.colors
    val tracks = job.tracks
    if (tracks.isEmpty()) return
    val done = tracks.count { it.state == TrackState.Done || it.state == TrackState.Skipped }
    Txt("${done} of ${tracks.firstOrNull()?.total ?: tracks.size} songs", style = Soiboi.type.caption, color = c.soft)
    tracks.takeLast(6).forEach { t ->
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(when (t.state) { TrackState.Done -> Glyph.Check; TrackState.Failed -> Glyph.Warning; TrackState.Skipped -> Glyph.Check; TrackState.Downloading -> Glyph.Download }, size = 16.dp, tint = if (t.state == TrackState.Failed) c.danger else c.soft)
            Txt(t.title, Modifier.weight(1f), style = Soiboi.type.caption, maxLines = 1)
            Txt(when (t.state) { TrackState.Done -> "Done"; TrackState.Failed -> t.detail.ifEmpty { "Failed" }; TrackState.Skipped -> if (t.owned) "Already yours" else "Skipped"; TrackState.Downloading -> "Downloading" }, style = Soiboi.type.caption, color = c.muted, maxLines = 1)
        }
    }
}

@Composable
private fun JobRow(app: AppController, j: DownloadJob, onLog: (DownloadJob) -> Unit) {
    val c = Soiboi.colors; val shape = Soiboi.look.shape(14)
    val failure = j.failure
    Column(Modifier.fillMaxWidth().background(c.raised, shape).border(1.5.dp, c.rule, shape).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(when (j.state) { DownloadJobState.Done -> Glyph.Check; DownloadJobState.Failed -> Glyph.Warning; DownloadJobState.Cancelled -> Glyph.Stop; else -> Glyph.Clock }, size = 20.dp, tint = if (j.state == DownloadJobState.Failed) c.danger else c.soft)
            Column(Modifier.weight(1f)) { Txt(j.label, style = Soiboi.type.bodyStrong, maxLines = 1); Txt(listOfNotNull(j.subtitle, j.group).joinToString(" · ").ifEmpty { j.status }, style = Soiboi.type.caption, color = c.soft, maxLines = 1) }
            when (j.state) {
                DownloadJobState.Queued -> SIconButton(Glyph.Close, "Remove ${j.label} from the queue", { app.engine.downloads.cancel(j) }, size = 36.dp, iconSize = 16.dp)
                DownloadJobState.Done, DownloadJobState.Cancelled -> SIconButton(Glyph.Close, "Dismiss", { app.engine.downloads.dismiss(j) }, size = 36.dp, iconSize = 16.dp)
                else -> {}
            }
        }
        if (failure != null) {
            Txt(failure.title, style = Soiboi.type.bodyStrong, color = c.danger)
            Txt(failure.detail, style = Soiboi.type.caption, color = c.soft)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                when (failure.fix) {
                    FailureFix.Retry -> SButton("Try again", { app.engine.downloads.retry(j) }, compact = true, tone = Tone.Primary)
                    FailureFix.SignIn, FailureFix.SetUpWrapper -> SButton(failure.fix.label, { app.go(Route.Settings) }, compact = true, tone = Tone.Primary)
                    FailureFix.ChooseFolder -> SButton("Choose a folder", { app.go(Route.Settings) }, compact = true, tone = Tone.Primary)
                    FailureFix.ChangeQuality -> SButton("Use AAC and retry", { app.engine.settings.update { copy(downloadCodec = "aac") }; app.engine.downloads.retry(j) }, compact = true, tone = Tone.Primary)
                    FailureFix.OpenLog -> SButton("Open the log", { onLog(j) }, compact = true)
                    FailureFix.Skip -> SButton("Dismiss", { app.engine.downloads.dismiss(j) }, compact = true)
                    FailureFix.Update -> SButton("Check for updates", { app.platform.openUrl("https://github.com/batgaurish/soiboi/releases") }, compact = true)
                }
                if (failure.fix != FailureFix.Retry) SButton("Try again", { app.engine.downloads.retry(j) }, compact = true, tone = Tone.Ghost)
                SButton("Log", { onLog(j) }, compact = true, tone = Tone.Ghost)
            }
        }
    }
}

@Composable
private fun LogDialog(job: DownloadJob, onDismiss: () -> Unit) {
    val c = Soiboi.colors
    SDialog("Log for ${job.label}", onDismiss, width = 640.dp) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 380.dp)) { items(job.log) { Txt(it, style = Soiboi.type.caption, color = c.soft) } }
        job.downloaderLogPath?.let { Txt("The downloader's own output is saved at $it", style = Soiboi.type.caption, color = c.muted) }
    }
}


/** "Why can't I download?", one line at a time: account, lossless wrapper, engine, folder, network. */
@Composable
private fun StatusChecksCard(app: AppController) {
    val c = Soiboi.colors; val engine = app.engine
    val settings by engine.settings.state.collectAsState()
    val signed by engine.cookieStore.signedIn.collectAsState()
    val expiry by engine.cookieStore.sessionExpiry.collectAsState()
    val wrapperSigned by engine.wrapper.signedIn.collectAsState()
    val wrapper by engine.wrapper.state.collectAsState()
    val caps by engine.capabilities.collectAsState()
    var refresh by remember { mutableStateOf(0) }
    val extras by produceState<Pair<Long?, Boolean?>>(null to null, refresh, settings.downloadFolder) {
        val free = engine.platform.pipeline.diskUsage(resolveDownloadDir(settings, engine.paths).path)?.first
        val online = reachesApple()
        value = free to online
    }
    val checks = buildStatusChecks(StatusInputs(
        Instant.now(), settings.downloadCodec, signed, expiry, wrapperSigned, wrapper, caps, settings.downloadFolder.trim(),
        resolveDownloadDir(settings, engine.paths).path, extras.first, extras.second,
    ))
    val problems = checks.count { it.level == StatusLevel.Problem }
    var open by remember { mutableStateOf(problems > 0) }
    val shape = Soiboi.look.shape(16)
    Column(Modifier.fillMaxWidth().background(c.raised, shape).border(1.5.dp, c.rule, shape).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(Modifier.fillMaxWidth().press({ open = !open }, "Download readiness", shape = Soiboi.look.shape(8)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            GlyphIcon(if (problems > 0) Glyph.Warning else Glyph.Check, size = 22.dp, tint = if (problems > 0) c.danger else c.ok)
            Txt(if (problems > 0) "${plural(problems, "thing")} stopping downloads" else "Ready to download", Modifier.weight(1f), style = Soiboi.type.bodyStrong)
            GlyphIcon(if (open) Glyph.ChevronUp else Glyph.ChevronDown, size = 18.dp, tint = c.soft)
        }
        if (open) checks.forEach { ch ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.Top) {
                GlyphIcon(when (ch.level) { StatusLevel.Ok -> Glyph.Check; StatusLevel.Problem -> Glyph.Warning; StatusLevel.Warning -> Glyph.Warning; StatusLevel.Info -> Glyph.Info }, size = 18.dp, tint = when (ch.level) { StatusLevel.Ok -> c.ok; StatusLevel.Problem -> c.danger; else -> c.soft })
                Column(Modifier.weight(1f)) { Txt(ch.label, style = Soiboi.type.bodyStrong); Txt(ch.detail, style = Soiboi.type.caption, color = c.soft) }
                ch.fix?.let { f -> SButton(f.label, { when (f) { StatusFix.Recheck -> refresh++; StatusFix.StartWrapper -> { app.scope.launch { engine.wrapper.startForDownload() } }; else -> app.go(Route.Settings) } }, compact = true) }
            }
        }
    }
}

/** Any answer at all from Apple Music's host counts: the question is whether the network gets there, not what the page says. */
private suspend fun reachesApple(): Boolean = app.soiboi.engine.sources.reachable("https://music.apple.com/")
