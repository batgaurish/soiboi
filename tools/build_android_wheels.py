"""Builds the local wheel repository the Android build installs from.

Four packages cannot be installed from PyPI as-is under Chaquopy, and each
needs a different repair:

  gamdl         published only as platform wheels whose native `_ammuxer`
                extension has no Android build. We repackage it once per
                ABI, embedding the extension cross-compiled by
                tools/build_android_muxer.sh, so Chaquopy's pip resolves the
                right one per architecture and the module imports normally.

  bliss_analyze not published at all -- it's our own PyO3 wrapper crate
                (pipeline/native/bliss_analyze) for mood analysis. Packaged
                the same way as `_ammuxer`: one wheel per ABI, each holding
                just the extension cross-compiled by
                tools/build_android_bliss.sh, imported as a bare
                `_bliss_analyze` module (no enclosing package, unlike
                gamdl's `_ammuxer`, since it has no Python wrapper to sit
                alongside).

  pywidevine    pins pycryptodome>=3.23, but Chaquopy's native-wheel
                repository tops out at 3.21. pywidevine only uses
                AES/RSA/CMAC/SHA APIs that have been stable for years, so
                the pin is relaxed rather than cross-compiling a newer
                pycryptodome.

  construct     pinned exactly at 2.8.8 by pymp4, and that release predates
                wheels entirely. It is pure Python, so building a wheel from
                the sdist is enough.

Everything else in the tree resolves normally from PyPI or Chaquopy's own
repository. Run via tools/build_android_pipeline.sh.
"""
import argparse
import base64
import csv
import hashlib
import io
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile
from pathlib import Path

GAMDL_VERSION = "3.8.5"
BLISS_ANALYZE_VERSION = "0.1.0"  # must match pipeline/native/bliss_analyze/Cargo.toml
# Chaquopy tags its own native wheels android_21_<abi>, so ours must match for
# pip to consider them compatible. 21 is the tag, not a minSdk claim.
ANDROID_ABIS = {"arm64-v8a": "android_21_arm64_v8a", "x86_64": "android_21_x86_64"}
PYTHON_TAG = "cp312-cp312"

# gamdl pins newer versions of two native packages than Chaquopy's prebuilt
# repository carries. Cross-compiling either for Android is a large amount of
# work for no behavioural gain, so the pins are relaxed to what Chaquopy has,
# with the check being what gamdl actually calls:
#
#   pillow  only Image.open/convert/save on cover art -- unchanged for years.
#   pycryptodome  reached through pywidevine, which uses AES/RSA/CMAC/SHA.
#
# Revisit if gamdl starts using APIs newer than these floors.
RELAXED_PINS = {
    "pillow": "pillow>=11.0.0",
    "pycryptodome": "pycryptodome<4.0.0,>=3.21.0",
}


def relax(requirement):
    name = re.split(r"[<>=!~\\[; ]", requirement, maxsplit=1)[0].strip().lower()
    return RELAXED_PINS.get(name, requirement)
PYWIDEVINE_VERSION = "1.9.0"
CONSTRUCT_VERSION = "2.8.8"


def run(*args):
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL)


def _record_line(name, data):
    digest = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b"=")
    return [name, "sha256=" + digest.decode(), len(data)]


def write_wheel(out_dir, dist, version, files, metadata, tag="py3-none-any"):
    """Assemble a wheel from {archive name: bytes}."""
    distinfo = f"{dist}-{version}.dist-info"
    purelib = tag.endswith("-any")
    payload = dict(files)
    payload[f"{distinfo}/METADATA"] = metadata
    payload[f"{distinfo}/WHEEL"] = (
        "Wheel-Version: 1.0\nGenerator: soiboi\n"
        f"Root-Is-Purelib: {str(purelib).lower()}\nTag: {tag}\n"
    ).encode()

    records = [_record_line(name, data) for name, data in payload.items()]
    records.append([f"{distinfo}/RECORD", "", ""])
    buf = io.StringIO()
    csv.writer(buf, lineterminator="\n").writerows(records)
    payload[f"{distinfo}/RECORD"] = buf.getvalue().encode()

    path = out_dir / f"{dist}-{version}-{tag}.whl"
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as zf:
        for name, data in payload.items():
            zf.writestr(name, data)
    print(f"    {path.name}")
    return path


def sdist(work, name, version):
    """Download and unpack an sdist, returning its root directory."""
    root = work / f"{name}-{version}"
    if not root.exists():
        run(sys.executable, "-m", "pip", "download", f"{name}=={version}",
            "--no-deps", "--no-binary", ":all:", "-d", str(work))
        archive = next(work.glob(f"{name}-{version}.tar.gz"))
        with tarfile.open(archive) as tf:
            tf.extractall(work)
    return root


