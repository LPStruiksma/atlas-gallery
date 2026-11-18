"""Host face-swap pipelines for the identity benchmark (IDS-2, issue #469).

One pipeline = detect (the shipped YuNet) -> 5-point align -> swap ->
mask (face parsing) -> colour match -> paste back at full resolution ->
optional restoration.  Everything here is offline evaluation code: it never
ships in the app, and the weights are downloaded at run time from their
original host into a cache outside the repository.

Model input conventions (template, size, mean/std, embedding handling) were
read from the public FaceFusion model table; the code is our own.
"""
from __future__ import annotations

import hashlib
import os
import threading
import time
import urllib.request
from dataclasses import dataclass, field

import numpy as np

try:  # OpenCV is needed for warps and the detector; tests skip without it.
    import cv2
except ImportError:  # pragma: no cover
    cv2 = None

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SHIPPED = os.path.join(REPO, "app", "src", "main", "assets", "models")
FF = "https://github.com/facefusion/facefusion-assets/releases/download/"

# name -> where it comes from, its bytes, and what the upstream says about its
# licence.  `licence` repeats the upstream table and is NOT a legal finding:
# docs/identity-studio/LICENCES.md (IDS-1, #468) decides integration.
MODELS = {
    "inswapper_128": dict(
        url=FF + "models-3.0.0/inswapper_128.onnx", size=555303150,
        sha256="a290273ed497312095dac48cdef20feec9d5208298223dd01288ab202b54bea7",
        licence="InsightFace, non-commercial research", role="swapper"),
    "hyperswap_1a_256": dict(
        url=FF + "models-3.3.0/hyperswap_1a_256.onnx", size=402742682,
        sha256="c0e98a8a03a238f461ed3d2570e426b49f46745ee400854a60dceeb70c246add",
        licence="FaceFusion, ResearchRAIL", role="swapper"),
    "hyperswap_1b_256": dict(
        url=FF + "models-3.3.0/hyperswap_1b_256.onnx", size=402742682,
        sha256="5124031789c42f71b9558fb71954ef7aedb6da7ed9fac79293e23c61a792a73e",
        licence="FaceFusion, ResearchRAIL", role="swapper"),
    "hyperswap_1c_256": dict(
        url=FF + "models-3.3.0/hyperswap_1c_256.onnx", size=402742682,
        sha256="5528c2d76fe9986c99d829278987ef9f3a630cb606db7628d02b57b330f406a5",
        licence="FaceFusion, ResearchRAIL", role="swapper"),
    "simswap_256": dict(
        url=FF + "models-3.0.0/simswap_256.onnx", size=220368724,
        sha256="5b6374b2ffab838588d4f0c32dc7ed5bb8d99237d755a663ed02ee265a1714fb",
        licence="SimSwap (neuralchen), non-commercial", role="swapper"),
    "crossface_simswap": dict(
        url=FF + "models-3.4.0/crossface_simswap.onnx", size=22083800,
        sha256="6452a261ec30cc30afdbe4a426d82c3b10a476f2df794e3494071c02574e6829",
        licence="FaceFusion embedding converter, terms unverified", role="converter"),
    "ghost_1_256": dict(
        url=FF + "models-3.0.0/ghost_1_256.onnx", size=514902950,
        sha256="4eac28021ae80128e9262080cf0da850e3190e41862650c9e805f9a516b3e924",
        licence="GHOST (ai-forever), Apache-2.0 per upstream", role="swapper"),
    "ghost_2_256": dict(
        url=FF + "models-3.0.0/ghost_2_256.onnx", size=738727769,
        sha256="27ae9d0b174b22c0426393e8239ca8d75e6ff47ec5dc596b002b182832a9454e",
        licence="GHOST (ai-forever), Apache-2.0 per upstream", role="swapper"),
    "ghost_3_256": dict(
        url=FF + "models-3.0.0/ghost_3_256.onnx", size=855514631,
        sha256="308d87f565e881b8872a5cbe711f97faeda6643e5d2b95ef757cc92a58662abd",
        licence="GHOST (ai-forever), Apache-2.0 per upstream", role="swapper"),
    "crossface_ghost": dict(
        url=FF + "models-3.4.0/crossface_ghost.onnx", size=22083800,
        sha256="9ec5862d9ff1f723a7380ea89baf87cddec9c56670d4db766702657939284957",
        licence="FaceFusion embedding converter, terms unverified", role="converter"),
    "alphaface_256": dict(
        url=FF + "models-3.9.0/alphaface_256.onnx", size=555624110,
        sha256="efcca3ffa1c28b75a007f689b39f7d4716c02810e7fa72d892e175f0b058a2e2",
        licence="AlphaFace, non-commercial per upstream", role="swapper"),
    "arcface_w600k_r50": dict(
        url=FF + "models-3.0.0/arcface_w600k_r50.onnx", size=174388474,
        sha256="f1f79dc3b0b79a69f94799af1fffebff09fbd78fd96a275fd8f0cbbea23270d1",
        licence="InsightFace buffalo_l, non-commercial research", role="identity encoder"),
    "glintr100": dict(
        url="https://github.com/deepinsight/insightface/releases/download/v0.7/antelopev2.zip",
        member="antelopev2/glintr100.onnx", size=260665334,
        sha256="4ab1d6435d639628a6f3e5008dd4f929edf4c4124b1a7169e1048f9fef534cdf",
        licence="InsightFace antelopev2, non-commercial research", role="research judge"),
    "bisenet_resnet_34": dict(
        url=FF + "models-3.0.0/bisenet_resnet_34.onnx", size=93632546,
        sha256="4a0b8c958a3c938913bd06a8365dbb3c8761afba6ecbf0d14b3b1f77eb230c96",
        licence="yakhyo face-parsing, MIT per upstream", role="face parser"),
    "selfie_multiclass_256": dict(
        url="https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/"
            "float32/latest/selfie_multiclass_256x256.tflite", file="selfie_multiclass_256.tflite", size=16371837,
        sha256="c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0",
        licence="MediaPipe multiclass selfie segmenter, Apache-2.0", role="face mask (licence-clean)"),
    "gfpgan_1.4": dict(
        url=FF + "models-3.0.0/gfpgan_1.4.onnx", size=340299087,
        sha256="accc4757b26bdb89b32b4d3500d4f79c9dff97c1dd7c7104bf9dcb95e3311385",
        licence="GFPGAN (TencentARC), Apache-2.0 per upstream", role="restoration"),
}
SHIPPED_MODELS = {
    "yunet": ("yunet/face_detection_yunet_2023mar.onnx",
              "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"),
    "sface": ("sface/face_recognition_sface_2021dec_int8.onnx",
              "2b0e941e6f16cc048c20aee0c8e31f569118f65d702914540f7bfdc14048d78a"),
}

