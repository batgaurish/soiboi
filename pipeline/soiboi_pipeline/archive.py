"""ArchivePipeline handler for BitChord.

Handles: enqueue, status, cancel, resume, result.
Returns JSON strings conforming to ArchiveProcessProtocol.
"""
import json
import logging
import os
import subprocess
import sys
import threading
from typing import Any

from . import downloader

logger = logging.getLogger(__name__)

# In-memory job state tracking
_LOCK = threading.Lock()
_JOBS: dict[str, dict[str, Any]] = {}


def _state_file_for(job_id: str, output_dir: str | None = None) -> str:
    state_dir = os.environ.get("XDG_STATE_HOME") or os.path.expanduser("~/.local/state")
    archive_state_dir = os.path.join(state_dir, "bitchord", "archive_jobs")
    os.makedirs(archive_state_dir, exist_ok=True)
    return os.path.join(archive_state_dir, f"{job_id}.json")


def _save_job_state(job_id: str, data: dict[str, Any]) -> None:
    try:
        path = _state_file_for(job_id, data.get("outputDirectory"))
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f)
    except Exception as exc:
        logger.warning("Could not persist job state for %s: %s", job_id, exc)


def _load_job_state(job_id: str) -> dict[str, Any] | None:
    try:
        path = _state_file_for(job_id)
        if os.path.exists(path):
            with open(path, "r", encoding="utf-8") as f:
                return json.load(f)
    except Exception as exc:
        logger.warning("Could not load job state for %s: %s", job_id, exc)
    return None


def _job_result(job_id: str, state: str, path: str | None = None, message: str | None = None) -> str:
    payload: dict[str, Any] = {"jobId": job_id, "state": state}
    if path is not None:
        payload["path"] = path
    if message is not None:
        payload["message"] = message
    return json.dumps(payload)


def _run_worker(
    job_id: str,
    media_id: str,
    media_type: str,
    output_dir: str,
    fmt: str,
    cookies_path: str | None,
) -> None:
    with _LOCK:
        job = _JOBS.get(job_id) or _load_job_state(job_id)
        if not job or job.get("state") == "CANCELLED":
            return
        job["state"] = "RUNNING"
        job["message"] = "downloading"
        _JOBS[job_id] = job
        _save_job_state(job_id, job)

    try:
        url = media_id if media_id.startswith("http") else f"https://music.apple.com/song/{media_id}"
        if media_type == "album" and not media_id.startswith("http"):
            url = f"https://music.apple.com/album/{media_id}"
        elif media_type == "playlist" and not media_id.startswith("http"):
            url = f"https://music.apple.com/playlist/{media_id}"

        c_path = cookies_path or ""
        if not c_path or not os.path.exists(c_path):
            os.makedirs(output_dir, exist_ok=True)
            c_path = os.path.join(output_dir, "cookies.txt")
            if not os.path.exists(c_path):
                with open(c_path, "w") as f:
                    f.write("")

        def _emit(event: dict[str, Any]) -> None:
            with _LOCK:
                j = _JOBS.get(job_id) or _load_job_state(job_id)
                if j and j.get("state") != "CANCELLED":
                    if event.get("event") == "progress":
                        j["message"] = event.get("stage", "progress")
                        _JOBS[job_id] = j
                        _save_job_state(job_id, j)

        use_lossless = fmt.lower() in ("alac", "lossless")
        req = downloader.DownloadRequest(
            url=url,
            cookies_path=c_path,
            output_dir=output_dir,
            codec="alac" if use_lossless else "aac",
            use_wrapper=use_lossless,
        )
        res = downloader.download(req, _emit)
        with _LOCK:
            j = _JOBS.get(job_id) or _load_job_state(job_id)
            if j and j.get("state") != "CANCELLED":
                if res.get("event") == "error":
                    j["state"] = "FAILED"
                    j["message"] = res.get("message", "download error")
                else:
                    j["state"] = "DONE"
                    j["message"] = "completed"
                    found_path = None
                    if os.path.exists(output_dir):
                        for root, _, files in os.walk(output_dir):
                            for f in files:
                                if f.lower().endswith((".m4a", ".flac", ".mp3", ".alac", ".wav")):
                                    found_path = os.path.join(root, f)
                                    break
                            if found_path:
                                break
                    j["path"] = found_path
                _JOBS[job_id] = j
                _save_job_state(job_id, j)
    except downloader.DownloadCancelled:
        with _LOCK:
            j = _JOBS.get(job_id) or _load_job_state(job_id)
            if j:
                j["state"] = "CANCELLED"
                j["message"] = "cancelled"
                _JOBS[job_id] = j
                _save_job_state(job_id, j)
    except Exception as exc:
        logger.exception("Archive download failed: %s", exc)
        with _LOCK:
            j = _JOBS.get(job_id) or _load_job_state(job_id)
            if j and j.get("state") != "CANCELLED":
                j["state"] = "FAILED"
                j["message"] = str(exc)
                _JOBS[job_id] = j
                _save_job_state(job_id, j)


