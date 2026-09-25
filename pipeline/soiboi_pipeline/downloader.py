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
import shutil
import threading
import time
from collections.abc import Iterator
from dataclasses import dataclass
from typing import TextIO

from . import acoustic, lyrics
from .protocol import Emit, Event, Payload, done, error, ignore, missing_fields
from .protocol import progress, warning

try:
    from gamdl.cli.cli import main as gamdl_main
    from gamdl.cli.utils import CustomOutputWriter
    from gamdl.downloader import base as gamdl_base
    from gamdl.downloader import downloader as gamdl_downloader
    from gamdl.downloader.exceptions import GamdlDownloaderMediaFileExistsError
    from gamdl.interface import interface as gamdl_interface
    from gamdl.interface.types import AppleMusicMedia
except ImportError as exc:  # the bundled environment is incomplete
    gamdl_main = CustomOutputWriter = gamdl_base = gamdl_downloader = None
    gamdl_interface = AppleMusicMedia = None
    _GAMDL_IMPORT_ERROR = str(exc)
else:
    _GAMDL_IMPORT_ERROR = None

logger = logging.getLogger(__name__)

# Set by the `cancel` command, which Android runs on a different thread from
# the download it is stopping. Desktop kills the process instead, but honours
# this too.
_cancel_requested = threading.Event()


class DownloadCancelled(BaseException):
    """Raised inside gamdl to stop a download.

    A BaseException, like KeyboardInterrupt, because gamdl catches Exception
    per track: an ordinary exception would skip one track and carry on with
    the rest of the album.
    """


def _stop_if_cancelled() -> None:
    if _cancel_requested.is_set():
        raise DownloadCancelled()

# Ordered stage markers. The first pattern to appear in a log line wins, so
# order matters: later stages are checked first to avoid an early keyword
# re-matching once the download has moved on.
# gamdl logs failures and then exits 0 -- an unsupported URL, an unavailable
# track or a dead session all "finish with 1 error(s)" rather than raising or
# setting a status. Without watching the log, the app reports a green tick for
# a download that never happened, which is worse than any error message.
# CRITICAL too: that is how gamdl reports an account with no active
# subscription, after which it downloads nothing and still exits 0.
_ERROR_LINE = re.compile(
    r"\[\s*(?:ERROR|CRITICAL)\s|\b(?:ERROR|CRITICAL)\s+\d\d:\d\d:\d\d"
)

# gamdl skips a track it cannot or need not download with a warning, not an
# error: 'Skipping "<title>": <reason>'. A download where every track was
# skipped wrote nothing, and must not end in a green tick.
_SKIP_LINE = re.compile(r'Skipping "(?P<title>.*)": (?P<reason>.+)$')

# A file that is already there is the one skip that is not a failure: with
# overwrite off, it is what makes a retry cheap.
_ALREADY_THERE = "Media file already exists"

# "[Track   3/12 ]": how many tracks the download covers.
_TRACK_OF = re.compile(r"\[Track\s+\d+\s*/\s*(\d+)\s*\]")

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
        # Tracks skipped for a reason other than already being on disk.
        self.skipped: list[str] = []
        # How many tracks the download covers; 1 until gamdl says otherwise.
        self.track_total = 1
        self._in_traceback = False

    def write(self, text: str) -> int:
        # gamdl and yt-dlp write here many times a second while working, which
        # makes this where a cancel is noticed promptly. Each track checks
        # too (see _skip_owned_tracks), so a stop never depends on output.
        _stop_if_cancelled()
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
        total = _TRACK_OF.search(line)
        if total:
            self.track_total = max(self.track_total, int(total.group(1)))
        skip = _SKIP_LINE.search(line)
        if skip:
            if not skip.group("reason").startswith(_ALREADY_THERE):
                self.skipped.append(_LOG_PREFIX.sub("", line).strip())
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
    wrapper_decrypt_port: int | None = None
    # Off is what makes a retry cheap; see download().
    overwrite: bool = False
    # owned_key() of every song already in the library, whatever its codec.
    owned: frozenset[str] = frozenset()

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
            wrapper_decrypt_port=payload.get("wrapper_decrypt_port"),
            overwrite=bool(payload.get("overwrite")),
            owned=frozenset(payload.get("owned") or ()),
        )


