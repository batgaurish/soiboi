package app.soiboi.engine.download

/** The one thing that fixes a failure. Screens turn it into a button. */
enum class FailureFix(val label: String) {
    SignIn("Sign in"),
    Retry("Retry"),
    Skip("Skip"),
    OpenLog("Open log"),
    ChooseFolder("Choose folder"),
    SetUpWrapper("Set up lossless"),
    ChangeQuality("Use AAC"),
    Update("Check for updates"),
}

/** A failure, explained in plain words with the one thing to do about it. */
data class FailureExplanation(
    val id: String,
    val title: String,
    val detail: String,
    val fix: FailureFix,
    /** Whether trying again later is likely to work (dropped connection, rate limit). */
    val temporary: Boolean = false,
    val raw: String = "",
) {
    val known: Boolean get() = id != UNKNOWN_ID
    val sentence: String get() = "$title. $detail"
}

private const val UNKNOWN_ID = "unknown"

private class Entry(
    val id: String,
    val title: String,
    val detail: String,
    val fix: FailureFix,
    val codes: Set<String> = emptySet(),
    patterns: List<String> = emptyList(),
    val temporary: Boolean = false,
) {
    private val regexes = patterns.map { Regex(it, RegexOption.IGNORE_CASE) }
    fun matches(code: String, message: String) = code in codes || regexes.any { it.containsMatchIn(message) }
}