def handle_archive(action: str, job_id: str, *args: str) -> str:
    """Handle an ArchivePipeline command.

    Actions:
      enqueue <media_id> <media_type> <output_directory> [format] [cookies_path]
      status
      result
      cancel
      resume
      _worker <media_id> <media_type> <output_directory> [format] [cookies_path]
    """
    if action == "_worker":
        media_id = args[0] if len(args) > 0 else ""
        media_type = args[1] if len(args) > 1 else "track"
        output_dir = args[2] if len(args) > 2 else ""
        fmt = args[3] if len(args) > 3 else "aac"
        cookies_path = args[4] if len(args) > 4 else None
        _run_worker(job_id, media_id, media_type, output_dir, fmt, cookies_path)
        return _job_result(job_id, "DONE")

    with _LOCK:
        if action == "enqueue":
            media_id = args[0] if len(args) > 0 else ""
            media_type = args[1] if len(args) > 1 else "track"
            output_dir = args[2] if len(args) > 2 else ""
            fmt = args[3] if len(args) > 3 else "aac"
            cookies_path = args[4] if len(args) > 4 else None

            # Check if downloader dependencies are available
            if downloader._GAMDL_IMPORT_ERROR:
                err_msg = f"Downloader dependencies unavailable: {downloader._GAMDL_IMPORT_ERROR}"
                job_data = {
                    "jobId": job_id,
                    "state": "FAILED",
                    "path": None,
                    "message": err_msg,
                }
                _JOBS[job_id] = job_data
                _save_job_state(job_id, job_data)
                return _job_result(job_id, "FAILED", message=err_msg)

            job_data = {
                "jobId": job_id,
                "state": "QUEUED",
                "mediaId": media_id,
                "mediaType": media_type,
                "outputDirectory": output_dir,
                "format": fmt,
                "path": None,
                "message": "queued",
            }
            _JOBS[job_id] = job_data
            _save_job_state(job_id, job_data)

            is_android = "CHAQUOPY" in os.environ or "ANDROID_ROOT" in os.environ
            if is_android:
                t = threading.Thread(
                    target=_run_worker,
                    args=(job_id, media_id, media_type, output_dir, fmt, cookies_path),
                    daemon=True,
                )
                t.start()
            else:
                worker_started = False
                if sys.executable and os.path.isfile(sys.executable):
                    try:
                        worker_args = [
                            sys.executable,
                            "-m",
                            "soiboi_pipeline",
                            "_worker",
                            job_id,
                            media_id,
                            media_type,
                            output_dir,
                            fmt,
                        ]
                        if cookies_path:
                            worker_args.append(cookies_path)
                        subprocess.Popen(
                            worker_args,
                            start_new_session=True,
                            stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL,
                        )
                        worker_started = True
                    except Exception as exc:
                        logger.warning("Could not spawn detached worker: %s", exc)

                if not worker_started:
                    t = threading.Thread(
                        target=_run_worker,
                        args=(job_id, media_id, media_type, output_dir, fmt, cookies_path),
                        daemon=False,
                    )
                    t.start()

            return _job_result(job_id, "QUEUED", message="queued")

        elif action == "status":
            job = _JOBS.get(job_id) or _load_job_state(job_id)
            if not job:
                return ""
            _JOBS[job_id] = job
            return _job_result(job_id, job["state"], job.get("path"), job.get("message"))

        elif action == "result":
            job = _JOBS.get(job_id) or _load_job_state(job_id)
            if not job:
                return ""
            _JOBS[job_id] = job
            # Return result if job has reached a terminal state
            if job["state"] in ("DONE", "FAILED", "CANCELLED"):
                return _job_result(job_id, job["state"], job.get("path"), job.get("message"))
            return ""

        elif action == "cancel":
            job = _JOBS.get(job_id) or _load_job_state(job_id)
            if job:
                job["state"] = "CANCELLED"
                job["message"] = "cancelled"
            else:
                job = {"jobId": job_id, "state": "CANCELLED", "message": "cancelled"}
            _JOBS[job_id] = job
            _save_job_state(job_id, job)
            downloader.request_cancel()
            return _job_result(job_id, "CANCELLED", message="cancelled")

        elif action == "resume":
            job = _JOBS.get(job_id) or _load_job_state(job_id)
            if job:
                job["state"] = "RUNNING"
                job["message"] = "resumed"
            else:
                job = {"jobId": job_id, "state": "RUNNING", "message": "resumed"}
            _JOBS[job_id] = job
            _save_job_state(job_id, job)
            return _job_result(job_id, "RUNNING", message="resumed")

        else:
            return _job_result(job_id, "FAILED", message=f"unknown action: {action}")