def build_gamdl(work, out_dir, native_dir):
    """Repackage gamdl once per ABI, embedding the cross-compiled extension."""
    root = sdist(work, "gamdl", GAMDL_VERSION)
    pyproject = (root / "pyproject.toml").read_text()

    # The dependency list is authoritative; reading it keeps this script
    # correct across gamdl upgrades instead of hardcoding a stale copy.
    block = re.search(r"^dependencies = \[(.*?)^\]", pyproject, re.S | re.M).group(1)
    requires = [relax(r) for r in re.findall(r'"([^"]+)"', block)]

    files = {}
    for path in sorted((root / "gamdl").rglob("*.py")):
        rel = path.relative_to(root)
        # The ammuxer/ directory is the Rust crate (and its multi-gigabyte
        # build tree); only ammuxer.py, its Python wrapper, belongs here.
        if "ammuxer/" in rel.as_posix() or "__pycache__" in rel.as_posix():
            continue
        files[rel.as_posix()] = path.read_bytes()
    if not files:
        raise SystemExit("no gamdl sources found")

    metadata = (
        "Metadata-Version: 2.1\n"
        f"Name: gamdl\nVersion: {GAMDL_VERSION}\n"
        "Summary: Apple Music downloader (Android repackage, native muxer via jniLibs)\n"
        "Requires-Python: >=3.10\n"
        + "".join(f"Requires-Dist: {r}\n" for r in requires)
    ).encode()

    for abi, platform_tag in ANDROID_ABIS.items():
        so = native_dir / abi / "_ammuxer.so"
        if not so.exists():
            raise SystemExit(
                f"missing {so}; run tools/build_android_muxer.sh first"
            )
        # Plain ".so" is in importlib's EXTENSION_SUFFIXES, so no ABI suffix
        # is needed in the name -- and Chaquopy extracts it on first import.
        per_abi = dict(files, **{"gamdl/_ammuxer.so": so.read_bytes()})
        write_wheel(out_dir, "gamdl", GAMDL_VERSION, per_abi, metadata,
                    tag=f"{PYTHON_TAG}-{platform_tag}")


def build_bliss_analyze(out_dir, native_dir):
    """Package the cross-compiled bliss-audio extension, one wheel per ABI.

    Unlike gamdl there's no surrounding pure-Python package -- the wheel is
    just the `.so` at its root, importable as a bare `_bliss_analyze` module.
    """
    metadata = (
        "Metadata-Version: 2.1\n"
        f"Name: bliss-analyze\nVersion: {BLISS_ANALYZE_VERSION}\n"
        "Summary: bliss-audio mood analysis (Android repackage, jniLibs extension)\n"
        "Requires-Python: >=3.10\n"
    ).encode()

    for abi, platform_tag in ANDROID_ABIS.items():
        so = native_dir / abi / "_bliss_analyze.so"
        if not so.exists():
            raise SystemExit(
                f"missing {so}; run tools/build_android_bliss.sh first"
            )
        files = {"_bliss_analyze.so": so.read_bytes()}
        write_wheel(out_dir, "bliss_analyze", BLISS_ANALYZE_VERSION, files,
                    metadata, tag=f"{PYTHON_TAG}-{platform_tag}")


def build_pywidevine(work, out_dir):
    """Copy the published wheel through, relaxing the pycryptodome pin."""
    run(sys.executable, "-m", "pip", "download",
        f"pywidevine=={PYWIDEVINE_VERSION}", "--no-deps", "-d", str(work))
    source = next(work.glob(f"pywidevine-{PYWIDEVINE_VERSION}-*.whl"))

    files, metadata = {}, None
    with zipfile.ZipFile(source) as zf:
        for info in zf.infolist():
            data = zf.read(info.filename)
            if info.filename.endswith(".dist-info/METADATA"):
                metadata = "".join(
                    f"Requires-Dist: {relax(line[len('Requires-Dist: '):])}\n"
                    if line.startswith("Requires-Dist: ") else line + "\n"
                    for line in data.decode().splitlines()
                ).encode()
            elif info.filename.endswith((".dist-info/RECORD", ".dist-info/WHEEL")):
                continue  # regenerated
            else:
                files[info.filename] = data
    if metadata is None:
        raise SystemExit("pywidevine wheel had no METADATA")
    return write_wheel(out_dir, "pywidevine", PYWIDEVINE_VERSION, files, metadata)


def build_construct(work, out_dir):
    """construct 2.8.8 predates wheels; build one from the sdist."""
    root = sdist(work, "construct", CONSTRUCT_VERSION)
    run(sys.executable, "-m", "pip", "wheel", str(root), "--no-deps", "-w", str(out_dir))
    built = next(out_dir.glob(f"construct-{CONSTRUCT_VERSION}-*.whl"))
    print(f"    {built.name}")
    return built


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True, help="local wheel repository")
    parser.add_argument("--native", required=True,
                        help="directory of per-ABI _ammuxer.so and _bliss_analyze.so builds")
    parser.add_argument("--work", default=None, help="scratch directory")
    args = parser.parse_args()

    out_dir = Path(args.out)
    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True)
    work = Path(args.work or tempfile.mkdtemp())
    work.mkdir(parents=True, exist_ok=True)

    print("==> building local wheel repository")
    build_gamdl(work, out_dir, Path(args.native))
    build_bliss_analyze(out_dir, Path(args.native))
    build_pywidevine(work, out_dir)
    build_construct(work, out_dir)


if __name__ == "__main__":
    main()
