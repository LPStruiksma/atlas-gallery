"""Where does GHOST 1's identity backbone come from?  (IDS-14, issue #505)

Compares GHOST's `arcface_model/backbone.pth` with public ArcFace-family
checkpoints, from the strongest evidence to the weakest:

1. file hashes;
2. tensors: conv and linear weights paired in network order, exact equality and
   cosine (per output channel too, so a checkpoint whose batch norms were folded
   into its convolutions still matches its source);
3. embeddings of the bench's synthetic faces: identical outputs mean an
   identical model; for different models, how far their similarity structure
   agrees.

Runs without PyTorch: `.pth` files are read with a restricted unpickler that
only rebuilds tensors, and an arcface_torch `iresnet` state dict is turned
into an ONNX graph for ONNX Runtime.  Weights are downloaded at run time into
`~/.cache/atlas-identity-bench/provenance/`, never committed.

    python3 -m tools.identity_bench.provenance.compare_backbone             # everything
    python3 -m tools.identity_bench.provenance.compare_backbone --only mirror_arcface_torch
    python3 -m tools.identity_bench.provenance.compare_backbone --no-embeddings
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import os
import pickle
import struct
import sys
import urllib.request
import zipfile

import numpy as np

HF = "https://huggingface.co/"

# Every checkpoint compared, with where it was fetched from and what its host says it is.
CHECKPOINTS = {
    "ghost_backbone": dict(
        url="https://github.com/ai-forever/ghost/releases/download/arcface/backbone.pth",
        sha256="a566a62357f0c55b679d9ff2f022a294486568be0c00665d39029d0e46a8109b", size=261223796,
        format="pth", claim="GHOST 1 identity backbone (release tag `arcface`, asset dated 2021-12-14); "
                            "origin not stated by GHOST"),
    "mirror_arcface_torch": dict(
        url=HF + "marcelohaps/arcface-torch/resolve/main/backbone.pth",
        sha256="a566a62357f0c55b679d9ff2f022a294486568be0c00665d39029d0e46a8109b", size=261223796,
        format="pth", claim="third-party mirror of an arcface_torch training folder, labelled "
                            "'ArcFace R100 MS1MV3 FP16'; ships rank_0..7 PartialFC shards"),
    "mirror_partial_fc_rank0": dict(
        url=HF + "marcelohaps/arcface-torch/resolve/main/rank_0_softmax_weight.pt",
        sha256="4ef1546e0ce230d1ddfb360a7e739ba47b16b58f0ebbbde496835edcb98249aa", size=23343864, format="pth-tensor",
        claim="PartialFC class-centre shard 0 of 8 from the same mirror folder"),
    "insightface_glintr100": dict(
        url="https://github.com/deepinsight/insightface/releases/download/v0.7/antelopev2.zip",
        member="antelopev2/glintr100.onnx",
        sha256="4ab1d6435d639628a6f3e5008dd4f929edf4c4124b1a7169e1048f9fef534cdf", size=260665334,
        format="onnx", claim="InsightFace antelopev2 recognition model, r100 trained on Glint360K "
                             "(research-only)"),
    "onnx_zoo_arcface_r100": dict(
        url=HF + "onnxmodelzoo/arcfaceresnet100-8/resolve/main/arcfaceresnet100-8.onnx",
        sha256="f3a6bc281e72f88862f5748b53be3d76b3b48f8f1ab1f4a537941bdc4e1b01da", size=261036388,
        format="onnx", claim="ONNX model zoo ArcFace LResNet100E-IR, the original InsightFace MXNet "
                             "model trained on MS1M (refined)"),
    "idiap_tuned_from_glintr100": dict(
        url=HF + "Idiap/ArtFace-IResNet100-Tuned/resolve/main/IResNet100-Tuned.pth",
        sha256="bd1a0b153b406c911ccee3e33faee86e4be065c250d026f8eac9dc65ab257908", size=260789686,
        format="pth", claim="Idiap ArtFace: 'fine-tuned from ArcFace-antelopev2' (glintr100). "
                            "Positive control: a known derivative of an InsightFace model"),
    "cvlface_arcface_ir101_webface4m": dict(
        url=HF + "minchul/cvlface_arcface_ir101_webface4m/resolve/main/model.safetensors",
        sha256="e90e0c8e24c94409fe53c2254a1343ef54f48a3a66b885b8ff69aefa21660ee7", size=260997808,
        format="safetensors", claim="CVLFace ArcFace IR101 trained on WebFace4M (independent "
                                    "re-implementation, not InsightFace code)"),
}

SUBJECT = "ghost_backbone"

# InsightFace's published training log for its arcface_torch model zoo entry ms1mv3_arcface_r100_fp16.
TRAINING_LOG = ("https://raw.githubusercontent.com/anxiangsir/insightface_arcface_log/master/"
                "ms1mv3_arcface_r100_fp16/training.log")


def log_steps(text: str) -> dict:
    """Planned and last logged global step of an arcface_torch training log."""
    import re
    total = re.search(r"Total Step is: (\d+)", text)
    steps = [int(x) for x in re.findall(r"Global Step: (\d+)", text)]
    return dict(total_step=int(total.group(1)) if total else None, last_step=max(steps) if steps else None)


# ---------------------------------------------------------------- reading checkpoints

_DTYPES = {"FloatStorage": np.float32, "HalfStorage": np.float16, "DoubleStorage": np.float64,
           "LongStorage": np.int64, "IntStorage": np.int32, "ShortStorage": np.int16,
           "ByteStorage": np.uint8, "CharStorage": np.int8, "BoolStorage": np.bool_,
           "BFloat16Storage": "bfloat16"}


def _bfloat16(raw: bytes) -> np.ndarray:
    return (np.frombuffer(raw, np.uint16).astype(np.uint32) << 16).view(np.float32)


def read_pth(path: str):
    """A PyTorch zip checkpoint as numpy arrays, without PyTorch.

    The unpickler resolves only tensor rebuilding and OrderedDict, so a
    checkpoint cannot run code while it is read.
    """
    archive = zipfile.ZipFile(path)
    names = archive.namelist()
    prefix = names[0].split("/")[0]
    storages: dict = {}

    def rebuild(storage, offset, size, stride, *_):
        if not size:
            return storage[offset:offset + 1].reshape(())
        item = storage.itemsize
        view = np.lib.stride_tricks.as_strided(storage[offset:], tuple(size), [s * item for s in stride])
        return np.array(view)

    class Restricted(pickle.Unpickler):
        def find_class(self, module, name):
            if module.startswith("torch") and name in ("_rebuild_tensor_v2", "_rebuild_tensor"):
                return rebuild
            if module.startswith("torch") and name == "_rebuild_parameter":
                return lambda data, *_: data
            if module == "collections" and name == "OrderedDict":
                return collections.OrderedDict
            if module.startswith("torch") and name in _DTYPES:
                return name
            raise pickle.UnpicklingError(f"refusing {module}.{name}")

        def persistent_load(self, pid):
            _, kind, key, _location, _count = pid
            if key not in storages:
                raw = archive.read(f"{prefix}/data/{key}")
                dtype = _DTYPES[kind]
                storages[key] = _bfloat16(raw) if dtype == "bfloat16" else np.frombuffer(raw, dtype)
            return storages[key]

    with archive.open(f"{prefix}/data.pkl") as handle:
        return Restricted(handle).load()


def read_safetensors(path: str) -> "collections.OrderedDict[str, np.ndarray]":
    kinds = {"F32": np.float32, "F16": np.float16, "F64": np.float64, "I64": np.int64,
             "I32": np.int32, "U8": np.uint8, "BOOL": np.bool_}
    with open(path, "rb") as f:
        length = struct.unpack("<Q", f.read(8))[0]
        header = json.loads(f.read(length))
        body = f.read()
    entries = sorted(((k, v) for k, v in header.items() if k != "__metadata__"),
                     key=lambda kv: kv[1]["data_offsets"][0])
    out = collections.OrderedDict()
    for key, spec in entries:
        start, end = spec["data_offsets"]
        raw = body[start:end]
        array = _bfloat16(raw) if spec["dtype"] == "BF16" else np.frombuffer(raw, kinds[spec["dtype"]])
        out[key] = array.reshape(spec["shape"])
    return out


def read_onnx(path: str) -> "collections.OrderedDict[str, np.ndarray]":
    """Initializers in the order the graph first uses them."""
    import onnx
    from onnx import numpy_helper
    graph = onnx.load(path).graph
    inits = {i.name: i for i in graph.initializer}
    out = collections.OrderedDict()
    for node in graph.node:
        for name in node.input:
            if name in inits and name not in out:
                out[name] = numpy_helper.to_array(inits[name])
    return out


def read(path: str, fmt: str):
    if fmt == "pth":
        state = read_pth(path)
        if isinstance(state, dict) and "state_dict" in state:
            state = state["state_dict"]
        return state
    if fmt == "pth-tensor":
        return read_pth(path)
    if fmt == "safetensors":
        return read_safetensors(path)
    if fmt == "onnx":
        return read_onnx(path)
    raise ValueError(fmt)


# ---------------------------------------------------------------- tensor comparison

def weights(state) -> list:
    """(name, array) for conv kernels (4-D) and linear weights (2-D), in file order."""
    return [(k, np.asarray(v, np.float32)) for k, v in state.items()
            if isinstance(v, np.ndarray) and v.ndim in (2, 4) and v.size >= 64]


def cosine(a: np.ndarray, b: np.ndarray) -> float:
    a, b = a.ravel().astype(np.float64), b.ravel().astype(np.float64)
    na, nb = np.linalg.norm(a), np.linalg.norm(b)
    return float(a @ b / (na * nb)) if na and nb else float("nan")


def channel_cosine(a: np.ndarray, b: np.ndarray) -> float:
    """Median |cosine| over output channels that are non-zero in both.

    Folding a batch norm into a convolution scales each output channel, which
    leaves this unchanged; a different model gives values near 0.
    """
    a = a.reshape(a.shape[0], -1).astype(np.float64)
    b = b.reshape(b.shape[0], -1).astype(np.float64)
    na, nb = np.linalg.norm(a, axis=1), np.linalg.norm(b, axis=1)
    keep = (na > 0) & (nb > 0)
    if not keep.any():
        return float("nan")
    values = np.abs((a[keep] * b[keep]).sum(1) / (na[keep] * nb[keep]))
    return float(np.median(values))


def pair_by_shape(a: list, b: list) -> list:
    """Pair the i-th tensor of each shape in `a` with the i-th of that shape in `b`."""
    queues: dict = collections.defaultdict(list)
    for name, array in b:
        queues[array.shape].append((name, array))
    seen: dict = collections.Counter()
    pairs = []
    for name, array in a:
        i = seen[array.shape]
        seen[array.shape] += 1
        if i < len(queues[array.shape]):
            pairs.append(((name, array), queues[array.shape][i]))
    return pairs


def best_match(a: np.ndarray, candidates: list) -> float:
    """Highest per-channel cosine of `a` against every same-shaped tensor."""
    return max((channel_cosine(a, c) for _, c in candidates if c.shape == a.shape), default=float("nan"))


def compare_tensors(a_state, b_state) -> dict:
    a, b = weights(a_state), weights(b_state)
    pairs = pair_by_shape(a, b)
    params_a = sum(x.size for _, x in a)
    exact = [p for p in pairs if np.array_equal(p[0][1], p[1][1])]
    paired_cos = [cosine(x, y) for (_, x), (_, y) in pairs]
    paired_ch = [channel_cosine(x, y) for (_, x), (_, y) in pairs if x.ndim == 4]
    convs = [(n, x) for n, x in a if x.ndim == 4]
    # best match for every 8th conv, so a different tensor order cannot hide a match
    best = [best_match(x, b) for _, x in convs[::8]]
    finite = lambda xs: [x for x in xs if np.isfinite(x)]
    return dict(
        tensors_a=len(a), tensors_b=len(b), params_a=int(params_a), paired=len(pairs),
        exact_equal=len(exact), exact_equal_params=int(sum(p[0][1].size for p in exact)),
        median_cosine=float(np.median(finite(paired_cos))) if finite(paired_cos) else float("nan"),
        median_channel_cosine=float(np.median(finite(paired_ch))) if finite(paired_ch) else float("nan"),
        best_match_channel_cosine=float(np.median(finite(best))) if finite(best) else float("nan"),
    )


# ---------------------------------------------------------------- arcface_torch iresnet as ONNX

def iresnet_onnx(state, name: str = "iresnet"):
    """An ONNX graph for an arcface_torch `iresnet` state dict (any depth)."""
    import onnx
    from onnx import TensorProto, helper, numpy_helper

    nodes, inits = [], []
    counter = [0]

    def const(array, label):
        key = f"{label}"
        inits.append(numpy_helper.from_array(np.asarray(array, np.float32), key))
        return key

    def out(label):
        counter[0] += 1
        return f"{label}_{counter[0]}"

    def bn(x, prefix):
        y = out(prefix)
        nodes.append(helper.make_node(
            "BatchNormalization",
            [x, const(state[prefix + ".weight"], prefix + ".w"), const(state[prefix + ".bias"], prefix + ".b"),
             const(state[prefix + ".running_mean"], prefix + ".m"),
             const(state[prefix + ".running_var"], prefix + ".v")], [y], epsilon=1e-5))
        return y

    def conv(x, prefix, stride, pad):
        y = out(prefix)
        w = state[prefix + ".weight"]
        nodes.append(helper.make_node("Conv", [x, const(w, prefix + ".w")], [y],
                                      strides=[stride, stride], pads=[pad] * 4))
        return y

    def prelu(x, prefix):
        y = out(prefix)
        slope = np.asarray(state[prefix + ".weight"], np.float32).reshape(-1, 1, 1)
        nodes.append(helper.make_node("PRelu", [x, const(slope, prefix + ".s")], [y]))
        return y

    x = conv("input", "conv1", 1, 1)
    x = prelu(bn(x, "bn1"), "prelu")
    layer = 1
    while f"layer{layer}.0.conv1.weight" in state:
        block = 0
        while f"layer{layer}.{block}.conv1.weight" in state:
            p = f"layer{layer}.{block}"
            stride = 2 if block == 0 else 1
            y = bn(x, p + ".bn1")
            y = conv(y, p + ".conv1", 1, 1)
            y = prelu(bn(y, p + ".bn2"), p + ".prelu")
            y = conv(y, p + ".conv2", stride, 1)
            y = bn(y, p + ".bn3")
            skip = x
            if p + ".downsample.0.weight" in state:
                skip = bn(conv(x, p + ".downsample.0", stride, 0), p + ".downsample.1")
            z = out(p + ".add")
            nodes.append(helper.make_node("Add", [y, skip], [z]))
            x = z
            block += 1
        layer += 1
    x = bn(x, "bn2")
    flat = out("flat")
    nodes.append(helper.make_node("Flatten", [x], [flat], axis=1))
    fc = out("fc")
    nodes.append(helper.make_node("Gemm", [flat, const(state["fc.weight"], "fc.w"), const(state["fc.bias"], "fc.b")],
                                  [fc], transB=1))
    # features is a BatchNorm1d (its affine weight is frozen at 1 in arcface_torch)
    scale = np.asarray(state["features.weight"], np.float64) / np.sqrt(np.asarray(state["features.running_var"]) + 1e-5)
    shift = np.asarray(state["features.bias"]) - np.asarray(state["features.running_mean"]) * scale
    mul, emb = out("feat_mul"), "embedding"
    nodes.append(helper.make_node("Mul", [fc, const(scale, "feat.scale")], [mul]))
    nodes.append(helper.make_node("Add", [mul, const(shift, "feat.shift")], [emb]))
    graph = helper.make_graph(nodes, name, [helper.make_tensor_value_info("input", TensorProto.FLOAT, [None, 3, 112, 112])],
                              [helper.make_tensor_value_info("embedding", TensorProto.FLOAT, [None, 512])], inits)
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 13)])
    model.ir_version = 8
    return model


class OnnxEmbedder:
    """112 px RGB face crop -> raw embedding.  `scale` is how pixels are normalised."""

    def __init__(self, model_bytes: bytes, scale: str = "pm1"):
        import onnxruntime as ort
        options = ort.SessionOptions()
        options.intra_op_num_threads = os.cpu_count() or 4
        self.net = ort.InferenceSession(model_bytes, options, providers=["CPUExecutionProvider"])
        self.input = self.net.get_inputs()[0].name
        self.scale = scale

    def embed(self, crops_bgr: np.ndarray) -> np.ndarray:
        rgb = crops_bgr[..., ::-1].astype(np.float32)
        if self.scale == "pm1":
            rgb = rgb / 127.5 - 1.0
        batch = np.ascontiguousarray(rgb.transpose(0, 3, 1, 2))
        return np.concatenate([self.net.run(None, {self.input: batch[i:i + 1]})[0] for i in range(len(batch))])


# ---------------------------------------------------------------- embedding comparison

def unit_rows(x: np.ndarray) -> np.ndarray:
    return x / np.linalg.norm(x, axis=1, keepdims=True)


def same_person_gap(emb: np.ndarray, labels: list) -> dict:
    """Mean cosine of same-identity pairs and of different-identity pairs (a sanity check)."""
    e = unit_rows(emb.astype(np.float64))
    sim = e @ e.T
    lab = np.array(labels)
    same = (lab[:, None] == lab[None, :]) & ~np.eye(len(lab), dtype=bool)
    diff = lab[:, None] != lab[None, :]
    return dict(same=float(sim[same].mean()), different=float(sim[diff].mean()))


def compare_embeddings(a: np.ndarray, b: np.ndarray) -> dict:
    """Identical outputs (max difference) and agreement of the two similarity structures."""
    ea, eb = unit_rows(a.astype(np.float64)), unit_rows(b.astype(np.float64))
    sa, sb = ea @ ea.T, eb @ eb.T
    upper = np.triu_indices(len(a), 1)
    return dict(max_abs_difference=float(np.abs(a - b).max()) if a.shape == b.shape else float("nan"),
                same_space_cosine=float(np.mean((ea * eb).sum(1))) if a.shape == b.shape else float("nan"),
                similarity_structure_r=float(np.corrcoef(sa[upper], sb[upper])[0, 1]))


def bench_faces(limit_identities: int | None = None):
    """Aligned 112 px crops of the bench's synthetic reference photos, with identity labels."""
    from tools.identity_bench import fixtures, pipeline
    detector = pipeline.Detector()
    ids = list(fixtures.IDENTITIES) + list(fixtures.DISTRACTORS)
    crops, labels, files = [], [], []
    for identity in ids[:limit_identities]:
        for file_id in fixtures.references(identity) if identity in fixtures.IDENTITIES else [f"flux/{identity}/doc"]:
            image = fixtures.reference_image(file_id)
            faces = detector.detect(image)
            if not faces:
                continue
            crop, _ = pipeline.align(image, faces[0].marks, "arcface_112_v2", 112)
            crops.append(crop)
            labels.append(identity)
            files.append(file_id)
    return np.stack(crops), labels, files


