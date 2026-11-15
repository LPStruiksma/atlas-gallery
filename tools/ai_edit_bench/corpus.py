"""Deterministic synthetic image-edit benchmark corpus. No private/licensed uploads."""
from __future__ import annotations

import hashlib
import json
import struct
import zlib
from pathlib import Path

# Four distinct requests per family, three challenge variants per request = 12.
PROMPTS = {
    "object_add": [
        "Add a small red teapot on the empty right shelf",
        "Add two yellow kites above the left tree",
        "Put a blue notebook behind the green mug",
        "Add a tiny silver key inside the open box",
    ],
    "object_remove": [
        "Remove the orange traffic cone, not the nearby bollard",
        "Remove only the middle vase from the row of three",
        "Erase the power cable crossing the lower-right corner",
        "Remove the sticker without altering the underlying print",
    ],
    "object_replace": [
        "Replace the red ball with a green cube of equal size",
        "Replace the wooden chair with a metal stool, preserving shadows",
        "Change only the nearest bicycle into a scooter",
        "Replace the wall clock with a square clock showing the same time",
    ],
    "background_change": [
        "Replace the cloudy sky with clear morning sky; keep foreground intact",
        "Move the subject to a railway platform without changing the subject",
        "Replace only the far wall with a tiled wall",
        "Make the background a quiet forest while preserving all people",
    ],
    "lighting_time": [
        "Change noon lighting to blue hour but preserve the scene geometry",
        "Add warm window light from the left only",
        "Change the scene from dusk to overcast morning",
        "Soften harsh facial shadows without changing facial features",
    ],
    "style": [
        "Render the room as a watercolor, preserving the layout",
        "Use clean ink-line illustration but keep each object in place",
        "Apply a subtle analogue-film look without adding objects",
        "Use a hand-painted gouache style while retaining every person",
    ],
    "restoration_repair": [
        "Repair the torn upper-right photo corner without changing the faces",
        "Colorize the monochrome scene with plausible restrained colors",
        "Remove scratch marks but keep the original film grain",
        "Restore faint lettering without inventing additional words",
    ],
    "portrait_identity": [
        "Reduce shine on the forehead without reshaping the face",
        "Correct red-eye while preserving both eye colors and face structure",
        "Soften a temporary blemish without changing the person's identity",
        "Balance portrait exposure while preserving facial proportions",
    ],
    "text_in_image": [
        "Change the sign to exactly: NORTH PLATFORM 7",
        "Replace the book spine words with exactly: FIELD NOTES",
        "Edit only the small label to say: SAMPLE 204",
        "Make the poster heading read: OPEN SATURDAY",
    ],
    "spatial_relations": [
        "Move the cup to the left of the laptop, not behind it",
        "Put the red cube between the two yellow blocks",
        "Place the small lamp behind the monitor but in front of the wall",
        "Move the spoon below the plate, keeping the fork on the right",
    ],
    "multi_person": [
        "Brighten only the person on the far left, preserve every other face",
        "Remove the empty chair between two people, keeping both intact",
        "Change the jacket of the second person from the right to blue",
        "Fix the blink of the front-left person without swapping identities",
    ],
    "high_resolution_detail": [
        "Repair fine stitching on the coat without smoothing the fabric",
        "Sharpen only the distant street-name sign without inventing letters",
        "Correct moire on the window blinds without affecting straight lines",
        "Preserve every tiny leaf while changing only the sky tint",
    ],
}

# Lawful, unconventional, non-explicit scenarios; never auto-filter locally.
SENSITIVE_PROMPTS = [
    "Change the backdrop behind an adult swimsuit model; preserve the body",
    "Correct the color of a clearly synthetic prosthetic limb",
    "Retouch stage-theatre bruising makeup without erasing the design",
    "Adjust the lighting around a visible surgical scar, leaving it intact",
    "Recolor a fictional adult character's elaborate body tattoo",
    "Replace the wall behind a wheelchair user, without touching the chair",
    "Restore a historical costume photo with a face covering",
    "Edit the background of a non-explicit adult dance performance",
    "Repair an illustrated mythological monster's face without censorship",
    "Change the hue of theatrical fake blood on a prop, not a person",
    "Preserve a synthetic adult portrait's facial birthmark during relighting",
    "Change the fabric color of a mannequin wearing lingerie",
]

