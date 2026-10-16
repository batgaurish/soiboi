package app.soiboi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.soiboi.engine.update.UpdateInfo
import app.soiboi.engine.update.UpdateState
import app.soiboi.ui.AppController
import app.soiboi.ui.components.Banner
import app.soiboi.ui.components.SButton
import app.soiboi.ui.components.SDialog
import app.soiboi.ui.components.SwitchRow
import app.soiboi.ui.components.Space
import app.soiboi.ui.components.Tone
import app.soiboi.ui.components.Txt
import app.soiboi.ui.theme.Glyph
import app.soiboi.ui.theme.Soiboi
import kotlinx.coroutines.launch
import java.io.File

private fun mb(bytes: Long) = if (bytes <= 0) "" else " (${"%.0f".format(bytes / 1_048_576.0)} MB)"

/** The folder the installer put Soiboi in, when this copy came from it. Only then can Soiboi replace itself. */
private fun installDir(): File? = System.getenv("SOIBOI_HOME")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { File(it, "app/bin/soiboi").exists() }

private fun act(app: AppController, scope: kotlinx.coroutines.CoroutineScope, info: UpdateInfo) { scope.launch { app.engine.updater.download(info, installDir()) } }

/** Installs the saved APK, or sends the person to the permission screen first. */
private fun install(app: AppController, file: File) {
    if (!app.platform.installApk(file)) app.toast("Allow installs from Soiboi, then tap Install again")
}

@Composable
fun UpdateDialog(app: AppController) {
    val c = Soiboi.colors
    val state by app.engine.updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    val info = when (val s = state) { is UpdateState.Available -> s.info; is UpdateState.Downloading -> s.info; is UpdateState.ReadyToInstall -> s.info; is UpdateState.Installed -> s.info; else -> null }
    val close = { app.updateOpen = false }
    if (app.web != null || (info == null && state !is UpdateState.Failed)) return
    SDialog(if (state is UpdateState.Installed) "Updated" else "Soiboi ${info?.version ?: ""} is out", close, width = 520.dp, actions = {
        when (val s = state) {
            is UpdateState.Available -> {
                SButton("Skip this version", { app.engine.settings.update { copy(skippedVersion = s.info.version) }; close() }, tone = Tone.Ghost)
                SButton("Later", close, tone = Tone.Ghost)
                SButton("Update now", { act(app, scope, s.info) }, tone = Tone.Primary, glyph = Glyph.Download)
            }
            is UpdateState.Downloading -> SButton("Downloading", {}, enabled = false, loading = true)
            is UpdateState.ReadyToInstall -> { SButton("Later", close, tone = Tone.Ghost); SButton("Install", { install(app, s.file) }, tone = Tone.Primary) }
            is UpdateState.Installed -> SButton("Got it", close, tone = Tone.Primary)
            is UpdateState.Failed -> SButton("Close", close, tone = Tone.Ghost)
            else -> SButton("Close", close, tone = Tone.Ghost)
        }
    }) {
        if (info != null) {
            Txt("You have ${app.engine.platform.appVersion}. ${info.assetName}${mb(info.size)}", style = Soiboi.type.caption, color = c.soft)
            if (info.notes.isNotBlank() && state is UpdateState.Available) LazyColumn(Modifier.fillMaxWidth().height(160.dp)) { item { Txt(info.notes.trim(), style = Soiboi.type.body) } }
        }
        when (val s = state) {
            is UpdateState.Downloading -> Txt(if (s.total > 0) "Downloading ${s.done * 100 / s.total}%" else "Downloading", style = Soiboi.type.bodyStrong)
            is UpdateState.ReadyToInstall -> Txt("Downloaded. Android asks you to confirm the install; your library and settings stay.", style = Soiboi.type.body)
            is UpdateState.Installed -> Txt("The new version is in place. Close Soiboi and open it again to use it.", style = Soiboi.type.body)
            is UpdateState.Failed -> Banner("Update failed", s.message, Glyph.Info, tint = c.danger)
            else -> {}
        }
    }
}

/** Settings controls: the launch check switch, a button to look now, and the result. */
@Composable
fun UpdateControls(app: AppController) {
    val c = Soiboi.colors
    val engine = app.engine
    val state by engine.updater.state.collectAsState()
    val s by engine.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Txt("Soiboi ${engine.platform.appVersion}. Updates come from the project's GitHub releases.", style = Soiboi.type.caption, color = c.soft)
        SwitchRow("Check when Soiboi opens", s.checkForUpdates, { v -> engine.settings.update { copy(checkForUpdates = v) } }, detail = "Asks GitHub for the latest version. Nothing else is sent.")
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            SButton("Check now", { scope.launch { engine.updater.check()?.let { engine.settings.update { copy(skippedVersion = "") }; app.updateOpen = true } } }, compact = true, loading = state is UpdateState.Checking, glyph = Glyph.Refresh)
            (state as? UpdateState.Available)?.let { a -> SButton("Update to ${a.info.version}", { app.updateOpen = true }, tone = Tone.Primary, compact = true, glyph = Glyph.Download) }
        }
        when (val st = state) {
            is UpdateState.UpToDate -> Txt("You have the latest version.", style = Soiboi.type.caption, color = c.ok)
            is UpdateState.Failed -> Txt(st.message, style = Soiboi.type.caption, color = c.danger)
            else -> {}
        }
    }
}
