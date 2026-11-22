"""Export the IDS-17 (#516) pre/post-processing parity fixture for the Kotlin JVM tests.

    python3 -m tools.identity_bench.export.hyperswap_fixture            # maths only, no weights
    python3 -m tools.identity_bench.export.hyperswap_fixture --real     # also run the real models

Writes `app/src/test/resources/identity/hyperswap_io_fixture.json`: small statistics of
the tensors the bench builds for ONE synthetic face, so `HyperSwapNetworkTest` can check
that the app's Kotlin pre- and post-processing matches the bench's maths with no weights.

The synthetic face is drawn from integer rules (`synthetic_face`) that the Kotlin test
re-draws bit for bit; no photo, no real person. Everything goes through the bench's own
code (`pipeline.align`, `Embedder.embed_crop`, `Swapper.source_vector`, `Swapper.swap_crop`);
the networks are replaced by recorders that capture the input tensor and return a
synthetic output tensor, so the maths around the networks is exported, not the networks.

`--real` additionally runs FaceFusion's `arcface_w600k_r50` and `hyperswap_1a_256` from the
bench cache (downloaded from their original hosts on first use, never committed) and
records the raw embedding and the real output statistics, for information only.
"""
import argparse
import json
import os

import numpy as np

from tools.identity_bench import pipeline

OUT = os.path.join(pipeline.REPO, "app", "src", "test", "resources", "identity", "hyperswap_io_fixture.json")
W, H = 200, 240
# Eyes, nose tip, mouth corners (x, y) in source pixels, matching the drawing below.
LANDMARKS = [[78.0, 105.0], [122.0, 105.0], [100.0, 130.0], [82.0, 155.0], [118.0, 155.0]]
# Pixel indices (into a crop, row-major) whose values the fixture records exactly.
SAMPLES_112 = [0, 111, 5000, 6216, 6300, 7000, 8100, 12543]
SAMPLES_256 = [0, 255, 20000, 30000, 32896, 40000, 50000, 65535]


def synthetic_face() -> np.ndarray:
    """RGB uint8, integer rules only (the Kotlin test draws the same pixels)."""
    img = np.zeros((H, W, 3), np.uint8)
    for y in range(H):
        for x in range(W):
            r = 40 + (x * 120) // (W - 1)
            g = 60 + (y * 100) // (H - 1)
            b = 90 + ((x + y) * 60) // (W + H - 2)
            dx, dy = x - 100, y - 120
            if dx * dx * 78 * 78 + dy * dy * 60 * 60 <= 60 * 60 * 78 * 78:   # face oval
                r, g, b = 210 - (y - 42) // 6, 165 - (x - 40) // 8, 130
            for ex in (78, 122):                                              # eyes
                if (x - ex) ** 2 + (y - 105) ** 2 <= 49:
                    r, g, b = 40, 30, 30
            if (x - 100) ** 2 + (y - 130) ** 2 <= 25:                         # nose
                r, g, b = 180, 120, 100
            if 82 <= x <= 118 and 152 <= y <= 158:                            # mouth
                r, g, b = 150, 50, 60
            img[y, x] = (r, g, b)
    return img


def synthetic_output(side: int) -> np.ndarray:
    """1x3xSxS float32 in about [-1.1, 1.09]; values outside [-1, 1] test the clip."""
    n = np.arange(side * side, dtype=np.int64)
    planes = [((n * 7 + k * 13) % 241).astype(np.float32) / np.float32(110) - np.float32(1.1) for k in range(3)]
    return np.stack(planes).reshape(1, 3, side, side).astype(np.float32)


class Recorder:
    """Stands in for an ONNX session: records the input, returns a fixed output."""

    def __init__(self, output):
        self.output = output
        self.inputs = None

    def run(self, _names, feeds):
        self.inputs = {k: np.array(v) for k, v in feeds.items()}
        return [self.output]


def tensor_stats(t: np.ndarray, samples) -> dict:
    """t is 1x3xSxS. Per-channel mean/std and the values at the sample pixels."""
    planes = t.reshape(3, -1).astype(np.float64)
    return dict(mean=[round(float(p.mean()), 6) for p in planes],
                std=[round(float(p.std()), 6) for p in planes],
                samples=[[round(float(planes[k, i]), 6) for k in range(3)] for i in samples])


def crop_rgb(crop_bgr: np.ndarray, samples) -> dict:
    rgb = crop_bgr[:, :, ::-1].reshape(-1, 3).astype(np.float64)
    return dict(mean=[round(float(rgb[:, k].mean()), 4) for k in range(3)],
                samples=[[int(v) for v in rgb[i]] for i in samples])


