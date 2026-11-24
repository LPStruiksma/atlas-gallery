"""IDS-18: fail-closed offline converter; no automatic download.

tflite2onnx 0.4.1 (the first pin) stops at TFLite op 74 SUM in this model, so the
converter is tf2onnx's TFLite front end, pinned with the TensorFlow it was run with.
"""
import argparse
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import tempfile

SOURCE_URL = ("https://storage.googleapis.com/mediapipe-models/image_segmenter/"
              "selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite")
SOURCE_SIZE = 16_371_837
SOURCE_SHA256 = "c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0"
CONVERTER = "tf2onnx"
CONVERTER_VERSION = "1.17.0"
TENSORFLOW = "tensorflow-cpu"
TENSORFLOW_VERSION = "2.21.0"
OPSET = 17
INPUT_SHAPE = (1, 256, 256, 3)
OUTPUT_SHAPE = (1, 256, 256, 6)


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def verify_source(path: Path, *, size: int = SOURCE_SIZE, digest: str = SOURCE_SHA256) -> None:
    if path.stat().st_size != size or sha256(path) != digest:
        raise ValueError("MediaPipe source does not match the pinned size and SHA-256")


def validate_shapes(inputs, outputs):
    if list(map(tuple, inputs)) != [INPUT_SHAPE] or list(map(tuple, outputs)) != [OUTPUT_SHAPE]:
        raise ValueError("Expected NHWC [1,256,256,3] -> [1,256,256,6]; refused incompatible layout")


def onnx_shapes(model):
    def shape(value):
        return tuple(dim.dim_value if dim.HasField("dim_value") else -1
                     for dim in value.type.tensor_type.shape.dim)
    return ([shape(v) for v in model.graph.input], [shape(v) for v in model.graph.output])


def convert(source: Path, target: Path) -> dict:
    verify_source(source)
    for package, pinned in ((CONVERTER, CONVERTER_VERSION), (TENSORFLOW, TENSORFLOW_VERSION)):
        version = importlib.metadata.version(package)
        if version != pinned:
            raise RuntimeError(f"Expected {package}=={pinned}; got {version}")
    import onnx
    import tf2onnx

    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=target.parent, prefix="ids18-") as tmp:
        candidate = Path(tmp) / "converted.onnx"
        tf2onnx.convert.from_tflite(str(source), opset=OPSET, output_path=str(candidate))
        model = onnx.load(str(candidate), load_external_data=False)
        onnx.checker.check_model(model)
        validate_shapes(*onnx_shapes(model))
        digest = sha256(candidate)
        os.replace(candidate, target)
    return {"source_url": SOURCE_URL, "source_bytes": SOURCE_SIZE,
            "source_sha256": SOURCE_SHA256, "converter": CONVERTER,
            "converter_version": CONVERTER_VERSION, "tensorflow": TENSORFLOW_VERSION,
            "opset": OPSET, "onnx_sha256": digest, "onnx_bytes": target.stat().st_size,
            "parity": "run mediapipe_parity.py / mediapipe_ids18_evidence.py"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(convert(args.source, args.output), indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
