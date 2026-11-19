"""Benchmark fixtures: synthetic faces only, fetched by URL and hash.

Nothing here is a photo of a real person.  Every file is one member of a
public archive; the manifest pins its byte offset, size and SHA-256, so one
ranged request fetches it and a changed byte is refused.  Images are cached
outside the repository and never committed.  Licences: FIXTURES.md.
"""
from __future__ import annotations

import hashlib
import io
import json
import os
import struct
import time
import urllib.request
import zlib
from dataclasses import dataclass

import numpy as np

try:
    import cv2
except ImportError:  # pragma: no cover
    cv2 = None

SOURCES = {
    "flux": dict(
        name="FLUXSynID", licence="CC BY-NC 4.0",
        url="https://zenodo.org/api/records/15172770/files/FLUXSynID.zip/content",
        page="https://zenodo.org/records/15172770", archive_md5="af90cbb852c21c5ac3e5361f3885258d",
        prefix="FLUXSynID/FLUXSynID/"),
    "digi": dict(
        name="DigiFace-1M (72 images per identity, part 1)", licence="Microsoft R-UDA 1.0 (non-commercial research)",
        url="https://facesyntheticspubwedata.z6.web.core.windows.net/wacv-2023/subjects_0-1999_72_imgs.zip",
        page="https://github.com/microsoft/DigiFace1M", prefix=""),
    "fs": dict(
        name="Face Synthetics (1,000-image sample)", licence="Microsoft R-UDA 1.0 (non-commercial research)",
        url="https://facesyntheticspubwedata.z6.web.core.windows.net/iccv-2021/dataset_1000.zip",
        page="https://github.com/microsoft/FaceSynthetics", prefix=""),
}
FLUX_POOL_BYTES = 209_715_200       # the pool is every identity fully inside the first 200 MiB
FLUX_DEV_IDENTITIES = 60            # the first 60 pool identities are for trying things out
FLUX_KINDS = ("doc", "live_0_e_d1", "live_0_a_d1", "live_0_p_d1")


def _cache() -> str:
    root = os.environ.get("IDENTITY_BENCH_CACHE") or os.path.join(
        os.path.expanduser("~"), ".cache", "atlas-identity-bench")
    path = os.path.join(root, "fixtures")
    os.makedirs(path, exist_ok=True)
    return path


# ---------------------------------------------------------------- archives

def _range(url: str, first: int, last: int) -> bytes:
    request = urllib.request.Request(url, headers={
        "Range": f"bytes={first}-{last}", "User-Agent": "identity-bench"})
    for attempt in range(5):
        try:
            with urllib.request.urlopen(request, timeout=600) as response:
                return response.read()
        except OSError:
            if attempt == 4:
                raise
            time.sleep(2 ** attempt)
    raise AssertionError("unreachable")


def _head(source: str, length: int) -> bytes:
    """The first `length` bytes of an archive, kept in the cache for the selection step."""
    path = os.path.join(_cache(), f"{source}-head-{length}.bin")
    if not os.path.exists(path):
        with open(path + ".tmp", "wb") as f:
            f.write(_range(SOURCES[source]["url"], 0, length - 1))
        os.replace(path + ".tmp", path)
    with open(path, "rb") as f:
        return f.read()


def member_bytes(blob: bytes, size: int) -> bytes:
    """Decode one zip member from bytes that start at its local header."""
    sig, _, _, method, _, _, _, _, _, name_len, extra_len = struct.unpack("<IHHHHHIIIHH", blob[:30])
    if sig != 0x04034B50:
        raise ValueError("not a zip local header")
    body = blob[30 + name_len + extra_len:]
    data = body[:size] if method == 0 else zlib.decompressobj(-15).decompress(body)
    if len(data) != size:
        raise ValueError("zip member is truncated")
    return data


def _span(size: int) -> int:
    """Bytes to request for a member of `size`: header, name and deflate slack."""
    return 30 + 1024 + size + size // 1000 + 1024


class RemoteArchive(io.RawIOBase):
    """Seekable read-only view of a remote file, for zipfile's directory read."""

    def __init__(self, url: str, block: int = 4 << 20):
        self.url, self.block, self.pos, self.blocks = url, block, 0, {}
        request = urllib.request.Request(url, headers={"Range": "bytes=0-0", "User-Agent": "identity-bench"})
        with urllib.request.urlopen(request, timeout=600) as response:
            self.size = int(response.headers["Content-Range"].split("/")[1])

    def seekable(self):
        return True

    def readable(self):
        return True

    def tell(self):
        return self.pos

    def seek(self, offset, whence=0):
        self.pos = offset if whence == 0 else (self.pos + offset if whence == 1 else self.size + offset)
        return self.pos

    def read(self, n=-1):
        end = self.size if n < 0 else min(self.size, self.pos + n)
        if end <= self.pos:
            return b""
        first, last = self.pos // self.block, (end - 1) // self.block
        missing = [b for b in range(first, last + 1) if b not in self.blocks]
        if missing:
            data = _range(self.url, missing[0] * self.block, min((missing[-1] + 1) * self.block, self.size) - 1)
            for i, b in enumerate(range(missing[0], missing[-1] + 1)):
                self.blocks[b] = data[i * self.block:(i + 1) * self.block]
        out = b"".join(self.blocks[b] for b in range(first, last + 1))
        start = self.pos - first * self.block
        self.pos = end
        return out[start:start + (end - (first * self.block + start))]


def archive_index(source: str) -> dict:
    """{member: (size, header offset)} read from the remote central directory (cached)."""
    path = os.path.join(_cache(), source + "-index.json")
    if os.path.exists(path):
        with open(path) as f:
            return {k: tuple(v) for k, v in json.load(f).items()}
    import zipfile
    with zipfile.ZipFile(RemoteArchive(SOURCES[source]["url"])) as archive:
        index = {i.filename: (i.file_size, i.header_offset) for i in archive.infolist() if not i.is_dir()}
    with open(path, "w") as f:
        json.dump(index, f)
    return index


def fetch(file_id: str, files: dict | None = None) -> str:
    """Local path of one pinned fixture file, fetched and hash-checked on first use."""
    source, member, offset, size, digest = (files or FILES)[file_id]
    path = os.path.join(_cache(), source, member)
    if not os.path.exists(path):
        blob = _range(SOURCES[source]["url"], offset, offset + _span(size) - 1)
        data = member_bytes(blob, size)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path + ".tmp", "wb") as f:
            f.write(data)
        os.replace(path + ".tmp", path)
    with open(path, "rb") as f:
        if hashlib.sha256(f.read()).hexdigest() != digest:
            raise RuntimeError(f"{file_id}: sha256 differs from the manifest ({path})")
    return path


def load(file_id: str) -> np.ndarray:
    image = cv2.imread(fetch(file_id), cv2.IMREAD_COLOR)
    if image is None:
        raise RuntimeError(f"{file_id}: not an image")
    return image


# ---------------------------------------------------------------- derived targets

def low_light(image: np.ndarray, gain: float = 0.16, noise: float = 5.0, seed: int = 469) -> np.ndarray:
    """Underexposed copy with sensor-like noise (deterministic)."""
    rng = np.random.default_rng(seed)
    dark = image.astype(np.float32) * gain + rng.normal(0.0, noise, image.shape)
    return np.clip(np.rint(dark), 0, 255).astype(np.uint8)


def small_face(image: np.ndarray, scale: float) -> np.ndarray:
    """The photo shrunk onto a blurred backdrop of itself, so the face is small in a full-size frame."""
    h, w = image.shape[:2]
    backdrop = cv2.GaussianBlur(image, (0, 0), 0.03 * max(h, w))
    small = cv2.resize(image, (max(8, round(w * scale)), max(8, round(h * scale))), interpolation=cv2.INTER_AREA)
    y, x = (h - small.shape[0]) // 2, (w - small.shape[1]) // 2
    out = backdrop.copy()
    out[y:y + small.shape[0], x:x + small.shape[1]] = small
    return out


def side_by_side(left: np.ndarray, right: np.ndarray) -> np.ndarray:
    """Two portraits in one frame; only the left face is the swap target."""
    h = min(left.shape[0], right.shape[0])
    fit = lambda im: cv2.resize(im, (round(im.shape[1] * h / im.shape[0]), h), interpolation=cv2.INTER_AREA)
    return np.hstack([fit(left), fit(right)])


