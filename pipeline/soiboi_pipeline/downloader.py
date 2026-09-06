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
        stage = _stage_for(line)
        if not stage:
            return
        progress, label = stage
        # Never let progress go backwards; a late-matching keyword shouldn't
        # make the bar jump back and look broken.
        if progress < self._last_progress:
            return
        self._last_progress = progress
        self._emit({"event": "progress", "progress": progress, "status": label})

    def flush(self):
        pass


def download(
    url,
    cookies_path,
    output_dir,
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

    args = [
        "-n",  # no interactive prompts
        "-o", output_dir,
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

    try:
        # gamdl logs to stdout/stderr; both are tapped so progress is seen
        # wherever it writes. standalone_mode=False stops click calling
        # sys.exit(), which would take the whole app down on Android.
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

    emit({"event": "progress", "progress": 100, "status": "Done"})
    return {"event": "done", "output_dir": output_dir}
