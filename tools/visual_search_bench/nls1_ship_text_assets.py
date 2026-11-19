#!/usr/bin/env python3
"""NLS-1A (#216): ship the SigLIP 2 text tower as numbered APK asset parts.

Takes the `int8_gather` graph that text_tower.py exports (NLS-0's size
candidate: int8 MatMul/Gemm with block 0's MLP fc2 kept fp32, plus an int8
embedding table) and writes it to app/src/main/assets/models/siglip2_text as
ordered parts of at most PART_BYTES each, with a manifest holding the whole-file
and per-part SHA-256. It refuses to ship anything but the exact graph NLS-0
measured: the export's SHA-256 must equal the int8_gather hash in NLS-0's
committed export report, or it stops (a deviation is reported, never shipped).

  # 1. export (needs the pinned checkpoint + tokenizer, see run_nls0.sh)
  python -I tools/visual_search_bench/text_tower.py --weights-dir $C/models --out-dir $C/onnx
  # 2. split, hash, verify reassembly, write the manifest
  python -I tools/visual_search_bench/nls1_ship_text_assets.py ship --onnx-dir $C/onnx
  # re-check a checkout's committed parts (numpy-free, stdlib only)
  python -I tools/visual_search_bench/nls1_ship_text_assets.py verify

Reassembly is streamed through a fixed 1 MiB buffer and never holds a part or
the model in memory.
"""
import argparse
import hashlib
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
SHIP_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "models", "siglip2_text")
NLS0_REPORT = os.path.join(ROOT, "docs", "visual-search", "results", "nls0_text_export_20261114.json")
NAME = "siglip2_b16_224"
VARIANT = "int8_gather"
FILE_NAME = "siglip2_b16_224_text_int8.onnx"
PART_BYTES = 48_000_000  # GitHub warns above 50 MB; the unit asks for parts < 50 MB.
CHUNK = 1 << 20


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(CHUNK), b""):
            h.update(b)
    return h.hexdigest()


def part_names(n):
    return [f"{FILE_NAME}.part{i}" for i in range(n)]


def split(src, out_dir, part_bytes=PART_BYTES):
    """Streams src into ordered parts of at most part_bytes; returns [(name, size, sha256)]."""
    parts = []
    with open(src, "rb") as f:
        i = 0
        while True:
            name = f"{FILE_NAME}.part{i}"
            h, size = hashlib.sha256(), 0
            with open(os.path.join(out_dir, name), "wb") as out:
                while size < part_bytes:
                    b = f.read(min(CHUNK, part_bytes - size))
                    if not b:
                        break
                    out.write(b)
                    h.update(b)
                    size += len(b)
            if size == 0:
                os.remove(os.path.join(out_dir, name))
                break
            parts.append((name, size, h.hexdigest()))
            i += 1
    return parts


def verify_dir(ship_dir):
    """Checks every part against the manifest and the ordered join against the whole pin."""
    with open(os.path.join(ship_dir, "manifest.json")) as f:
        m = json.load(f)
    names = [p["name"] for p in m["parts"]]
    if names != part_names(len(names)):
        raise SystemExit(f"manifest parts are not numbered 0..{len(names) - 1}: {names}")
    on_disk = sorted(n for n in os.listdir(ship_dir) if n.startswith(FILE_NAME + ".part"))
    if sorted(names) != on_disk:
        raise SystemExit(f"parts on disk {on_disk} differ from the manifest {names}")
    whole, total = hashlib.sha256(), 0
    for p in m["parts"]:
        path = os.path.join(ship_dir, p["name"])
        h, size = hashlib.sha256(), 0
        with open(path, "rb") as f:
            for b in iter(lambda: f.read(CHUNK), b""):
                h.update(b)
                whole.update(b)
                size += len(b)
        if size != p["bytes"] or h.hexdigest() != p["sha256"]:
            raise SystemExit(f"{p['name']}: {size} bytes {h.hexdigest()} != manifest")
        if size >= 50_000_000:
            raise SystemExit(f"{p['name']} is {size} bytes, not below 50 MB")
        total += size
    if total != m["bytes"] or whole.hexdigest() != m["sha256"]:
        raise SystemExit(f"joined {total} bytes {whole.hexdigest()} != manifest {m['bytes']} {m['sha256']}")
    return m


