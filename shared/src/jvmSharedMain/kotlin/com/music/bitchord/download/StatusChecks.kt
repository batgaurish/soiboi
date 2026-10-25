package com.music.bitchord.download

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

enum class StatusLevel { Ok, Warning, Problem, Info }

enum class StatusFix(val label: String) {
    SignIn("Sign in"),
    SetUpLossless("Set up"),
    StartWrapper("Try again"),
    ChooseFolder("Choose folder"),
    Recheck("Check again")
}

data class StatusCheck(
    val label: String,
    val level: StatusLevel,
    val detail: String,
    val fix: StatusFix? = null
)

data class PipelineCapabilities(
    val canDownload: Boolean,
    val pythonVersion: String = "",
    val availableCodecs: List<String> = emptyList(),
    val ready: Boolean = true,
    val summary: String = ""
)

data class StatusInputs(
    val now: Instant,
    val codec: String,
    val cookiesSignedIn: Boolean,
    val cookieExpiry: Instant?,
    val wrapperSignedIn: Boolean,
    val wrapper: WrapperState,
    val capabilities: PipelineCapabilities?,
    val chosenFolder: String,
    val saveFolder: String,
    val freeBytes: Long?,
    val online: Boolean?,
)

private const val LOW_SPACE = 1024L * 1024 * 1024
private const val OUT_OF_SPACE = 200L * 1024 * 1024
private val EXPIRY_SOON: Duration = Duration.ofDays(7)

fun buildStatusChecks(i: StatusInputs): List<StatusCheck> = listOf(
    account(i),
    wrapper(i),
    engine(i),
    folder(i),
    network(i)
)

private fun date(d: Instant) = d.atZone(ZoneId.systemDefault()).toLocalDate().toString()

private fun account(i: StatusInputs): StatusCheck {
    val label = "Apple Music account"
    if (i.wrapperSignedIn) {
        val account = i.wrapper.account
        return StatusCheck(
            label,
            StatusLevel.Ok,
            "Lossless sign-in${account?.let { " as $it" } ?: ""}${if (i.cookiesSignedIn) ", browser sign-in as a fallback" else ""}"
        )
    }
    if (!i.cookiesSignedIn) {
        return StatusCheck(
            label,
            StatusLevel.Problem,
            "Not signed in. Downloading needs an Apple Music subscription.",
            StatusFix.SignIn
        )
    }
    val expiry = i.cookieExpiry ?: return StatusCheck(label, StatusLevel.Ok, "Browser sign-in")
    return when {
        !expiry.isAfter(i.now) -> StatusCheck(
            label,
            StatusLevel.Problem,
            "Browser sign-in expired on ${date(expiry)}",
            StatusFix.SignIn
        )
        Duration.between(i.now, expiry) < EXPIRY_SOON -> StatusCheck(
            label,
            StatusLevel.Warning,
            "Browser sign-in expires on ${date(expiry)}",
            StatusFix.SignIn
        )
        else -> StatusCheck(label, StatusLevel.Ok, "Browser sign-in, expires ${date(expiry)}")
    }
}

private fun wrapper(i: StatusInputs): StatusCheck {
    val label = "Lossless wrapper"
    val stage = i.wrapper.stage
    if (stage == WrapperStage.Unsupported) {
        return StatusCheck(label, StatusLevel.Info, "Not available in this build. AAC downloads work.")
    }
    val needed = i.codec == "alac" || (i.wrapperSignedIn && !i.cookiesSignedIn)
    val msg = i.wrapper.message
    val (level, detail, fix) = when (stage) {
        WrapperStage.Ready -> Triple(StatusLevel.Ok, "Running", null)
        WrapperStage.Stopped -> if (i.wrapperSignedIn) {
            Triple(StatusLevel.Ok, "Starts when a download needs it", null)
        } else {
            Triple(StatusLevel.Problem, "Not signed in", StatusFix.SetUpLossless)
        }
        WrapperStage.Starting -> Triple(StatusLevel.Info, "Starting…", null)
        WrapperStage.NeedsLibraries -> Triple(StatusLevel.Problem, "Needs the Apple Music app file", StatusFix.SetUpLossless)
        WrapperStage.SignedOut -> if (i.wrapperSignedIn) {
            Triple(StatusLevel.Warning, msg ?: "Apple did not confirm the sign-in", StatusFix.StartWrapper)
        } else {
            Triple(StatusLevel.Problem, "Needs sign-in", StatusFix.SetUpLossless)
        }
        WrapperStage.NeedsCode -> Triple(StatusLevel.Problem, "Needs sign-in", StatusFix.SetUpLossless)
        WrapperStage.Failed -> Triple(StatusLevel.Problem, "Did not start${msg?.let { ": $it" } ?: ""}", StatusFix.StartWrapper)
        WrapperStage.Unsupported -> Triple(StatusLevel.Info, "", null)
    }
    if (!needed && level == StatusLevel.Problem) {
        return StatusCheck(label, StatusLevel.Info, "Only needed for ALAC. $detail.", fix)
    }
    return StatusCheck(label, level, detail, fix)
}

private fun engine(i: StatusInputs): StatusCheck {
    val label = "Download engine"
    val caps = i.capabilities ?: return StatusCheck(label, StatusLevel.Info, "Checking…")
    if (caps.canDownload) {
        return StatusCheck(
            label,
            StatusLevel.Ok,
            if (caps.pythonVersion.isEmpty()) "Ready" else "Ready (Python ${caps.pythonVersion})"
        )
    }
    return StatusCheck(label, StatusLevel.Problem, caps.summary.ifEmpty { "Engine unavailable" }, StatusFix.Recheck)
}

private fun size(bytes: Long): String {
    val gb = 1024L * 1024 * 1024
    return if (bytes >= gb) "%.1f GB".format(bytes.toDouble() / gb) else "${Math.round(bytes / (1024.0 * 1024))} MB"
}

private fun folder(i: StatusInputs): StatusCheck {
    val label = "Download folder"
    val where = if (i.chosenFolder.isEmpty()) "The app's own folder" else i.saveFolder
    if (i.chosenFolder.isNotEmpty() && i.saveFolder != i.chosenFolder) {
        return StatusCheck(
            label,
            StatusLevel.Warning,
            "${i.chosenFolder} can't be written to, so downloads go to the app's own folder",
            StatusFix.ChooseFolder
        )
    }
    val free = i.freeBytes ?: return StatusCheck(label, StatusLevel.Ok, where)
    return when {
        free < OUT_OF_SPACE -> StatusCheck(label, StatusLevel.Problem, "$where: only ${size(free)} free", StatusFix.ChooseFolder)
        free < LOW_SPACE -> StatusCheck(label, StatusLevel.Warning, "$where: ${size(free)} free, enough for a few albums", StatusFix.ChooseFolder)
        else -> StatusCheck(label, StatusLevel.Ok, "$where: ${size(free)} free")
    }
}

private fun network(i: StatusInputs): StatusCheck {
    val label = "Network"
    return when (i.online) {
        null -> StatusCheck(label, StatusLevel.Info, "Checking…")
        true -> StatusCheck(label, StatusLevel.Ok, "Apple Music is reachable")
        false -> StatusCheck(label, StatusLevel.Problem, "Can't reach Apple Music. Check the connection.", StatusFix.Recheck)
    }
}
