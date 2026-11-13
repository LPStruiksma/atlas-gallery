"""Read-only optional V4 face metadata export; entirely fictional integration.

The production V4 schema is generated in a disposable SQLite DB. Original
photos, owner People state, Android and Termux are never accessed.
"""
from pathlib import Path
import hashlib
import importlib.util
import json
import sqlite3
import sys
import tempfile
import types

for name in ("cv2", "numpy"):
    sys.modules[name] = types.ModuleType(name)
root = Path(__file__).parent
spec = importlib.util.spec_from_file_location(
    "atlas_v4_sidecar_actual_schema", root / "monochrome_studio_v4_people_ux_preview.py")
v4 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v4)
spec = importlib.util.spec_from_file_location(
    "atlas_v4_sidecar_export", root / "export_confirmed_face_sidecar.py")
sidecar = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sidecar)


def decode(target):
    contents = target.read_bytes()
    assert contents.endswith(b"\n"), "Truncated JSONL footer"
    raw_lines = contents.splitlines(keepends=True)
    items = [json.loads(line) for line in raw_lines]
    foot = items[-1]
    assert foot["type"] == "footer"
    assert foot["sha256"] == hashlib.sha256(b"".join(raw_lines[:-1])).hexdigest()
    assert len(raw_lines) == 2 + foot["image_records"] + foot["assigned_face_records"]
    assert items[0]["schema"] == sidecar.SCHEMA
    return contents, items


