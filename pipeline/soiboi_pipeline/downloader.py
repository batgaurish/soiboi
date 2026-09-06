"""Download orchestration, in-process.

Ported from download_bridge.py's worker, minus Flask and minus the subprocess.
gamdl's CLI is a click command, so it is invoked directly in this interpreter --
which is what makes the same code work under Chaquopy on Android, where
spawning a `gamdl` binary is not an option.

Progress reporting is the awkward part. gamdl emits no percentages, so like the
original bridge this infers stages from its log output. That means progress is
indicative rather than precise, and the UI should treat it as such: the stage
label is the honest signal, the number is a comfort.
"""
import io
import os
import re
import sys
import contextlib

# Ordered stage markers. The first pattern to appear in a log line wins, so
# order matters: later stages are checked first to avoid an early keyword
# re-matching once the download has moved on.
# gamdl logs failures and then exits 0 -- an unsupported URL, an unavailable
# track or a dead session all "finish with 1 error(s)" rather than raising or
# setting a status. Without watching the log, the app reports a green tick for
# a download that never happened, which is worse than any error message.
_ERROR_LINE = re.compile(r"\[\s*ERROR\s|\bERROR\s+\d\d:\d\d:\d\d")

# Colour codes have to go before matching: structlog writes "[31m[ERROR ...".
_ANSI = re.compile(r"\x1b\[[0-9;]*m")

# Leading log furniture: "[ERROR 00:41:00] [URL 1/1] ". Stripped so the UI
# shows gamdl's explanation and not its progress counters.
_LOG_PREFIX = re.compile(r"^(?:\[[^\]]*\]\s*)+")

_STAGES = [
    (re.compile(r"tagging|writing tags", re.I), 88, "Tagging"),
    (re.compile(r"remux|muxing|decrypt", re.I), 78, "Muxing"),
    (re.compile(r"cover|artwork", re.I), 66, "Saving cover"),
    (re.compile(r"download|fetching|\d+\.\d+%", re.I), 50, "Downloading"),
    (re.compile(r"metadata|track info|resolving", re.I), 28, "Reading metadata"),
]


def _stage_for(line):
    for pattern, progress, label in _STAGES:
        if pattern.search(line):
            return progress, label
    return None


class _StreamTap(io.TextIOBase):
    """Captures gamdl's output and turns interesting lines into progress events.

    gamdl writes yt-dlp progress with carriage returns rather than newlines, so
    splitting on "\\n" alone would buffer the whole download into one line and
    report nothing until it finished.
    """

    def __init__(self, emit):
        self._emit = emit
        self._buffer = ""
        self._last_progress = 0
        # Not "errors": io.TextIOBase already defines that as a read-only
        # attribute, and assigning to it raises.
        self.failures = []

    def write(self, text):
        if not text:
            return 0
        self._buffer += text
        while True:
            index = min(
                (i for i in (self._buffer.find("\n"), self._buffer.find("\r")) if i >= 0),
                default=-1,
            )
            if index < 0:
                break
            line, self._buffer = self._buffer[:index], self._buffer[index + 1 :]
            line = line.strip()
            if line:
                self._handle(line)
        return len(text)

    def _handle(self, line):
        line = _ANSI.sub("", line)
        if _ERROR_LINE.search(line):
            # Keep the message, not the log furniture, so the UI can show
            # gamdl's own explanation rather than "download failed".
            self.failures.append(_LOG_PREFIX.sub("", line).strip() or line)
            return
        stage = _stage_for(line)
        if not stage:
            return
        progress, label = stage
        # Never let progress go backwards; a late-matching keyword shouldn't
        # make the bar jump back and look broken. Equal is dropped too: yt-dlp
        # emits a progress line many times a second and they all map to the
        # same stage, so forwarding each one floods the channel to say nothing.
        if progress <= self._last_progress:
            return
        self._last_progress = progress
        self._emit({"event": "progress", "progress": progress, "status": label})

    def flush(self):
        pass


