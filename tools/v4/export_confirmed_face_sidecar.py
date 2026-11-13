#!/usr/bin/env python3
"""Explicit, read-only V4 face metadata handoff; NEVER an identity importer.

This OPTIONAL post-release companion leaves the existing five-column CSV
untouched. It cannot rename, tag, merge, move or delete any photographs.
No V4 server, OpenCV, NumPy or Android environment is required.

Usage:
  python3 tools/v4/export_confirmed_face_sidecar.py \\
      --db /path/to/people.sqlite3 --output /path/to/new-sidecar.jsonl

Output MUST be a new file. The last line seals the exact UTF-8 header/body
bytes with SHA-256; an interrupted export is invalid and never trusted.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import sqlite3


SCHEMA = "atlas.v4.face_metadata.v2"
LEGACY_SCHEMA = "atlas.v4.face_metadata.v1"
REQUIRED = {
    "images": {"id", "path", "width", "height", "size", "mtime_ns", "state", "num_faces"},
    "faces": {"id", "image_id", "person_id", "ignored", "review",
              "x", "y", "w", "h"},
    "people": {"id", "merged_into"},
    "person_covers": {"person_id", "face_id"},
}
MAX_RECORD_BYTES = 1024 * 1024


def connect_read_only(path: Path) -> sqlite3.Connection:
    path = Path(path).expanduser().resolve(strict=True)
    if not path.is_file():
        raise ValueError("V4 database must be an existing regular file")
    c = sqlite3.connect(path.as_uri() + "?mode=ro", uri=True, timeout=15,
                        isolation_level=None)  # Explicit read transactions only.
    try:
        c.row_factory = sqlite3.Row
        c.execute("PRAGMA query_only=ON")
        c.execute("PRAGMA busy_timeout=15000")
        for table, required in REQUIRED.items():
            # Hard-coded table names; never interpolated from database rows.
            present = {r["name"] for r in c.execute("PRAGMA table_info(" + table + ")")}
            if not required <= present:
                raise ValueError("Expected V4 schema unavailable: " + table)
        return c
    except BaseException:
        c.close()
        raise


def valid_face_box(face: sqlite3.Row, width, height):
    """Return raw V4 pixel geometry only when strictly inside source bounds.

    V4 coordinates are not EXIF-orientation-corrected native MediaStore crops.
    Consumers must validate the original orientation/dimensions independently.
    """
    try:
        width = float(width)
        height = float(height)
        values = [float(face[field]) for field in ("x", "y", "w", "h")]
    except (TypeError, ValueError, OverflowError):
        return None
    x, y, w, h = values
    if not all(math.isfinite(v) for v in (width, height, x, y, w, h)):
        return None
    if width <= 0 or height <= 0 or x < 0 or y < 0 or w <= 0 or h <= 0:
        return None
    # Float coordinates can represent partially out-of-frame detections.
    # Refuse rather than silently clamp and claim the crop was measured.
    if x + w > width or y + h > height:
        return None
    return dict(x=x, y=y, w=w, h=h)


def export_read_only(c: sqlite3.Connection, output: Path) -> dict:
    """Stream one deterministic read snapshot to a NEW owner-selected file.

    Not a backup: omits embeddings, private labels, Undo, journal and all
    unassigned face geometry. Separate CSV remains the sole identity claim.
    """
    output = Path(output).expanduser()
    if not output.parent.is_dir():
        raise ValueError("Destination directory does not exist")
    if output.exists() or output.is_symlink():
        raise FileExistsError("Sidecar already exists; refusing overwrite")
    # A caller supplied read-only connection is required, not just assumed.
    if c.execute("PRAGMA query_only").fetchone()[0] != 1:
        raise ValueError("Sidecar requires a query-only SQLite connection")
    if c.in_transaction:
        raise ValueError("Caller must finish its previous read transaction")
    # Avoid clobbering symlinks and never delete any pre-existing destination.
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    fd = os.open(output, flags, 0o600)
    st = os.fstat(fd)
    complete = False
    images = faces = unknown_counts = invalid_boxes = 0
    digest = hashlib.sha256()
    try:
        with os.fdopen(fd, "wb", closefd=True) as file:
            c.execute("BEGIN")  # One pinned SQLite snapshot, no writer lock.
            try:
                def record(data, *, sealed=True):
                    line = (json.dumps(data, ensure_ascii=False, sort_keys=True,
                                       separators=(",", ":"), allow_nan=False) + "\n").encode("utf-8")
                    if len(line) > MAX_RECORD_BYTES:
                        raise ValueError("Unreasonably large metadata row")
                    file.write(line)
                    if sealed:
                        digest.update(line)

                record({"type": "header", "schema": SCHEMA,
                        "identity_authority": "unchanged V4 five-column CSV",
                        "crop_coordinates": "raw V4 stored pixels; orientation unverified"})

                images_sql = """SELECT i.id,i.path,i.width,i.height,i.size,i.mtime_ns,i.state,i.num_faces,
                    (SELECT COUNT(*) FROM faces fx WHERE fx.image_id=i.id) AS observed_faces
                    FROM images i ORDER BY i.id"""
                # A pair of ordered cursors, not one SELECT for each image.
                # A 150k-item catalogue must NOT trigger 150k SQLite round trips,
                # nor build a full per-image face list in Python memory.
                faces_sql = """SELECT f.image_id,f.id,f.person_id,f.review,
                    f.x,f.y,f.w,f.h,(pc.face_id IS NOT NULL) AS manual_cover
                    FROM faces f JOIN people p ON p.id=f.person_id
                    LEFT JOIN person_covers pc ON pc.person_id=f.person_id AND pc.face_id=f.id
                    WHERE f.person_id IS NOT NULL
                      AND f.ignored=0 AND p.merged_into IS NULL
                    ORDER BY f.image_id,f.id"""
                face_cursor = iter(c.execute(faces_sql))
                next_face = next(face_cursor, None)
                for image in c.execute(images_sql):
                    # A dangling face with no indexed image is an inconsistent
                    # source snapshot, not an invitation to silently misattach.
                    if next_face is not None and next_face["image_id"] < image["id"]:
                        raise ValueError("Assigned face references an absent V4 image")

                    images += 1
                    observed = int(image["observed_faces"])
                    claimed = int(image["num_faces"])
                    verified = image["state"] == "done" and claimed >= 0 and claimed == observed
                    total = observed if verified else None
                    if not verified:
                        unknown_counts += 1
                    source_dimensions = {
                        "width_px": int(image["width"]) if image["width"] is not None
                            and int(image["width"]) > 0 else None,
                        "height_px": int(image["height"]) if image["height"] is not None
                            and int(image["height"]) > 0 else None,
                        "size_bytes": int(image["size"]),
                        "mtime_ns": int(image["mtime_ns"]),
                    }
                    # Hints from V4's own index, never a MediaStore generation
                    # or authenticated content hash. Android must revalidate.
                    record({"type": "image", "image_path": image["path"],
                            "v4_image_id": int(image["id"]),
                            "scan_state": image["state"],
                            "total_faces": total,
                            "count_status": "verified" if verified else "unknown",
                            "indexed_source": source_dimensions})
                    while next_face is not None and next_face["image_id"] == image["id"]:
                        face = next_face
                        next_face = next(face_cursor, None)
                        faces += 1
                        box = valid_face_box(face, image["width"], image["height"])
                        if box is None:
                            invalid_boxes += 1
                        normalized = None
                        if box is not None:
                            width, height = float(image["width"]), float(image["height"])
                            normalized = {"x": box["x"] / width, "y": box["y"] / height,
                                          "w": box["w"] / width, "h": box["h"] / height}
                        record({"type": "face", "image_path": image["path"],
                                "face_id": int(face["id"]),
                                "person_id": int(face["person_id"]),
                                "review_state": face["review"],
                                "manual_cover": bool(face["manual_cover"]),
                                "box": box, "normalized_box": normalized})
                if next_face is not None:
                    raise ValueError("Assigned face references an absent V4 image")
                record({"type": "footer", "schema": SCHEMA,
                        "sha256": digest.hexdigest(),
                        "image_records": images,
                        "assigned_face_records": faces,
                        "unknown_count_images": unknown_counts,
                        "invalid_crop_boxes": invalid_boxes}, sealed=False)
            finally:
                c.rollback()  # Close our read snapshot; never write source DB.
            file.flush()
            os.fsync(file.fileno())
        complete = True
    finally:
        if not complete:
            try:
                present = output.stat()
                if (present.st_dev, present.st_ino) == (st.st_dev, st.st_ino):
                    output.unlink()
            except OSError:
                pass
    return {"images": images, "assigned_faces": faces,
            "unknown_count_images": unknown_counts,
            "invalid_boxes": invalid_boxes,
            "sha256": digest.hexdigest()}


def verify_sidecar(path: Path) -> dict:
    """Bounded strict JSONL/manifest verification; no identity assertions.

    Rejects noncanonical/extra/truncated records and tampered line content.
    SHA-256 establishes *integrity only*, never author/signer authenticity.
    """
    path = Path(path).expanduser()
    digest = hashlib.sha256()
    seen_header = seen_footer = False
    images = faces = unknown = invalid = 0
    last_image_id = last_face_id = 0
    active_path = active_schema = active_source = None

    def require(condition, message):
        if not condition:
            raise ValueError("Invalid V4 sidecar: " + message)

    def exact_fields(row, keys):
        require(set(row) == set(keys), "unexpected or missing record fields")

    def integer(value, min_value=0):
        return type(value) is int and value >= min_value

    with path.open("rb") as data:
        while True:
            raw = data.readline(MAX_RECORD_BYTES + 1)
            if not raw:
                break
            require(len(raw) <= MAX_RECORD_BYTES, "oversize record")
            require(raw.endswith(b"\n") and not raw.endswith(b"\r\n"),
                    "partial or noncanonical line ending")
            try:
                row = json.loads(raw.decode("utf-8", "strict"))
                require(isinstance(row, dict), "object required")
                canonical = (json.dumps(row, ensure_ascii=False, sort_keys=True,
                                        separators=(",", ":"), allow_nan=False) + "\n").encode("utf-8")
            except (UnicodeError, ValueError, TypeError) as error:
                raise ValueError("Invalid V4 sidecar JSON record") from error
            require(canonical == raw, "noncanonical JSON record")
            kind = row.get("type")
            require(not seen_footer, "unexpected bytes after footer")

            if not seen_header:
                exact_fields(row, ("type", "schema", "identity_authority",
                                   "crop_coordinates"))
                require(kind == "header" and row["schema"] in (SCHEMA, LEGACY_SCHEMA),
                        "wrong header/schema")
                active_schema = row["schema"]
                require(row["identity_authority"] == "unchanged V4 five-column CSV",
                        "identity authority changed")
                require(isinstance(row["crop_coordinates"], str), "crop disclaimer")
                seen_header = True
            elif kind == "image":
                image_fields = ("type", "image_path", "v4_image_id", "scan_state",
                                "total_faces", "count_status")
                if active_schema == SCHEMA:
                    image_fields += ("indexed_source",)
                exact_fields(row, image_fields)
                image_id, path_value = row["v4_image_id"], row["image_path"]
                require(integer(image_id, 1) and image_id > last_image_id,
                        "image IDs not strictly increasing")
                require(isinstance(path_value, str) and bool(path_value),
                        "missing image path")
                require(isinstance(row["scan_state"], str), "missing scan state")
                status, total = row["count_status"], row["total_faces"]
                require(status in ("verified", "unknown"), "invalid count status")
                require((status == "unknown" and total is None) or
                        (status == "verified" and row["scan_state"] == "done" and
                         integer(total)), "unknown vs known-zero distinction")
                if active_schema == SCHEMA:
                    source = row["indexed_source"]
                    require(isinstance(source, dict), "invalid indexed source")
                    exact_fields(source, ("width_px", "height_px", "size_bytes", "mtime_ns"))
                    require(integer(source["size_bytes"]) and type(source["mtime_ns"]) is int,
                            "invalid V4 indexed source metadata")
                    for field in ("width_px", "height_px"):
                        require(source[field] is None or integer(source[field], 1),
                                "invalid indexed dimensions")
                    active_source = source
                else:
                    active_source = None
                last_image_id, last_face_id = image_id, 0
                active_path = path_value
                images += 1
                if status == "unknown":
                    unknown += 1
            elif kind == "face":
                face_fields = ("type", "image_path", "face_id", "person_id",
                               "review_state", "manual_cover", "box")
                if active_schema == SCHEMA:
                    face_fields += ("normalized_box",)
                exact_fields(row, face_fields)
                require(active_path is not None and row["image_path"] == active_path,
                        "face not attached to preceding image")
                require(integer(row["face_id"], 1) and
                        row["face_id"] > last_face_id, "face IDs not increasing")
                require(integer(row["person_id"], 1), "invalid Person ID")
                require(isinstance(row["review_state"], str) and
                        bool(row["review_state"]), "missing reviewed provenance")
                require(type(row["manual_cover"]) is bool, "invalid cover flag")
                box = row["box"]
                if box is None:
                    invalid += 1
                else:
                    require(isinstance(box, dict), "invalid face geometry")
                    exact_fields(box, ("x", "y", "w", "h"))
                    require(all(type(box[v]) in (int, float) and
                                math.isfinite(box[v]) for v in ("x", "y", "w", "h")),
                            "nonfinite face geometry")
                    require(box["x"] >= 0 and box["y"] >= 0 and
                            box["w"] > 0 and box["h"] > 0, "invalid box bounds")
                if active_schema == SCHEMA:
                    norm = row["normalized_box"]
                    if box is None:
                        require(norm is None, "invalid crop supplied normalized geometry")
                    else:
                        require(isinstance(norm, dict), "missing normalized box")
                        exact_fields(norm, ("x", "y", "w", "h"))
                        width, height = active_source["width_px"], active_source["height_px"]
                        require(width is not None and height is not None,
                                "raw crop without source dimensions")
                        for field, dim in (("x", width), ("y", height),
                                           ("w", width), ("h", height)):
                            value = norm[field]
                            require(type(value) in (int, float) and math.isfinite(value),
                                    "nonfinite normalized crop")
                            require(0 <= value <= 1 and
                                    abs(value - box[field] / dim) < 1e-9,
                                    "normalized crop disagrees with indexed dimensions")
                        require(norm["x"] + norm["w"] <= 1 + 1e-9 and
                                norm["y"] + norm["h"] <= 1 + 1e-9,
                                "normalized crop exceeds image")
                last_face_id = row["face_id"]
                faces += 1
            elif kind == "footer":
                exact_fields(row, ("type", "schema", "sha256", "image_records",
                                   "assigned_face_records", "unknown_count_images",
                                   "invalid_crop_boxes"))
                require(row["schema"] == active_schema, "footer schema mismatch")
                require(row["sha256"] == digest.hexdigest(),
                        "SHA-256 mismatch or altered metadata")
                for name, expected in (
                    ("image_records", images), ("assigned_face_records", faces),
                    ("unknown_count_images", unknown),
                    ("invalid_crop_boxes", invalid)):
                    require(integer(row[name]) and row[name] == expected,
                            "footer count mismatch: " + name)
                seen_footer = True
                require(data.read(1) == b"", "trailing bytes after footer")
                break
            else:
                raise ValueError("Invalid V4 sidecar: unexpected record type")
            digest.update(raw)
    require(seen_header and seen_footer, "missing complete header or footer")
    return {"images": images, "assigned_faces": faces,
            "unknown_count_images": unknown, "invalid_boxes": invalid,
            "sha256": digest.hexdigest()}


def main():
    parser = argparse.ArgumentParser(
        description="Optional read-only V4 face geometry sidecar; never changes identity assignments")
    parser.add_argument("--db", help="Existing V4 SQLite database (opened mode=ro/query_only)")
    parser.add_argument("--output", help="NEW .jsonl output; no overwrite")
    parser.add_argument("--verify", help="Existing JSONL sidecar to strictly verify; no DB required")
    args = parser.parse_args()
    if args.verify is not None:
        if args.db is not None or args.output is not None:
            parser.error("--verify cannot be combined with --db/--output")
        result = verify_sidecar(Path(args.verify))
    else:
        if not args.db or not args.output:
            parser.error("export requires both --db and --output")
        c = connect_read_only(Path(args.db))
        try:
            result = export_read_only(c, Path(args.output))
        finally:
            c.close()
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