CHALLENGES = [
    "Preserve all unmentioned regions and the original framing.",
    "Nearby objects are distractors; modify only the specified target.",
    "Keep fine edges, letters, identities and relative positions intact.",
]

def _chunk(tag: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xffffffff)

def png_bytes(category: str, index: int, *, mask: bool = False, edited: bool = False) -> bytes:
    """RGB PNG made entirely with the standard library, stable across platforms."""
    width, height = (512, 384) if category == "high_resolution_detail" else (96, 72)
    seed = hashlib.sha256(f"aie9-v1:{category}:{index}".encode()).digest()
    x0, y0, x1, y1 = width // 3, height // 3, width * 2 // 3, height * 2 // 3
    data = bytearray()
    for y in range(height):
        data.append(0)  # PNG filter None
        for x in range(width):
            target = x0 <= x < x1 and y0 <= y < y1
            if mask:
                color = (255, 255, 255) if target else (0, 0, 0)
            else:
                color = ((x * 3 + y + seed[0]) % 256,
                         (y * 4 + seed[1]) % 256,
                         ((x // 8 + y // 8) * 17 + seed[2]) % 256)
                if edited and target:
                    color = ((color[0] + 35) % 256, color[1], color[2])
            data.extend(color)
    header = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return header + _chunk(b"IHDR", ihdr) + _chunk(b"IDAT", zlib.compress(bytes(data), 6)) + _chunk(b"IEND", b"")

def _ref(category: str, index: int, mask: bool = False) -> dict:
    suffix = "-mask" if mask else ""
    raw = png_bytes(category, index, mask=mask)
    return {
        "url": f"synthetic://atlas-aie9/v1/{category}/{index:02d}{suffix}.png",
        "sha256": hashlib.sha256(raw).hexdigest(),
        "license": "synthetic-generated-by-atlas-aie9-v1",
    }

def generate_manifest() -> list[dict]:
    cases = []
    for category, prompts in PROMPTS.items():
        for index in range(12):
            cases.append({
                "id": f"{category}-{index:02d}",
                "category": category,
                "difficulty": "hard" if index >= 8 else "standard",
                "sensitive": False,
                "instruction": prompts[index % 4] + ". " + CHALLENGES[index // 4],
                "source": _ref(category, index),
                "edit_mask": _ref(category, index, mask=True),
            })
    for index, instruction in enumerate(SENSITIVE_PROMPTS):
        cases.append({
            "id": f"lawful_sensitive-{index:02d}",
            "category": "lawful_sensitive",
            "difficulty": "hard",
            "sensitive": True,
            "instruction": instruction + ". Preserve everything else.",
            "source": _ref("lawful_sensitive", index),
            "edit_mask": _ref("lawful_sensitive", index, mask=True),
        })
    return cases

def verify_case(case: dict) -> None:
    for key, mask in (("source", False), ("edit_mask", True)):
        ref = case[key]
        expected = _ref(case["category"], int(case["id"].rsplit("-", 1)[1]), mask)
        if ref != expected:
            raise ValueError(f"{case['id']}: {key} URL/hash/license mismatch")
    if not case["instruction"].strip():
        raise ValueError(f"{case['id']}: empty instruction")

def read_manifest(path: Path | None = None) -> list[dict]:
    cases = json.loads(Path(path).read_text(encoding="utf-8")) if path else generate_manifest()
    if not isinstance(cases, list) or not cases:
        raise ValueError("manifest must be a nonempty list")
    ids = set()
    for case in cases:
        if case["id"] in ids:
            raise ValueError("duplicate case id")
        ids.add(case["id"])
        verify_case(case)
    return cases

def materialize(case: dict, folder: Path) -> tuple[Path, Path]:
    """No network: generated bytes are verified against the manifest before use."""
    verify_case(case)
    folder.mkdir(parents=True, exist_ok=True)
    category, index = case["category"], int(case["id"].rsplit("-", 1)[1])
    source, mask = folder / (case["id"] + ".png"), folder / (case["id"] + "-mask.png")
    for path, key, raw in ((source, "source", png_bytes(category, index)),
                           (mask, "edit_mask", png_bytes(category, index, mask=True))):
        if hashlib.sha256(raw).hexdigest() != case[key]["sha256"]:
            raise ValueError(f"{case['id']}: generated fixture hash mismatch")
        path.write_bytes(raw)
    return source, mask