def _capture_gamdl_logging(tap):
    """Point gamdl's logger at [tap]. Returns True if it took effect.

    gamdl builds its log writer as `CustomOutputWriter(streams=[sys.stdout])`
    -- a mutable default argument, so the real stdout is bound when
    gamdl.cli.utils is first imported, long before any redirect_stdout can
    apply. Redirecting stdout therefore does nothing at all for gamdl's own
    logging: on desktop its raw log lines land in the middle of our JSON
    protocol and break the parse, and on both platforms no progress is
    reported and no failure is noticed.

    Rebinding the default is narrow, and unlike importing gamdl inside the
    redirect it keeps working for the second and later downloads in one
    process -- which is the normal case on Android, where the interpreter
    outlives any single download.
    """
    try:
        from gamdl.cli.utils import CustomOutputWriter

        CustomOutputWriter.__init__.__defaults__ = ([tap],)
        return True
    except Exception:
        # A gamdl upgrade may drop or rename this. Losing progress reporting
        # is survivable; failing the download over it is not.
        return False


def download(
    url,
    cookies_path,
    output_dir,
    temp_dir=None,
    codec="aac",
    emit=None,
    wvd_path=None,
    use_wrapper=False,
    wrapper_url=None,
):
    """Download one Apple Music URL into [output_dir].

    Returns a result dict. Raises nothing on a download failure -- the failure
    is reported in the result, because a caller streaming events wants a final
    event rather than an exception crossing the process boundary.
    """
    emit = emit or (lambda event: None)

    if not os.path.exists(cookies_path) and not use_wrapper:
        return {
            "event": "error",
            "message": "No Apple Music cookies. Sign in from Settings.",
            "code": "no_cookies",
        }

    os.makedirs(output_dir, exist_ok=True)

    # gamdl defaults its temp directory to the working directory and click
    # validates that it is writable. On Android the working directory is "/",
    # so the download fails before it starts with "Directory '.' is not
    # readable"; on desktop it would scatter scratch files wherever the app
    # was launched from. Either way it must be passed explicitly.
    temp_dir = temp_dir or os.path.join(output_dir, ".temp")
    os.makedirs(temp_dir, exist_ok=True)

    args = [
        "-n",  # no interactive prompts
        "-o", output_dir,
        "--temp-path", temp_dir,
        "--song-codec-priority", codec,
        "--overwrite",
    ]
    if not use_wrapper:
        args += ["-c", cookies_path]
    if wvd_path:
        args += ["--wvd-path", wvd_path]
    if use_wrapper:
        args += ["--use-wrapper"]
        if wrapper_url:
            args += ["--wrapper-url", wrapper_url]
    args.append(url)

    emit({"event": "progress", "progress": 8, "status": "Starting"})

    tap = _StreamTap(emit)
    try:
        from gamdl.cli.cli import main as gamdl_main
    except Exception as exc:
        return {
            "event": "error",
            "message": f"Downloader unavailable: {exc}",
            "code": "no_gamdl",
        }

    if not _capture_gamdl_logging(tap):
        emit({
            "event": "progress",
            "progress": 8,
            "status": "Downloading (no progress detail)",
        })

    try:
        # redirect_* catches anything gamdl's dependencies write directly --
        # yt-dlp in particular. gamdl's own logger is handled above, since it
        # does not go through sys.stdout. standalone_mode=False stops click
        # calling sys.exit(), which would take the whole app down on Android.
        with contextlib.redirect_stdout(tap), contextlib.redirect_stderr(tap):
            gamdl_main(args, standalone_mode=False)
    except SystemExit as exc:
        if exc.code not in (0, None):
            return {
                "event": "error",
                "message": f"Downloader exited with status {exc.code}",
                "code": "gamdl_failed",
            }
    except Exception as exc:
        return {"event": "error", "message": str(exc), "code": "gamdl_error"}

    if tap.failures:
        return {
            "event": "error",
            "message": tap.failures[0],
            "code": "gamdl_reported_error",
        }

    emit({"event": "progress", "progress": 100, "status": "Done"})
    return {"event": "done", "output_dir": output_dir}
