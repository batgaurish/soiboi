"""Runtime capability probing.

The app needs to answer "can this device actually download?" before showing a
Download tab that would fail. Rather than assume the bundled environment is
intact, every dependency is probed and reported, so the UI can say precisely
what is missing instead of failing at the first download.

The native muxer is worth probing on its own: it is the one component that must
be compiled per-platform, so it is also the one most likely to be absent or
built for the wrong architecture.
"""
import importlib
import os
import platform
import sys

# Pure-Python pieces gamdl needs. Absence of any of these means the bundled
# environment is incomplete rather than the device being unsupported.
REQUIRED_MODULES = [
    "gamdl",
    "mutagen",
    "httpx",
    "m3u8",
    "pywidevine",
    "yt_dlp",
    "click",
]


def _probe(module_name):
    try:
        module = importlib.import_module(module_name)
        return {
            "available": True,
            "version": getattr(module, "__version__", None),
        }
    except Exception as exc:
        return {"available": False, "error": str(exc)}


def probe_native_muxer():
    """gamdl's Rust decrypt-and-mux engine.

    Separated from the pure-Python checks because this is the piece that must be
    cross-compiled per architecture. On Android it has to be an
    aarch64-linux-android build; a manylinux wheel will import-fail here, which
    is exactly the signal we want surfaced rather than swallowed.
    """
    try:
        from gamdl import _ammuxer  # noqa: F401

        return {"available": True, "path": getattr(_ammuxer, "__file__", None)}
    except Exception as exc:
        return {"available": False, "error": str(exc)}


def is_android():
    # Chaquopy reports a normal Linux platform, so the reliable tell is the
    # Android-specific path layout rather than sys.platform.
    return "ANDROID_ROOT" in os.environ or "ANDROID_DATA" in os.environ


def capabilities():
    modules = {name: _probe(name) for name in REQUIRED_MODULES}
    muxer = probe_native_muxer()
    missing = [name for name, info in modules.items() if not info["available"]]

    return {
        "python": sys.version.split()[0],
        "platform": platform.platform(),
        "machine": platform.machine(),
        "android": is_android(),
        "modules": modules,
        "native_muxer": muxer,
        "missing": missing,
        # Downloading needs both the orchestration code and the native engine;
        # either alone is useless.
        "can_download": not missing and muxer["available"],
    }
