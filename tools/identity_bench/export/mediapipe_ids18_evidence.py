"""IDS-18 evidence: both parity cohorts plus the FaceBlendMask guard re-measure.

    python -m tools.identity_bench.export.mediapipe_ids18_evidence SOURCE.tflite MODEL.onnx OUTDIR

Cohorts: (1) "licensed" = the 29 IDS-2 bench targets (generated or rendered faces;
FIXTURES.md licences, non-commercial evaluation), aligned like the app's HyperSwap crop
(arcface_128 template, 256 px); (2) "synthetic" = 48 seeded procedural images plus seeded
augmentations of those crops. Corpora and the JSON go to OUTDIR, never into Git (only the
numbers are copied into SOURCE.md). Needs the bench's network fetch for fixtures and YuNet.
"""
import json
import math
import sys
from pathlib import Path

import cv2
import numpy as np

from tools.identity_bench import fixtures, pipeline  # noqa: E402
from tools.identity_bench.export import mediapipe_parity as parity  # noqa: E402

source, onnx_path, out = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
out.mkdir(parents=True, exist_ok=True)

# ---- synthetic cohort: seeded, procedural (no faces of anyone), float32 RGB in [0,1]
rng = np.random.default_rng(519)
yy, xx = np.mgrid[0:256, 0:256].astype(np.float32) / 255.0
synth = []
for i in range(48):
    base = np.stack([xx * rng.random(), yy * rng.random(), (1 - xx) * rng.random()], -1)
    img = (0.6 * base + 0.4 * rng.random(3)).astype(np.float32)
    canvas = (img * 255).astype(np.uint8).copy()
    for _ in range(int(rng.integers(2, 6))):
        c = tuple(int(v) for v in rng.integers(0, 256, 3))
        centre = tuple(int(v) for v in rng.integers(30, 226, 2))
        axes = tuple(int(v) for v in rng.integers(15, 90, 2))
        cv2.ellipse(canvas, centre, axes, float(rng.integers(0, 180)), 0, 360, c, -1)
    if i % 3 == 0:  # skin-toned oval with darker cap, a crude face-like layout
        skin = tuple(int(v) for v in (rng.integers(150, 240), rng.integers(100, 190), rng.integers(80, 160)))
        cv2.ellipse(canvas, (128, 140), (60, 80), 0, 0, 360, skin, -1)
        cv2.ellipse(canvas, (128, 85), (70, 40), 0, 180, 360, (40, 30, 20), -1)
    noise = rng.normal(0, 4, canvas.shape)
    synth.append(np.clip(canvas + noise, 0, 255).astype(np.float32) / 255.0)
synth = np.stack(synth).astype(np.float32)
np.save(out / "synthetic.npy", synth)

# ---- bench cohort: the 29 IDS-2 targets (synthetic/rendered faces, non-commercial eval licences)
detector = pipeline.Detector()
tmpl = pipeline.template_points("arcface_128", 256)
crops, coverage_rows = [], []
for target in fixtures.TARGETS:
    image = fixtures.target_image(target, detect=detector.detect)
    face = fixtures.pick_face(target, detector.detect(image))
    if face is None:
        coverage_rows.append({"target": target.id, "detected": False})
        continue
    crop, _ = pipeline.align(image, face.marks, "arcface_128", 256)
    rgb = crop[:, :, ::-1].astype(np.float32) / 255.0
    crops.append(rgb)
    coverage_rows.append({"target": target.id, "detected": True, "tags": list(target.tags)})
licensed = np.stack(crops).astype(np.float32)
np.save(out / "licensed.npy", licensed)

# Synthetic cohort, part 2: seeded augmentations of those crops (mirror, rotation, gamma,
# colour cast, noise, procedural backdrop blend) so face-skin and hair occur in it too.
aug_rng = np.random.default_rng(1918)
augmented = []
for idx, crop in enumerate(licensed):
    img = crop[:, ::-1] if idx % 2 else crop
    angle = float(aug_rng.uniform(-20, 20))
    m = cv2.getRotationMatrix2D((128, 128), angle, float(aug_rng.uniform(0.85, 1.15)))
    img = cv2.warpAffine(np.ascontiguousarray(img), m, (256, 256), borderMode=cv2.BORDER_REFLECT)
    img = np.clip(img, 0, 1) ** float(aug_rng.uniform(0.6, 1.6))
    img = img * aug_rng.uniform(0.8, 1.2, 3) + aug_rng.normal(0, 0.02, img.shape)
    img = 0.85 * img + 0.15 * synth[idx % len(synth)]
    augmented.append(np.clip(img, 0, 1).astype(np.float32))
synth = np.concatenate([synth, np.stack(augmented)]).astype(np.float32)
np.save(out / "synthetic.npy", synth)

# ---- parity (the committed harness, unchanged)
result = parity.compare(source, onnx_path, {"synthetic": out / "synthetic.npy", "licensed": out / "licensed.npy"})

# ---- guard coverage on the HyperSwap 256 crop, same rule as FaceBlendMask.decide
lm = tmpl.reshape(-1)
eye_x, eye_y = (lm[0] + lm[2]) / 2, (lm[1] + lm[3]) / 2
mouth_x, mouth_y = (lm[6] + lm[8]) / 2, (lm[7] + lm[9]) / 2
eye_dist = math.hypot(lm[2] - lm[0], lm[3] - lm[1])
eye_mouth = math.hypot(mouth_x - eye_x, mouth_y - eye_y)
oval_area = math.pi * (1.05 * eye_dist) * (0.95 * eye_mouth)

import onnxruntime as ort  # noqa: E402
from ai_edge_litert.interpreter import Interpreter  # noqa: E402
sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
lite = Interpreter(model_path=str(source), num_threads=1)
lite.allocate_tensors()
li, lo = lite.get_input_details()[0]["index"], lite.get_output_details()[0]["index"]
k = 0
for row in coverage_rows:
    if not row["detected"]:
        continue
    x = licensed[k][None]
    k += 1
    lite.set_tensor(li, x)
    lite.invoke()
    ref = lite.get_tensor(lo)[0].argmax(-1)
    onx = sess.run(None, {sess.get_inputs()[0].name: x})[0][0].argmax(-1)
    row["coverage_litert"] = round(float((ref == 3).sum() / oval_area), 3)
    row["coverage_onnx"] = round(float((onx == 3).sum() / oval_area), 3)
    row["crop_share_onnx"] = round(float((onx == 3).mean()), 3)
    row["decision_onnx"] = "segmenter" if row["coverage_onnx"] >= 0.5 else "fallback"
result["guard"] = {"min_coverage": 0.5, "crop": "hyperswap_1a_256 / arcface_128 @256",
                   "oval_area_px": round(oval_area, 1), "rows": coverage_rows}
(out / "evidence.json").write_text(json.dumps(result, indent=2, sort_keys=True))
print(json.dumps(result, indent=2, sort_keys=True))