/** First match wins, so specific entries come before broad ones. */
private val catalog = listOf(
    Entry("cancelled", "Stopped", "This download was stopped before it finished.", FailureFix.Retry,
        codes = setOf("cancelled"), patterns = listOf("^Download stopped$")),
    Entry("sign_in_unconfirmed", "Could not reach Apple Music",
        "Your lossless sign-in is saved, but Apple did not confirm it, which usually means there is no connection. Check it and retry.",
        FailureFix.Retry, codes = setOf("wrapper_unconfirmed"),
        patterns = listOf("Apple did not confirm the lossless sign-in"), temporary = true),
    Entry("not_signed_in", "Not signed in to Apple Music",
        "Sign in to Apple Music, or set up the lossless wrapper, before downloading.",
        FailureFix.SignIn, codes = setOf("no_cookies"), patterns = listOf("No Apple Music cookies")),
    Entry("no_subscription", "No active Apple Music subscription",
        "Apple only hands out songs to an account with a subscription. Check it, or sign in with another account.",
        FailureFix.SignIn, codes = setOf("no_subscription"), patterns = listOf("No active Apple Music subscription")),
    Entry("downloader_missing", "The downloader is missing",
        "This copy of Soiboi cannot find its built-in downloader. Update or reinstall the app.",
        FailureFix.Update, codes = setOf("no_runtime", "no_gamdl", "spawn_failed"),
        patterns = listOf("Bundled Python runtime not found", "Downloader unavailable")),
    Entry("device_unsupported", "This device cannot run the downloader",
        "The downloader needs something this device does not provide. Please report it with the log.",
        FailureFix.OpenLog, codes = setOf("no_multiprocessing")),
    Entry("app_bug", "Something went wrong inside Soiboi",
        "This is a bug, not something you did. The log shows the details.",
        FailureFix.OpenLog,
        codes = setOf("bad_request", "bad_json", "unknown_command", "bad_response", "channel")),
    Entry("wrapper_outdated", "The lossless wrapper is out of date",
        "The downloader needs a newer wrapper than this build has. Update Soiboi.",
        FailureFix.Update, patterns = listOf("Unsupported wrapper-v2 API version")),
    Entry("playback_refused", "Apple would not hand over this track",
        "Apple's store refused this one track just now. It usually works on a retry; if it keeps failing, it may not be available for your account.",
        FailureFix.Retry, patterns = listOf("playback_dispatch_failed", "Apple store error"), temporary = true),
    Entry("wrapper_down", "The lossless wrapper is not running",
        "It stopped, or did not start in time. Retrying starts it again; if that fails too, check it under Settings > Lossless.",
        FailureFix.SetUpWrapper, temporary = true, patterns = listOf(
            "Error fetching wrapper", "wrapper did not come up", "wrapper stopped \\(exit code",
            "wrapper.*(Connection refused|ConnectError|All connection attempts failed)",
            "127\\.0\\.0\\.1.*(Connection refused|ConnectError)")),
    Entry("signed_out", "Apple Music sign-in expired",
        "Apple no longer accepts this sign-in. Sign in again, then retry.",
        FailureFix.SignIn, codes = setOf("auth"), patterns = listOf(
            "Status code: 40[13]\\b", "\\b401 Unauthorized\\b", "HTTP Error 401",
            "Wrapper is not authenticated", "Wrapper account info is missing auth tokens",
            "Wrapper (2FA )?login failed", "media-user-token")),
    Entry("rate_limited", "Apple is limiting downloads",
        "Too many requests in a short time. It clears by itself after a few minutes.",
        FailureFix.Retry, temporary = true, patterns = listOf(
            "Status code: 429", "\\b429 Too Many Requests\\b", "HTTP Error 429", "Too Many Requests")),
    Entry("apple_unavailable", "Apple Music had a problem",
        "Apple's servers failed to answer. Try again in a little while.",
        FailureFix.Retry, temporary = true,
        patterns = listOf("Status code: 5\\d\\d", "HTTP Error 5\\d\\d", "Server error '5\\d\\d")),
    Entry("disk_full", "Storage is full",
        "There is no room left in the download folder. Free some space, or choose another folder.",
        FailureFix.ChooseFolder, patterns = listOf("No space left on device", "Errno 28\\b", "ENOSPC")),
    Entry("folder_not_writable", "Cannot write to the download folder",
        "Soiboi is not allowed to save files there. Choose another folder; on Android, allowing All files access also fixes it.",
        FailureFix.ChooseFolder, patterns = listOf(
            "Permission denied", "Errno 13\\b", "Read-only file system", "Errno 30\\b",
            "Operation not permitted", "is not writable")),
    Entry("quality_unavailable", "Not available in lossless",
        "Apple has no ALAC version of this. Switch download quality to AAC to get it.",
        FailureFix.ChangeQuality, patterns = listOf("Requested format is not available")),
    Entry("needs_wrapper", "Needs the lossless wrapper",
        "This can only be decrypted by the lossless wrapper. Set it up, or switch download quality to AAC.",
        FailureFix.SetUpWrapper,
        patterns = listOf("Decryption is not available", "wrapper_api is required for FairPlay")),
    Entry("not_streamable", "Not available on your account",
        "Apple Music will not play this in your region or on your account, so it cannot be downloaded.",
        FailureFix.Skip, patterns = listOf("Media is not streamable")),
    Entry("unsupported_link", "Link not supported",
        "This is not an Apple Music song, album or playlist link that can be downloaded.",
        FailureFix.Skip, patterns = listOf("URL is not valid or supported")),
    Entry("unsupported_media", "Nothing to download here",
        "Soiboi downloads songs, albums and playlists; this link points at something else.",
        FailureFix.Skip, patterns = listOf("Media type is disallowed", "Artist has no media of type")),
    Entry("not_found", "Not found on Apple Music",
        "Apple Music has nothing at this link any more. It may have been removed, or be limited to other regions.",
        FailureFix.Skip, patterns = listOf("Status code: 404", "HTTP Error 404", "\\b404 Not Found\\b")),
    Entry("apple_changed", "Apple Music changed something",
        "The downloader no longer understands the Apple Music website. A newer Soiboi usually fixes this.",
        FailureFix.Update, patterns = listOf("Error finding index\\.js URI", "Error finding token in index\\.js")),
    Entry("network", "Network problem",
        "The connection dropped or timed out. Check the connection, then retry.",
        FailureFix.Retry, codes = setOf("io"), temporary = true, patterns = listOf(
            "Error fetching Apple Music homepage", "Error fetching index\\.js page", "ConnectError",
            "ConnectTimeout", "ReadTimeout", "WriteTimeout", "PoolTimeout", "timed out",
            "Name or service not known", "Temporary failure in name resolution",
            "No address associated with hostname", "getaddrinfo failed", "Network is unreachable",
            "Connection reset", "Connection refused", "Connection aborted", "RemoteProtocolError",
            "Server disconnected", "Failed to establish a new connection", "SSL: ",
            "EOF occurred in violation of protocol", "IncompleteRead", "yt-dlp (HLS|HTTP) download failed")),
    Entry("widevine_rejected", "Apple refused the decryption key",
        "The built-in Widevine device may have been blocked. Use the lossless wrapper, or add your own .wvd file in Settings.",
        FailureFix.SetUpWrapper,
        patterns = listOf("Widevine CDM is not configured", "pywidevine", "InvalidLicense", "SignatureMismatch")),
    Entry("tool_missing", "A download tool is missing",
        "This needs a tool the app does not include. Update Soiboi.",
        FailureFix.Update, patterns = listOf("Required dependency not found")),
    Entry("downloader_crashed", "The downloader stopped unexpectedly",
        "It closed without saying why. Retry; if it happens again, the log shows what went wrong.",
        FailureFix.OpenLog, patterns = listOf("downloader stopped without finishing", "Downloader exited with status")),
)

/** The catalog entry for a failure, or null when the catalog does not know it. */
fun explainFailure(message: String, code: String = ""): FailureExplanation? =
    catalog.firstOrNull { it.matches(code, message) }?.let {
        FailureExplanation(it.id, it.title, it.detail, it.fix, it.temporary, message)
    }

/** Like [explainFailure], but always has an answer; an unknown failure keeps the reported message. */
fun describeFailure(message: String, code: String = ""): FailureExplanation =
    explainFailure(message, code) ?: FailureExplanation(
        UNKNOWN_ID, "Download failed",
        message.trim().ifEmpty { "No reason was given." }, FailureFix.OpenLog, raw = message,
    )

/** Every entry's id, so a test can check each one is reachable. */
val catalogIds: List<String> get() = catalog.map { it.id }
