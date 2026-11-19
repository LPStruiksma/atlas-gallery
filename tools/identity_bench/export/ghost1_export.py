"""Export GHOST 1 (ai-forever) from its official release to ONNX, in FP32, FP16-weight and int8 variants.

    python3 -m tools.identity_bench.export.ghost1_export            # export, check, write variants
    python3 -m tools.identity_bench.export.ghost1_export --blocks 1

Needs PyTorch (CPU is enough) for the export itself; everything after the
export (variants, operator check, bench) runs on ONNX Runtime alone.

What is exported:
  * the AEI-Net generator from `G_unet_<n>block(s).pth` (Apache-2.0 release
    asset of ai-forever/ghost), inputs `target` (1x3x256x256 in [-1, 1]) and
    `source` (1x512 raw identity vector), output `output` (1x3x256x256 in
    [-1, 1]); the same call the bench already makes of FaceFusion's file;
  * GHOST's identity backbone `backbone.pth` (input 1x3x112x112 in [-1, 1],
    output the raw 512-d vector). That backbone is InsightFace's research-only
    `ms1mv3_arcface_r100_fp16` (#505, GHOST_BACKBONE.md): **bench-only**, it
    may be measured here and never shipped.

Nothing here is committed: weights, source checkouts and exports live in
`~/.cache/atlas-identity-bench/export/`.
"""
import argparse
import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.request

import numpy as np

from tools.identity_bench import pipeline

GHOST_REPO = "https://github.com/ai-forever/ghost.git"
GHOST_COMMIT = "44e58aad8600ee83ad4c8213aaa9698ccaf66c1c"       # main, 2025-02-25
RELEASE = "https://github.com/ai-forever/ghost/releases/download/"
GENERATORS = {
    1: dict(file="G_unet_1block.pth", size=513995014,
            sha256="01dbe8d18edd1c947e9db3f5ca13910a9eb64b3c369013af7b216650f3fe6d96"),
    2: dict(file="G_unet_2blocks.pth", size=737523486,
            sha256="85f130421c65722927c47502ea1b28b62be4893ca9180fec735b67be1e5edd8d"),
    3: dict(file="G_unet_3blocks.pth", size=961051894,
            sha256="7c4fe7779b3ed998f08215cc2f5cdd4b0679f1da5f4e9db9619159ee86ce28da"),
}
OPSET = 17
VARIANTS = ("fp32", "fp16", "int8")

# ATLAS ships the full `onnxruntime-android` AAR (app/build.gradle.kts), which registers every CPU kernel,
# so a graph runs on the phone when (1) it uses only standard ai.onnx operators and (2) the same ORT
# version runs it on the host. `android_gaps` checks (1); `--ort-check` re-runs each variant under (2).
ANDROID_ORT = "1.23.2"


def export_dir() -> str:
    path = os.path.join(pipeline.cache_dir(), "export")
    os.makedirs(path, exist_ok=True)
    return path


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def fetch_generator(blocks: int) -> str:
    spec = GENERATORS[blocks]
    path = os.path.join(export_dir(), spec["file"])
    if not os.path.exists(path):
        tmp = path + ".tmp"
        request = urllib.request.Request(RELEASE + "sber-swap-v2.0/" + spec["file"],
                                         headers={"User-Agent": "identity-bench"})
        with urllib.request.urlopen(request, timeout=600) as response, open(tmp, "wb") as out:
            for block in iter(lambda: response.read(1 << 20), b""):
                out.write(block)
        os.replace(tmp, path)
    if sha256(path) != spec["sha256"]:
        raise RuntimeError(f"{spec['file']}: sha256 mismatch, refusing to use it")
    return path


def ghost_source() -> str:
    """A checkout of ai-forever/ghost at the pinned commit (code only, Apache-2.0)."""
    path = os.path.join(export_dir(), "ghost-src")
    if not os.path.isdir(os.path.join(path, ".git")):
        subprocess.run(["git", "clone", "--filter=blob:limit=2m", "--no-checkout", GHOST_REPO, path], check=True)
    subprocess.run(["git", "-C", path, "checkout", "-q", GHOST_COMMIT, "--", "network"], check=True)
    return path


# ---------------------------------------------------------------- export (PyTorch)

def load_generator(blocks: int):
    import torch
    sys.path.insert(0, ghost_source())
    try:
        from network.AEI_Net import AEI_Net
    finally:
        sys.path.pop(0)
    net = AEI_Net("unet", num_blocks=blocks, c_id=512)
    net.load_state_dict(torch.load(fetch_generator(blocks), map_location="cpu", weights_only=True))
    return net.eval()