def _normalise(value: str) -> str:
    """Same as the app's normaliseForMatch, except that a name with no
    Latin letters or digits (Hindi, Japanese) keeps its own characters
    rather than collapsing to an empty string that matches everything."""
    lowered = value.lower().replace("&", "and")
    latin = re.sub(r"[^a-z0-9]+", "", lowered)
    return latin or re.sub(r"\s+", "", lowered)


# Same as the app's _titleCredits: '(From "Film")', '- From "Film"',
# '[feat. X]' name where a recording comes from, not a different recording.
_TITLE_CREDITS = re.compile(
    r"""\s*[(\[]\s*(?:from\s+["“”'‘’]|feat\.?\s|ft\.?\s|featuring\s)[^)\]]*[)\]]"""
    r"""|\s+-\s+from\s+["“”'‘’].*$""",
    re.IGNORECASE,
)


def bare_title(title: str) -> str:
    """title without soundtrack and featuring credits (bareSongTitle)."""
    bare = _TITLE_CREDITS.sub("", title).strip()
    return bare or title


def owned_key(artist: str, title: str) -> str:
    return f"{_normalise(artist)}|{_normalise(bare_title(title))}"


# The library as the app saw it when this download started. gamdl only skips
# a track whose exact output path exists, so an AAC copy never stopped the
# ALAC download of the same song, and neither did a file named differently.
_owned: frozenset[str] = frozenset()
_owned_patched = False


def _owned_title(media_metadata: dict | None) -> str | None:
    """The track's title if the library already has it, else None."""
    attrs = (media_metadata or {}).get("attributes") or {}
    name = attrs.get("name")
    if name and owned_key(attrs.get("artistName") or "", name) in _owned:
        return name
    return None


def _already_owned(name: str) -> Exception:
    return GamdlDownloaderMediaFileExistsError(f"{name} (already in your library)")


# How much a catalog song looks like a re-release rather than the song on
# its own album. Same order as the app's pickOriginalRelease.
_EDITION = re.compile(
    r"deluxe|anniversary|expanded|remaster|special edition|bonus", re.IGNORECASE
)
_SINGLE_OR_EP = re.compile(r"\s-\s(?:Single|EP)$")
_FROM_CREDIT = re.compile(r"from\s", re.IGNORECASE)
_MAIN_ARTIST = re.compile(r",|&|\bfeat\.?|\bft\.?|\bx\b", re.IGNORECASE)


def _album_attrs(song: dict) -> dict:
    albums = ((song.get("relationships") or {}).get("albums") or {}).get("data")
    return (albums[0].get("attributes") or {}) if albums else {}


def release_cost(song: dict) -> int:
    """0 for the song on its own album; more for compilations, "(From
    "Film")" copies, singles and EPs, deluxe and anniversary editions."""
    attrs = song.get("attributes") or {}
    album = _album_attrs(song)
    album_name = album.get("name") or attrs.get("albumName") or ""
    title = (attrs.get("name") or "").strip()
    cost = 0
    if album.get("isCompilation") or (
        (album.get("artistName") or "").lower() == "various artists"
    ):
        cost += 8
    if bare_title(title) != title and _FROM_CREDIT.search(title):
        cost += 4
    if album.get("isSingle") or _SINGLE_OR_EP.search(album_name):
        cost += 2
    if _EDITION.search(album_name):
        cost += 1
    return cost


def _same_recording(want: dict, have: dict) -> bool:
    """Same song by the same main artist, no more than two seconds apart.

    For search results, which unlike an ISRC match can be a live take or
    another artist's cover under the same name.
    """
    if _normalise(bare_title(want.get("name") or "")) != _normalise(
        bare_title(have.get("name") or "")
    ):
        return False
    main = _normalise(_MAIN_ARTIST.split(want.get("artistName") or "")[0])
    if main and main not in _normalise(have.get("artistName") or ""):
        return False
    a, b = want.get("durationInMillis"), have.get("durationInMillis")
    return a is not None and b is not None and abs(a - b) <= 2000


