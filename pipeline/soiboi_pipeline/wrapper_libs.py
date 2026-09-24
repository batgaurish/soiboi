"""Installing Apple Music's native libraries for the lossless wrapper.

wrapper-v2 decrypts ALAC with Apple Music's own Android libraries. Soiboi
ships none of them. The user downloads the one Apple Music build wrapper-v2
is pinned to, and this module pulls the libraries for this machine's CPU out
of it and checks each against the SHA-256 in wrapper-v2's LIBS_VERSION.json.

Accepts either download APKMirror offers: an `.apkm` bundle (a zip holding
`split_config.<arch>.apk`), or a single `.apk` with the libraries inside.
"""
import hashlib
import io
import json
import os
import zipfile

from .protocol import Emit, Event, Payload, done, error, missing_fields, progress

# APKMirror names a split by ABI with the hyphen swapped for an underscore.
_SPLIT_NAMES = {"x86_64": "split_config.x86_64.apk", "arm64-v8a": "split_config.arm64_v8a.apk"}


class WrongApk(Exception):
    """The file is not the Apple Music build the wrapper needs."""


def _pins(libs_version_path: str, arch: str) -> tuple[dict[str, str], dict]:
    with open(libs_version_path, encoding="utf-8") as f:
        manifest = json.load(f)
    return manifest["libs"][arch], manifest["apple_music"]


def _arch_apk(bundle: zipfile.ZipFile, arch: str) -> zipfile.ZipFile:
    """The zip that holds lib/<arch>/, from a bundle or a plain APK."""
    names = set(bundle.namelist())
    if any(name.startswith(f"lib/{arch}/") for name in names):
        return bundle
    split = _SPLIT_NAMES[arch]
    if split not in names:
        raise WrongApk(f"This download has no {arch} libraries")
    return zipfile.ZipFile(io.BytesIO(bundle.read(split)))


def install(apk_path: str, libs_version_path: str, arch: str, out_dir: str) -> list[str]:
    """Extract and verify every pinned library into [out_dir].

    Nothing is written unless every library is present and matches, so a
    wrong file never leaves a half-installed wrapper behind.
    """
    pins, apple = _pins(libs_version_path, arch)
    try:
        bundle = zipfile.ZipFile(apk_path)
    except zipfile.BadZipFile as exc:
        raise WrongApk("That file is not an APK") from exc

    wanted = f"Apple Music {apple['version']} (build {apple['build']})"
    with bundle:
        apk = _arch_apk(bundle, arch)
        verified: dict[str, bytes] = {}
        for name, expected in pins.items():
            try:
                data = apk.read(f"lib/{arch}/{name}")
            except KeyError as exc:
                raise WrongApk(f"{name} is missing. This needs {wanted}.") from exc
            if hashlib.sha256(data).hexdigest() != expected:
                raise WrongApk(f"{name} does not match. This needs {wanted}.")
            verified[name] = data

    os.makedirs(out_dir, exist_ok=True)
    for name, data in verified.items():
        path = os.path.join(out_dir, name)
        with open(path + ".tmp", "wb") as f:
            f.write(data)
        os.replace(path + ".tmp", path)
    return sorted(verified)


def handle_install(payload: Payload, emit: Emit) -> Event:
    problem = missing_fields(payload, "apk_path", "libs_version", "arch", "out_dir")
    if problem:
        return problem
    emit(progress(20, "Checking the Apple Music file"))
    try:
        installed = install(
            payload["apk_path"], payload["libs_version"], payload["arch"], payload["out_dir"]
        )
    except WrongApk as exc:
        return error("wrong_apk", str(exc))
    except OSError as exc:
        return error("io", str(exc))
    emit(progress(100, "Done"))
    return done(installed=installed)