# Five-point templates, normalised to the crop side (eyes, nose, mouth corners).
TEMPLATES = {
    "arcface_112_v1": [[0.35473214, 0.45658929], [0.64526786, 0.45658929], [0.50000000, 0.61154464],
                       [0.37913393, 0.77687500], [0.62086607, 0.77687500]],
    "arcface_112_v2": [[0.34191607, 0.46157411], [0.65653393, 0.45983393], [0.50022500, 0.64050536],
                       [0.37097589, 0.82469196], [0.63151696, 0.82325089]],
    "arcface_128": [[0.36167656, 0.40387734], [0.63696719, 0.40235469], [0.50019687, 0.56044219],
                    [0.38710391, 0.72160547], [0.61507734, 0.72034453]],
    "ffhq_512": [[0.37691676, 0.46864664], [0.62285697, 0.46912813], [0.50123859, 0.61331904],
                 [0.39308822, 0.72541100], [0.61150205, 0.72490465]],
}

# kind, template, crop side, mean, std, output range is [-1, 1]
SWAPPERS = {
    "inswapper_128": ("inswapper", "arcface_128", 128, (0.0,) * 3, (1.0,) * 3, False),
    "hyperswap_1a_256": ("hyperswap", "arcface_128", 256, (0.5,) * 3, (0.5,) * 3, True),
    "hyperswap_1b_256": ("hyperswap", "arcface_128", 256, (0.5,) * 3, (0.5,) * 3, True),
    "hyperswap_1c_256": ("hyperswap", "arcface_128", 256, (0.5,) * 3, (0.5,) * 3, True),
    "simswap_256": ("simswap", "arcface_112_v1", 256, (0.485, 0.456, 0.406), (0.229, 0.224, 0.225), False),
    "ghost_1_256": ("ghost", "arcface_112_v1", 256, (0.5,) * 3, (0.5,) * 3, True),
    "ghost_2_256": ("ghost", "arcface_112_v1", 256, (0.5,) * 3, (0.5,) * 3, True),
    "ghost_3_256": ("ghost", "arcface_112_v1", 256, (0.5,) * 3, (0.5,) * 3, True),
    "alphaface_256": ("alphaface", "arcface_128", 256, (0.0,) * 3, (1.0,) * 3, False),
}
# BiSeNet classes that belong to the replaced face: skin, brows, eyes, nose,
# mouth, lips.  Glasses (6), ears, hair, neck and cloth stay the target's.
FACE_CLASSES = (1, 2, 3, 4, 5, 10, 11, 12, 13)
THREADS = int(os.environ.get("IDENTITY_BENCH_THREADS", "4"))


