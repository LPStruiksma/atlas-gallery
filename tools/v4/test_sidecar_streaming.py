"""Verify V4 JSONL export is two-query streaming at a 2,401-image scale.

All records are synthetic, and use the real production schema and exporter.
No actual photographs, People DB or phone are accessed.
"""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import sys
import types

for module in ("cv2", "numpy"):
    sys.modules[module] = types.ModuleType(module)
root = Path(__file__).parent


def imported(name, path):
    spec = importlib.util.spec_from_file_location(name, root / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


v4 = imported("atlas_v4_sidecar_stream_fixture", "monochrome_studio_v4_people_ux_preview.py")
sidecar = imported("atlas_v4_sidecar_stream_actual", "export_confirmed_face_sidecar.py")

COUNT = 2401
with tempfile.TemporaryDirectory(prefix="atlas-v4-2401-fictional-") as tempdir:
    temp = Path(tempdir)
    v4.STATE = temp
    v4.DB = temp / "fictional.sqlite3"
    v4.DB_READY_PATH = None
    db = v4.connect()
    person = v4.create_person(db, "Fictional human, no real identity")
    cases = {0: "two", 1: "one", 2: "unassigned", 3: "empty", 4: "bad-count", 5: "pending"}
    # Bulk inserts are fiction only; 400 groups of six include 0, 1, 2 face
    # cases. Preserve stable ascending image+face IDs, including one image
    # with two attached faces and one with an unassigned extra.
    images = []
    faces = []
    next_face_id = 1
    for image_id in range(1, COUNT + 1):
        case = cases[image_id % 6]
        scanned = case != "pending"
        face_count = {"two": 2, "one": 1, "unassigned": 1,
                      "empty": 0, "bad-count": 1, "pending": 0}[case]
        images.append((image_id, f"/fictional-indexed/image-{image_id:05d}.jpg",
                       1024, 99, 100, 80, "done" if scanned else "pending",
                       face_count))
        count_actual = {
            "two": 2, "one": 1, "unassigned": 1,
            "empty": 0, "bad-count": 0, "pending": 0}[case]
        for face_num in range(count_actual):
            # Unassigned candidates are included in per-image totals but
            # excluded from face metadata.
            owner = None if case == "unassigned" else person
            faces.append((next_face_id, image_id, face_num, 10 + 15 * face_num,
                          15, 20, 24, bytes(512), owner, "confirmed"))
            next_face_id += 1
    with db:
        db.executemany(
            "INSERT INTO images(id,path,size,mtime_ns,width,height,state,num_faces) "
            "VALUES(?,?,?,?,?,?,?,?)", images)
        db.executemany(
            "INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,person_id,review) "
            "VALUES(?,?,?,?,?,?,?,?,?,?)", faces)
    db.close()
    source_hash = hashlib.sha256(v4.DB.read_bytes()).hexdigest()
    reader = sidecar.connect_read_only(v4.DB)
    statements = []
    reader.set_trace_callback(lambda sql: statements.append(sql))
    try:
        output = temp / "stream.jsonl"
        saved = sidecar.export_read_only(reader, output)
        selects = [q.lower().lstrip() for q in statements if q.lstrip().lower().startswith("select")]
        img_queries = [q for q in selects if "from images i" in q]
        face_queries = [q for q in selects if "from faces f join people p" in q]
        assert len(img_queries) == 1, img_queries
        assert len(face_queries) == 1, face_queries
        assert len(selects) == 2, selects
        assert saved["images"] == COUNT
        # For image IDs 1..2401, 0 and 1 cases appear 400 times each:
        # 400 two-face, 401 one-face groups -> 1201 face records.
        assert saved["assigned_faces"] == 1201
        assert saved["unknown_count_images"] == 800
        assert saved["invalid_boxes"] == 0
        assert sidecar.verify_sidecar(output) == saved

        # Streaming metadata contains exactly one image record before each
        # group of increasing face IDs, and never exposes the identity name.
        current_image = 0
        seen_face = -1
        for line in output.read_text(encoding="utf-8").splitlines():
            row = json.loads(line)
            if row["type"] == "image":
                assert row["v4_image_id"] > current_image
                current_image = row["v4_image_id"]
                seen_face = -1
                assert row["image_path"].endswith(f"image-{current_image:05d}.jpg")
                kind = cases[current_image % 6]
                if kind in ("bad-count", "pending"):
                    assert row["total_faces"] is None
                elif kind == "empty":
                    assert row["total_faces"] == 0
                elif kind == "two":
                    assert row["total_faces"] == 2
                else:
                    assert row["total_faces"] == 1
            elif row["type"] == "face":
                assert row["face_id"] > seen_face
                seen_face = row["face_id"]
                assert row["image_path"].endswith(f"image-{current_image:05d}.jpg")
                assert row["person_id"] == person
                assert "Fictional human" not in line
        assert current_image == COUNT

        # Same pinned database must reproduce precisely the same bytes,
        # despite a different destination pathname and new read snapshot.
        next_output = temp / "stream-again.jsonl"
        assert sidecar.export_read_only(reader, next_output) == saved
        assert next_output.read_bytes() == output.read_bytes()
        assert hashlib.sha256(v4.DB.read_bytes()).hexdigest() == source_hash
    finally:
        reader.close()
print("PASS: 2401 fictional images, 1201 assigned faces, exactly two streaming SELECTs, "
      "canonical verified JSONL, known-zero/Unknown, no source DB mutation")
