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
import logging
import multiprocessing
import os
import queue
import re
from collections.abc import Iterator
from dataclasses import dataclass
from typing import TextIO

from . import acoustic
from .protocol import Emit, Event, Payload, done, error, ignore, missing_fields
from .protocol import progress, warning

try:
    from gamdl.cli.cli import main as gamdl_main
    from gamdl.cli.utils import CustomOutputWriter
    from gamdl.downloader import base as gamdl_base
except ImportError as exc:  # the bundled environment is incomplete
    gamdl_main = CustomOutputWriter = gamdl_base = None
    _GAMDL_IMPORT_ERROR = str(exc)
else:
    _GAMDL_IMPORT_ERROR = None

logger = logging.getLogger(__name__)

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


def _stage_for(line: str) -> tuple[int, str] | None:
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

    def __init__(self, emit: Emit, log: TextIO | None = None):
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

    def write(self, text: str) -> int:
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

    def _handle(self, line: str) -> None:
        line = _ANSI.sub("", line)
        if self._log is not None:
            try:
                self._log.write(line + "\n")
                self._log.flush()
            except OSError:
                # A full disk or a vanished SD card must never be the reason
                # a download fails; stop mirroring and carry on.
                logger.warning("Stopped mirroring the download log", exc_info=True)
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
        percent, label = stage
        # Never let progress go backwards; a late-matching keyword shouldn't
        # make the bar jump back and look broken. Equal is dropped too: yt-dlp
        # emits a progress line many times a second and they all map to the
        # same stage, so forwarding each one floods the channel to say nothing.
        if percent <= self._last_progress:
            return
        self._last_progress = percent
        self._emit(progress(percent, label))

    def flush(self) -> None:
        if self._log is not None:
            self._log.flush()


def _multiprocessing_works() -> bool:
    """Whether multiprocessing.Queue can actually be constructed here.

    Android has no POSIX named semaphores, so creating one raises
    "This platform lacks a functioning sem_open implementation". Probing is
    better than checking for Android by name: the same limitation applies
    anywhere sem_open is missing, and a future Android that gains it would
    keep working without a code change.
    """
    try:
        multiprocessing.get_context().Queue().close()
    except (OSError, ImportError):
        # sem_open missing surfaces as either, depending on the build.
        return False
    return True


def _patch_ytdlp_to_run_in_thread() -> bool:
    """Run gamdl's yt-dlp step in a thread rather than a child process.

    gamdl isolates yt-dlp in a subprocess and collects the result through a
    multiprocessing.Queue. Neither is possible on Android, so every download
    failed at the first byte with an OSError about sem_open, reported to the
    user only as 'Error downloading "<title>"'.

    The worker gamdl runs there is a plain function: it writes a file and puts
    a result on a queue, with no shared state and no reliance on being in
    another process. Running it in a thread with a plain queue is therefore
    equivalent in everything except crash isolation, and there is nothing to
    isolate on Android, where a hard crash in yt-dlp would take the embedded
    interpreter down either way.

    Returns True if the patch was applied.
    """
    worker = getattr(gamdl_base, "_download_ytdlp_process", None)
    downloader_class = getattr(gamdl_base, "AppleMusicBaseDownloader", None)
    if worker is None or downloader_class is None:
        # A gamdl upgrade renamed one of them; the caller reports it.
        return False

    async def _download_ytdlp_async(self, stream_url, download_path):
        result_queue = queue.Queue()
        await asyncio.to_thread(
            worker, stream_url, download_path, self.silent, result_queue
        )
        try:
            status, error_repr, error_traceback = result_queue.get_nowait()
        except queue.Empty:
            return
        if status == "error":
            raise RuntimeError(f"yt-dlp failed: {error_repr}\n{error_traceback}")

    downloader_class._download_ytdlp_async = _download_ytdlp_async
    return True