def cache_dir() -> str:
    """Weights and fixtures live here, outside the repository."""
    root = os.environ.get("IDENTITY_BENCH_CACHE") or os.path.join(
        os.path.expanduser("~"), ".cache", "atlas-identity-bench")
    os.makedirs(root, exist_ok=True)
    return root


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def model_path(name: str, fetch: bool = True) -> str:
    """Path of a pinned model, downloaded from its original host on first use."""
    if name in SHIPPED_MODELS:
        rel, digest = SHIPPED_MODELS[name]
        path = os.path.join(SHIPPED, rel)
        if sha256_file(path) != digest:
            raise RuntimeError(f"{name}: bytes differ from the pinned APK asset")
        return path
    spec = MODELS[name]
    folder = os.path.join(cache_dir(), "models")
    os.makedirs(folder, exist_ok=True)
    path = os.path.join(folder, spec.get("member") or spec.get("file") or name + ".onnx")
    marker = path + ".sha256-ok"
    if os.path.exists(path) and os.path.exists(marker):
        return path
    if not os.path.exists(path):
        if not fetch:
            raise FileNotFoundError(path)
        _download(spec, folder, path)
    if sha256_file(path) != spec["sha256"]:
        raise RuntimeError(f"{name}: sha256 mismatch, refusing to use {path}")
    open(marker, "w").close()
    return path


