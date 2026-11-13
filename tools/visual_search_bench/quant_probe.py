#!/usr/bin/env python3
"""Find the MatMul whose dynamic int8 quantisation breaks an export.

For each MLP output projection, quantise everything except that node and
report the mean cosine to the fp32 embedding over sample images. A node
that lifts the cosine from ~0.75 to >0.97 belongs in the model's
int8_keep_fp32 list in models.json.

Usage:
  quant_probe.py --model siglip2_b16_224 --onnx-dir DIR --images DIR --set licensed.csv
"""
import argparse
import csv
import json
import os
import sys
import tempfile

import numpy as np
import onnx
from onnxruntime.quantization import QuantType, quantize_dynamic

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import bench  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--onnx-dir", required=True)
    ap.add_argument("--images", required=True)
    ap.add_argument("--set", required=True)
    ap.add_argument("--n", type=int, default=96)
    a = ap.parse_args()
    spec = json.load(open(os.path.join(HERE, "models.json")))[a.model]
    fp32 = os.path.join(a.onnx_dir, f"{a.model}.fp32.onnx")
    rows = list(csv.DictReader(open(a.set, newline="")))[: a.n]
    x = np.stack([bench.preprocess(os.path.join(a.images, r["ImageID"] + ".jpg"), spec) for r in rows])
    ref = bench.session(fp32, os.cpu_count()).run(None, {"pixels": x})[0]
    nodes = [n.name for n in onnx.load(fp32, load_external_data=False).graph.node
             if n.op_type == "MatMul" and ("fc2" in n.name or "c_proj" in n.name)]
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "q.onnx")
        for name in [None] + nodes:
            quantize_dynamic(fp32, out, weight_type=QuantType.QInt8,
                             op_types_to_quantize=["MatMul", "Gemm"],
                             nodes_to_exclude=[name] if name else [])
            e = bench.session(out, os.cpu_count()).run(None, {"pixels": x})[0]
            print(f"{(e * ref).sum(1).mean():.4f}\t{name or '(all int8)'}", flush=True)


if __name__ == "__main__":
    main()