async def original_release(api, media_id: str, media_metadata: dict) -> dict | None:
    """The same recording on its own album, when [media_id] is a re-release.

    Playlists often hold a film song's compilation copy ("Chai aur Baarish",
    'Iktara (From "Wake Up Sid")'), and the download takes its album, cover
    and track number from the copy it is given. Looks for the recording by
    ISRC and, if that finds nothing better, by a catalog search checked on
    title, artist and duration. Returns the better song's metadata, or None
    to keep [media_id].
    """
    attrs = media_metadata.get("attributes") or {}
    songs_uri = f"/v1/catalog/{api.storefront}/songs"
    full = {"include": "albums", "extend": "extendedAssetUrls"}
    found: dict[str, dict] = {}

    isrc = attrs.get("isrc")
    if isrc:
        answer = await api._amp_request(songs_uri, {"filter[isrc]": isrc, **full})
        found = {song["id"]: song for song in answer.get("data") or []}

    current = found.get(media_id)
    best = min(found.values(), key=release_cost, default=None)
    if current is not None and best is not None and release_cost(best) == 0:
        return best if release_cost(current) > 0 else None

    # No ISRC, or every copy with it is a re-release: search by name.
    name = bare_title(attrs.get("name") or "")
    answer = await api._amp_request(
        f"/v1/catalog/{api.storefront}/search",
        {"term": f"{attrs.get('artistName') or ''} {name}".strip(),
         "types": "songs", "limit": 25},
    )
    hits = (((answer.get("results") or {}).get("songs") or {}).get("data")) or []
    ids = [media_id] + [
        hit["id"] for hit in hits
        if hit["id"] not in found and hit["id"] != media_id
        and _same_recording(attrs, hit.get("attributes") or {})
    ]
    if len(ids) > 1 or current is None:
        answer = await api._amp_request(songs_uri, {"ids": ",".join(ids), **full})
        found.update({song["id"]: song for song in answer.get("data") or []})

    current = found.get(media_id)
    if current is None:
        return None
    # min() keeps the first of equal costs; the current song goes first so a
    # tie never swaps.
    ranked = [current] + [s for i, s in found.items() if i != media_id]
    best = min(ranked, key=release_cost)
    return None if best is current else best


def _album_name(song: dict) -> str:
    return _album_attrs(song).get("name") or (
        (song.get("attributes") or {}).get("albumName") or "?"
    )


def _skip_owned_tracks() -> None:
    """Make gamdl treat a song the library already has as already downloaded.

    Raising gamdl's own "file exists" error keeps its existing handling: a
    warning, not a failure, so the stream tap reports success.

    The check runs as soon as gamdl has the album's or playlist's track list
    (fetched whole, every page, before the first track), not after it has
    fetched each track's details: that fetch took 2-3 seconds per song, so a
    300-song playlist that was mostly owned spent ten minutes skipping before
    it reached a single new track. The check in _download stays for single
    song URLs, whose metadata only arrives with the details.

    Both places also check for a stop, so a stop lands between tracks even
    when gamdl writes nothing.
    """
    global _owned_patched
    if _owned_patched or gamdl_downloader is None or gamdl_interface is None:
        return
    cls = gamdl_downloader.AppleMusicDownloader
    original = cls._download

    async def _download(self, item):
        _stop_if_cancelled()
        name = None if self.overwrite else _owned_title(item.media.media_metadata)
        if name:
            raise _already_owned(name)
        return await original(self, item)

    interface = gamdl_interface.AppleMusicInterface
    original_song_media = interface._get_song_media

    async def _get_song_media(
        self,
        media_id,
        index=None,
        total=None,
        media_metadata=None,
        playlist_metadata=None,
        is_library=False,
    ):
        _stop_if_cancelled()
        # _owned is empty when overwriting, so this never skips a redownload.
        name = _owned_title(media_metadata)
        if name:
            # Shaped like gamdl's own failed lookup: not partial, with an
            # error that its CLI reports as a skip.
            yield AppleMusicMedia(
                media_id=media_id,
                is_library=is_library,
                index=index,
                total=total,
                media_metadata=media_metadata,
                playlist_metadata=playlist_metadata,
                partial=False,
                error=_already_owned(name),
            )
            return
        # Only a playlist's tracks: an album or song link names the release
        # the user asked for, compilation or not.
        if (
            playlist_metadata is not None
            and not is_library
            and (media_metadata or {}).get("type") == "songs"
        ):
            try:
                better = await original_release(
                    self.base.apple_music_api, media_id, media_metadata
                )
            except Exception:
                # A failed lookup only costs the nicer album; download the
                # playlist's copy as before.
                logger.warning("Original release lookup failed", exc_info=True)
                better = None
            if better is not None:
                print(
                    f'Using "{_album_name(better)}" instead of '
                    f'"{_album_name(media_metadata)}" for '
                    f'"{(media_metadata.get("attributes") or {}).get("name")}"'
                )
                media_id, media_metadata = better["id"], better
        async for media in original_song_media(
            self, media_id, index, total, media_metadata, playlist_metadata,
            is_library,
        ):
            yield media

    cls._download = _download
    interface._get_song_media = _get_song_media
    _owned_patched = True


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
        if request.wrapper_decrypt_port:
            # The bundled wrapper listens on a free port chosen at start.
            args += [
                "--wrapper-decrypt-host", "127.0.0.1",
                "--wrapper-decrypt-port", str(request.wrapper_decrypt_port),
            ]
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
    except DownloadCancelled:
        return error("cancelled", "Download stopped")
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
    if tap.skipped and len(tap.skipped) >= tap.track_total:
        return error("gamdl_reported_error", tap.skipped[0])
    if tap.skipped:
        emit(
            warning(
                f"Skipped {len(tap.skipped)} of {tap.track_total} tracks: "
                f"{tap.skipped[0]}"
            )
        )
    return None