class GeneratorOnly:
    """Wraps AEI_Net so the graph returns the image only, as the bench calls it."""

    def __new__(cls, net):
        import torch

        class Wrapped(torch.nn.Module):
            def __init__(self):
                super().__init__()
                self.net = net

            def forward(self, target, source):
                return self.net(target, source)[0]

        return Wrapped().eval()


def load_backbone():
    """GHOST's backbone as a PyTorch iresnet100 (state dict read by #505's restricted reader)."""
    import torch
    from tools.identity_bench.export import iresnet_torch
    from tools.identity_bench.provenance import compare_backbone as cb
    state = cb.read(cb.fetch("ghost_backbone"), "pth")
    net = iresnet_torch.iresnet100()
    net.load_state_dict({k: torch.from_numpy(np.array(v)) for k, v in state.items()})
    return net.eval()


def torch_export(module, inputs: dict, path: str, output: str = "output") -> str:
    import torch
    args = tuple(torch.from_numpy(v) for v in inputs.values())
    with torch.no_grad():
        torch.onnx.export(module, args, path, input_names=list(inputs), output_names=[output],
                          opset_version=OPSET, do_constant_folding=True, dynamo=False)
    return path


def torch_run(module, inputs: dict) -> np.ndarray:
    import torch
    with torch.no_grad():
        return module(*(torch.from_numpy(v) for v in inputs.values())).numpy()


# ---------------------------------------------------------------- variants (ONNX only)

def fp16_weights(model):
    """Store every large float initializer as FP16 and cast it back to FP32 in the graph.

    Halves the download; compute stays FP32, so any CPU or NNAPI-less phone runs it unchanged.
    """
    import onnx
    from onnx import helper, numpy_helper
    model = onnx.ModelProto.FromString(model.SerializeToString())
    graph = model.graph
    keep, casts = [], []
    for init in graph.initializer:
        array = numpy_helper.to_array(init)
        if init.data_type != onnx.TensorProto.FLOAT or array.size < 16:
            keep.append(init)
            continue
        name16 = init.name + "__fp16"
        keep.append(numpy_helper.from_array(array.astype(np.float16), name16))
        casts.append(helper.make_node("Cast", [name16], [init.name], to=onnx.TensorProto.FLOAT,
                                      name=init.name + "__cast"))
    del graph.initializer[:]
    graph.initializer.extend(keep)
    nodes = list(graph.node)
    del graph.node[:]
    graph.node.extend(casts + nodes)
    return model


def int8_weights(model, min_size: int = 1024):
    """Store large weight tensors as symmetric int8, one scale per output channel, and dequantise in the graph.

    Weight-only: compute stays FP32 (DequantizeLinear feeds the original op), so the activations are untouched.
    The download is a quarter of FP32. Every op is standard ai.onnx (DequantizeLinear with `axis`, opset 13+).
    """
    import onnx
    from onnx import helper, numpy_helper
    model = onnx.ModelProto.FromString(model.SerializeToString())
    graph = model.graph
    keep, deq = [], []
    for init in graph.initializer:
        array = numpy_helper.to_array(init)
        if init.data_type != onnx.TensorProto.FLOAT or array.size < min_size or array.ndim < 2:
            keep.append(init)
            continue
        flat = np.abs(array.reshape(array.shape[0], -1)).max(axis=1)
        scale = np.where(flat > 0, flat / 127.0, 1.0).astype(np.float32)
        with np.errstate(divide="ignore", invalid="ignore"):
            q = np.rint(array / scale.reshape((-1,) + (1,) * (array.ndim - 1)))
        q = np.clip(np.nan_to_num(q), -127, 127).astype(np.int8)
        keep += [numpy_helper.from_array(q, init.name + "__q"), numpy_helper.from_array(scale, init.name + "__scale"),
                 numpy_helper.from_array(np.zeros_like(scale, dtype=np.int8), init.name + "__zero")]
        deq.append(helper.make_node("DequantizeLinear", [init.name + "__q", init.name + "__scale", init.name + "__zero"],
                                    [init.name], axis=0, name=init.name + "__dequant"))
    del graph.initializer[:]
    graph.initializer.extend(keep)
    nodes = list(graph.node)
    del graph.node[:]
    graph.node.extend(deq + nodes)
    return model