def pole(image: np.ndarray, marks: np.ndarray) -> np.ndarray:
    """A flat foreground pole over the image-left eye, top to bottom."""
    out = image.copy()
    eye, distance = marks[0], float(np.linalg.norm(marks[1] - marks[0]))
    x1, x2 = int(round(eye[0] - 0.32 * distance)), int(round(eye[0] + 0.32 * distance))
    out[:, max(0, x1):max(0, x2)] = (96, 104, 88)
    return out


# ---------------------------------------------------------------- the benchmark set

@dataclass(frozen=True)
class Target:
    id: str
    files: tuple            # one file id, or two for a side-by-side
    recipe: str             # none | low_light | small:<scale> | pair | pole
    tags: tuple             # hard-case labels used in the per-tag table
    identity: str           # pool identity of the face to replace ("" when unknown)


def target_image(target: Target, detect=None) -> np.ndarray:
    """Build the target photo.  `detect` (image -> faces) is only needed for the pole recipe."""
    image = load(target.files[0])
    if target.recipe == "low_light":
        return low_light(image)
    if target.recipe.startswith("small:"):
        return small_face(image, float(target.recipe.split(":")[1]))
    if target.recipe == "pair":
        return side_by_side(image, load(target.files[1]))
    if target.recipe == "pole":
        faces = detect(image)
        if not faces:
            raise RuntimeError(f"{target.id}: no face to place the pole on")
        return pole(image, faces[0].marks)
    return image


def pick_face(target: Target, faces: list):
    """The face to replace: the left one of the two largest for a pair, else the largest."""
    if not faces:
        return None
    if target.recipe == "pair":
        return min(faces[:2], key=lambda f: f.box[0])
    return faces[0]


def pairs(targets=None, identities=None, rendered=None) -> list:
    """Fixed (target id, identity id) list: two photoreal identities per target, plus the rendered track."""
    targets = TARGETS if targets is None else targets
    identities = list(IDENTITIES if identities is None else identities)
    rendered = list(RENDERED if rendered is None else rendered)
    out = []
    for n, target in enumerate(targets):
        for k in (n % len(identities), (n + 3) % len(identities)):
            out.append((target.id, identities[k]))
    for k, identity in enumerate(rendered):
        for n in (k, k + 4, k + 8):
            if n < len(targets):
                out.append((targets[n].id, identity))
    return out


# ---------------------------------------------------------------- selection (how the manifest was made)

def flux_pool() -> list:
    """Pool identity ids in archive order: every identity whose five files lie in the first 200 MiB."""
    index = archive_index("flux")
    prefix = SOURCES["flux"]["prefix"]
    members: dict = {}
    for name, (size, offset) in index.items():
        if name.startswith(prefix) and offset + _span(size) < FLUX_POOL_BYTES:
            members.setdefault(name[len(prefix):].split("/")[0], []).append(name)
    return sorted(i for i, names in members.items() if len(names) == 5)


def flux_member(index: dict, identity: str, kind: str) -> str:
    prefix = SOURCES["flux"]["prefix"] + identity + "/"
    suffix = ".json" if kind == "json" else f"_{kind}.jpg"
    return next(n for n in index if n.startswith(prefix) and n.endswith(suffix))


def _pin(source: str, member: str, index: dict, blob: bytes | None = None) -> tuple:
    size, offset = index[member]
    if blob is None:
        blob = _range(SOURCES[source]["url"], offset, offset + _span(size) - 1)
    data = member_bytes(blob, size)
    path = os.path.join(_cache(), source, member)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)
    return (source, member, offset, size, hashlib.sha256(data).hexdigest())


def select(log=print) -> dict:
    """Re-derive the manifest from the archives by the rules in FIXTURES.md.

    Needs the models (detector and research judge) and about 600 MB of
    downloads.  Prints nothing about any swap result: selection only looks at
    the untouched fixtures.
    """
    from . import pipeline
    files: dict = {}
    index = archive_index("flux")
    pool = flux_pool()
    log(f"flux pool: {len(pool)} identities")
    head = _head("flux", FLUX_POOL_BYTES)

    scanned: dict = {}

    def pin_flux(identity: str, kind: str, keep: bool = True) -> str:
        member = flux_member(index, identity, kind)
        size, offset = index[member]
        file_id = f"flux/{identity}/{kind}"
        if file_id not in scanned:
            scanned[file_id] = _pin("flux", member, index, head[offset:offset + _span(size)])
        if keep:
            files[file_id] = scanned[file_id]
        return file_id

    detector, judge = pipeline.Detector(), pipeline.Embedder("glintr100")
    attributes, vectors = {}, {}
    for identity in pool:
        with open(fetch(pin_flux(identity, "json", keep=False), scanned)) as f:
            attributes[identity] = json.load(f)["attributes"]
        image = cv2.imread(fetch(pin_flux(identity, "doc", keep=False), scanned))
        faces = detector.detect(image)
        if faces:
            vectors[identity] = pipeline.unit(judge.embed(image, faces[0].marks))
    test = [i for i in pool[FLUX_DEV_IDENTITIES:] if i in vectors]
    gender = lambda i: attributes[i].get("genders.txt", "")
    age = lambda i: int(attributes[i].get("ages.txt", "0").replace("+", "-").split("-")[0])
    has = lambda i, key, *words: any(w in attributes[i].get(key, "") for w in words)
    sim = lambda a, b: float(vectors[a] @ vectors[b])

    # 1. Identities: the two closest same-gender look-alike pairs, then four for spread.
    ranked = sorted(((sim(a, b), a, b) for n, a in enumerate(test) for b in test[n + 1:]
                     if gender(a) == gender(b)), reverse=True)
    identities: list = []
    for _, a, b in ranked:
        if a not in identities and b not in identities:
            identities += [a, b]
        if len(identities) == 4:
            break
    for want_gender, young in (("Female", True), ("Female", False), ("Male", True), ("Male", False)):
        identities.append(next(i for i in test if i not in identities and gender(i) == want_gender
                               and (age(i) < 30 if young else age(i) >= 50)))
    # 2. Hard negatives: each identity's three nearest pool look-alikes.
    distractors: list = []
    for identity in identities:
        near = sorted((i for i in test if i not in identities and i not in distractors),
                      key=lambda i: -sim(identity, i))[:3]
        distractors += near
    taken = set(identities) | set(distractors)
    free = [i for i in test if i not in taken]

    def take(rule, count):
        chosen = [i for i in free if rule(i)][:count]
        for i in chosen:
            free.remove(i)
        return chosen

    plan = [  # (tags, kind, recipe, rule, count)
        (("frontal",), "doc", "none", lambda i: True, 3),
        (("scene",), "live_0_p_d1", "none", lambda i: True, 4),
        (("expression",), "live_0_e_d1", "none", lambda i: True, 3),
        (("glasses",), "live_0_p_d1", "none", lambda i: "eyewear.txt" in attributes[i], 2),
        (("beard",), "live_0_p_d1", "none", lambda i: has(i, "facial_hair.txt", "beard", "Beard"), 2),
        (("headwear",), "live_0_p_d1", "none", lambda i: "headwear.txt" in attributes[i], 1),
        (("low-light",), "live_0_p_d1", "low_light", lambda i: True, 2),
        (("small-face",), "live_0_p_d1", "small:0.25", lambda i: True, 1),
        (("small-face",), "live_0_p_d1", "small:0.12", lambda i: True, 1),
        (("occlusion",), "doc", "pole", lambda i: True, 1),
    ]
    targets: list = []
    for tags, kind, recipe, rule, count in plan:
        for identity in take(rule, count):
            targets.append(Target(f"T{len(targets) + 1:02d}", (pin_flux(identity, kind),), recipe, tags, identity))
    for _ in range(2):
        left, right = take(lambda i: True, 2)
        targets.append(Target(f"T{len(targets) + 1:02d}", (pin_flux(left, "live_0_p_d1"), pin_flux(right, "live_0_p_d1")),
                              "pair", ("two-people",), left))
    dev_identities = pool[:4]
    dev_targets = [Target(f"D{n + 1:02d}", (pin_flux(identity, "live_0_p_d1"),), "none", ("dev",), identity)
                   for n, identity in enumerate(pool[4:10])]
    for identity in identities + dev_identities:
        for kind in FLUX_KINDS:
            pin_flux(identity, kind)
    for identity in distractors:
        pin_flux(identity, "doc")
    del head

    # 3. Rendered targets with ground-truth labels (Face Synthetics, first 1,000 frames).
    fs_index = archive_index("fs")
    blob = _head("fs", RemoteArchive(SOURCES["fs"]["url"]).size)
    stats = []
    for frame in range(1000):
        seg_name, marks_name = f"{frame:06d}_seg.png", f"{frame:06d}_ldmks.txt"
        cut = lambda name: member_bytes(blob[fs_index[name][1]:fs_index[name][1] + _span(fs_index[name][0])], fs_index[name][0])
        seg = cv2.imdecode(np.frombuffer(cut(seg_name), np.uint8), cv2.IMREAD_GRAYSCALE)
        marks = np.loadtxt(io.BytesIO(cut(marks_name)))
        image = cv2.imdecode(np.frombuffer(cut(f"{frame:06d}.png"), np.uint8), cv2.IMREAD_COLOR)
        skin = (seg == 1) | (seg == 2)
        stats.append(dict(
            frame=frame, yaw=abs((marks[30, 0] - marks[36, 0]) / (marks[45, 0] - marks[36, 0] + 1e-9) - 0.5),
            glasses=float((seg == 16).mean()), mouth=float((seg == 9).mean()), beard=float((seg == 14).mean()),
            luma=float(cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)[skin].mean()) if skin.sum() > 500 else 255.0))
    used: set = set()

    def pin_frame(frame: int) -> tuple:
        size, offset = fs_index[f"{frame:06d}.png"]
        return _pin("fs", f"{frame:06d}.png", fs_index, blob[offset:offset + _span(size)])

    def first(rule):
        frame = next(s["frame"] for s in stats if s["frame"] not in used and rule(s))
        used.add(frame)
        return frame

    rendered_plan = [
        (("profile", "rendered"), lambda s: s["yaw"] > 0.75), (("profile", "rendered"), lambda s: s["yaw"] > 0.75),
        (("glasses", "rendered"), lambda s: s["glasses"] > 0.015 and s["yaw"] < 0.25),
        (("expression", "rendered"), lambda s: s["mouth"] > 0.012 and s["yaw"] < 0.25),
        (("low-light", "rendered"), lambda s: s["luma"] < 40 and s["yaw"] < 0.25),
        (("beard", "rendered"), lambda s: s["beard"] > 0.02 and s["yaw"] < 0.25),
    ]
    for tags, rule in rendered_plan:
        frame = first(rule)
        file_id = f"fs/{frame:06d}"
        files[file_id] = pin_frame(frame)
        targets.append(Target(f"T{len(targets) + 1:02d}", (file_id,), "none", tags, ""))
    widest = max((s for s in stats if s["frame"] not in used), key=lambda s: s["glasses"])["frame"]
    file_id = f"fs/{widest:06d}"
    files[file_id] = pin_frame(widest)
    targets.append(Target(f"T{len(targets) + 1:02d}", (file_id,), "none", ("occlusion", "rendered"), ""))
    del blob

    # 4. Rendered identities with real pose spread (DigiFace-1M subjects 0..3, 8 references each).
    digi_index = archive_index("digi")
    rendered = {}
    for subject in range(4):
        refs = []
        spans = [digi_index[f"{subject}/{k}.png"] for k in range(72)]
        low = min(offset for _, offset in spans)
        chunk = _range(SOURCES["digi"]["url"], low, max(offset + _span(size) for size, offset in spans) - 1)
        for start in range(0, 72, 9):
            for k in range(start, start + 9):
                member = f"{subject}/{k}.png"
                size, offset = digi_index[member]
                pinned = _pin("digi", member, digi_index, chunk[offset - low:offset - low + _span(size)])
                image = cv2.imread(os.path.join(_cache(), "digi", member))
                if detector.detect(cv2.resize(image, (448, 448), interpolation=cv2.INTER_CUBIC)):
                    files[f"digi/{subject}/{k}"] = pinned
                    refs.append(f"digi/{subject}/{k}")
                    break
        rendered[f"digi-{subject}"] = refs
    return dict(files=files, identities=identities, distractors=distractors, targets=targets, rendered=rendered,
                dev_identities=dev_identities, dev_targets=dev_targets)