def _capture_gamdl_logging(tap: _StreamTap) -> bool:
    """Point gamdl's logger at [tap]. Returns True if it took effect.

    gamdl builds its log writer as `CustomOutputWriter(streams=[sys.stdout])`,
    a mutable default argument, so the real stdout is bound when
    gamdl.cli.utils is first imported, long before any redirect_stdout can
    apply. Redirecting stdout therefore does nothing at all for gamdl's own
    logging: on desktop its raw log lines land in the middle of our JSON
    protocol and break the parse, and on both platforms no progress is
    reported and no failure is noticed.

    Rebinding the default is narrow, and unlike importing gamdl inside the
    redirect it keeps working for the second and later downloads in one
    process, which is the normal case on Android, where the interpreter
    outlives any single download.
    """
    init = getattr(CustomOutputWriter, "__init__", None)
    if CustomOutputWriter is None or getattr(init, "__defaults__", None) is None:
        # A gamdl upgrade changed the writer. Losing progress reporting is
        # survivable; failing the download over it is not.
        return False
    init.__defaults__ = ([tap],)
    return True


@dataclass(frozen=True)
class DownloadRequest:
    """One download, as the app asks for it."""

    url: str
    cookies_path: str
    output_dir: str
    temp_dir: str | None = None
    log_path: str | None = None
    codec: str = "aac"
    wvd_path: str | None = None
    use_wrapper: bool = False
    wrapper_url: str | None = None
    # Off is what makes a retry cheap; see download().
    overwrite: bool = False

    @classmethod
    def from_payload(cls, payload: Payload) -> "DownloadRequest":
        return cls(
            url=payload["url"],
            cookies_path=payload["cookies_path"],
            output_dir=payload["output_dir"],
            temp_dir=payload.get("temp_dir"),
            log_path=payload.get("log_path"),
            codec=payload.get("codec") or "aac",
            wvd_path=payload.get("wvd_path"),
            use_wrapper=bool(payload.get("use_wrapper")),
            wrapper_url=payload.get("wrapper_url"),
            overwrite=bool(payload.get("overwrite")),
        )


def _prepare_dirs(request: DownloadRequest) -> tuple[str, str]:
    """Create the output, temp and log locations. Returns (temp_dir, log_path).

    gamdl defaults its temp directory to the working directory and click
    validates that it is writable. On Android the working directory is "/",
    so the download fails before it starts with "Directory '.' is not
    readable"; on desktop it would scatter scratch files wherever the app was
    launched from. Either way it must be passed explicitly.
    """
    os.makedirs(request.output_dir, exist_ok=True)
    temp_dir = request.temp_dir or os.path.join(request.output_dir, ".temp")
    os.makedirs(temp_dir, exist_ok=True)

    # The app has no console on either platform, and gamdl's summary line
    # ('Error downloading "<title>"') omits the exception that explains it,
    # so the stream tap mirrors everything here. gamdl's own --log-file is
    # not used: it would duplicate every line the tap mirrors, and it never
    # sees the traceback.
    log_path = request.log_path or os.path.join(temp_dir, "gamdl.log")
    os.makedirs(os.path.dirname(log_path) or ".", exist_ok=True)
    return temp_dir, log_path


def _gamdl_args(request: DownloadRequest, temp_dir: str) -> list[str]:
    args = [
        "-n",  # no interactive prompts
        "-o", request.output_dir,
        "--temp-path", temp_dir,
        "--song-codec-priority", request.codec,
    ]
    if request.overwrite:
        args.append("--overwrite")
    if request.use_wrapper:
        args.append("--use-wrapper")
        if request.wrapper_url:
            args += ["--wrapper-url", request.wrapper_url]
    else:
        args += ["-c", request.cookies_path]
    if request.wvd_path:
        args += ["--wvd-path", request.wvd_path]
    args.append(request.url)
    return args