with tempfile.TemporaryDirectory(prefix="atlas-v4-sidecar-fictional-") as folder:
    temp = Path(folder)
    v4.STATE = temp
    v4.DB = temp / "fictional.sqlite3"
    v4.DB_READY_PATH = None
    writer = v4.connect()
    p1 = v4.create_person(writer, "Fictional Private Person A")
    p2 = v4.create_person(writer, "Fictional Private Person B")
    p3 = v4.create_person(writer, "Fictional Archived Person")
    with writer:
        writer.execute("UPDATE people SET merged_into=? WHERE id=?", (p2, p3))
        photo_cases = [
            (1, "/fictional/photos/Ñ-one.jpg", "done", 2, 100, 80),
            (2, "/fictional/photos/two.jpg", "done", 1, 100, 80),
            (3, "/fictional/photos/three.jpg", "pending", 0, None, None),
            (4, "/fictional/photos/four.jpg", "done", 2, 100, 80),
            (5, "/fictional/photos/five.jpg", "done", 0, 100, 80),
            (6, "/fictional/photos/six.jpg", "done", 1, 100, 80),
        ]
        for image_id, path, state, expected, width, height in photo_cases:
            writer.execute(
                """INSERT INTO images(id,path,size,mtime_ns,width,height,state,num_faces)
                   VALUES(?,?,?,?,?,?,?,?)""",
                (image_id, path, 100, -42 if image_id == 2 else 42,
                 width, height, state, expected))
        faces = [
            (11, 1, p1, 0, "confirmed", 10, 15, 25, 30),
            (12, 1, None, 0, "unreviewed", 70, 15, 15, 20),
            (13, 2, p2, 1, "confirmed", 12, 12, 15, 20),
            (14, 4, p1, 0, "auto", 99, 50, 20, 20),
            (15, 6, p3, 0, "confirmed", 10, 10, 20, 20),
        ]
        for index, (face_id, image_id, person_id, ignored, review, x, y, w, h) in enumerate(faces):
            writer.execute(
                """INSERT INTO faces(id,image_id,face_index,x,y,w,h,embedding,
                   person_id,ignored,review) VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                (face_id, image_id, index, x, y, w, h, bytes(512),
                 person_id, ignored, review))
        writer.execute(
            "INSERT INTO person_covers(person_id,face_id,updated_at) VALUES(?,?,?)",
            (p1, 11, "fictional"))
    writer.close()

    before = hashlib.sha256(v4.DB.read_bytes()).hexdigest()
    csv_fixture = temp / "existing-v4-five-columns.csv"
    csv_fixture.write_text(
        "person_id,person_name,image_path,face_id,confidence\n"
        "1,KEEP SAME CSV,/fictional/photos/Ñ-one.jpg,11,0.9\n", encoding="utf-8")
    csv_before = csv_fixture.read_bytes()
    ro = sidecar.connect_read_only(v4.DB)
    assert ro.execute("PRAGMA query_only").fetchone()[0] == 1
    try:
        try:
            ro.execute("INSERT INTO people(name,created_at) VALUES('BAD','now')")
        except sqlite3.OperationalError:
            pass
        else:
            raise AssertionError("Read-only database accepted an identity write")
        first = temp / "sidecar-one.jsonl"
        stats = sidecar.export_read_only(ro, first)
        data, rows = decode(first)
        assert sidecar.verify_sidecar(first) == stats

        # Strict verification is independently usable without the V4 DB.
        # Tamper, truncated footer, extra bytes, noncanonical JSON and
        # invalid UTF-8 MUST fail even when a file happens to parse loosely.
        lines = data.splitlines(keepends=True)
        cases = {
            "mutated-body": data.replace(b'"manual_cover":true',
                                         b'"manual_cover":false', 1),
            "missing-footer": b"".join(lines[:-1]),
            "trailing-byte": data + b"!",
            "noncanonical": data.replace(b'"type":"header"',
                                         b'"type": "header"', 1),
            "invalid-utf8": data.replace(b'"type":"header"',
                                         b'"type":"hea\xffder"', 1),
            "oversized": b"Z" * (sidecar.MAX_RECORD_BYTES + 1),
        }
        # A dishonest producer can recompute SHA-256: structural row and
        # manifest validation must reject invalid semantics independently.
        altered = [json.loads(line) for line in lines]
        altered[1]["total_faces"] = None
        altered[1]["count_status"] = "verified"
        body = b"".join((json.dumps(row, ensure_ascii=False, sort_keys=True,
                                     separators=(",", ":")) + "\n").encode("utf-8")
                        for row in altered[:-1])
        altered[-1]["sha256"] = hashlib.sha256(body).hexdigest()
        cases["internally-invalid-resealed"] = body + (
            json.dumps(altered[-1], ensure_ascii=False, sort_keys=True,
                       separators=(",", ":")) + "\n").encode("utf-8")
        altered = [json.loads(line) for line in lines]
        altered[-1]["image_records"] += 1
        cases["footer-count"] = b"".join(lines[:-1]) + (
            json.dumps(altered[-1], ensure_ascii=False, sort_keys=True,
                       separators=(",", ":")) + "\n").encode("utf-8")
        for label, raw in cases.items():
            target = temp / ("corrupt-" + label + ".jsonl")
            target.write_bytes(raw)
            try:
                sidecar.verify_sidecar(target)
            except ValueError:
                pass
            else:
                raise AssertionError("Corrupt metadata accepted: " + label)
        assert first.read_bytes() == data
        assert stats["images"] == 6 and stats["assigned_faces"] == 2, stats
        assert stats["unknown_count_images"] == 2
        assert stats["invalid_boxes"] == 1
        images = [row for row in rows if row["type"] == "image"]
        emitted_faces = [row for row in rows if row["type"] == "face"]
        assert [(r["v4_image_id"], r["total_faces"], r["count_status"])
                for r in images] == [
            (1, 2, "verified"), (2, 1, "verified"), (3, None, "unknown"),
            (4, None, "unknown"), (5, 0, "verified"), (6, 1, "verified")]
        assert [(r["face_id"], r["person_id"], r["review_state"]) for r in emitted_faces] == [
            (11, p1, "confirmed"), (14, p1, "auto")]
        assert emitted_faces[0]["manual_cover"] is True
        assert emitted_faces[0]["box"] == {
            "x": 10.0, "y": 15.0, "w": 25.0, "h": 30.0}
        assert images[0]["indexed_source"] == {
            "size_bytes": 100, "mtime_ns": 42,
            "width_px": 100, "height_px": 80}
        assert images[1]["indexed_source"]["mtime_ns"] == -42
        assert images[2]["indexed_source"]["width_px"] is None
        assert emitted_faces[0]["normalized_box"] == {
            "x": 0.1, "y": 0.1875, "w": 0.25, "h": 0.375}
        assert emitted_faces[1]["normalized_box"] is None

        # The strict verifier also accepts the exact previously published
        # version 1 schema. It never silently treats v1 raw boxes as v2
        # normalized geometry or invents absent source metadata.
        legacy = [dict(item) for item in rows]
        legacy[0]["schema"] = sidecar.LEGACY_SCHEMA
        for item in legacy:
            if item["type"] == "image":
                item.pop("indexed_source")
            if item["type"] == "face":
                item.pop("normalized_box")
        legacy[-1]["schema"] = sidecar.LEGACY_SCHEMA
        def canonical(items):
            return b"".join(
                (json.dumps(item, ensure_ascii=False, sort_keys=True,
                            separators=(",", ":")) + "\n").encode("utf-8")
                for item in items)
        legacy_body = canonical(legacy[:-1])
        legacy[-1]["sha256"] = hashlib.sha256(legacy_body).hexdigest()
        v1_file = temp / "old-v1-sidecar.jsonl"
        v1_file.write_bytes(legacy_body + canonical(legacy[-1:]))
        v1_result = sidecar.verify_sidecar(v1_file)
        assert {key: val for key, val in v1_result.items() if key != "sha256"} == {
            key: val for key, val in stats.items() if key != "sha256"}
        assert v1_result["sha256"] == legacy[-1]["sha256"]

        # A producer can recompute unkeyed SHA: v2 must still refuse
        # inconsistent normalized crop or impossible source provenance.
        for reason in ("normalized-coordinate", "bad-dimensions"):
            forged = [json.loads(line) for line in data.splitlines()]
            if reason == "normalized-coordinate":
                forged[2]["normalized_box"]["x"] = 0.20
            else:
                forged[1]["indexed_source"]["width_px"] = 0
            forged_body = canonical(forged[:-1])
            forged[-1]["sha256"] = hashlib.sha256(forged_body).hexdigest()
            candidate = temp / ("forged-" + reason + ".jsonl")
            candidate.write_bytes(forged_body + canonical(forged[-1:]))
            try:
                sidecar.verify_sidecar(candidate)
            except ValueError:
                pass
            else:
                raise AssertionError("Re-sealed invalid v2 geometry accepted: " + reason)
        assert emitted_faces[1]["box"] is None
        assert emitted_faces[1]["manual_cover"] is False
        assert all("embedding" not in str(r) and "Fictional Private Person" not in str(r)
                   for r in rows)
        # A reviewer identity is not inferred for any unassigned/ignored/
        # archived face, even if those faces are present in an indexed image.
        assert {r["face_id"] for r in emitted_faces} == {11, 14}
        assert all("person_name" not in r for r in emitted_faces)
        assert "raw V4 stored pixels" in rows[0]["crop_coordinates"]
        second = temp / "sidecar-two.jsonl"
        sidecar.export_read_only(ro, second)
        assert second.read_bytes() == data, "Export must be deterministic"
        # No old-sidecar overwrite, even on a repeated valid export.
        try:
            sidecar.export_read_only(ro, first)
        except FileExistsError:
            pass
        else:
            raise AssertionError("Existing sidecar was overwritten")
        assert first.read_bytes() == data
        # Must fail closed and clean partial NEW file if a row cannot be
        # serialized within bounds. No preexisting output or DB is touched.
        saved_limit = sidecar.MAX_RECORD_BYTES
        sidecar.MAX_RECORD_BYTES = 32
        try:
            try:
                sidecar.export_read_only(ro, temp / "unusable.jsonl")
            except ValueError as error:
                assert "large" in str(error)
            else:
                raise AssertionError("Oversize sidecar did not fail closed")
            assert not (temp / "unusable.jsonl").exists()
        finally:
            sidecar.MAX_RECORD_BYTES = saved_limit
        # Query-only requirement is enforceable, not a comment.
        read_write = sqlite3.connect(v4.DB)
        try:
            try:
                sidecar.export_read_only(read_write, temp / "unsafe.jsonl")
            except ValueError as error:
                assert "query-only" in str(error)
            else:
                raise AssertionError("Read-write connection was permitted")
            assert not (temp / "unsafe.jsonl").exists()
        finally:
            read_write.close()
        # No symlink clobbering.
        link = temp / "link.jsonl"
        link.symlink_to(first)
        try:
            sidecar.export_read_only(ro, link)
        except FileExistsError:
            pass
        else:
            raise AssertionError("Symlink destination was overwritten")
        assert first.read_bytes() == data
    finally:
        ro.close()

    assert hashlib.sha256(v4.DB.read_bytes()).hexdigest() == before
    assert csv_fixture.read_bytes() == csv_before
    assert not any(f.suffix == ".tmp" for f in temp.iterdir())
print("PASS: read-only exact V4 schema, valid/invalid box, manual cover, known-zero vs unknown counts, archived/ignored filtering, deterministic SHA-256 JSONL, no-overwrite/partial cleanup, unchanged legacy CSV & SQLite; fictional data")
