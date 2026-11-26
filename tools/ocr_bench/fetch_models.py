#!/usr/bin/env python3
"""Download the OCR-0A candidate artifacts at pinned immutable revisions and verify SHA-256.

Models stay OUTSIDE Git:  python3 tools/ocr_bench/fetch_models.py MODELS_DIR
Fails (exit 1) on any hash mismatch; never falls back to an unpinned revision.
"""

from __future__ import annotations

import hashlib
import sys
import urllib.request
from pathlib import Path

# (repo, immutable revision, file, bytes, sha256). Licence conclusions: docs/visual-search/OCR_0_FEASIBILITY.md
ARTIFACTS = [
    ("PaddlePaddle/PP-OCRv5_mobile_det_onnx", "e6f4fa85f00e168c862bc462aebca69eef9b3d3d",
     "inference.onnx", 4826518, "a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d"),
    ("PaddlePaddle/PP-OCRv5_mobile_det_onnx", "e6f4fa85f00e168c862bc462aebca69eef9b3d3d",
     "inference.yml", 903, "98069072e1b6b37d727fd9d9f11725faa46d6ea0de012f2ed26caea011c37699"),
    ("PaddlePaddle/latin_PP-OCRv5_mobile_rec_onnx", "89d3a50e2c27e2e7cceeab0e944c25c807d5db4f",
     "inference.onnx", 8042023, "7888113072263cb471b93f66dd5e2ad70548dc526fa1ace760d0d973dd121498"),
    ("PaddlePaddle/latin_PP-OCRv5_mobile_rec_onnx", "89d3a50e2c27e2e7cceeab0e944c25c807d5db4f",
     "inference.yml", 6817, "0bbe984570f597af3638e50bdf2e8276f3ab26a61966096538b3b0d1849f5c84"),
    ("Felix92/onnxtr-db-mobilenet-v3-large", "21338515c12c7f685b8f1022b9babd90a4b9588a",
     "model.onnx", 16076965, "4987e7bdea372559808bd5add85fda10e179dc639696fb489e59a197a25b4c64"),
    ("Felix92/onnxtr-db-mobilenet-v3-large", "21338515c12c7f685b8f1022b9babd90a4b9588a",
     "config.json", 457, "2b75da19916844714b169373a4be87486bc5f5836af4e32c35a42c4393a95d44"),
    ("Felix92/onnxtr-crnn-mobilenet-v3-small", "96dcb28209d6454213d0197029e8754625ce9d53",
     "model.onnx", 8331378, "bded4d49b3e91dac24591ed4f0af3de4c3baab1f9cc07e8e7dc07c9ba66b3b33"),
    ("Felix92/onnxtr-crnn-mobilenet-v3-small", "96dcb28209d6454213d0197029e8754625ce9d53",
     "config.json", 638, "5d23566e934a95b940b512a1457563cc958bd9ff6925086ed9421ecea0fc1747"),
]


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    root = Path(sys.argv[1])
    for repo, rev, name, size, digest in ARTIFACTS:
        target = root / repo.replace("/", "__") / name
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists():
            url = f"https://huggingface.co/{repo}/resolve/{rev}/{name}"
            target.write_bytes(urllib.request.urlopen(url, timeout=300).read())
        data = target.read_bytes()
        if len(data) != size or hashlib.sha256(data).hexdigest() != digest:
            print(f"MISMATCH {repo}@{rev}/{name}: {len(data)} bytes, {hashlib.sha256(data).hexdigest()}")
            return 1
        print(f"ok {repo}@{rev[:10]}/{name} {size} {digest[:12]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
