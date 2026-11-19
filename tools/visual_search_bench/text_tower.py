#!/usr/bin/env python3
"""SigLIP 2 B/16 224 text tower: tokenizer, PyTorch port, ONNX export (NLS-0).

The shipped image tower (`siglip2_b16_224` in models.json, exported by
export_onnx.py through timm) and this text tower come from the same pinned
big_vision checkpoint, so their outputs share SigLIP 2's image-text space:
logit = t * <img, txt> + b with both vectors L2-normalised. timm only ports
the image half, so the text half is ported here from big_vision
(models/proj/image_text/text_transformer.py and models/vit.py at the commit
pinned in models.json):

  tokens [n, 64] -> Embed(256000, 768) + pos_embedding -> 12 pre-LN blocks
  (MHA 12x64, MLP 3072 with tanh-GELU, LayerNorm eps 1e-6) -> encoder_norm
  -> take the LAST position -> Dense head 768x768 -> L2 normalise.

Tokenisation follows the SigLIP 2 demo colab exactly:
  lower(key="text")|tok(length=64, model="gemma", bos="no", eos="sticky")
i.e. ASCII lowercasing (tf.strings.lower), Gemma SentencePiece ids, then EOS
(id 1) appended and kept even when truncated, padded with the pad id (0).

Usage:
  text_tower.py --weights-dir ~/.cache/atlas-vs/models --out-dir ~/.cache/atlas-vs/onnx
writes siglip2_b16_224_text.{fp32,int8}.onnx and text_export_report.json
(sizes, hashes, PyTorch-vs-numpy-reference and fp32-vs-int8 parity).
"""
import argparse
import hashlib
import json
import os

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
SEQLEN = 64
PAD_ID, EOS_ID = 0, 1
NAME = "siglip2_b16_224"
TXT = "params/txt/"


def ascii_lower(s):
    """tf.strings.lower with its default encoding lowercases ASCII only."""
    return "".join(chr(ord(c) + 32) if "A" <= c <= "Z" else c for c in s)


def pad_sticky(ids, length=SEQLEN):
    """big_vision tokenize(..., eos="sticky", pad_value=0) on already-EOS'd ids."""
    ids = list(ids) + [EOS_ID]
    if len(ids) >= length:
        return ids[: length - 1] + [EOS_ID]
    return ids + [PAD_ID] * (length - len(ids))


class Tokenizer:
    def __init__(self, model_path):
        import sentencepiece as spm
        self.sp = spm.SentencePieceProcessor(model_file=model_path)
        assert self.sp.pad_id() == PAD_ID and self.sp.eos_id() == EOS_ID, (self.sp.pad_id(), self.sp.eos_id())

    def __call__(self, texts):
        return np.array([pad_sticky(self.sp.encode(ascii_lower(t))) for t in texts], dtype=np.int64)


def load_txt_params(npz_path):
    z = np.load(npz_path)
    p = {k[len(TXT):]: z[k] for k in z.files if k.startswith(TXT)}
    p["_t"], p["_b"] = float(z["params/t"][0]), float(z["params/b"][0])
    return p


# ---------------------------------------------------------------- numpy reference

def _ln(x, s, b, eps=1e-6):
    m = x.mean(-1, keepdims=True)
    v = ((x - m) ** 2).mean(-1, keepdims=True)
    return (x - m) / np.sqrt(v + eps) * s + b


def _gelu_tanh(x):
    return 0.5 * x * (1 + np.tanh(np.sqrt(2 / np.pi) * (x + 0.044715 * x ** 3)))


def numpy_reference(p, tokens):
    """Independent float64 forward of the big_vision text tower (for parity)."""
    blk = "Encoder_0/encoderblock/"
    att = blk + "MultiHeadDotProductAttention_0/"
    x = p["Embed_0/embedding"][tokens].astype(np.float64) + p["pos_embedding"][0]
    for i in range(p[blk + "LayerNorm_0/scale"].shape[0]):
        y = _ln(x, p[blk + "LayerNorm_0/scale"][i], p[blk + "LayerNorm_0/bias"][i])
        q = np.einsum("nld,dhk->nlhk", y, p[att + "query/kernel"][i]) + p[att + "query/bias"][i]
        k = np.einsum("nld,dhk->nlhk", y, p[att + "key/kernel"][i]) + p[att + "key/bias"][i]
        v = np.einsum("nld,dhk->nlhk", y, p[att + "value/kernel"][i]) + p[att + "value/bias"][i]
        a = np.einsum("nqhk,nmhk->nhqm", q, k) / np.sqrt(q.shape[-1])
        a = np.exp(a - a.max(-1, keepdims=True))
        a /= a.sum(-1, keepdims=True)
        o = np.einsum("nhqm,nmhk->nqhk", a, v)
        x = x + np.einsum("nqhk,hkd->nqd", o, p[att + "out/kernel"][i]) + p[att + "out/bias"][i]
        y = _ln(x, p[blk + "LayerNorm_1/scale"][i], p[blk + "LayerNorm_1/bias"][i])
        y = _gelu_tanh(y @ p[blk + "MlpBlock_0/Dense_0/kernel"][i] + p[blk + "MlpBlock_0/Dense_0/bias"][i])
        x = x + y @ p[blk + "MlpBlock_0/Dense_1/kernel"][i] + p[blk + "MlpBlock_0/Dense_1/bias"][i]
    x = _ln(x, p["Encoder_0/encoder_norm/scale"], p["Encoder_0/encoder_norm/bias"])[:, -1]
    x = x @ p["head/kernel"] + p["head/bias"]
    return x / np.linalg.norm(x, axis=-1, keepdims=True)


