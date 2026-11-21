"""IDS-18 real two-runtime parity; requires synthetic AND licence-cleared .npy RGB crops."""
import argparse
import json
from pathlib import Path
from .mediapipe_segmenter_to_onnx import sha256, verify_source

LABELS = ("background", "hair", "body-skin", "face-skin", "clothes", "others")


def score(reference, converted):
    import numpy as np
    if reference.shape != converted.shape or reference.shape[-1] != len(LABELS):
        raise ValueError("Incompatible output shape")
    if not np.isfinite(reference).all() or not np.isfinite(converted).all():
        raise ValueError("Non-finite output")
    original = reference.argmax(axis=-1)
    exported = converted.argmax(axis=-1)
    result = {}
    for k, label in enumerate(LABELS):
        union = np.logical_or(original == k, exported == k).sum()
        result[label] = (float(np.logical_and(original == k, exported == k).sum() / union)
                         if union else None)
    return result, float(np.max(np.abs(reference - converted)))


def compare(source: Path, onnx: Path, corpora: dict) -> dict:
    import numpy as np
    import onnxruntime as ort
    from ai_edge_litert.interpreter import Interpreter
    verify_source(source)
    tflite = Interpreter(model_path=str(source), num_threads=1)
    tflite.allocate_tensors()
    inputs, outputs = tflite.get_input_details(), tflite.get_output_details()
    if len(inputs) != 1 or len(outputs) != 1 or tuple(inputs[0]["shape"]) != (1, 256, 256, 3):
        raise ValueError("Unexpected TFLite IO layout")
    session = ort.InferenceSession(str(onnx), providers=["CPUExecutionProvider"])
    if len(session.get_inputs()) != 1 or len(session.get_outputs()) != 1:
        raise ValueError("Unexpected ONNX IO count")
    evidence = {}
    for group, path in corpora.items():
        images = np.load(path, allow_pickle=False)
        if images.ndim != 4 or images.shape[1:] != (256, 256, 3) or images.dtype != np.float32 or not len(images):
            raise ValueError("Expected nonempty float32 NHWC RGB corpus")
        if not np.isfinite(images).all() or images.min() < 0 or images.max() > 1:
            raise ValueError("Corpus not finite RGB in [0,1]")
        refs, results = [], []
        for image in images:
            sample = image[None]
            tflite.set_tensor(inputs[0]["index"], sample)
            tflite.invoke()
            left = tflite.get_tensor(outputs[0]["index"])
            right = session.run(None, {session.get_inputs()[0].name: sample})[0]
            if left.shape != (1, 256, 256, 6) or right.shape != left.shape:
                raise ValueError("Unexpected class output layout")
            refs.append(left[0])
            results.append(right[0])
        iou, max_difference = score(np.stack(refs), np.stack(results))
        evidence[group] = {"samples": len(images), "corpus_sha256": sha256(path),
                           "per_class_iou": iou, "max_abs_output_difference": max_difference}
    return {"source_sha256": sha256(source), "onnx_sha256": sha256(onnx), "evidence": evidence}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for key in ("source", "onnx", "synthetic", "licensed"):
        p.add_argument("--" + key, type=Path, required=True)
    p.add_argument("--licensed-provenance", required=True,
                   help="Licence-cleared dataset provenance; never commit private media")
    args = p.parse_args()
    result = compare(args.source, args.onnx, {"synthetic": args.synthetic, "licensed": args.licensed})
    result["licensed_provenance"] = args.licensed_provenance
    print(json.dumps(result, indent=2, sort_keys=True))
    if any(v is None or v < 0.99 for group in result["evidence"].values()
           for v in group["per_class_iou"].values()):
        raise SystemExit("FAIL: every class requires IoU >= 0.99 for both corpora")


if __name__ == "__main__":
    main()