@contextlib.contextmanager
def _open_log(path: str) -> Iterator[TextIO | None]:
    try:
        log = open(path, "a", encoding="utf-8")
    except OSError:
        logger.warning("Cannot write the download log at %s", path, exc_info=True)
        yield None
        return
    with log:
        yield log


def _run_gamdl(args: list[str], tap: _StreamTap, emit: Emit) -> Event | None:
    """Run gamdl's CLI in-process. Returns an error event, or None on success."""
    if not _capture_gamdl_logging(tap):
        emit(progress(8, "Downloading (no progress detail)"))
    try:
        # redirect_* catches anything gamdl's dependencies write directly,
        # yt-dlp in particular; gamdl's own logger is captured above.
        # standalone_mode=False stops click calling sys.exit(), which would
        # take the whole app down on Android.
        with contextlib.redirect_stdout(tap), contextlib.redirect_stderr(tap):
            gamdl_main(args, standalone_mode=False)
    except SystemExit as exc:
        if exc.code not in (0, None):
            return error("gamdl_failed", f"Downloader exited with status {exc.code}")
    except Exception as exc:
        # gamdl can raise almost anything from deep inside its dependencies;
        # every one of them is a failed download with a message worth showing.
        logger.warning("gamdl raised", exc_info=True)
        return error("gamdl_error", str(exc))
    # gamdl logs failures and then exits 0, so the log is the only signal.
    if tap.failures:
        return error("gamdl_reported_error", tap.failures[0])
    return None


def _analyse_output(output_dir: str, emit: Emit) -> None:
    """Write mood sidecars for what was just downloaded.

    Analysis is a bonus, not a gate: a download that succeeded reports
    success even when its features could not be extracted.
    """
    if not acoustic.ANALYSIS_AVAILABLE:
        return
    try:
        acoustic.analyze_directory(output_dir, emit=emit)
    except OSError as exc:
        logger.warning("Audio analysis failed in %s", output_dir, exc_info=True)
        emit(warning(f"Audio analysis skipped: {exc}"))


def download(request: DownloadRequest, emit: Emit = ignore) -> Event:
    """Download one Apple Music URL into the request's output directory.

    Returns a terminal event and raises nothing on a download failure: a
    caller streaming events wants a final event, not an exception crossing the
    process boundary.

    There is no byte-level resume to be had: gamdl hands yt-dlp
    `overwrites: True` and drives HttpFD / HlsFD directly with no
    `continuedl`, so an interrupted file always restarts from zero. What *is*
    resumable is the track. With overwrite off, gamdl skips any item whose
    final path already exists (a warning, not an error), so re-running a
    fifty-track playlist that died at track forty downloads the last ten.
    Files reach that final path only after muxing and tagging, so a
    half-written download never counts as present.
    """
    if not request.use_wrapper and not os.path.exists(request.cookies_path):
        return error("no_cookies", "No Apple Music cookies. Sign in from Settings.")
    if gamdl_main is None:
        return error("no_gamdl", f"Downloader unavailable: {_GAMDL_IMPORT_ERROR}")

    temp_dir, log_path = _prepare_dirs(request)
    emit(progress(8, "Starting"))

    if not _multiprocessing_works() and not _patch_ytdlp_to_run_in_thread():
        return error(
            "no_multiprocessing",
            "This device cannot run the downloader: it has no working "
            "multiprocessing support and the in-thread fallback could not "
            "be applied.",
        )

    with _open_log(log_path) as log:
        failure = _run_gamdl(
            _gamdl_args(request, temp_dir), _StreamTap(emit, log=log), emit
        )
    if failure:
        return failure

    _analyse_output(request.output_dir, emit)
    emit(progress(100, "Done"))
    return done(output_dir=request.output_dir, log_path=log_path)


def handle_download(payload: Payload, emit: Emit) -> Event:
    problem = missing_fields(payload, "url", "cookies_path", "output_dir")
    if problem:
        return problem
    return download(DownloadRequest.from_payload(payload), emit)