def _analyse_output(output_dir: str, emit: Emit, since: float) -> None:
    """Write mood sidecars for what was just downloaded.

    Analysis is a bonus, not a gate: a download that succeeded reports
    success even when its features could not be extracted.
    """
    if not acoustic.ANALYSIS_AVAILABLE:
        return
    try:
        acoustic.analyze_directory(output_dir, emit=emit, since=since)
    except OSError as exc:
        logger.warning("Audio analysis failed in %s", output_dir, exc_info=True)
        emit(warning(f"Audio analysis skipped: {exc}"))


def _discard_partial_files(temp_dir: str, log_path: str) -> None:
    """Remove what a stopped download left behind.

    Finished files are safe: gamdl moves a track into the output folder only
    after muxing and tagging, so everything partial is under the temp folder.
    The log is kept, since it explains the run.
    """
    for entry in os.scandir(temp_dir):
        if entry.path == log_path:
            continue
        if entry.is_dir(follow_symlinks=False):
            shutil.rmtree(entry.path, ignore_errors=True)
        else:
            with contextlib.suppress(OSError):
                os.remove(entry.path)


def request_cancel() -> None:
    """Stop the download in progress at its next log line."""
    _cancel_requested.set()


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

    _cancel_requested.clear()
    started = time.time()
    temp_dir, log_path = _prepare_dirs(request)
    emit(progress(8, "Starting"))

    if not _multiprocessing_works() and not _patch_ytdlp_to_run_in_thread():
        return error(
            "no_multiprocessing",
            "This device cannot run the downloader: it has no working "
            "multiprocessing support and the in-thread fallback could not "
            "be applied.",
        )

    global _owned
    _owned = request.owned
    _skip_owned_tracks()
    with _open_log(log_path) as log:
        failure = _run_gamdl(
            _gamdl_args(request, temp_dir), _StreamTap(emit, log=log), emit
        )
    if failure:
        if failure["code"] == "cancelled":
            _discard_partial_files(temp_dir, log_path)
        return failure

    _analyse_output(request.output_dir, emit, since=started)
    lyrics.fetch_for_directory(
        request.cookies_path,
        request.output_dir,
        emit,
        since=started,
        wrapper_url=request.wrapper_url if request.use_wrapper else None,
    )
    emit(progress(100, "Done"))
    return done(output_dir=request.output_dir, log_path=log_path)


def handle_download(payload: Payload, emit: Emit) -> Event:
    problem = missing_fields(payload, "url", "cookies_path", "output_dir")
    if problem:
        return problem
    return download(DownloadRequest.from_payload(payload), emit)


def handle_cancel(_payload: Payload, _emit: Emit) -> Event:
    request_cancel()
    return done()
