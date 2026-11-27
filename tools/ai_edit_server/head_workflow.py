"""IDS-12B activation helpers for the self-hosted FLUX.2 whole-head route.

Standard library only, so exact-head CI can run every case without Pillow,
model weights, a GPU or a real provider.
"""
from __future__ import annotations

import binascii
import struct
import zlib

# The repository-side verdict ledger. Activation needs this to read "PASS",
# and only the separately reviewed verdict unit (#1092) may change it after a
# real 20-pair run (#1091) and adapter dossier (#1090) pass. While it reads
# NOT_MEASURED, no deployment can activate the route, whatever its config says.
HEAD_VERDICT_LEDGER = "NOT_MEASURED"

ENGINE_VERSION = "ids12b-activation-1"

# Extra evidence gates needed on top of the scaffold's admission gates.
ACTIVATION_EVIDENCE = ("adapter_dossier_passed", "head_verdict_passed")

_SIGNATURE = b"\x89PNG\r\n\x1a\n"
_CHANNELS = {0: 1, 2: 3, 4: 2, 6: 4}


class HeadOutputError(ValueError):
    """The provider output is not exactly one valid crop-sized PNG."""


def _chunk(kind, payload):
    return (struct.pack(">I", len(payload)) + kind + payload +
            struct.pack(">I", binascii.crc32(kind + payload) & 0xFFFFFFFF))


def strip_png_metadata(data, width, height):
    """Return a fresh PNG with only IHDR, IDAT and IEND, or raise.

    The provider PNG must be complete, CRC-valid, non-interlaced, 8 or 16-bit
    grey/RGB/alpha (no palette), exactly ``width`` x ``height``, with
    decompressible pixel data of the exact expected length. Every ancillary
    chunk (tEXt/iTXt/zTXt prompt and workflow text, eXIf, tIME, iCCP, pHYs and
    any private chunk) is dropped. IDAT chunks must be consecutive and hold
    exactly one zlib stream with no trailing bytes; the decoded pixels are
    recompressed, so nothing but raster data survives.
    """
    if not isinstance(data, (bytes, bytearray)) or not data.startswith(_SIGNATURE):
        raise HeadOutputError("Provider output is not a PNG")
    data = bytes(data)
    offset = len(_SIGNATURE)
    ihdr = None
    idat = []
    ended = False
    idat_closed = False
    while offset < len(data):
        if ended:
            raise HeadOutputError("Provider PNG has data after IEND")
        if offset + 12 > len(data):
            raise HeadOutputError("Provider PNG is truncated")
        length = struct.unpack(">I", data[offset:offset + 4])[0]
        kind = data[offset + 4:offset + 8]
        end = offset + 12 + length
        if end > len(data):
            raise HeadOutputError("Provider PNG is truncated")
        payload = data[offset + 8:offset + 8 + length]
        crc = struct.unpack(">I", data[offset + 8 + length:end])[0]
        if crc != binascii.crc32(kind + payload) & 0xFFFFFFFF:
            raise HeadOutputError("Provider PNG has a corrupt chunk")
        if ihdr is None and kind != b"IHDR":
            raise HeadOutputError("Provider PNG must start with IHDR")
        if kind == b"IHDR":
            if ihdr is not None or length != 13:
                raise HeadOutputError("Provider PNG has an invalid IHDR")
            ihdr = payload
        elif kind == b"IDAT":
            if idat_closed:
                raise HeadOutputError("Provider PNG has non-consecutive IDAT chunks")
            idat.append(payload)
        elif kind == b"IEND":
            if length:
                raise HeadOutputError("Provider PNG has a non-empty IEND")
            ended = True
        elif kind == b"PLTE":
            raise HeadOutputError("Palette PNG output is not supported")
        elif not (kind[0] & 0x20):
            # Unknown critical chunk: cannot be dropped safely.
            raise HeadOutputError("Provider PNG has an unknown critical chunk")
        if idat and kind != b"IDAT":
            idat_closed = True
        offset = end
    if ihdr is None or not ended or not idat:
        raise HeadOutputError("Provider PNG is incomplete")
    out_w, out_h, depth, colour, compression, filtering, interlace = struct.unpack(
        ">IIBBBBB", ihdr)
    if colour not in _CHANNELS or depth not in (8, 16):
        raise HeadOutputError("Unsupported provider PNG pixel format")
    if compression or filtering or interlace:
        raise HeadOutputError("Unsupported provider PNG encoding")
    if (out_w, out_h) != (width, height):
        raise HeadOutputError("Provider crop does not match the declared size")
    stride = out_w * _CHANNELS[colour] * depth // 8
    expected = out_h * (1 + stride)
    stream = b"".join(idat)
    inflater = zlib.decompressobj()
    try:
        pixels = inflater.decompress(stream, expected + 1)
    except zlib.error as exc:
        raise HeadOutputError("Provider PNG pixel data is corrupt") from exc
    if (len(pixels) != expected or inflater.unconsumed_tail or not inflater.eof
            or inflater.unused_data):
        raise HeadOutputError("Provider PNG pixel data has the wrong length")
    if any(pixels[row * (1 + stride)] > 4 for row in range(out_h)):
        raise HeadOutputError("Provider PNG has an invalid row filter")
    return (_SIGNATURE + _chunk(b"IHDR", ihdr) + _chunk(b"IDAT", zlib.compress(pixels)) +
            _chunk(b"IEND", b""))


def head_lineage(*, seed, artifact_hashes, mode):
    """Reproducible lineage for one published head crop."""
    return {
        "engine": "self-hosted-flux2-head",
        "version": ENGINE_VERSION,
        "mode": mode,
        "seed": seed,
        "model_sha256": artifact_hashes["model"],
        "encoder_sha256": artifact_hashes["encoder"],
        "runtime_sha256": artifact_hashes["runtime"],
        "adapter_sha256": artifact_hashes["adapter"],
        "workflow_sha256": artifact_hashes["workflow"],
    }
