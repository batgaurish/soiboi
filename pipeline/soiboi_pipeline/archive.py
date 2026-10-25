"""ArchivePipeline handler for BitChord.

Handles: enqueue, status, cancel, resume, result.
Returns JSON strings conforming to ArchiveProcessProtocol.
"""
import json
import logging
import threading
from typing import Any

from . import downloader

logger = logging.getLogger(__name__)

# In-memory job state tracking
_LOCK = threading.Lock()
_JOBS: dict[str, dict[str, Any]] = {}


def _job_result(job_id: str, state: str, path: str | None = None, message: str | None = None) -> str:
    payload: dict[str, Any] = {"jobId": job_id, "state": state}
    if path is not None:
        payload["path"] = path
    if message is not None:
        payload["message"] = message
    return json.dumps(payload)


def handle_archive(action: str, job_id: str, *args: str) -> str:
    """Handle an ArchivePipeline command.

    Actions:
      enqueue <media_id> <media_type> <output_directory> [format]
      status
      result
      cancel
      resume
    """
    with _LOCK:
        if action == "enqueue":
            media_id = args[0] if len(args) > 0 else ""
            media_type = args[1] if len(args) > 1 else "track"
            output_dir = args[2] if len(args) > 2 else ""
            fmt = args[3] if len(args) > 3 else "aac"

            # Check if downloader dependencies are available
            if downloader._GAMDL_IMPORT_ERROR:
                err_msg = f"Downloader dependencies unavailable: {downloader._GAMDL_IMPORT_ERROR}"
                _JOBS[job_id] = {
                    "jobId": job_id,
                    "state": "FAILED",
                    "path": None,
                    "message": err_msg,
                }
                return _job_result(job_id, "FAILED", message=err_msg)

            _JOBS[job_id] = {
                "jobId": job_id,
                "state": "QUEUED",
                "mediaId": media_id,
                "mediaType": media_type,
                "outputDirectory": output_dir,
                "format": fmt,
                "path": None,
                "message": "queued",
            }
            return _job_result(job_id, "QUEUED", message="queued")

        elif action == "status":
            job = _JOBS.get(job_id)
            if not job:
                return ""
            return _job_result(job_id, job["state"], job.get("path"), job.get("message"))

        elif action == "result":
            job = _JOBS.get(job_id)
            if not job:
                return ""
            # Return result if job has reached a terminal state
            if job["state"] in ("DONE", "FAILED", "CANCELLED"):
                return _job_result(job_id, job["state"], job.get("path"), job.get("message"))
            return ""

        elif action == "cancel":
            job = _JOBS.get(job_id)
            if job:
                job["state"] = "CANCELLED"
                job["message"] = "cancelled"
            else:
                _JOBS[job_id] = {"jobId": job_id, "state": "CANCELLED", "message": "cancelled"}
            return _job_result(job_id, "CANCELLED", message="cancelled")

        elif action == "resume":
            job = _JOBS.get(job_id)
            if job:
                job["state"] = "RUNNING"
                job["message"] = "resumed"
            else:
                _JOBS[job_id] = {"jobId": job_id, "state": "RUNNING", "message": "resumed"}
            return _job_result(job_id, "RUNNING", message="resumed")

        else:
            return _job_result(job_id, "FAILED", message=f"unknown action: {action}")