def manifest_source(selection: dict) -> str:
    """Python text for the pinned tables below, from a `select()` result."""
    lines = ["FILES = {"]
    for file_id, row in sorted(selection["files"].items()):
        lines.append(f"    {file_id!r}: {row!r},")
    lines += ["}", f"IDENTITIES = {tuple(selection['identities'])!r}",
              f"DISTRACTORS = {tuple(selection['distractors'])!r}", "RENDERED = {"]
    for name, refs in selection["rendered"].items():
        lines.append(f"    {name!r}: {tuple(refs)!r},")
    lines += ["}", f"DEV_IDENTITIES = {tuple(selection['dev_identities'])!r}"]
    for name in ("targets", "dev_targets"):
        lines.append(name.upper() + " = (")
        for t in selection[name]:
            lines.append(f"    Target({t.id!r}, {t.files!r}, {t.recipe!r}, {t.tags!r}, {t.identity!r}),")
        lines.append(")")
    return "\n".join(lines) + "\n"


def references(identity: str) -> tuple:
    """File ids of an identity's reference photos (photoreal or rendered)."""
    if identity in RENDERED:
        return RENDERED[identity]
    return tuple(f"flux/{identity}/{kind}" for kind in FLUX_KINDS)


def reference_image(file_id: str) -> np.ndarray:
    """Reference photo; the 112 px rendered ones are enlarged so the detector finds the face."""
    image = load(file_id)
    if file_id.startswith("digi/"):
        image = cv2.resize(image, (448, 448), interpolation=cv2.INTER_CUBIC)
    return image


