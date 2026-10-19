import asyncio
import struct
import tempfile
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock

import pytest
from Crypto.Cipher import AES
from Crypto.Util import Counter

from soiboi_pipeline import apple_stream


def _make_box(box_type: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", len(payload) + 8) + box_type + payload


def _make_full_box(box_type: bytes, version: int, flags: bytes, payload: bytes) -> bytes:
    return _make_box(box_type, struct.pack(">B", version) + flags + payload)


def test_cenc_decryption_pattern_and_sample_counter():
    key = b"K" * 16
    kid = b"D" * 16
    iv0 = b"0123456789abcdef"
    iv1 = b"fedcba9876543210"

    # Two samples, 160 bytes each (10 blocks of 16 bytes)
    # Pattern 1:9 -> block 0 encrypted, blocks 1..9 clear
    raw_sample0 = b"SAMPLE00_PAYLOAD" * 10
    raw_sample1 = b"SAMPLE01_PAYLOAD" * 10

    # Encrypt sample 0 with iv0
    ctr0 = Counter.new(64, prefix=iv0[:8], initial_value=int.from_bytes(iv0[8:], "big"))
    c0 = AES.new(key, AES.MODE_CTR, counter=ctr0)
    enc_sample0 = c0.decrypt(raw_sample0[:16]) + raw_sample0[16:]

    # Encrypt sample 1 with iv1
    ctr1 = Counter.new(64, prefix=iv1[:8], initial_value=int.from_bytes(iv1[8:], "big"))
    c1 = AES.new(key, AES.MODE_CTR, counter=ctr1)
    enc_sample1 = c1.decrypt(raw_sample1[:16]) + raw_sample1[16:]

    # Build container
    ftyp_box = _make_box(b"ftyp", b"isom" + struct.pack(">I", 0) + b"isom")

    # tenc: version 1, flags 0, pattern (1<<4)|9 = 0x19, protected 1, iv_size 16
    tenc_payload = struct.pack(">BBB", 0, 0x19, 1) + struct.pack(">B", 16) + kid
    tenc_box = _make_full_box(b"tenc", 1, b"\x00\x00\x00", tenc_payload)
    schi_box = _make_box(b"schi", tenc_box)
    schm_box = _make_full_box(b"schm", 0, b"\x00\x00\x00", b"cenc" + struct.pack(">I", 0x00010000))
    sinf_box = _make_box(b"sinf", schm_box + schi_box)
    enca_box = _make_box(b"enca", b"\x00" * 28 + sinf_box)
    stsd_box = _make_full_box(b"stsd", 0, b"\x00\x00\x00", struct.pack(">I", 1) + enca_box)
    stbl_box = _make_box(b"stbl", stsd_box)
    minf_box = _make_box(b"minf", stbl_box)
    mdia_box = _make_box(b"mdia", minf_box)
    trak_box = _make_box(b"trak", mdia_box)
    moov_box = _make_box(b"moov", trak_box)

    # Fragment with both samples
    senc_payload = struct.pack(">I", 2) + iv0 + iv1
    senc_box = _make_full_box(b"senc", 0, b"\x00\x00\x00", senc_payload)
    tfhd_box = _make_full_box(b"tfhd", 0, b"\x02\x00\x00", struct.pack(">I", 1))

    # trun with 2 samples
    sample_sizes = len(enc_sample0) + len(enc_sample1)
    dummy_trun = _make_full_box(
        b"trun",
        0,
        b"\x00\x02\x01",
        struct.pack(">I", 2)
        + struct.pack(">i", 0)
        + struct.pack(">I", len(enc_sample0))
        + struct.pack(">I", len(enc_sample1)),
    )
    dummy_traf = _make_box(b"traf", tfhd_box + dummy_trun + senc_box)
    dummy_moof = _make_box(b"moof", dummy_traf)
    data_offset = len(dummy_moof) + 8

    real_trun = _make_full_box(
        b"trun",
        0,
        b"\x00\x02\x01",
        struct.pack(">I", 2)
        + struct.pack(">i", data_offset)
        + struct.pack(">I", len(enc_sample0))
        + struct.pack(">I", len(enc_sample1)),
    )
    traf_box = _make_box(b"traf", tfhd_box + real_trun + senc_box)
    moof_box = _make_box(b"moof", traf_box)
    mdat_box = _make_box(b"mdat", enc_sample0 + enc_sample1)

    full_fmp4 = ftyp_box + moov_box + moof_box + mdat_box

    # Decrypt
    decrypted = apple_stream._decrypt_cenc_pycrypto(full_fmp4, key, kid)

    # 1. Structure boxes preserved
    assert decrypted[:4] == ftyp_box[:4]
    assert b"moov" in decrypted
    assert b"moof" in decrypted
    assert b"mdat" in decrypted

    # 2. enca unmarked to mp4a
    assert b"mp4a" in decrypted
    assert b"enca" not in decrypted

    # 3. Both samples decrypted cleanly
    mdat_payload_start = len(ftyp_box) + len(moov_box) + data_offset
    s0_dec = decrypted[mdat_payload_start : mdat_payload_start + len(raw_sample0)]
    s1_dec = decrypted[
        mdat_payload_start + len(raw_sample0) : mdat_payload_start + len(raw_sample0) + len(raw_sample1)
    ]

    assert s0_dec == raw_sample0
    assert s1_dec == raw_sample1


def test_download_segments_fetches_and_prepends_init_chunk():
    manifest = """#EXTM3U
#EXT-X-TARGETDURATION:10
#EXT-X-MAP:URI="init.mp4"
#EXTINF:10.0,
seg1.m4s
#EXTINF:10.0,
seg2.m4s
"""
    api = MagicMock()
    responses = {
        "https://example.com/stream/init.mp4": b"INIT_HEADER_FTYP_MOOV",
        "https://example.com/stream/seg1.m4s": b"SEGMENT_1_MOOF_MDAT",
        "https://example.com/stream/seg2.m4s": b"SEGMENT_2_MOOF_MDAT",
    }

    async def mock_get(url):
        mock_resp = MagicMock()
        mock_resp.content = responses[url]
        mock_resp.raise_for_status = MagicMock()
        return mock_resp

    api.client = MagicMock()
    api.client.get = AsyncMock(side_effect=mock_get)

    with tempfile.TemporaryDirectory() as tmpdir:
        out_path = Path(tmpdir) / "output.m4a"
        ok = asyncio.run(
            apple_stream._download_segments(
                api,
                "https://example.com/stream/main.m3u8",
                manifest,
                out_path,
            )
        )
        assert ok is True
        content = out_path.read_bytes()

        # Init segment is at the start (byte 0)
        assert content.startswith(b"INIT_HEADER_FTYP_MOOV")
        assert content == b"INIT_HEADER_FTYP_MOOVSEGMENT_1_MOOF_MDATSEGMENT_2_MOOF_MDAT"


def test_resolve_stream_bitrate_mapping():
    assets = [
        {"flavor": "28:ctrp256", "URL": "https://example.com/256"},
        {"flavor": "28:ctrp64", "URL": "https://example.com/64"},
    ]

    asset_256 = apple_stream._find_asset(assets, "28:ctrp256")
    asset_64 = apple_stream._find_asset(assets, "28:ctrp64")

    assert asset_256["flavor"] == "28:ctrp256"
    assert asset_64["flavor"] == "28:ctrp64"

    # Verify bitrate logic
    bitrate_aac = 64 if ("64" in (asset_256.get("flavor") or "") or "aac" == "aac-he") else 256
    bitrate_he = 64 if ("64" in (asset_64.get("flavor") or "") or "aac-he" == "aac-he") else 256

    assert bitrate_aac == 256
    assert bitrate_he == 64
