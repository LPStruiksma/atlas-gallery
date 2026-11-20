#!/usr/bin/env python3
"""DESIGN-8T #627: copy the measured numbers this target cites out of the
emulator artifacts into measured-baseline.json, keyed by frame key.

Usage: python3 extract_baseline.py <dir with viewer-609-*.json> [viewer-627-gesture.json <run id> <head sha>]

Nothing is invented here: every value is copied from a report written by the
instrumented tests (Viewer609Recorder). Frames, insets and semantic bounds only;
no image content, URIs or file names.
"""
import json
import pathlib
import sys

TAGS = [
    "atlas-viewer-top-chrome", "atlas-back", "atlas-viewer-details-open",
    "atlas-viewer-find-person", "atlas-viewer-find-similar", "atlas-viewer-more",
    "atlas-viewer-bottom-chrome", "atlas-filmstrip", "atlas-thumbnail-0",
    "atlas-thumbnail-1", "atlas-viewer-favourite", "atlas-edit-photo",
    "atlas-share-media", "atlas-viewer-trash", "atlas-video-controls",
    "atlas-video-play-pause", "atlas-video-seek",
]
INSETS = [
    "compose_safeDrawing", "compose_statusBars", "compose_navigationBars",
    "compose_displayCutout", "android_systemGestures",
    "android_mandatorySystemGestures", "android_tappableElement",
]


def frame_summary(frame):
    ins = frame["insets"]
    out = {
        "capture": frame.get("capture"),
        "capture_status": frame.get("capture_status"),
        "navigation_mode": frame.get("navigation_mode"),
        "rotation": frame.get("rotation"),
        "window_px": frame.get("window_px"),
        "pixels_per_dp": frame.get("pixels_per_dp"),
        "insets_dp": {k: [round(v, 2) for v in ins[k]["dp"]] for k in INSETS if k in ins},
        "cutout_rectangles_px": ins.get("cutout_rectangles_px"),
        "regions_dp": {},
    }
    for tag in TAGS:
        region = frame["regions"].get(tag, {})
        if region.get("status") != "MEASURED":
            out["regions_dp"][tag] = region.get("status", "ABSENT")
            continue
        b = region["bounds_dp"]
        out["regions_dp"][tag] = {k: round(b[k], 2) for k in ("left", "top", "right", "bottom", "width", "height")}
    gap = frame.get("video_controls_to_filmstrip_gap_dp")
    if isinstance(gap, (int, float)):
        out["video_controls_to_filmstrip_gap_dp"] = round(gap, 2)
    return out


def main():
    src = pathlib.Path(sys.argv[1])
    reports = [src / "viewer-609-photo.json", src / "viewer-609-video.json"]
    if len(sys.argv) > 2:
        reports.append(pathlib.Path(sys.argv[2]))
    result = {"source_reports": [], "frames": {}}
    run_609 = {"run": "44627923073", "artifact": "ATLAS-android-UI-evidence-shard-0", "pr": 616}
    run_627 = {"run": sys.argv[3] if len(sys.argv) > 3 else None,
               "head": sys.argv[4] if len(sys.argv) > 4 else None}
    for path in reports:
        data = json.loads(path.read_text())
        result["source_reports"].append({"file": path.name, "issue": data.get("issue"),
                                         **(run_627 if data.get("issue") == 627 else run_609),
                                         "host": data.get("host"), "not_captured": data.get("not_captured")})
        for frame in data["frames"]:
            result["frames"][frame["key"]] = frame_summary(frame)
    out = pathlib.Path(__file__).with_name("measured-baseline.json")
    out.write_text(json.dumps(result, indent=1, sort_keys=False) + "\n")
    print(f"wrote {out} with {len(result['frames'])} frames")


if __name__ == "__main__":
    main()