# ---------------------------------------------------------------- PyTorch port

def build_torch(p):
    import torch
    from torch import nn

    class Block(nn.Module):
        def __init__(self, d=768, h=12, mlp=3072):
            super().__init__()
            self.h = h
            self.ln1, self.ln2 = nn.LayerNorm(d, eps=1e-6), nn.LayerNorm(d, eps=1e-6)
            self.q, self.k, self.v, self.o = (nn.Linear(d, d) for _ in range(4))
            self.fc1, self.fc2 = nn.Linear(d, mlp), nn.Linear(mlp, d)

        def forward(self, x):
            n, l, d = x.shape
            y = self.ln1(x)
            q, k, v = (f(y).view(n, l, self.h, d // self.h).transpose(1, 2) for f in (self.q, self.k, self.v))
            a = torch.nn.functional.scaled_dot_product_attention(q, k, v)
            x = x + self.o(a.transpose(1, 2).reshape(n, l, d))
            return x + self.fc2(torch.nn.functional.gelu(self.fc1(self.ln2(x)), approximate="tanh"))

    class TextTower(nn.Module):
        def __init__(self):
            super().__init__()
            self.embed = nn.Embedding(256000, 768)
            self.pos = nn.Parameter(torch.zeros(1, SEQLEN, 768))
            self.blocks = nn.ModuleList(Block() for _ in range(12))
            self.norm = nn.LayerNorm(768, eps=1e-6)
            self.head = nn.Linear(768, 768)

        def forward(self, tokens):
            x = self.embed(tokens) + self.pos
            for b in self.blocks:
                x = b(x)
            x = self.head(self.norm(x)[:, -1])
            return torch.nn.functional.normalize(x, dim=-1)

    m = TextTower().eval()
    T = lambda a: torch.from_numpy(np.ascontiguousarray(a, dtype=np.float32))  # noqa: E731
    blk, att = "Encoder_0/encoderblock/", "Encoder_0/encoderblock/MultiHeadDotProductAttention_0/"
    with torch.no_grad():
        m.embed.weight.copy_(T(p["Embed_0/embedding"]))
        m.pos.copy_(T(p["pos_embedding"]))
        for i, b in enumerate(m.blocks):
            b.ln1.weight.copy_(T(p[blk + "LayerNorm_0/scale"][i])); b.ln1.bias.copy_(T(p[blk + "LayerNorm_0/bias"][i]))
            b.ln2.weight.copy_(T(p[blk + "LayerNorm_1/scale"][i])); b.ln2.bias.copy_(T(p[blk + "LayerNorm_1/bias"][i]))
            for lin, key in ((b.q, "query"), (b.k, "key"), (b.v, "value")):
                lin.weight.copy_(T(p[att + key + "/kernel"][i].reshape(768, 768).T))
                lin.bias.copy_(T(p[att + key + "/bias"][i].reshape(768)))
            b.o.weight.copy_(T(p[att + "out/kernel"][i].reshape(768, 768).T)); b.o.bias.copy_(T(p[att + "out/bias"][i]))
            b.fc1.weight.copy_(T(p[blk + "MlpBlock_0/Dense_0/kernel"][i].T)); b.fc1.bias.copy_(T(p[blk + "MlpBlock_0/Dense_0/bias"][i]))
            b.fc2.weight.copy_(T(p[blk + "MlpBlock_0/Dense_1/kernel"][i].T)); b.fc2.bias.copy_(T(p[blk + "MlpBlock_0/Dense_1/bias"][i]))
        m.norm.weight.copy_(T(p["Encoder_0/encoder_norm/scale"])); m.norm.bias.copy_(T(p["Encoder_0/encoder_norm/bias"]))
        m.head.weight.copy_(T(p["head/kernel"].T)); m.head.bias.copy_(T(p["head/bias"]))
    return m


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


PARITY_TEXTS = [
    "a man and a woman standing in a doorway inside a church",
    "een man en een vrouw die in een deuropening in een kerk staan",
    "Someone doing a cartwheel on grass.",
    "een hond die springt om iets te vangen",
    "a crowded outdoor party at night",
    "x",
    " ".join(["a very long caption that must be truncated"] * 12),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--weights-dir", required=True)
    ap.add_argument("--out-dir", required=True)
    a = ap.parse_args()
    import torch
    from onnxruntime.quantization import QuantType, quantize_dynamic
    import onnxruntime as ort

    spec = json.load(open(os.path.join(HERE, "models.json")))
    src = os.path.join(a.weights_dir, os.path.basename(spec[NAME]["url"]))
    if sha256(src) != spec[NAME]["sha256"]:
        raise SystemExit("checkpoint sha256 does not match models.json pin")
    tok_spec = spec["gemma_tokenizer"]
    tok_path = os.path.join(a.weights_dir, os.path.basename(tok_spec["url"]))
    if sha256(tok_path) != tok_spec["sha256"]:
        raise SystemExit("tokenizer sha256 does not match models.json pin")

    os.makedirs(a.out_dir, exist_ok=True)
    p = load_txt_params(src)
    tok = Tokenizer(tok_path)
    tokens = tok(PARITY_TEXTS)
    ref = numpy_reference(p, tokens)
    model = build_torch(p)
    with torch.no_grad():
        pt = model(torch.from_numpy(tokens)).numpy()

    fp32 = os.path.join(a.out_dir, f"{NAME}_text.fp32.onnx")
    with torch.no_grad():
        torch.onnx.export(model, (torch.from_numpy(tokens[:1]),), fp32, input_names=["tokens"],
                          output_names=["embedding"], opset_version=17,
                          dynamic_axes={"tokens": {0: "n"}, "embedding": {0: "n"}}, dynamo=False)
    report = {"seq_len": SEQLEN, "vocab": 256000, "dim": 768, "t": p["_t"], "b": p["_b"],
              "logit_scale_exp_t": float(np.exp(p["_t"])),
              "params_m": round(sum(x.numel() for x in model.parameters()) / 1e6, 1),
              "embedding_table_params_m": round(model.embed.weight.numel() / 1e6, 1), "tokenizer_sha256": tok_spec["sha256"],
              "fp32_mb": round(os.path.getsize(fp32) / 1e6, 1), "fp32_sha256": sha256(fp32),
              "parity_torch_vs_numpy_min_cos": float((pt * ref).sum(-1).min())}
    sess32 = ort.InferenceSession(fp32, providers=["CPUExecutionProvider"])
    o32 = sess32.run(None, {"tokens": tokens})[0]
    report["parity_onnx_fp32_vs_numpy_min_cos"] = float((o32 * ref).sum(-1).min())
    # Block 0's MLP output projection has activation outliers that per-tensor
    # dynamic int8 cannot represent (min cosine 0.82 -> 0.996 when it stays
    # fp32; found by probing each fc2 as quant_probe.py does for the image tower).
    keep = spec[NAME].get("text_int8_keep_fp32", [])
    variants = {
        "int8_plain": dict(op_types_to_quantize=["MatMul", "Gemm"]),
        # MatMul only: the 256k x 768 embedding table (70% of the weights) stays fp32.
        "int8": dict(op_types_to_quantize=["MatMul", "Gemm"], nodes_to_exclude=keep),
        # Also the embedding Gather (per-tensor int8 table): the size candidate.
        "int8_gather": dict(op_types_to_quantize=["MatMul", "Gemm", "Gather"], nodes_to_exclude=keep),
    }
    for vname, kw in variants.items():
        path = os.path.join(a.out_dir, f"{NAME}_text.{vname}.onnx")
        quantize_dynamic(fp32, path, weight_type=QuantType.QInt8, **kw)
        o = ort.InferenceSession(path, providers=["CPUExecutionProvider"]).run(None, {"tokens": tokens})[0]
        cos = (o * o32).sum(-1)
        report[vname] = {"mb": round(os.path.getsize(path) / 1e6, 1), "sha256": sha256(path),
                         "cos_to_fp32_min": float(cos.min()), "cos_to_fp32_mean": float(cos.mean())}
    with open(os.path.join(a.out_dir, "text_export_report.json"), "w") as f:
        json.dump(report, f, indent=2)
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
