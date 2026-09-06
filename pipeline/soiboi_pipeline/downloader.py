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
import asyncio
import contextlib
import io
import multiprocessing
import os
import queue
import re
import sys

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

# A log line always opens with a bracketed level, so anything else following an
# error is the traceback gamdl prints for it.
_LOG_LINE = re.compile(r"^\[")

# The last line of a traceback: "ValueError: something went wrong". This is the
# only part worth showing -- gamdl's own error text is a generic
# 'Error downloading "<title>"' that says nothing about the cause.
_EXCEPTION_LINE = re.compile(r"^([A-Za-z_][\w.]*(?:Error|Exception|Exit)\w*): (.+)$")

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

    def __init__(self, emit, log=None):
        self._emit = emit
        # Everything that passes through is mirrored here. gamdl's own
        # --log-file only records what its logger emits; the traceback that
        # explains a failure is printed separately, and so is anything yt-dlp
        # writes. Without mirroring, a failure on a phone leaves no trace.
        self._log = log
        self._buffer = ""
        self._last_progress = 0
        # Not "errors": io.TextIOBase already defines that as a read-only
        # attribute, and assigning to it raises.
        self.failures = []
        self._in_traceback = False

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
        if self._log is not None:
            try:
                self._log.write(line + "\n")
                self._log.flush()
            except Exception:
                # Logging must never be the reason a download fails.
                self._log = None

        if self._in_traceback:
            if _LOG_LINE.match(line):
                self._in_traceback = False
            else:
                match = _EXCEPTION_LINE.match(line)
                # Later frames replace earlier ones: the last exception in a
                # chain is the one that actually stopped the download.
                if match and self.failures:
                    self.failures[-1] = f"{self.failures[-1]}: {match.group(2)}"
                return

        if _ERROR_LINE.search(line):
            # Keep the message, not the log furniture, so the UI can show
            # gamdl's own explanation rather than "download failed".
            self.failures.append(_LOG_PREFIX.sub("", line).strip() or line)
            self._in_traceback = True
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


def _multiprocessing_works():
    """Whether multiprocessing.Queue can actually be constructed here.

    Android has no POSIX named semaphores, so creating one raises
    "This platform lacks a functioning sem_open implementation". Probing is
    better than checking for Android by name: the same limitation applies
    anywhere sem_open is missing, and a future Android that gains it would
    keep working without a code change.
    """
    try:
        multiprocessing.get_context().Queue().close()
        return True
    except Exception:
        return False


def _patch_ytdlp_to_run_in_thread():
    """Run gamdl's yt-dlp step in a thread rather than a child process.

    gamdl isolates yt-dlp in a subprocess and collects the result through a
    multiprocessing.Queue. Neither is possible on Android, so every download
    failed at the first byte with an OSError about sem_open -- reported to the
    user only as 'Error downloading "<title>"'.

    The worker gamdl runs there is a plain function: it writes a file and puts
    a result on a queue, with no shared state and no reliance on being in
    another process. Running it in a thread with a plain queue is therefore
    equivalent in everything except crash isolation -- and there is nothing to
    isolate on Android, where a hard crash in yt-dlp would take the embedded
    interpreter down either way.

    Returns True if the patch was applied.
    """
    try:
        from gamdl.downloader import base as gamdl_base

        async def _download_ytdlp_async(self, stream_url, download_path):
            result_queue = queue.Queue()
            await asyncio.to_thread(
                gamdl_base._download_ytdlp_process,
                stream_url,
                download_path,
                self.silent,
                result_queue,
            )
            try:
                status, error_repr, error_traceback = result_queue.get_nowait()
            except queue.Empty:
                return
            if status == "error":
                raise RuntimeError(f"yt-dlp failed: {error_repr}\n{error_traceback}")

        gamdl_base.AppleMusicBaseDownloader._download_ytdlp_async = (
            _download_ytdlp_async
        )
        return True
    except Exception:
        return False


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
    log_path=None,
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

    # A log on disk, written by the stream tap. The app has no console on
    # either platform, and gamdl's summary line ('Error downloading
    # "<title>"') omits the exception that explains it -- so without this a
    # failed download is undiagnosable on a phone. gamdl's own --log-file is
    # deliberately not used: it would duplicate every line the tap already
    # mirrors, and it never sees the traceback.
    log_path = log_path or os.path.join(temp_dir, "gamdl.log")
    os.makedirs(os.path.dirname(log_path) or ".", exist_ok=True)

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

    try:
        from gamdl.cli.cli import main as gamdl_main
    except Exception as exc:
        return {
            "event": "error",
            "message": f"Downloader unavailable: {exc}",
            "code": "no_gamdl",
        }

    try:
        log = open(log_path, "a", encoding="utf-8")
    except OSError:
        log = None

    tap = _StreamTap(emit, log=log)

    if not _multiprocessing_works() and not _patch_ytdlp_to_run_in_thread():
        if log is not None:
            log.close()
        return {
            "event": "error",
            "message": (
                "This device cannot run the downloader: it has no working "
                "multiprocessing support and the in-thread fallback could not "
                "be applied."
            ),
            "code": "no_multiprocessing",
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
    finally:
        if log is not None:
            log.close()

    if tap.failures:
        return {
            "event": "error",
            "message": tap.failures[0],
            "code": "gamdl_reported_error",
        }

    emit({"event": "progress", "progress": 100, "status": "Done"})
    return {"event": "done", "output_dir": output_dir, "log_path": log_path}
