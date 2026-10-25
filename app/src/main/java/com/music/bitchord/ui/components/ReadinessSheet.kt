package com.music.bitchord.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.music.bitchord.BitChordApplication
import com.music.bitchord.R
import com.music.bitchord.data.Http
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.PipelineCapabilities
import com.music.bitchord.download.StatusCheck
import com.music.bitchord.download.StatusFix
import com.music.bitchord.download.StatusInputs
import com.music.bitchord.download.StatusLevel
import com.music.bitchord.download.WrapperStage
import com.music.bitchord.download.WrapperState
import com.music.bitchord.download.buildStatusChecks
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Android readiness verification sheet for catalogue downloads.
 *
 * Follows the three-zone hierarchy:
 * 1. Readiness summary card (overall health and blocker tally)
 * 2. Grouped checks (account, wrapper, engine, storage, network)
 * 3. One recovery action (primary action button with retry/recheck)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadinessSheet(
    onDismiss: () -> Unit,
    onSignIn: (() -> Unit)? = null,
    onChooseFolder: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier,
    ) {
        ReadinessSheetContent(
            onDismiss = onDismiss,
            onSignIn = onSignIn,
            onChooseFolder = onChooseFolder,
        )
    }
}

@Composable
fun ReadinessSheetContent(
    onDismiss: () -> Unit,
    onSignIn: (() -> Unit)? = null,
    onChooseFolder: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var refreshTrigger by remember { mutableIntStateOf(0) }

    val downloadQuality by AppSettings.downloadQuality.collectAsStateWithLifecycle()
    val codec = if (downloadQuality.keepsLossless) "alac" else "aac"

    var isProbing by remember { mutableStateOf(true) }
    var probeError by remember { mutableStateOf<String?>(null) }
    var freeBytes by remember { mutableStateOf<Long?>(null) }
    var isOnline by remember { mutableStateOf<Boolean?>(null) }
    var capabilities by remember { mutableStateOf<PipelineCapabilities?>(null) }

    val saveDir = remember(context) { File(context.filesDir, "downloads").apply { mkdirs() } }

    LaunchedEffect(refreshTrigger) {
        isProbing = true
        probeError = null
        try {
            withContext(Dispatchers.IO) {
                val space = runCatching { saveDir.usableSpace }.getOrNull()

                val online = runCatching {
                    val req = okhttp3.Request.Builder()
                        .url("https://music.apple.com/")
                        .head()
                        .build()
                    Http.client.newCall(req).execute().use { res ->
                        res.isSuccessful || res.code in 200..499
                    }
                }.getOrElse { false }

                val caps = runCatching {
                    if (!Python.isStarted()) {
                        Python.start(AndroidPlatform(context.applicationContext))
                    }
                    val py = Python.getInstance()
                    val sysModule = py.getModule("sys")
                    val pyVersion = sysModule.get("version")?.toString()?.split(" ")?.firstOrNull() ?: ""

                    // Probe the real archive bridge entrypoint module
                    val mainModule = py.getModule("soiboi_pipeline.__main__")
                    val handleArchive = runCatching { mainModule.get("handle_archive") }.getOrNull()
                    if (handleArchive == null) {
                        PipelineCapabilities(
                            canDownload = false,
                            pythonVersion = pyVersion,
                            availableCodecs = emptyList(),
                            ready = false,
                            summary = "Archive entrypoint handle_archive not found in soiboi_pipeline.__main__",
                        )
                    } else {
                        // Check downloader dependency import status directly
                        val downloaderModule = runCatching { py.getModule("soiboi_pipeline.downloader") }.getOrNull()
                        val gamdlImportError = downloaderModule?.let { runCatching { it.get("_GAMDL_IMPORT_ERROR") }.getOrNull() }
                            ?.toString()
                            ?.takeIf { it != "None" }

                        // Query pipeline capabilities contract
                        val rawCapsJson = mainModule.callAttr("handle_json", "capabilities", "{}")?.toString()
                        if (rawCapsJson.isNullOrBlank()) {
                            PipelineCapabilities(
                                canDownload = false,
                                pythonVersion = pyVersion,
                                availableCodecs = emptyList(),
                                ready = false,
                                summary = "Empty capability response from soiboi_pipeline",
                            )
                        } else {
                            val json = JSONObject(rawCapsJson)
                            if (json.optString("event") == "error") {
                                val errorMsg = json.optString("message", "Archive capability check failed")
                                PipelineCapabilities(
                                    canDownload = false,
                                    pythonVersion = pyVersion,
                                    availableCodecs = emptyList(),
                                    ready = false,
                                    summary = errorMsg,
                                )
                            } else {
                                val reportedCanDownload = json.optBoolean("can_download", false)
                                val capsPyVersion = json.optString("python", "").ifEmpty { pyVersion }
                                val missingArray = json.optJSONArray("missing")
                                val missingList = mutableListOf<String>()
                                if (missingArray != null) {
                                    for (i in 0 until missingArray.length()) {
                                        val item = missingArray.optString(i)
                                        if (item.isNotEmpty()) missingList.add(item)
                                    }
                                }
                                val nativeMuxer = json.optJSONObject("native_muxer")
                                val muxerAvailable = nativeMuxer?.optBoolean("available", false) ?: false
                                val muxerError = nativeMuxer?.optString("error")

                                val effectiveCanDownload = reportedCanDownload &&
                                    gamdlImportError == null &&
                                    missingList.isEmpty() &&
                                    muxerAvailable

                                val summary = when {
                                    missingList.isNotEmpty() -> "Missing dependencies: ${missingList.joinToString(", ")}"
                                    gamdlImportError != null -> "Downloader unavailable: $gamdlImportError"
                                    !muxerAvailable -> "Native muxer (gamdl._ammuxer) unavailable${if (!muxerError.isNullOrBlank()) ": $muxerError" else ""}"
                                    !effectiveCanDownload -> "Archive pipeline unavailable"
                                    else -> "Ready"
                                }

                                PipelineCapabilities(
                                    canDownload = effectiveCanDownload,
                                    pythonVersion = capsPyVersion,
                                    availableCodecs = if (effectiveCanDownload) listOf("aac") else emptyList(),
                                    ready = effectiveCanDownload,
                                    summary = summary,
                                )
                            }
                        }
                    }
                }.getOrElse { err ->
                    val firstLine = err.message?.lines()?.firstOrNull()?.trim()
                    val msg = if (firstLine.isNullOrEmpty()) {
                        "Archive pipeline unavailable"
                    } else {
                        firstLine
                    }
                    PipelineCapabilities(
                        canDownload = false,
                        summary = msg,
                        ready = false
                    )
                }

                freeBytes = space
                isOnline = online
                capabilities = caps
            }
        } catch (t: Throwable) {
            probeError = t.message ?: "Verification failed"
        } finally {
            isProbing = false
        }
    }

    val cookie = remember(refreshTrigger) {
        runCatching { BitChordApplication.authStore.appleMusicCookie }.getOrNull()
    }
    val cookiesSignedIn = !cookie.isNullOrBlank()

    val inputs = StatusInputs(
        now = Instant.now(),
        codec = codec,
        cookiesSignedIn = cookiesSignedIn,
        cookieExpiry = null,
        wrapperSignedIn = false,
        wrapper = WrapperState(WrapperStage.Unsupported, "Not available in this build. AAC downloads work."),
        capabilities = capabilities,
        chosenFolder = "",
        saveFolder = saveDir.absolutePath,
        freeBytes = freeBytes,
        online = if (isProbing) null else isOnline,
    )

    val rawChecks = remember(inputs) { buildStatusChecks(inputs) }
    val checks = remember(rawChecks, onChooseFolder) {
        rawChecks.map { check ->
            if (check.fix == StatusFix.ChooseFolder && onChooseFolder == null) {
                // Folder selection is unsupported on Android (archive downloads land in the app's own folder).
                // Replace nonfunctional ChooseFolder action with Recheck while preserving honest guidance.
                check.copy(fix = StatusFix.Recheck)
            } else {
                check
            }
        }
    }
    val problemCount = checks.count { it.level == StatusLevel.Problem }
    val warningCount = checks.count { it.level == StatusLevel.Warning }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Download Readiness",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "Pre-flight checks for Apple Music catalogue downloads",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Zone 1: Readiness summary
            ReadinessSummaryCard(
                isProbing = isProbing,
                problemCount = problemCount,
                warningCount = warningCount,
                probeError = probeError,
            )

            // Zone 2: Grouped checks
            Text(
                text = "CHECKS",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
            )

            if (checks.isEmpty() && !isProbing) {
                EmptyChecksView()
            } else {
                checks.forEach { check ->
                    CheckCard(
                        check = check,
                        onFix = { fix ->
                            when (fix) {
                                StatusFix.SignIn -> {
                                    onSignIn?.invoke()
                                    onDismiss()
                                }
                                StatusFix.ChooseFolder -> {
                                    if (onChooseFolder != null) {
                                        onChooseFolder.invoke()
                                    } else {
                                        refreshTrigger++
                                    }
                                }
                                StatusFix.Recheck, StatusFix.StartWrapper, StatusFix.SetUpLossless -> {
                                    refreshTrigger++
                                }
                            }
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))

        // Zone 3: One recovery action
        RecoveryActionBar(
            isProbing = isProbing,
            problemCount = problemCount,
            cookiesSignedIn = cookiesSignedIn,
            onSignIn = onSignIn,
            onDismiss = onDismiss,
            onRecheck = { refreshTrigger++ }
        )
    }
}

@Composable
private fun ReadinessSummaryCard(
    isProbing: Boolean,
    problemCount: Int,
    warningCount: Int,
    probeError: String?,
) {
    val cardShape = RoundedCornerShape(14.dp)
    val cardBorder = MaterialTheme.colorScheme.outlineVariant

    val (icon, tint, title, subtitle) = when {
        isProbing -> Quad(
            null,
            MaterialTheme.colorScheme.primary,
            "Checking readiness…",
            "Validating account, engine, storage, and network reachability."
        )
        probeError != null -> Quad(
            Icons.Rounded.ErrorOutline,
            MaterialTheme.colorScheme.error,
            "Verification error",
            probeError
        )
        problemCount > 0 -> Quad(
            Icons.Rounded.ErrorOutline,
            MaterialTheme.colorScheme.error,
            "$problemCount ${if (problemCount == 1) "issue blocking downloads" else "issues blocking downloads"}",
            "Resolve the required items below to enable catalog downloads."
        )
        warningCount > 0 -> Quad(
            Icons.Rounded.Warning,
            Color(0xFFE5A93C),
            "Ready with $warningCount ${if (warningCount == 1) "warning" else "warnings"}",
            "Downloads can proceed, but review the warnings below."
        )
        else -> Quad(
            Icons.Rounded.CheckCircle,
            MaterialTheme.colorScheme.primary,
            "Ready for downloads",
            "All dependency, account, and storage checks passed."
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, cardBorder, cardShape),
        shape = cardShape,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (isProbing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = tint
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CheckCard(
    check: StatusCheck,
    onFix: (StatusFix) -> Unit,
) {
    val cardShape = RoundedCornerShape(12.dp)
    val cardBorder = MaterialTheme.colorScheme.outlineVariant

    val (badgeText, badgeColor, icon) = when (check.level) {
        StatusLevel.Ok -> Triple("OK", MaterialTheme.colorScheme.primary, Icons.Rounded.CheckCircle)
        StatusLevel.Warning -> Triple("WARNING", Color(0xFFE5A93C), Icons.Rounded.Warning)
        StatusLevel.Problem -> Triple("PROBLEM", MaterialTheme.colorScheme.error, Icons.Rounded.ErrorOutline)
        StatusLevel.Info -> Triple("INFO", MaterialTheme.colorScheme.onSurfaceVariant, Icons.Rounded.Info)
    }

    val remediation = remediationFor(check)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, cardBorder, cardShape),
        shape = cardShape,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = check.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(badgeColor.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = badgeColor
                    )
                }
            }

            Text(
                text = check.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (remediation != null) {
                Text(
                    text = remediation,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (check.level == StatusLevel.Problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            check.fix?.let { fix ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { onFix(fix) },
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(
                            text = fix.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyChecksView() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "No checks available",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "System check inventory could not be loaded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RecoveryActionBar(
    isProbing: Boolean,
    problemCount: Int,
    cookiesSignedIn: Boolean,
    onSignIn: (() -> Unit)?,
    onDismiss: () -> Unit,
    onRecheck: () -> Unit,
) {
    if (problemCount > 0 && !cookiesSignedIn && onSignIn != null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    onSignIn()
                    onDismiss()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Sign in to Apple Music")
            }
            OutlinedButton(
                onClick = onRecheck,
                enabled = !isProbing
            ) {
                if (isProbing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Recheck")
                }
            }
        }
    } else {
        Button(
            onClick = onRecheck,
            enabled = !isProbing,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isProbing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(8.dp))
                Text("Checking…")
            } else {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Check again")
            }
        }
    }
}

private fun remediationFor(check: StatusCheck): String? = when (check.level) {
    StatusLevel.Problem -> when (check.label) {
        "Apple Music account" -> "Sign in with an active Apple Music subscription to authorize catalog downloads."
        "Lossless wrapper" -> "ALAC lossless decryption requires the wrapper. AAC downloads remain functional."
        "Download engine" -> "Verify embedded Python runtime environment and package dependencies."
        "Download folder" -> "Storage space is critically low (< 200 MB). Free up internal storage to proceed."
        "Network" -> "Check your internet connection and verify music.apple.com is reachable."
        else -> "Resolve the underlying condition to restore full functionality."
    }
    StatusLevel.Warning -> when (check.label) {
        "Apple Music account" -> "Session expiring soon. Re-authenticate to prevent download interruption."
        "Download folder" -> "Storage space is low (< 1 GB). Free up space for multi-track album downloads."
        else -> null
    }
    StatusLevel.Ok, StatusLevel.Info -> null
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
