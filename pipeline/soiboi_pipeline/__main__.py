"""Entry point, shared by both transports.

Desktop spawns this as `python -m soiboi_pipeline <command>`; Android calls
`handle()` directly through Chaquopy. Both paths run the same functions, so a
fix on one platform is a fix on both.

Protocol: one JSON object per line on stdout. Every line is either a progress
event or a terminal result, so the Dart side can parse incrementally and never
has to wait for the process to exit before showing something.
"""
import json
import sys

from . import __version__, downloader, runtime


def _emit(event):
    """One JSON object per line, flushed immediately.

    Flushing matters: without it Python buffers stdout when not attached to a
    terminal, and the app would see nothing until the download finished.
    """
    sys.stdout.write(json.dumps(event) + "\n")
    sys.stdout.flush()


def handle(command, payload=None, emit=None):
    """Run [command]. Returns the terminal result dict.

    Used directly by Chaquopy on Android, where there is no stdout to parse.
    """
    emit = emit or (lambda event: None)
    payload = payload or {}

    if command == "capabilities":
        return {"event": "done", **runtime.capabilities()}

    if command == "version":
        return {"event": "done", "version": __version__}

    if command == "analyze":
        directory = payload.get("directory")
        if not directory:
            return {
                "event": "error",
                "code": "bad_request",
                "message": "Missing: directory",
            }
        from . import acoustic

        if not acoustic.ESSENTIA_AVAILABLE:
            return {
                "event": "error",
                "code": "no_essentia",
                "message": "Acoustic analysis is not available on this device.",
            }
        result = acoustic.analyze_directory(directory, emit=emit)
        return {"event": "done", **result}

    if command == "download":
        missing = [
            key for key in ("url", "cookies_path", "output_dir")
            if not payload.get(key)
        ]
        if missing:
            return {
                "event": "error",
                "code": "bad_request",
                "message": f"Missing: {', '.join(missing)}",
            }
        return downloader.download(
            url=payload["url"],
            cookies_path=payload["cookies_path"],
            output_dir=payload["output_dir"],
            temp_dir=payload.get("temp_dir"),
            log_path=payload.get("log_path"),
            codec=payload.get("codec", "aac"),
            wvd_path=payload.get("wvd_path"),
            use_wrapper=bool(payload.get("use_wrapper")),
            wrapper_url=payload.get("wrapper_url"),
            emit=emit,
        )

    return {"event": "error", "code": "unknown_command", "message": command}


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv:
        _emit({"event": "error", "code": "bad_request", "message": "No command"})
        return 2

    command = argv[0]
    payload = {}
    if len(argv) > 1:
        try:
            payload = json.loads(argv[1])
        except json.JSONDecodeError as exc:
            _emit({"event": "error", "code": "bad_json", "message": str(exc)})
            return 2

    result = handle(command, payload, emit=_emit)
    _emit(result)
    return 0 if result.get("event") != "error" else 1


if __name__ == "__main__":
    sys.exit(main())


def handle_json(command, payload_json, emit_callable=None):
    """JSON-in, JSON-out entry point for the Android bridge.

    Chaquopy marshals Java strings cleanly but not nested dicts, so both sides
    exchange JSON text. [emit_callable] is a Java object whose __call__ takes a
    JSON string; progress is pushed through it as it happens rather than
    accumulating until the download finishes.
    """
    try:
        payload = json.loads(payload_json) if payload_json else {}
    except (TypeError, json.JSONDecodeError) as exc:
        return json.dumps(
            {"event": "error", "code": "bad_json", "message": str(exc)}
        )

    def emit(event):
        if emit_callable is None:
            return
        try:
            emit_callable(json.dumps(event))
        except Exception:
            # A UI-side failure must never abort a download in progress.
            pass

    try:
        result = handle(command, payload, emit=emit)
    except Exception as exc:  # never let an exception cross the JNI boundary
        result = {"event": "error", "code": "pipeline", "message": str(exc)}
    return json.dumps(result)