# ---------------------------------------------------------------- fetching

def cache_dir() -> str:
    root = os.environ.get("IDENTITY_BENCH_CACHE", os.path.expanduser("~/.cache/atlas-identity-bench"))
    path = os.path.join(root, "provenance")
    os.makedirs(path, exist_ok=True)
    return path


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch(name: str) -> str:
    spec = CHECKPOINTS[name]
    if name == "insightface_glintr100":
        from tools.identity_bench import pipeline
        return pipeline.model_path("glintr100")
    path = os.path.join(cache_dir(), name + "." + spec["format"].split("-")[0])
    if not os.path.exists(path) or os.path.getsize(path) != spec["size"]:
        request = urllib.request.Request(spec["url"], headers={"User-Agent": "identity-bench"})
        with urllib.request.urlopen(request, timeout=600) as response, open(path + ".part", "wb") as f:
            while chunk := response.read(1 << 20):
                f.write(chunk)
        os.replace(path + ".part", path)
    if spec["sha256"] and sha256_file(path) != spec["sha256"]:
        raise RuntimeError(f"{name}: sha256 differs from the pinned value")
    return path


# ---------------------------------------------------------------- the run

def run(only: list | None = None, embeddings: bool = True, log=print) -> dict:
    names = [n for n in CHECKPOINTS if n != SUBJECT and (not only or n in only)]
    subject_path = fetch(SUBJECT)
    subject = read(subject_path, "pth")
    result = dict(subject=dict(name=SUBJECT, sha256=sha256_file(subject_path),
                               tensors=len(subject), params=int(sum(v.size for v in subject.values())),
                               num_batches_tracked={k: int(v) for k, v in subject.items()
                                                    if k.endswith("num_batches_tracked") and k.count(".") == 1}),
                  checkpoints={})
    states = {}
    for name in names:
        spec = CHECKPOINTS[name]
        path = fetch(name)
        digest = sha256_file(path)
        row = dict(claim=spec["claim"], url=spec["url"], sha256=digest,
                   same_file=digest == result["subject"]["sha256"])
        state = read(path, spec["format"])
        if spec["format"] == "pth-tensor":
            row["shape"] = list(np.shape(state))
        else:
            states[name] = state
            row["tensors"] = compare_tensors(subject, state)
        result["checkpoints"][name] = row
        log(f"{name}: same file {row['same_file']}; " +
            (f"{row['tensors']['exact_equal']}/{row['tensors']['paired']} tensors equal, "
             f"channel cosine {row['tensors']['median_channel_cosine']:.3f}" if "tensors" in row else f"shape {row['shape']}"))
    # The batch-norm step counter of a checkpoint is the number of training steps it saw.
    try:
        request = urllib.request.Request(TRAINING_LOG, headers={"User-Agent": "identity-bench"})
        with urllib.request.urlopen(request, timeout=120) as response:
            text = response.read().decode("utf-8", "replace")
        result["training_log"] = dict(url=TRAINING_LOG, **log_steps(text))
        log(f"training log: {result['training_log']}")
    except OSError as error:
        result["training_log"] = dict(url=TRAINING_LOG, error=str(error))
    if "mirror_partial_fc_rank0" in result["checkpoints"]:
        rows = result["checkpoints"]["mirror_partial_fc_rank0"]["shape"][0]
        # arcface_torch gives ranks below (classes % 8) one extra row; the mirror's shard sizes
        # are 4 x 11398 and 4 x 11397 rows (from their byte sizes), so classes = 8 * 11397 + 4.
        result["checkpoints"]["mirror_partial_fc_rank0"]["classes_from_shards"] = 4 * rows + 4 * (rows - 1)
    # Controls: the method must find a known derivative and must not find one between unrelated models.
    controls = [("idiap_tuned_from_glintr100", "insightface_glintr100"),
                ("idiap_tuned_from_glintr100", "cvlface_arcface_ir101_webface4m")]
    result["controls"] = {}
    for x, y in controls:
        if x in states and y in states:
            result["controls"][f"{x} vs {y}"] = row = compare_tensors(states[x], states[y])
            log(f"control {x} vs {y}: {row['exact_equal']}/{row['paired']} tensors equal, "
                f"channel cosine {row['median_channel_cosine']:.3f}")
    if embeddings:
        result["embeddings"] = embedding_run(subject, states, log)
    return result