def _download(spec: dict, folder: str, path: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    request = urllib.request.Request(spec["url"], headers={"User-Agent": "identity-bench"})
    tmp = os.path.join(folder, "download.tmp")
    with urllib.request.urlopen(request, timeout=600) as response, open(tmp, "wb") as out:
        for block in iter(lambda: response.read(1 << 20), b""):
            out.write(block)
    if "member" in spec:
        import zipfile
        with zipfile.ZipFile(tmp) as archive, open(path, "wb") as out:
            out.write(archive.read(spec["member"]))
        os.remove(tmp)
    else:
        os.replace(tmp, path)


def session(name: str):
    import onnxruntime as ort
    options = ort.SessionOptions()
    options.intra_op_num_threads = THREADS
    options.inter_op_num_threads = 1
    options.log_severity_level = 3
    return ort.InferenceSession(model_path(name), options, providers=["CPUExecutionProvider"])


# ---------------------------------------------------------------- geometry

def similarity_transform(src, dst) -> np.ndarray:
    """Least-squares similarity (Umeyama, no reflection) mapping src -> dst, 2x3."""
    src = np.asarray(src, np.float64)
    dst = np.asarray(dst, np.float64)
    ms, md = src.mean(0), dst.mean(0)
    sc, dc = src - ms, dst - md
    cov = dc.T @ sc / len(src)
    u, d, vt = np.linalg.svd(cov)
    sign = np.ones(2)
    if np.linalg.det(cov) < 0:
        sign[1] = -1
    rot = u @ np.diag(sign) @ vt
    scale = (d * sign).sum() / (sc ** 2).sum() * len(src)
    m = np.zeros((2, 3))
    m[:, :2] = scale * rot
    m[:, 2] = md - scale * rot @ ms
    return m


def template_points(template: str, side: int) -> np.ndarray:
    return np.asarray(TEMPLATES[template], np.float64) * side


def align(image: np.ndarray, marks, template: str, side: int):
    """Aligned crop and the 2x3 matrix that maps image -> crop."""
    m = similarity_transform(marks, template_points(template, side))
    scale = float(np.hypot(m[0, 0], m[0, 1]))
    flags = cv2.INTER_AREA if scale < 1 else cv2.INTER_LINEAR
    crop = cv2.warpAffine(image, m, (side, side), flags=flags, borderMode=cv2.BORDER_REPLICATE)
    return crop, m


def box_mask(side: int, margin: float = 0.06, blur: float = 0.04) -> np.ndarray:
    """1 inside the crop, falling to exactly 0 at the crop border."""
    mask = np.zeros((side, side), np.float32)
    pad = max(2, int(round(side * margin)))
    mask[pad:-pad, pad:-pad] = 1.0
    if cv2 is not None and blur > 0:
        mask = cv2.GaussianBlur(mask, (0, 0), side * blur)
        edge = np.zeros_like(mask)
        edge[1:-1, 1:-1] = 1.0
        mask *= edge  # the outermost crop pixels never blend
    return mask


def feather(binary: np.ndarray, sigma: float) -> np.ndarray:
    """Soft mask that only shrinks: 0 stays 0 outside the binary region."""
    soft = cv2.GaussianBlur(binary.astype(np.float32), (0, 0), sigma)
    return np.clip((soft - 0.5) * 2.0, 0.0, 1.0)


def colour_match(swapped: np.ndarray, original: np.ndarray, mask: np.ndarray, strength: float = 1.0) -> np.ndarray:
    """Match mean and spread of the swapped crop to the original, per Lab channel, inside the mask."""
    weight = mask.astype(np.float64)
    total = weight.sum()
    if total < 16:
        return swapped
    a = cv2.cvtColor(swapped, cv2.COLOR_BGR2LAB).astype(np.float64)
    b = cv2.cvtColor(original, cv2.COLOR_BGR2LAB).astype(np.float64)
    out = a.copy()
    for c in range(3):
        ma = (a[..., c] * weight).sum() / total
        mb = (b[..., c] * weight).sum() / total
        sa = np.sqrt((((a[..., c] - ma) ** 2) * weight).sum() / total) + 1e-6
        sb = np.sqrt((((b[..., c] - mb) ** 2) * weight).sum() / total) + 1e-6
        gain = np.clip(sb / sa, 0.6, 1.6)
        matched = (a[..., c] - ma) * gain + mb
        out[..., c] = a[..., c] + strength * (matched - a[..., c])
    return cv2.cvtColor(np.clip(out, 0, 255).astype(np.uint8), cv2.COLOR_LAB2BGR)


def paste_back(image: np.ndarray, crop: np.ndarray, mask: np.ndarray, matrix: np.ndarray):
    """Blend the crop back at the source resolution.

    Returns (result, full_mask).  Where full_mask is 0 the result is the
    source pixel itself, so the untouched region is bit-identical.
    """
    h, w = image.shape[:2]
    inverse = cv2.invertAffineTransform(matrix)
    side = crop.shape[0]
    corners = np.array([[0, 0], [side, 0], [side, side], [0, side]], np.float64)
    spots = corners @ inverse[:, :2].T + inverse[:, 2]
    x1, y1 = np.clip(np.floor(spots.min(0)).astype(int), 0, [w, h])
    x2, y2 = np.clip(np.ceil(spots.max(0)).astype(int), 0, [w, h])
    full_mask = np.zeros((h, w), np.float32)
    result = image.copy()
    if x2 <= x1 or y2 <= y1:
        return result, full_mask
    local = inverse.copy()
    local[:, 2] -= (x1, y1)
    size = (int(x2 - x1), int(y2 - y1))
    warped_mask = cv2.warpAffine(mask.astype(np.float32), local, size, flags=cv2.INTER_LINEAR,
                                 borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    warped_mask = np.where(warped_mask < 1.0 / 255.0, 0.0, np.clip(warped_mask, 0.0, 1.0)).astype(np.float32)
    warped = cv2.warpAffine(crop, local, size, flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
    region = image[y1:y2, x1:x2].astype(np.float32)
    alpha = warped_mask[..., None]
    blend = np.clip(np.rint(region * (1 - alpha) + warped.astype(np.float32) * alpha), 0, 255).astype(np.uint8)
    touched = warped_mask > 0
    out_region = result[y1:y2, x1:x2]
    out_region[touched] = blend[touched]
    full_mask[y1:y2, x1:x2] = warped_mask
    return result, full_mask


# ---------------------------------------------------------------- resources

class StageMeter:
    """Wall time, CPU time and peak resident memory per named stage."""

    def __init__(self):
        self.rows: dict[str, dict] = {}

    def stage(self, name: str):
        return _Stage(self, name)

    def add(self, name: str, wall: float, cpu: float, peak_mb: float):
        row = self.rows.setdefault(name, dict(wall_s=0.0, cpu_s=0.0, peak_rss_mb=0.0, calls=0))
        row["wall_s"] += wall
        row["cpu_s"] += cpu
        row["peak_rss_mb"] = max(row["peak_rss_mb"], peak_mb)
        row["calls"] += 1


def rss_mb() -> float:
    try:
        with open("/proc/self/statm") as f:
            return int(f.read().split()[1]) * os.sysconf("SC_PAGE_SIZE") / 1e6
    except OSError:  # not Linux: fall back to the process high-water mark
        import resource
        return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1e3


class _Stage:
    def __init__(self, meter: StageMeter, name: str):
        self.meter, self.name = meter, name

    def __enter__(self):
        self.peak = rss_mb()
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self._sample, daemon=True)
        self.thread.start()
        self.wall, self.cpu = time.perf_counter(), time.process_time()
        return self

    def _sample(self):
        while not self.stop.wait(0.01):
            self.peak = max(self.peak, rss_mb())

    def __exit__(self, *exc):
        wall, cpu = time.perf_counter() - self.wall, time.process_time() - self.cpu
        self.stop.set()
        self.thread.join()
        self.meter.add(self.name, wall, cpu, max(self.peak, rss_mb()))
        return False


# ---------------------------------------------------------------- models

@dataclass
class Face:
    box: np.ndarray        # x, y, w, h in image pixels
    marks: np.ndarray      # 5 x 2: eyes, nose, mouth corners (image-left first)
    score: float


class Detector:
    """The shipped YuNet, letterboxed like the app (640 px, top-left)."""

    def __init__(self, score: float = 0.6, input_side: int = 640):
        self.side = input_side
        self.net = cv2.FaceDetectorYN.create(model_path("yunet"), "", (input_side, input_side), score, 0.3, 5000)

    def detect(self, image: np.ndarray) -> list[Face]:
        h, w = image.shape[:2]
        scale = self.side / max(h, w)
        small = cv2.resize(image, (max(1, round(w * scale)), max(1, round(h * scale))),
                           interpolation=cv2.INTER_AREA if scale < 1 else cv2.INTER_LINEAR)
        canvas = np.zeros((self.side, self.side, 3), np.uint8)
        canvas[:small.shape[0], :small.shape[1]] = small
        _, found = self.net.detect(canvas)
        faces = []
        for row in (found if found is not None else []):
            row = row.copy()
            row[:14] /= scale
            faces.append(Face(row[:4], row[4:14].reshape(5, 2), float(row[14])))
        faces.sort(key=lambda f: -float(f.box[2] * f.box[3]))
        return faces


class Embedder:
    """ArcFace-style 112 px identity encoder.  `sface` is the shipped model."""

    def __init__(self, name: str):
        self.name = name
        self.net = session(name)
        self.input = self.net.get_inputs()[0].name

    def embed_crop(self, crop112: np.ndarray) -> np.ndarray:
        rgb = crop112[:, :, ::-1].astype(np.float32)
        if self.name != "sface":            # SFace takes 0..255 RGB
            rgb = rgb / 127.5 - 1.0
        return self.net.run(None, {self.input: rgb.transpose(2, 0, 1)[None]})[0][0].astype(np.float64)

    def embed(self, image: np.ndarray, marks) -> np.ndarray:
        if self.name == "sface":            # zero border and bilinear, as SFaceAlignment.kt
            m = similarity_transform(marks, template_points("arcface_112_v2", 112))
            crop = cv2.warpAffine(image, m, (112, 112), flags=cv2.INTER_LINEAR,
                                  borderMode=cv2.BORDER_CONSTANT, borderValue=0)
        else:
            crop, _ = align(image, marks, "arcface_112_v2", 112)
        return self.embed_crop(crop)


def unit(vector: np.ndarray) -> np.ndarray:
    return vector / max(float(np.linalg.norm(vector)), 1e-12)


class Swapper:
    """One identity-conditioned swap network behind a common call."""

    def __init__(self, name: str):
        self.name = name
        self.kind, self.template, self.side, mean, std, self.signed = SWAPPERS[name]
        self.mean = np.asarray(mean, np.float32)
        self.std = np.asarray(std, np.float32)
        self.net = session(name)
        self.converter = None
        if self.kind in ("simswap", "ghost"):
            self.converter = session("crossface_" + self.kind)
        self.emap = None
        if self.kind == "inswapper":
            import onnx
            from onnx import numpy_helper
            graph = onnx.load(model_path(name)).graph
            self.emap = numpy_helper.to_array(graph.initializer[-1]).astype(np.float64)

    def source_vector(self, raw_embedding: np.ndarray) -> np.ndarray:
        """Turn the raw w600k_r50 embedding into what this network expects."""
        e = np.asarray(raw_embedding, np.float64).reshape(1, -1)
        if self.kind == "inswapper":
            e = e @ self.emap / np.linalg.norm(e)
        elif self.kind == "hyperswap":
            e = e / np.linalg.norm(e)
        elif self.kind in ("simswap", "ghost"):
            e = self.converter.run(None, {"input": e.astype(np.float32)})[0].astype(np.float64)
            if self.kind == "simswap":
                e = e / np.linalg.norm(e)
        return e.astype(np.float32)

    def swap_crop(self, crop: np.ndarray, source: np.ndarray) -> np.ndarray:
        x = crop[:, :, ::-1].astype(np.float32) / 255.0
        x = ((x - self.mean) / self.std).transpose(2, 0, 1)[None]
        out = self.net.run(None, {"target": x, "source": source})[0][0].transpose(1, 2, 0)
        if self.signed:
            out = out * self.std + self.mean
        return np.clip(np.rint(out[:, :, ::-1] * 255.0), 0, 255).astype(np.uint8)


class NoSwap:
    """Control: hands the aligned crop back unchanged (measures the round trip alone)."""
    name, kind, template, side = "control-none", "none", "arcface_128", 256

    def source_vector(self, raw_embedding):
        return None

    def swap_crop(self, crop, source):
        return crop.copy()


class FaceParser:
    def __init__(self):
        self.net = session("bisenet_resnet_34")

    def face_region(self, crop: np.ndarray, classes=FACE_CLASSES) -> np.ndarray:
        """Binary face-region map at the crop's own size."""
        x = cv2.resize(crop, (512, 512), interpolation=cv2.INTER_LINEAR)[:, :, ::-1].astype(np.float32) / 255.0
        x = (x - np.array([0.485, 0.456, 0.406], np.float32)) / np.array([0.229, 0.224, 0.225], np.float32)
        logits = self.net.run(None, {"input": x.transpose(2, 0, 1)[None]})[0][0]
        region = np.isin(logits.argmax(0), classes).astype(np.float32)
        return cv2.resize(region, crop.shape[1::-1], interpolation=cv2.INTER_LINEAR)


class SelfieSegmenter:
    """MediaPipe multiclass selfie segmenter (LiteRT): class 3 is face skin.

    Glasses, hair and anything in front of the face fall in other classes, so
    the same call also keeps occluders out of the mask.
    """

    def __init__(self):
        from ai_edge_litert.interpreter import Interpreter
        self.net = Interpreter(model_path=model_path("selfie_multiclass_256"), num_threads=THREADS)
        self.net.allocate_tensors()
        self.input = self.net.get_input_details()[0]["index"]
        self.output = self.net.get_output_details()[0]["index"]

    def face_region(self, crop: np.ndarray) -> np.ndarray:
        x = cv2.resize(crop, (256, 256), interpolation=cv2.INTER_LINEAR)[:, :, ::-1].astype(np.float32) / 255.0
        self.net.set_tensor(self.input, x[None])
        self.net.invoke()
        region = (self.net.get_tensor(self.output)[0].argmax(-1) == 3).astype(np.float32)
        return cv2.resize(region, crop.shape[1::-1], interpolation=cv2.INTER_LINEAR)


class Restorer:
    """GFPGAN 1.4 on the FFHQ 512 crop."""
    template, side = "ffhq_512", 512

    def __init__(self):
        self.net = session("gfpgan_1.4")

    def restore_crop(self, crop: np.ndarray) -> np.ndarray:
        x = (crop[:, :, ::-1].astype(np.float32) / 255.0 - 0.5) / 0.5
        out = self.net.run(None, {"input": x.transpose(2, 0, 1)[None]})[0][0]
        out = (np.clip(out, -1, 1) + 1) / 2
        return np.clip(np.rint(out.transpose(1, 2, 0)[:, :, ::-1] * 255.0), 0, 255).astype(np.uint8)


# ---------------------------------------------------------------- pipeline

@dataclass(frozen=True)
class Config:
    """One benchmark candidate.  `label` is what tables and sheet keys print."""
    swapper: str
    restore: bool = False
    # "box" | "hard-box" (control) | "parse" (BiSeNet on the swapped crop) |
    # "parse-both" (BiSeNet, swapped and original crop) | "mp" (MediaPipe, swapped and original crop)
    mask: str = "parse"
    colour: bool = True
    restore_blend: float = 0.8

    @property
    def label(self) -> str:
        parts = [self.swapper]
        if self.mask != "parse":
            parts.append("mask-" + self.mask)
        if not self.colour:
            parts.append("no-colour")
        if self.restore:
            parts.append("gfpgan")
        return "+".join(parts)


@dataclass
class SwapResult:
    image: np.ndarray
    mask: np.ndarray               # full-resolution blend weight, 0 = untouched
    crop_before: np.ndarray
    crop_after: np.ndarray
    notes: list = field(default_factory=list)


class Toolbox:
    """Lazily loaded models shared by every candidate."""

    def __init__(self, meter: StageMeter | None = None):
        self.meter = meter or StageMeter()
        self._cache: dict = {}

    def get(self, key: str, factory):
        if key not in self._cache:
            with self.meter.stage("load:" + key):
                self._cache[key] = factory()
        return self._cache[key]

    def drop(self, key: str):
        self._cache.pop(key, None)

    @property
    def detector(self) -> Detector:
        return self.get("yunet", Detector)

    @property
    def encoder(self) -> Embedder:
        return self.get("arcface_w600k_r50", lambda: Embedder("arcface_w600k_r50"))

    @property
    def parser(self) -> FaceParser:
        return self.get("bisenet_resnet_34", FaceParser)

    @property
    def selfie(self) -> SelfieSegmenter:
        return self.get("selfie_multiclass_256", SelfieSegmenter)

    @property
    def restorer(self) -> Restorer:
        return self.get("gfpgan_1.4", Restorer)

    def swapper(self, name: str):
        return self.get(name, NoSwap if name == NoSwap.name else (lambda: Swapper(name)))


def swap_face(image: np.ndarray, marks, source_raw: np.ndarray, config: Config, tools: Toolbox,
              swapper=None, jitter: np.ndarray | None = None) -> SwapResult:
    """Replace the face at `marks` in `image` with the identity `source_raw`.

    `source_raw` is the mean raw (un-normalised) w600k_r50 embedding of the
    reference photos.  `jitter` (5 x 2 pixels) perturbs the landmarks for the
    stability check.  `swapper` lets a test inject a stand-in network.
    """
    meter = tools.meter
    net = swapper or tools.swapper(config.swapper)
    marks = np.asarray(marks, np.float64) + (0 if jitter is None else jitter)
    notes = []
    with meter.stage("align"):
        crop, matrix = align(image, marks, net.template, net.side)
    with meter.stage("swap"):
        swapped = net.swap_crop(crop, net.source_vector(source_raw))
    with meter.stage("mask"):
        mask = box_mask(net.side, blur=0.0 if config.mask == "hard-box" else 0.04)
        if config.mask in ("parse", "parse-both", "mp"):
            segmenter = tools.selfie if config.mask == "mp" else tools.parser
            region = segmenter.face_region(swapped) > 0.5
            if config.mask != "parse":      # the original crop keeps occluders and glasses out
                region &= segmenter.face_region(crop) > 0.5
            region = feather(region, net.side * 0.02)
            if region.sum() < 0.02 * region.size:
                notes.append("no face region found in the crop; box mask used")
            else:
                mask = np.minimum(mask, region)
    if config.colour:
        with meter.stage("colour"):
            swapped = colour_match(swapped, crop, mask)
    with meter.stage("paste"):
        result, full_mask = paste_back(image, swapped, mask, matrix)
    if config.restore:
        with meter.stage("restore"):
            result, full_mask = _restore(image, result, full_mask, marks, config, tools)
    return SwapResult(result, full_mask, crop, swapped, notes)


def _restore(original, swapped_image, full_mask, marks, config: Config, tools: Toolbox):
    """Run restoration on the swapped face only, inside the same blend mask."""
    restorer = tools.restorer
    crop, matrix = align(swapped_image, marks, restorer.template, restorer.side)
    restored = restorer.restore_crop(crop)
    crop_mask = cv2.warpAffine(full_mask, matrix, (restorer.side, restorer.side), flags=cv2.INTER_LINEAR,
                               borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    crop_mask = np.minimum(crop_mask, box_mask(restorer.side)) * float(config.restore_blend)
    result, restore_mask = paste_back(swapped_image, restored, crop_mask, matrix)
    # Restoration may never touch a pixel the swap left alone.
    outside = full_mask <= 0
    result[outside] = original[outside]
    return result, np.where(outside, 0.0, np.maximum(full_mask, restore_mask)).astype(np.float32)