def matrix(m) -> list:
    return [round(float(v), 8) for v in np.asarray(m).reshape(-1)]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--real", action="store_true", help="also run the real models from the bench cache")
    args = ap.parse_args()

    image = synthetic_face()[:, :, ::-1].copy()      # the bench works in BGR
    marks = np.asarray(LANDMARKS, np.float64)
    fixture = dict(
        about="IDS-17 (#516) parity fixture, exported by tools/identity_bench/export/hyperswap_fixture.py. "
              "Synthetic face only; no weights; statistics of the bench's own tensors.",
        image=dict(width=W, height=H, landmarks=LANDMARKS))

    # ArcFace w600k_r50 input: 112 px, template arcface_112_v2, RGB, x / 127.5 - 1.
    crop112, m112 = pipeline.align(image, marks, "arcface_112_v2", 112)
    emb = pipeline.Embedder.__new__(pipeline.Embedder)
    emb.name, emb.input = "arcface_w600k_r50", "input"
    emb.net = Recorder(np.zeros((1, 512), np.float32))
    emb.embed_crop(crop112)
    fixture["arcface"] = dict(
        cropSize=112, template=matrix(pipeline.template_points("arcface_112_v2", 112)), matrix=matrix(m112),
        crop=crop_rgb(crop112, SAMPLES_112), input=tensor_stats(emb.net.inputs["input"], SAMPLES_112),
        samplePixels=SAMPLES_112)

    # HyperSwap 1a 256: template arcface_128 at 256 px, RGB, (x/255 - 0.5) / 0.5; output back the same way.
    kind, template, side, mean, std, signed = pipeline.SWAPPERS["hyperswap_1a_256"]
    crop256, m256 = pipeline.align(image, marks, template, side)
    sw = pipeline.Swapper.__new__(pipeline.Swapper)
    sw.name, sw.kind, sw.template, sw.side, sw.signed = "hyperswap_1a_256", kind, template, side, signed
    sw.mean, sw.std = np.asarray(mean, np.float32), np.asarray(std, np.float32)
    sw.converter, sw.emap = None, None
    out = synthetic_output(side)
    sw.net = Recorder(out)
    # A fixed raw identity vector (not a real embedding) for the source normalisation.
    raw = np.asarray([((i * 37) % 101 - 50) / 25.0 for i in range(512)], np.float64)
    source = sw.source_vector(raw)
    result = sw.swap_crop(crop256, source)
    rgb = result[:, :, ::-1].reshape(-1, 3).astype(np.int64)
    fixture["hyperswap"] = dict(
        cropSize=side, templateName=template, template=matrix(pipeline.template_points(template, side)),
        matrix=matrix(m256), crop=crop_rgb(crop256, SAMPLES_256),
        target=tensor_stats(sw.net.inputs["target"], SAMPLES_256), samplePixels=SAMPLES_256,
        inputs=sorted(sw.net.inputs), sourceShape=list(sw.net.inputs["source"].shape),
        rawIdentityRule="raw[i] = ((i * 37) % 101 - 50) / 25",
        source=dict(norm=round(float(np.linalg.norm(source)), 6), first=[round(float(v), 6) for v in source[0, :8]]),
        syntheticOutputRule="out[k][n] = float32((n * 7 + k * 13) % 241) / 110f - 1.1f, n = y * 256 + x, k = R,G,B",
        result=dict(sum=[int(rgb[:, k].sum()) for k in range(3)],
                    clipped0=[int((rgb[:, k] == 0).sum()) for k in range(3)],
                    clipped255=[int((rgb[:, k] == 255).sum()) for k in range(3)],
                    samples=[[int(v) for v in rgb[i]] for i in SAMPLES_256]))

    if args.real:
        real_emb = pipeline.Embedder("arcface_w600k_r50").embed_crop(crop112)
        real_sw = pipeline.Swapper("hyperswap_1a_256")
        vec = real_sw.source_vector(real_emb)
        x = crop256[:, :, ::-1].astype(np.float32) / 255.0
        x = ((x - real_sw.mean) / real_sw.std).transpose(2, 0, 1)[None]
        real_out = real_sw.net.run(None, {"target": x, "source": vec})[0]
        fixture["real"] = dict(
            note="Information only: the real networks on the synthetic face (bench cache, original hosts).",
            embeddingNorm=round(float(np.linalg.norm(real_emb)), 4),
            embeddingFirst=[round(float(v), 5) for v in real_emb[:8]],
            outputMean=[round(float(v), 5) for v in real_out.reshape(3, -1).mean(1)],
            outputRange=[round(float(real_out.min()), 5), round(float(real_out.max()), 5)])

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as f:
        json.dump(fixture, f, indent=1)
        f.write("\n")
    print(OUT, os.path.getsize(OUT), "bytes")


if __name__ == "__main__":
    main()