def ship(a):
    spec = json.load(open(os.path.join(HERE, "models.json")))
    nls0 = json.load(open(NLS0_REPORT))
    report_path = os.path.join(a.onnx_dir, "text_export_report.json")
    report = json.load(open(report_path))
    src = os.path.join(a.onnx_dir, f"{NAME}_text.{VARIANT}.onnx")
    got = sha256_file(src)
    deviations = []
    if got != report[VARIANT]["sha256"]:
        deviations.append(f"file sha256 {got} != export report {report[VARIANT]['sha256']}")
    if got != nls0[VARIANT]["sha256"]:
        deviations.append(f"file sha256 {got} != NLS-0 {nls0[VARIANT]['sha256']}")
    for key in ("fp32_sha256", "tokenizer_sha256", "t", "b"):
        if report[key] != nls0[key]:
            deviations.append(f"{key} {report[key]} != NLS-0 {nls0[key]}")
    if deviations:
        print("DEVIATION from NLS-0; nothing shipped:\n  " + "\n  ".join(deviations), file=sys.stderr)
        raise SystemExit(2)

    os.makedirs(a.ship_dir, exist_ok=True)
    for n in os.listdir(a.ship_dir):
        if n.startswith(FILE_NAME + ".part"):
            os.remove(os.path.join(a.ship_dir, n))
    parts = split(src, a.ship_dir, a.part_bytes)
    manifest = {
        "_note": "NLS-1A (#216). Written by tools/visual_search_bench/nls1_ship_text_assets.py; see SOURCE.md.",
        "file": FILE_NAME,
        "bytes": os.path.getsize(src),
        "sha256": got,
        "parts": [{"name": n, "bytes": s, "sha256": h} for n, s, h in parts],
        "checkpoint": {"url": spec[NAME]["url"], "sha256": spec[NAME]["sha256"]},
        "tokenizer": {"url": spec["gemma_tokenizer"]["url"], "sha256": spec["gemma_tokenizer"]["sha256"],
                      "bundled": False},
        "fp32_sha256": report["fp32_sha256"],
        "quantization": {"variant": VARIANT, "op_types": ["MatMul", "Gemm", "Gather"],
                         "weight_type": "QInt8 per-tensor dynamic (onnxruntime.quantization.quantize_dynamic)",
                         "keep_fp32": spec[NAME]["text_int8_keep_fp32"]},
        "io": {"input": {"name": "tokens", "dtype": "int64", "shape": ["n", report["seq_len"]]},
               "output": {"name": "embedding", "dtype": "float32", "shape": ["n", report["dim"]],
                          "l2_normalised": True},
               "opset": 17},
        "tokenization": "ascii-lowercase; Gemma SentencePiece ids; no BOS; EOS id 1 appended and kept when "
                        "truncated (sticky); pad id 0 to length 64",
        "logit": {"t": report["t"], "b": report["b"], "scale_exp_t": report["logit_scale_exp_t"]},
        "parity": {"cos_to_fp32_min": report[VARIANT]["cos_to_fp32_min"],
                   "cos_to_fp32_mean": report[VARIANT]["cos_to_fp32_mean"]},
    }
    with open(os.path.join(a.ship_dir, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")
    verify_dir(a.ship_dir)
    print(json.dumps({k: manifest[k] for k in ("file", "bytes", "sha256", "parts")}, indent=2))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("ship")
    s.add_argument("--onnx-dir", required=True, help="text_tower.py --out-dir")
    s.add_argument("--ship-dir", default=SHIP_DIR)
    s.add_argument("--part-bytes", type=int, default=PART_BYTES)
    v = sub.add_parser("verify")
    v.add_argument("--ship-dir", default=SHIP_DIR)
    a = ap.parse_args()
    if a.cmd == "ship":
        if not 0 < a.part_bytes < 50_000_000:
            raise SystemExit("--part-bytes must be below 50 MB")
        ship(a)
    else:
        m = verify_dir(a.ship_dir)
        print(f"OK {m['file']} {m['bytes']} bytes {m['sha256']} from {len(m['parts'])} parts")


if __name__ == "__main__":
    main()