def embedding_run(subject, states: dict, log=print) -> dict:
    crops, labels, files = bench_faces()
    models = {SUBJECT: OnnxEmbedder(iresnet_onnx(subject).SerializeToString())}
    if "mirror_arcface_torch" in states:
        models["mirror_arcface_torch"] = OnnxEmbedder(iresnet_onnx(states["mirror_arcface_torch"]).SerializeToString())
    if "insightface_glintr100" in states:
        models["insightface_glintr100"] = OnnxEmbedder(open(fetch("insightface_glintr100"), "rb").read())
    if "onnx_zoo_arcface_r100" in states:      # normalises inside the graph, takes 0..255 RGB
        models["onnx_zoo_arcface_r100"] = OnnxEmbedder(open(fetch("onnx_zoo_arcface_r100"), "rb").read(), "raw")
    out = dict(faces=len(crops), identities=len(set(labels)), files=files, models={})
    emb = {name: m.embed(crops) for name, m in models.items()}
    for name, e in emb.items():
        row = dict(sanity=same_person_gap(e, labels))
        if name != SUBJECT:
            row.update(compare_embeddings(emb[SUBJECT], e))
        out["models"][name] = row
        log(f"embeddings {name}: {row}")
    return out


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--only", help="comma-separated checkpoint names")
    parser.add_argument("--no-embeddings", action="store_true")
    parser.add_argument("--out", default=os.path.join(cache_dir(), "compare_backbone.json"))
    args = parser.parse_args(argv)
    result = run(args.only.split(",") if args.only else None, not args.no_embeddings)
    with open(args.out, "w") as f:
        json.dump(result, f, indent=1)
    print(f"written to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