def int8_dynamic(src: str, dst: str) -> str:
    """Dynamic int8: weights stored as int8, activations quantised per call (Conv, MatMul, Gemm)."""
    from onnxruntime.quantization import QuantType, quantize_dynamic
    quantize_dynamic(src, dst, weight_type=QuantType.QInt8, op_types_to_quantize=["Conv", "MatMul", "Gemm"])
    return dst


def operators(model) -> dict:
    ops = {}
    for node in model.graph.node:
        ops[node.op_type] = ops.get(node.op_type, 0) + 1
    return dict(sorted(ops.items()))


def android_gaps(model) -> list:
    """Operators outside the standard ai.onnx domain (contrib ops), which a phone build may lack."""
    return sorted({f"{n.domain}:{n.op_type}" for n in model.graph.node if n.domain not in ("", "ai.onnx")})


def ort_session(path_or_bytes):
    import onnxruntime as ort
    options = ort.SessionOptions()
    options.intra_op_num_threads = pipeline.THREADS
    options.inter_op_num_threads = 1
    options.log_severity_level = 3
    return ort.InferenceSession(path_or_bytes, options, providers=["CPUExecutionProvider"])


def ort_run(path_or_bytes, inputs: dict) -> np.ndarray:
    return ort_session(path_or_bytes).run(None, inputs)[0]


# ---------------------------------------------------------------- the run

def variant_path(kind: str, variant: str, blocks: int = 1) -> str:
    stem = f"ghost{blocks}_generator" if kind == "generator" else "ghost_backbone"
    return os.path.join(export_dir(), f"{stem}_{variant}.onnx")


def sample_inputs(seed: int = 0) -> tuple:
    rng = np.random.default_rng(seed)
    gen = dict(target=rng.uniform(-1, 1, (1, 3, 256, 256)).astype(np.float32),
               source=(rng.normal(size=(1, 512)) * 0.8).astype(np.float32))      # GHOST-like norm (about 18)
    back = dict(input=rng.uniform(-1, 1, (1, 3, 112, 112)).astype(np.float32))
    return gen, back


def run(blocks: int = 1, log=print) -> dict:
    import onnx
    report = dict(ghost_commit=GHOST_COMMIT, generator=GENERATORS[blocks], opset=OPSET, parts={})
    gen_in, back_in = sample_inputs()
    parts = [("generator", lambda: GeneratorOnly(load_generator(blocks)), gen_in),
             ("backbone", load_backbone, back_in)]
    for kind, build, inputs in parts:
        log(f"exporting the {kind}")
        module = build()
        fp32 = variant_path(kind, "fp32", blocks)
        t = time.time()
        torch_export(module, inputs, fp32)
        reference = torch_run(module, inputs)
        del module
        model = onnx.load(fp32)
        onnx.save(fp16_weights(model), variant_path(kind, "fp16", blocks))
        onnx.save(int8_weights(model), variant_path(kind, "int8", blocks))
        part = dict(export_seconds=round(time.time() - t, 1), variants={})
        # dynamic int8 (activations quantised too), tried and reported, not kept as a variant
        dyn = int8_dynamic(fp32, os.path.join(export_dir(), "dynamic-int8.tmp.onnx"))
        part["int8_dynamic_tried"] = dict(bytes=os.path.getsize(dyn), max_abs_difference_vs_pytorch=float(
            np.abs(ort_run(dyn, inputs) - reference).max()))
        os.remove(dyn)
        for variant in VARIANTS:
            path = variant_path(kind, variant, blocks)
            out = ort_run(path, inputs)
            loaded = onnx.load(path)
            part["variants"][variant] = dict(
                file=os.path.basename(path), bytes=os.path.getsize(path), sha256=sha256(path),
                max_abs_difference_vs_pytorch=float(np.abs(out - reference).max()),
                mean_abs_difference_vs_pytorch=float(np.abs(out - reference).mean()),
                operators=operators(loaded), non_standard_operators=android_gaps(loaded))
            log(f"  {variant}: {part['variants'][variant]['bytes'] / 1e6:.1f} MB, max |ORT - PyTorch| "
                f"{part['variants'][variant]['max_abs_difference_vs_pytorch']:.2e}")
        report["parts"][kind] = part
    return report


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--blocks", type=int, default=1, choices=sorted(GENERATORS))
    parser.add_argument("--out", default=os.path.join(pipeline.cache_dir(), "runs", "export", "ghost1_export.json"))
    args = parser.parse_args(argv)
    report = run(args.blocks)
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w") as f:
        json.dump(report, f, indent=1)
    print(f"written to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
