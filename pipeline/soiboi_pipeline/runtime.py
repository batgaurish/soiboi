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
import shutil
import sys
from importlib import metadata

from . import acoustic
from .protocol import Emit, Event, JsonValue, Payload, done, error, missing_fields

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

# gamdl's Rust decrypt-and-mux engine.
NATIVE_MUXER = "gamdl._ammuxer"

Probe = dict[str, JsonValue]


def _version(module_name: str) -> str | None:
    """The installed distribution's version, from its package metadata.

    Read from metadata rather than `module.__version__`, which not every
    package sets and Click is removing.
    """
    distribution = module_name.split(".")[0].replace("_", "-")
    try:
        return metadata.version(distribution)
    except metadata.PackageNotFoundError:
        return None


def _probe(module_name: str) -> Probe:
    try:
        module = importlib.import_module(module_name)
    except Exception as exc:
        # Anything can fail inside a native import (a wrong-ABI .so raises
        # ImportError, a broken one OSError), and every failure is an answer.
        return {"available": False, "error": str(exc)}
    return {
        "available": True,
        "version": _version(module_name),
        "path": getattr(module, "__file__", None),
    }


def probe_native_muxer() -> Probe:
    """gamdl's Rust engine, probed apart from the pure-Python modules.

    This is the piece that must be cross-compiled per architecture. On Android
    it has to be an aarch64-linux-android build; a manylinux wheel import-fails
    here, and that failure is the signal the UI needs.
    """
    return _probe(NATIVE_MUXER)


def is_android() -> bool:
    # Chaquopy reports a normal Linux platform, so the reliable tell is the
    # Android-specific environment rather than sys.platform.
    return "ANDROID_ROOT" in os.environ or "ANDROID_DATA" in os.environ


def capabilities() -> Event:
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
        # bliss's extension is cross-compiled per ABI like the muxer, so it
        # can be missing on its own; reporting it lets the UI say so instead
        # of silently never producing a sidecar.
        "acoustic_analysis": {"available": acoustic.ANALYSIS_AVAILABLE},
        "missing": missing,
        # Downloading needs both the orchestration code and the native engine;
        # either alone is useless.
        "can_download": not missing and bool(muxer["available"]),
    }


def handle_capabilities(_payload: Payload, _emit: Emit) -> Event:
    return done(**capabilities())


def handle_disk_usage(payload: Payload, _emit: Emit) -> Event:
    """Free and total bytes on the disk holding payload["path"].

    For the app's status panel. Python answers this the same way on Linux and
    Android, where Dart itself has no call for it. The folder may not exist
    yet (downloads create it), so the nearest existing parent is measured.
    """
    if problem := missing_fields(payload, "path"):
        return problem
    probe = os.path.abspath(str(payload["path"]))
    while not os.path.exists(probe) and os.path.dirname(probe) != probe:
        probe = os.path.dirname(probe)
    try:
        usage = shutil.disk_usage(probe)
    except OSError as exc:
        return error("disk_usage_failed", str(exc))
    return done(free=usage.free, total=usage.total, path=probe)