# ---------------------------------------------------------------- pinned manifest (generated by select())
# MANIFEST-BEGIN
FILES = {
    'digi/0/0': ('digi', '0/0.png', 0, 21128, '5e8a14d29c16ed77e5ca469cd417c8675f7b87d197e3df3e04c453915fbd440e'),
    'digi/0/18': ('digi', '0/18.png', 195082, 21147, '4cd3b5426482a6fe5e20bf32a6c08cdee3545322ae30908c712d29683e0b57c9'),
    'digi/0/27': ('digi', '0/27.png', 401471, 17191, '01c79d5ec010c2099c8fbb48001f677ed1f7322ba9dd6457bd909aa65c53b265'),
    'digi/0/36': ('digi', '0/36.png', 600055, 21622, 'cd6b722be4f44996a8a41881808b290fb4e5d82597a7e9e2d5b73f9eaf8b4624'),
    'digi/0/45': ('digi', '0/45.png', 803498, 20178, '8677bc297e38dab3241ea31beee87d2a146467b88e9ac36f07fe5088049e9e9d'),
    'digi/0/54': ('digi', '0/54.png', 1011024, 20501, '8ef46c0eecda94b230e065a523dd80e1ff6644a39d06612969d104e8c00711f3'),
    'digi/0/63': ('digi', '0/63.png', 1212583, 20499, 'bd1d04a53a389b9d2963dfa804fa6021ee830a9abcbe14485603e119c188cadf'),
    'digi/0/9': ('digi', '0/9.png', 1428227, 16374, '9009f6ad11162b5afdacb0f4dbd51d3b271d9987c2d8628623db950995619960'),
    'digi/1/0': ('digi', '1/0.png', 1444638, 22536, 'f27e9d1ee1ecf996ee9c1d424962c763d3d3988f2eeafc36938d6bee094a82ec'),
    'digi/1/19': ('digi', '1/19.png', 1684351, 22864, '8bc27e102ae3edbe24b290810b354250507de8126e03a1eeaac198df7ac6a4bd'),
    'digi/1/27': ('digi', '1/27.png', 1881802, 23470, '4df5b5b0142f8ecd8d6f9d02ec53f40d4d549aca6517a0db2b6ffc67c9816290'),
    'digi/1/36': ('digi', '1/36.png', 2100640, 20961, '000886d0e76af23aba24ab6f7581de7239a5259bc42b134c9d8c7d8df8c0bf55'),
    'digi/1/45': ('digi', '1/45.png', 2314330, 23974, 'b3b4da7c181563ace3ba924a52838e42e595878ff090c09eacd2b3e4c7f415f9'),
    'digi/1/54': ('digi', '1/54.png', 2534123, 25535, '5ba9d05a3243c27249cf3b10428b03090d7444312a03a4bb2031639777d7577d'),
    'digi/1/64': ('digi', '1/64.png', 2778376, 20474, 'a32476b0f37d037e03fbfc10cf2e44c8ba6151dda9a47f66569761abbbb804ed'),
    'digi/1/9': ('digi', '1/9.png', 2995542, 22629, '43eab1db1e010f4d55ad1ce8d6dc1e6905375579d07dc40b143b67b6172afb83'),
    'digi/2/0': ('digi', '2/0.png', 3018208, 23938, '90f66021b25cbe131f19e9b5d193d5e974036089fb48504841755f5827c975b8'),
    'digi/2/18': ('digi', '2/18.png', 3224835, 17923, '96d66594cf756c5313b4e1a1de9b5372d8a24bbf077f3a68296febcb845e47e1'),
    'digi/2/27': ('digi', '2/27.png', 3431094, 22000, 'd54793bca2220272807b5baa4d3757a7a1fcd9f8cbe18c66e039d15017dfc813'),
    'digi/2/36': ('digi', '2/36.png', 3646477, 19965, '7752a333064a36f99538f611510b71e9bde37a9d5ae7e85b7443a9d3a54f8515'),
    'digi/2/45': ('digi', '2/45.png', 3846965, 21773, 'a0e6c8e1c67d279d2febd6cc60b31dc2a9860fd2b967c3e7897b941960ecf517'),
    'digi/2/54': ('digi', '2/54.png', 4050615, 21612, '9bfb8f5cabb996e77d9478f57e7f3a16df3b9e7540b9ac6d8c1cfaa9967b4765'),
    'digi/2/63': ('digi', '2/63.png', 4261101, 19659, 'f17bbeada27bf375c9146b7d2e6d07cc62d9721572a10891433f77034e778b0c'),
    'digi/2/9': ('digi', '2/9.png', 4479880, 21081, '1ec50f74d001b12e93c79897f4d123f1b67265e5096b384817db44547a79e20d'),
    'digi/3/0': ('digi', '3/0.png', 4500998, 21802, 'eda6871f7f55d52efdc7a9802a5a25e3b87037e4bb7571009dc146f86de41bde'),
    'digi/3/18': ('digi', '3/18.png', 4702448, 20055, '4581dba0fce85a77a9e08a171e24f8d626cb5617d285ab3e66dec010ff9e4c2d'),
    'digi/3/27': ('digi', '3/27.png', 4910195, 22975, '715f7128ebe54d1b77b2acc47137e4ca5f72e9e09446ec57a749ffd38c081288'),
    'digi/3/36': ('digi', '3/36.png', 5122604, 18871, '6a65b67832dd35b2ebbb82e85f7b32d5a95e7e9bccc1ccf2640caef36bbd3bb9'),
    'digi/3/45': ('digi', '3/45.png', 5321533, 20834, '6fcd8f9dca58b746c6401772d12fafd50339806ceee2cfac80ff69f5246d98aa'),
    'digi/3/54': ('digi', '3/54.png', 5534322, 20476, 'ad9abc88df92456fb77da633b3405d6cd1ba1f5241361869b4a1bf3832ae1052'),
    'digi/3/63': ('digi', '3/63.png', 5738486, 16910, 'f8737d84c230650887cc960a745749d874789634f023da79cdf28d33d84853d2'),
    'digi/3/9': ('digi', '3/9.png', 5963025, 17465, '7612131e718acd164a89c09499c156b706ee7b4ce2db624d7d5384a8ac4be8da'),
    'flux/00000bb0d1b9/doc': ('flux', 'FLUXSynID/FLUXSynID/00000bb0d1b9/00000bb0d1b9_f_doc.jpg', 4504, 196925, '79542077b7ad020ec6b98964b3a602da25bdfaea73bf1207f7e6a4b5198cde6a'),
    'flux/00000bb0d1b9/live_0_a_d1': ('flux', 'FLUXSynID/FLUXSynID/00000bb0d1b9/00000bb0d1b9_f_live_0_a_d1.jpg', 199369, 74995, 'a952b6c5c8ff9ca07b5d79eec599686a3a40e5da62f3caa31bb31c2417ff7084'),
    'flux/00000bb0d1b9/live_0_e_d1': ('flux', 'FLUXSynID/FLUXSynID/00000bb0d1b9/00000bb0d1b9_f_live_0_e_d1.jpg', 274359, 171915, '5c2868e744a9a72302dd8f2f1c3df08d8b31ba9932225888cc7a87c167ecd0a9'),
    'flux/00000bb0d1b9/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/00000bb0d1b9/00000bb0d1b9_f_live_0_p_d1.jpg', 444731, 250374, 'fd4ad3d8514f366f70485e173c175def6f8fef60bba3c90cb10ebac51f2fd1b1'),
    'flux/00028290bd21/doc': ('flux', 'FLUXSynID/FLUXSynID/00028290bd21/00028290bd21_f_doc.jpg', 695780, 160876, 'cfc561e36a4ba38d576d9310218d5d42893b79aac837cb80e7a9dfc418f33ca1'),
    'flux/00028290bd21/live_0_a_d1': ('flux', 'FLUXSynID/FLUXSynID/00028290bd21/00028290bd21_f_live_0_a_d1.jpg', 854451, 84507, '1138ea5748c680ab045680e4c6e294d4b4390f398df3293546bc7d293d5e55b5'),
    'flux/00028290bd21/live_0_e_d1': ('flux', 'FLUXSynID/FLUXSynID/00028290bd21/00028290bd21_f_live_0_e_d1.jpg', 938943, 152102, '33f2ee9b6ebfb46e86dd823c9a2de5f3e1cdba6ad3d82f21152a641720aaa58d'),
    'flux/00028290bd21/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/00028290bd21/00028290bd21_f_live_0_p_d1.jpg', 1090201, 267008, 'cc2e445cc823b4e1495cb3bc1c89e306ac3c9cb864e2e4545a94d93c3d7d3c9c'),
    'flux/00054d45f524/doc': ('flux', 'FLUXSynID/FLUXSynID/00054d45f524/00054d45f524_f_doc.jpg', 1357879, 206449, '2fab933cacecc3c5e368ab0ab3e7c4436a5c65ca2b25912b2b7c0671c93d0335'),
    'flux/00054d45f524/live_0_a_d1': ('flux', 'FLUXSynID/FLUXSynID/00054d45f524/00054d45f524_f_live_0_a_d1.jpg', 1560439, 78695, '9954aa8c2eb1460bc19550aef762a63d915667182491fe8b1e399ba08aca51d0'),
    'flux/00054d45f524/live_0_e_d1': ('flux', 'FLUXSynID/FLUXSynID/00054d45f524/00054d45f524_f_live_0_e_d1.jpg', 1639133, 157385, 'd6776bac0257e1d9be80dae908013f4db3431b01207749331d5114178768fe90'),
    'flux/00054d45f524/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/00054d45f524/00054d45f524_f_live_0_p_d1.jpg', 1793761, 301142, 'a3f8c877e9e778477d21a67753a2685a41c4d83a9c41fc4834d63f50cc875379'),
    'flux/000e23cdce23/doc': ('flux', 'FLUXSynID/FLUXSynID/000e23cdce23/000e23cdce23_f_doc.jpg', 2095626, 260634, '5dc95e960e09f251bd6ee639069b69e8c6ce72c5c045579dbb016e1a87b53bd3'),
    'flux/000e23cdce23/live_0_a_d1': ('flux', 'FLUXSynID/FLUXSynID/000e23cdce23/000e23cdce23_f_live_0_a_d1.jpg', 2355630, 101447, '24fb4aead7c9aa0935c781f0de6d1628515adc8d621bd6fca583660a8b31d0b6'),
    'flux/000e23cdce23/live_0_e_d1': ('flux', 'FLUXSynID/FLUXSynID/000e23cdce23/000e23cdce23_f_live_0_e_d1.jpg', 2457059, 228737, '5fc216cbf5e08da83f267517175a8bde840bd146112f94bb40ef7fc790ec0cc9'),
    'flux/000e23cdce23/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/000e23cdce23/000e23cdce23_f_live_0_p_d1.jpg', 2685526, 319147, '47e3ec04a24037195d986e6acc2c2a70d5e79328b150ee82df5ee04651590a9c'),
    'flux/00194c971ca4/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/00194c971ca4/00194c971ca4_m_live_0_p_d1.jpg', 3487903, 311565, '61af0a6762b17372182971a31473adb6b07f04dc3b26bc6bf19c2ed3592b10b6'),
    'flux/001bd789984b/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/001bd789984b/001bd789984b_m_live_0_p_d1.jpg', 4337648, 307078, 'a860490dbebd36640a9159eec201ee6fc4512e9bc89c1af136d64553a44b67de'),
    'flux/001d6e39269e/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/001d6e39269e/001d6e39269e_f_live_0_p_d1.jpg', 5119839, 300785, '394c9329925f92adb712eff841d70addfff7b0ce1a5b469643688ff1a3dd474e'),
    'flux/00242572c9ee/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/00242572c9ee/00242572c9ee_f_live_0_p_d1.jpg', 5942471, 303162, 'aba081a33942eda875c89ab001924cc377ee1d4adf01509a7975bb331cd6f9d8'),
    'flux/0024d7b330c2/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/0024d7b330c2/0024d7b330c2_m_live_0_p_d1.jpg', 6736064, 300551, 'b50297f6ae078e602af1c434653ad96f3dae64bc7940c7fc19baa6b61e546748'),
    'flux/002e68360d8a/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/002e68360d8a/002e68360d8a_f_live_0_p_d1.jpg', 7463135, 280316, '4e66e5449db62ff41e6e6bc0b8b0de56c7a4509b70a7596ef9f7dc7d455869e2'),
    'flux/013cfc72cfbc/doc': ('flux', 'FLUXSynID/FLUXSynID/013cfc72cfbc/013cfc72cfbc_m_doc.jpg', 47552085, 236054, 'ffedf49e5e999eb3270639b3f43fc81db0359bb42111d246319c73f747ae7c39'),
    'flux/013cfc72cfbc/live_0_a_d1': ('flux', 'FLUXSynID/FLUXSynID/013cfc72cfbc/013cfc72cfbc_m_live_0_a_d1.jpg', 47785110, 66931, '41b501293457819d5283744e36c824bf1e8cf366429878dd6ccec41ac80371a5'),
    'flux/013cfc72cfbc/live_0_e_d1': ('flux', 'FLUXSynID/FLUXSynID/013cfc72cfbc/013cfc72cfbc_m_live_0_e_d1.jpg', 47852007, 200560, 'e0c26632507718fcfb908fb2fb574b2dd87eba4f206dd01103a3fa313e54bf44'),
    'flux/013cfc72cfbc/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/013cfc72cfbc/013cfc72cfbc_m_live_0_p_d1.jpg', 48050699, 349021, 'ce58017a0ac25026d242f4044b8ccd44d9e6e2f6ca8f07dfcce610db3021f116'),
    'flux/0140620c0616/doc': ('flux', 'FLUXSynID/FLUXSynID/0140620c0616/0140620c0616_m_doc.jpg', 48400399, 212885, 'f81698928dac27642758d3569acc9a50c6a9a72748c28d91d837be0c84b5729c'),
    'flux/014618edeef8/doc': ('flux', 'FLUXSynID/FLUXSynID/014618edeef8/014618edeef8_f_doc.jpg', 49146742, 269557, '335e9bf5219e3eeac6bea39f96a646e95f5d57f2eff586520507f4f4a4d7d043'),
    'flux/014618edeef8/live_0_a_d1': ('flux', 'FLUXSynID/FLUXSynID/014618edeef8/014618edeef8_f_live_0_a_d1.jpg', 49416306, 72213, '80cd1758e8f6a4f3f76b97ce842ec3d67276ca8c072fd25c3161ddabdde34ec7'),
    'flux/014618edeef8/live_0_e_d1': ('flux', 'FLUXSynID/FLUXSynID/014618edeef8/014618edeef8_f_live_0_e_d1.jpg', 49488505, 232034, '2efa2bc30168a627f02c8141200d93f8f6ebcc328edbaf2e4ddcd146a1be42fa'),
    'flux/014618edeef8/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/014618edeef8/014618edeef8_f_live_0_p_d1.jpg', 49720542, 334347, '2376fc08af3b6f11113a2dde2c1af10109f0a9d328f7278da049840088e5521d'),
    'flux/0147427bd564/doc': ('flux', 'FLUXSynID/FLUXSynID/0147427bd564/0147427bd564_f_doc.jpg', 50055563, 273292, '9fbaa54395c3f52452b99d772e31823dc5ff4bc162f244794c6adfd76858839a'),
    'flux/014ceeb40aed/doc': ('flux', 'FLUXSynID/FLUXSynID/014ceeb40aed/014ceeb40aed_m_doc.jpg', 50980359, 242831, '9819ca8b056ec32cb31ff2bda495c61d469d3d8f9b78649940df915c7dc5a397'),
    'flux/014ceeb40aed/live_0_a_d1': ('flux', 'FLUXSynID/FLUXSynID/014ceeb40aed/014ceeb40aed_m_live_0_a_d1.jpg', 51220848, 89556, '3c337082f661279be96ad0de00c4bc197f15153d4ccf60e028ac33374e232759'),
    'flux/014ceeb40aed/live_0_e_d1': ('flux', 'FLUXSynID/FLUXSynID/014ceeb40aed/014ceeb40aed_m_live_0_e_d1.jpg', 51310387, 181163, '3a0ed2c936056dba1365da17959d2e123fd7d8d8858d713993a0447273f2c14e'),
    'flux/014ceeb40aed/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/014ceeb40aed/014ceeb40aed_m_live_0_p_d1.jpg', 51488638, 359673, 'abb73c1315c2bf291e8fde342032c65538d437b33ae70c402f7f6f38425e37cb'),
    'flux/01520e2af928/doc': ('flux', 'FLUXSynID/FLUXSynID/01520e2af928/01520e2af928_m_doc.jpg', 51848992, 157763, '1bb68cc81925190a55a11ebbe15ea7427f1611d2e85f124f0d4fbc1c93dbf366'),
    'flux/0152c8450005/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/0152c8450005/0152c8450005_f_live_0_p_d1.jpg', 52984458, 340535, '28a2fb60696c1bc5e196d01ade6f73bd6b21dd308a5d9d9fe9da5ea2358ca13f'),
    'flux/015d9b2ddaf9/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/015d9b2ddaf9/015d9b2ddaf9_m_live_0_p_d1.jpg', 53741304, 288603, '7c0dbf386c5ed02e631bb93104b42e9a7e287681a606a44570744feb77be1045'),
    'flux/0160116b9162/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/0160116b9162/0160116b9162_m_live_0_p_d1.jpg', 54514496, 327370, '7acf657786b248e2d8693d6611b81b7d70501f4f3d17ed89f3ce0f09c556eecd'),
    'flux/0169c2c21ce7/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/0169c2c21ce7/0169c2c21ce7_f_live_0_p_d1.jpg', 55331029, 341139, '04a60fa0ed0f11b345ef07b9672f55b892817d68e7b97e15f022a8e687c85276'),
    'flux/0176b4220a36/live_0_e_d1': ('flux', 'FLUXSynID/FLUXSynID/0176b4220a36/0176b4220a36_f_live_0_e_d1.jpg', 55917681, 173148, '3e8510a378f8199eafc845cd718a2ef886fb6762eb519078f38bc54fe48ddc3f'),
    'flux/0176c353d764/live_0_e_d1': ('flux', 'FLUXSynID/FLUXSynID/0176c353d764/0176c353d764_m_live_0_e_d1.jpg', 56770984, 222130, 'cbaf659f80f2344a28ccf4ae1e93f1f775b6e938f97f6426d8e066597e9a9ee0'),
    'flux/017c742ef4c0/live_0_e_d1': ('flux', 'FLUXSynID/FLUXSynID/017c742ef4c0/017c742ef4c0_m_live_0_e_d1.jpg', 57620132, 191349, '9ae3c12687e9b21843db4399adbdc49dcd6cc56d562941a2dbd3346f895a9d4e'),
    'flux/017d04e13ad0/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/017d04e13ad0/017d04e13ad0_f_live_0_p_d1.jpg', 58741355, 371469, '3863ebd39000c492280697021d7ce82ace5d6daaf8098078d265da4a171db0b2'),
    'flux/0181c1150cee/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/0181c1150cee/0181c1150cee_m_live_0_p_d1.jpg', 59543426, 290174, '4d3747a81711c96b273b3192a368de43be343e3039b550ae96069f9cfad8e6f0'),
    'flux/01829a4fe5cc/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/01829a4fe5cc/01829a4fe5cc_nb_live_0_p_d1.jpg', 60245524, 287611, '8051bb03be4abb8497a76ccc24cceb431cfa4ba5583498ce2deb295e6a75882f'),
    'flux/0184c84872dc/doc': ('flux', 'FLUXSynID/FLUXSynID/0184c84872dc/0184c84872dc_f_doc.jpg', 60533811, 188836, '882284491e79adccf3553c584d79ff789d6aa405de4e5e11b7b3194b129f58e4'),
    'flux/0184c84872dc/live_0_a_d1': ('flux', 'FLUXSynID/FLUXSynID/0184c84872dc/0184c84872dc_f_live_0_a_d1.jpg', 60719731, 72668, 'c1fc7ab5c1dd4a59e50372311cdf130eb230fad7002a3a02985e03a0399a2f45'),
    'flux/0184c84872dc/live_0_e_d1': ('flux', 'FLUXSynID/FLUXSynID/0184c84872dc/0184c84872dc_f_live_0_e_d1.jpg', 60792388, 181080, '4641bf2404c9c9c56d4c03c4f2661f046265f446d2878d7f2ac3d6ccc34f13fd'),
    'flux/0184c84872dc/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/0184c84872dc/0184c84872dc_f_live_0_p_d1.jpg', 60972225, 304073, '33a964463116d85fa829baf5a81705a6574c344edb73ff773ea7489843e1b153'),
    'flux/01868f7265ab/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/01868f7265ab/01868f7265ab_f_live_0_p_d1.jpg', 61849081, 340551, '48541eab90212c5c8925bc17a7c66a56d88f573cd7e7e7b30f496d7530c05afd'),
    'flux/018728683b7b/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/018728683b7b/018728683b7b_m_live_0_p_d1.jpg', 62631055, 311418, '88d7743561600c38ca5c9089ff248e2fe097b35191078a3346510bdf191a3a83'),
    'flux/018850795aca/doc': ('flux', 'FLUXSynID/FLUXSynID/018850795aca/018850795aca_f_doc.jpg', 62943076, 273093, '4dc5ed9e36195013cd76c1627a1324aae399392c3a9449df6a5607ea385320ec'),
    'flux/0197b1c56341/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/0197b1c56341/0197b1c56341_f_live_0_p_d1.jpg', 64266665, 293629, '0875d5c2752de9b975e7cd4157a2e2a89973693e003eb88ac0cb5a25b08efc1f'),
    'flux/019b36fa2b73/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/019b36fa2b73/019b36fa2b73_m_live_0_p_d1.jpg', 64979040, 296817, 'e7242cd4789251e594a1a0e5ff2f86c3e3810fcc0b9e9280d206af51ed5f0210'),
    'flux/019c5f9e3959/doc': ('flux', 'FLUXSynID/FLUXSynID/019c5f9e3959/019c5f9e3959_f_doc.jpg', 65276597, 193420, 'a8c49be934466ba79b97bfb2e3dc2dfe340c994d17224fd41571a67f6f1e3a48'),
    'flux/01a774683a3e/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/01a774683a3e/01a774683a3e_m_live_0_p_d1.jpg', 66528791, 314943, 'b8dd98112cca4ca026824d12d904b91862849a64f0489ec63335ea1974ba506d'),
    'flux/01a79b79e217/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/01a79b79e217/01a79b79e217_f_live_0_p_d1.jpg', 67409786, 344596, 'c96d8a3bce8649e61f76b30ac55ac68e8b1b1432a610542d9c4f64fd2ed56ed5'),
    'flux/01b078a0e44d/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/01b078a0e44d/01b078a0e44d_f_live_0_p_d1.jpg', 71289912, 304442, '000beed482e27f88650281d6f679b5b562d5d8ba4e8ac2c7b38315791e2d6643'),
    'flux/01b401d4540e/doc': ('flux', 'FLUXSynID/FLUXSynID/01b401d4540e/01b401d4540e_m_doc.jpg', 71595065, 207642, 'c92ff671bb5994331e460af5d881623bf39841bfb8501b2bc154bb87a5c9aa3b'),
    'flux/01c40096d14d/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/01c40096d14d/01c40096d14d_m_live_0_p_d1.jpg', 75902240, 293331, '95a6987652939c7e122522fb73cb16e567a0399b31a4019cac053eadb088fbec'),
    'flux/01c5abf5879e/doc': ('flux', 'FLUXSynID/FLUXSynID/01c5abf5879e/01c5abf5879e_f_doc.jpg', 76196255, 204479, '4496aa4fd645ab2cdb84676343e24ce6f280a9bd8e4160a9cc0be0cb093d22f0'),
    'flux/01cb2a1b9842/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/01cb2a1b9842/01cb2a1b9842_m_live_0_p_d1.jpg', 78311944, 341869, 'dedfa55fb9f2902d2f61b14e8573b457b87394ae79fda97c0d19265b31b6b4b4'),
    'flux/0213ccb4776a/doc': ('flux', 'FLUXSynID/FLUXSynID/0213ccb4776a/0213ccb4776a_f_doc.jpg', 84505653, 274904, '2d4d8f0a772d4cdb51f6bb969f23f190cd970d4045115850940c74a2bed367db'),
    'flux/0213ccb4776a/live_0_a_d1': ('flux', 'FLUXSynID/FLUXSynID/0213ccb4776a/0213ccb4776a_f_live_0_a_d1.jpg', 84777701, 108408, 'cb0741a0bc50e25cda730fb9413906e816b44e96072042460818669f56a752c6'),
    'flux/0213ccb4776a/live_0_e_d1': ('flux', 'FLUXSynID/FLUXSynID/0213ccb4776a/0213ccb4776a_f_live_0_e_d1.jpg', 84886077, 229989, '05b8677e437702a2bd209a6ac060ea90a3534cd1e9d272df6c27d05eb84669a9'),
    'flux/0213ccb4776a/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/0213ccb4776a/0213ccb4776a_f_live_0_p_d1.jpg', 85114277, 344652, 'eaa5c6666b8366b262971c6851cc78a22481f1c7ccad005fc7e041f412b4ff5c'),
    'flux/02292017fd98/doc': ('flux', 'FLUXSynID/FLUXSynID/02292017fd98/02292017fd98_f_doc.jpg', 87005300, 251196, '06cd3efb05aeaaaaebca69b4458efb266a1abdda21e4af67dad3b94780fb5894'),
    'flux/02320c808c04/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/02320c808c04/02320c808c04_m_live_0_p_d1.jpg', 90635604, 301409, '75a8c78bddfda83bd99399a661ed1af00cd02780f90d8391b206f78d13e66a78'),
    'flux/02483466a5d7/doc': ('flux', 'FLUXSynID/FLUXSynID/02483466a5d7/02483466a5d7_f_doc.jpg', 94081172, 222997, '122686bf3d6bfaa020a762a504515b2a0c2fea608158542a3ef384d62f0c5dae'),
    'flux/0289e2064181/doc': ('flux', 'FLUXSynID/FLUXSynID/0289e2064181/0289e2064181_f_doc.jpg', 109809234, 241464, '5a07d01f0246a3b5846401120755b0217025d3869a9b676c8d4a92d564c3b257'),
    'flux/029df6865263/doc': ('flux', 'FLUXSynID/FLUXSynID/029df6865263/029df6865263_m_doc.jpg', 112987373, 207068, '23f74bd2b52eb3388f3e11c44c9ce695f225965aaa76865949b0009bf8d40ed1'),
    'flux/02a81aa8efcc/doc': ('flux', 'FLUXSynID/FLUXSynID/02a81aa8efcc/02a81aa8efcc_f_doc.jpg', 114411792, 205151, 'b48d0d59a868e67c8028dbecc3efbd2f157612f1caabde517d466301dbdfee2b'),
    'flux/02c5a57dff8e/doc': ('flux', 'FLUXSynID/FLUXSynID/02c5a57dff8e/02c5a57dff8e_f_doc.jpg', 121352492, 214429, '569c170432bbe79e1c92cfbd657e6ae67f3395079bf6814300c0c9f3db4dcfb0'),
    'flux/02c5a57dff8e/live_0_a_d1': ('flux', 'FLUXSynID/FLUXSynID/02c5a57dff8e/02c5a57dff8e_f_live_0_a_d1.jpg', 121562422, 73303, 'e07ab44baae50b009e877443836bcdf397a1785d0fc336d0a104e294bb830008'),
    'flux/02c5a57dff8e/live_0_e_d1': ('flux', 'FLUXSynID/FLUXSynID/02c5a57dff8e/02c5a57dff8e_f_live_0_e_d1.jpg', 121635720, 162456, '4144bb1d00e38401f00ceca9d0c0fedb3bca2fb4112bd9100d48e7ec4b1d9aae'),
    'flux/02c5a57dff8e/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/02c5a57dff8e/02c5a57dff8e_f_live_0_p_d1.jpg', 121793730, 320447, '9146633ff867d13db6749cca97072e7ceddce4e188fc58a85f0927ddc6550654'),
    'flux/02d72297f42f/doc': ('flux', 'FLUXSynID/FLUXSynID/02d72297f42f/02d72297f42f_f_doc.jpg', 125398722, 217016, '97aaf9dbe86d5aaecb2f642124c5363ce52ca6bc6c73fc430391f1578b55640f'),
    'flux/030aff3243ea/doc': ('flux', 'FLUXSynID/FLUXSynID/030aff3243ea/030aff3243ea_f_doc.jpg', 133006071, 197713, '50865e4acbaa05e4dc5e22a47f4909a2d493488439b731b5a7da6c262ee7abf9'),
    'flux/031f16bc4649/doc': ('flux', 'FLUXSynID/FLUXSynID/031f16bc4649/031f16bc4649_f_doc.jpg', 136263683, 224479, '5784c01e657904bcb03dac439b3309eb10a193daf78e000eb7bb622224f47912'),
    'flux/031f2ce919f7/doc': ('flux', 'FLUXSynID/FLUXSynID/031f2ce919f7/031f2ce919f7_m_doc.jpg', 137053129, 202929, '7f2b074d595ddee73cd7d388479c7d13c757a231af613fc9d55d0139b38f3ea6'),
    'flux/033528c18da0/doc': ('flux', 'FLUXSynID/FLUXSynID/033528c18da0/033528c18da0_f_doc.jpg', 142614771, 249124, 'bd488d3c8a4341f4f1d67c41535f3204708d23e900d86e59165e220bc5ad8131'),
    'flux/033528c18da0/live_0_a_d1': ('flux', 'FLUXSynID/FLUXSynID/033528c18da0/033528c18da0_f_live_0_a_d1.jpg', 142862489, 79006, 'a2b445ce41f8f44dd7879aea32553df715d5f76d828bec04cb9144aad6abc458'),
    'flux/033528c18da0/live_0_e_d1': ('flux', 'FLUXSynID/FLUXSynID/033528c18da0/033528c18da0_f_live_0_e_d1.jpg', 142941480, 205376, '44c1f2f7948541b66f9c70d74b211fcc559c5ae2a1252daeaf3c08f6e052ab41'),
    'flux/033528c18da0/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/033528c18da0/033528c18da0_f_live_0_p_d1.jpg', 143146451, 320427, 'ed43fc754c9ae5240e0b4956049fd932cf5f84f13c3735f0bc17bb41a42e4f41'),
    'flux/033b8076aa7b/doc': ('flux', 'FLUXSynID/FLUXSynID/033b8076aa7b/033b8076aa7b_f_doc.jpg', 143467576, 198369, '0171f484967acc3835a73d6423432812d5776ebe71b3544a6d535add6dc6ac91'),
    'flux/03626178f7e0/doc': ('flux', 'FLUXSynID/FLUXSynID/03626178f7e0/03626178f7e0_f_doc.jpg', 151296461, 192132, '76e0cc280b4b35104a1f3778920ad2f0c51d26f13907b6f3e3a796da5d516f3d'),
    'flux/03985cc638d7/doc': ('flux', 'FLUXSynID/FLUXSynID/03985cc638d7/03985cc638d7_f_doc.jpg', 163855038, 263215, 'c86f7d198da8520b9106548fe3b039f5aa9eb53544dee9bca796d70de55f18bd'),
    'flux/039c6e2908e3/doc': ('flux', 'FLUXSynID/FLUXSynID/039c6e2908e3/039c6e2908e3_f_doc.jpg', 165450921, 223134, 'd211aba66a50f1efb831348577b0ee1020c8515c32449beee0bc6f63516a1bd2'),
    'flux/03c88c631972/doc': ('flux', 'FLUXSynID/FLUXSynID/03c88c631972/03c88c631972_f_doc.jpg', 176337657, 234863, 'dbfa8bdcf5b951aa3e685e45c6e49c08a2ca9be36767045669dcffd1f7212478'),
    'flux/03d01caf8e3b/doc': ('flux', 'FLUXSynID/FLUXSynID/03d01caf8e3b/03d01caf8e3b_nb_doc.jpg', 178891767, 165772, '52c3f06cb3bd5a2b5b4de16d27a114f41f19c227d1498ad61ec186b1e597970f'),
    'flux/03d48a0ef17a/doc': ('flux', 'FLUXSynID/FLUXSynID/03d48a0ef17a/03d48a0ef17a_m_doc.jpg', 179560375, 218672, '82c607eab1ce294b670fa171ad01af016645837118dbebd77a26210970080f8a'),
    'flux/03d53170ad8c/doc': ('flux', 'FLUXSynID/FLUXSynID/03d53170ad8c/03d53170ad8c_f_doc.jpg', 180363498, 233510, '719f606ddf21f046a40e6093e93ae273f4354914fa1914e3161a03e66418e330'),
    'flux/03d53170ad8c/live_0_a_d1': ('flux', 'FLUXSynID/FLUXSynID/03d53170ad8c/03d53170ad8c_f_live_0_a_d1.jpg', 180594913, 82084, '65c76bdf2cdb86aee328e4e10d7b4b03b3aef02890a0c1fbb15fc373033053f0'),
    'flux/03d53170ad8c/live_0_e_d1': ('flux', 'FLUXSynID/FLUXSynID/03d53170ad8c/03d53170ad8c_f_live_0_e_d1.jpg', 180676988, 189026, 'dec562e582e169d0c4aa2cfae7f8fb2e2584a10be98bd72fa7fe8e5d1995dc3e'),
    'flux/03d53170ad8c/live_0_p_d1': ('flux', 'FLUXSynID/FLUXSynID/03d53170ad8c/03d53170ad8c_f_live_0_p_d1.jpg', 180863475, 354077, '39708d4bb6446cea6736a1bc04b267736f28787afead13a87eb691b5b4c07cad'),
    'flux/03e1050132f7/doc': ('flux', 'FLUXSynID/FLUXSynID/03e1050132f7/03e1050132f7_m_doc.jpg', 183566607, 241639, '8ac35c770274945d08fa7bc913fc274f47c501e4d83c112e313564178a670bbb'),
    'flux/0403d186d24d/doc': ('flux', 'FLUXSynID/FLUXSynID/0403d186d24d/0403d186d24d_nb_doc.jpg', 192479758, 234391, 'f2a3c6acd859290c56538e02b5778cfa134d6a130a75d4a027bc13b8af9c7246'),
    'flux/040ac4aa6d77/doc': ('flux', 'FLUXSynID/FLUXSynID/040ac4aa6d77/040ac4aa6d77_f_doc.jpg', 194139089, 246790, 'bb0a23773ef7152f30ddb942ff3871c54abbd6ceedfb28b842c8400231c663ab'),
    'flux/0424234d5519/doc': ('flux', 'FLUXSynID/FLUXSynID/0424234d5519/0424234d5519_f_doc.jpg', 200352973, 183899, 'fa076d76c4126dd9d58d3120a74df3b234299d96366b47bba305d217e165d81a'),
    'flux/0456558c8251/doc': ('flux', 'FLUXSynID/FLUXSynID/0456558c8251/0456558c8251_f_doc.jpg', 206650239, 297888, '2d1c3a3904ba4d57efe88f1f31774c95a9b4ec640b9ae48e0c94e2f1bdf480ff'),
    'fs/000002': ('fs', '000002.png', 634081, 435820, '625fe2e1d7fead82d3be1de2b34436fa3f2bc3f150aa7199ab012cbcd5e66579'),
    'fs/000013': ('fs', '000013.png', 4706576, 295679, 'a70fac4d0338813c2ee84c7862951c7a5f9d24cb551ee9bbae695e25e86bd94f'),
    'fs/000059': ('fs', '000059.png', 20497328, 422460, '3476cc6705c09c55754b59b766218d56b55f05422a852f360bc4510e9c4fd747'),
    'fs/000063': ('fs', '000063.png', 22026203, 194922, '58c02f0822ae6841e94d24215567154b350e246467040b33219031d65ca3b875'),
    'fs/000083': ('fs', '000083.png', 29075547, 332092, 'c7a81d2b3f0f08be943a04116f170ba523d7b9dcb01587a62823d2b74586433a'),
    'fs/000572': ('fs', '000572.png', 194424272, 458403, '0033b5a7acdb0e5c16ac22db937f461ec649d909ae195cfc675678fe0601eec5'),
    'fs/000685': ('fs', '000685.png', 232273356, 349259, '3e97dcea9eef0ce75b317e29b4ed3d3f1c02f7684a61469e35439287ebdf3450'),
}
IDENTITIES = ('0213ccb4776a', '02c5a57dff8e', '033528c18da0', '03d53170ad8c', '0184c84872dc', '014618edeef8', '013cfc72cfbc', '014ceeb40aed')
DISTRACTORS = ('02292017fd98', '03626178f7e0', '0289e2064181', '0456558c8251', '039c6e2908e3', '019c5f9e3959', '033b8076aa7b', '031f16bc4649', '030aff3243ea', '02a81aa8efcc', '02d72297f42f', '03985cc638d7', '02483466a5d7', '03d01caf8e3b', '03c88c631972', '01c5abf5879e', '040ac4aa6d77', '0403d186d24d', '029df6865263', '01b401d4540e', '0424234d5519', '031f2ce919f7', '03e1050132f7', '03d48a0ef17a')
RENDERED = {
    'digi-0': ('digi/0/0', 'digi/0/9', 'digi/0/18', 'digi/0/27', 'digi/0/36', 'digi/0/45', 'digi/0/54', 'digi/0/63'),
    'digi-1': ('digi/1/0', 'digi/1/9', 'digi/1/19', 'digi/1/27', 'digi/1/36', 'digi/1/45', 'digi/1/54', 'digi/1/64'),
    'digi-2': ('digi/2/0', 'digi/2/9', 'digi/2/18', 'digi/2/27', 'digi/2/36', 'digi/2/45', 'digi/2/54', 'digi/2/63'),
    'digi-3': ('digi/3/0', 'digi/3/9', 'digi/3/18', 'digi/3/27', 'digi/3/36', 'digi/3/45', 'digi/3/54', 'digi/3/63'),
}
DEV_IDENTITIES = ('00000bb0d1b9', '00028290bd21', '00054d45f524', '000e23cdce23')
TARGETS = (
    Target('T01', ('flux/0140620c0616/doc',), 'none', ('frontal',), '0140620c0616'),
    Target('T02', ('flux/0147427bd564/doc',), 'none', ('frontal',), '0147427bd564'),
    Target('T03', ('flux/01520e2af928/doc',), 'none', ('frontal',), '01520e2af928'),
    Target('T04', ('flux/0152c8450005/live_0_p_d1',), 'none', ('scene',), '0152c8450005'),
    Target('T05', ('flux/015d9b2ddaf9/live_0_p_d1',), 'none', ('scene',), '015d9b2ddaf9'),
    Target('T06', ('flux/0160116b9162/live_0_p_d1',), 'none', ('scene',), '0160116b9162'),
    Target('T07', ('flux/0169c2c21ce7/live_0_p_d1',), 'none', ('scene',), '0169c2c21ce7'),
    Target('T08', ('flux/0176b4220a36/live_0_e_d1',), 'none', ('expression',), '0176b4220a36'),
    Target('T09', ('flux/0176c353d764/live_0_e_d1',), 'none', ('expression',), '0176c353d764'),
    Target('T10', ('flux/017c742ef4c0/live_0_e_d1',), 'none', ('expression',), '017c742ef4c0'),
    Target('T11', ('flux/01b078a0e44d/live_0_p_d1',), 'none', ('glasses',), '01b078a0e44d'),
    Target('T12', ('flux/01cb2a1b9842/live_0_p_d1',), 'none', ('glasses',), '01cb2a1b9842'),
    Target('T13', ('flux/01c40096d14d/live_0_p_d1',), 'none', ('beard',), '01c40096d14d'),
    Target('T14', ('flux/02320c808c04/live_0_p_d1',), 'none', ('beard',), '02320c808c04'),
    Target('T15', ('flux/017d04e13ad0/live_0_p_d1',), 'none', ('headwear',), '017d04e13ad0'),
    Target('T16', ('flux/0181c1150cee/live_0_p_d1',), 'low_light', ('low-light',), '0181c1150cee'),
    Target('T17', ('flux/01829a4fe5cc/live_0_p_d1',), 'low_light', ('low-light',), '01829a4fe5cc'),
    Target('T18', ('flux/01868f7265ab/live_0_p_d1',), 'small:0.25', ('small-face',), '01868f7265ab'),
    Target('T19', ('flux/018728683b7b/live_0_p_d1',), 'small:0.12', ('small-face',), '018728683b7b'),
    Target('T20', ('flux/018850795aca/doc',), 'pole', ('occlusion',), '018850795aca'),
    Target('T21', ('flux/0197b1c56341/live_0_p_d1', 'flux/019b36fa2b73/live_0_p_d1'), 'pair', ('two-people',), '0197b1c56341'),
    Target('T22', ('flux/01a774683a3e/live_0_p_d1', 'flux/01a79b79e217/live_0_p_d1'), 'pair', ('two-people',), '01a774683a3e'),
    Target('T23', ('fs/000002',), 'none', ('profile', 'rendered'), ''),
    Target('T24', ('fs/000013',), 'none', ('profile', 'rendered'), ''),
    Target('T25', ('fs/000063',), 'none', ('glasses', 'rendered'), ''),
    Target('T26', ('fs/000572',), 'none', ('expression', 'rendered'), ''),
    Target('T27', ('fs/000083',), 'none', ('low-light', 'rendered'), ''),
    Target('T28', ('fs/000059',), 'none', ('beard', 'rendered'), ''),
    Target('T29', ('fs/000685',), 'none', ('occlusion', 'rendered'), ''),
)
DEV_TARGETS = (
    Target('D01', ('flux/00194c971ca4/live_0_p_d1',), 'none', ('dev',), '00194c971ca4'),
    Target('D02', ('flux/001bd789984b/live_0_p_d1',), 'none', ('dev',), '001bd789984b'),
    Target('D03', ('flux/001d6e39269e/live_0_p_d1',), 'none', ('dev',), '001d6e39269e'),
    Target('D04', ('flux/00242572c9ee/live_0_p_d1',), 'none', ('dev',), '00242572c9ee'),
    Target('D05', ('flux/0024d7b330c2/live_0_p_d1',), 'none', ('dev',), '0024d7b330c2'),
    Target('D06', ('flux/002e68360d8a/live_0_p_d1',), 'none', ('dev',), '002e68360d8a'),
)
# MANIFEST-END
