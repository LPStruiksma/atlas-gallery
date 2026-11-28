"""Deterministically join frozen IDS-12F rows and the two immutable raw records.
Run from any directory: python3 tools/identity_bench/head/results/ids12f/assemble_votes.py
Checks exact input blob hashes and committed output equality; never runs inference.
"""
import hashlib
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
HEAD = HERE.parent.parent

def load(path, expected):
    data = path.read_bytes()
    blob = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
    if blob != expected:
        raise ValueError(f"frozen input changed: {path.name}")
    return json.loads(data)

def index(records, key, ids):
    result = {r[key]: r for r in records}
    if len(records) != len(ids) or set(result) != set(ids):
        raise ValueError(f"missing or duplicate {key}")
    return result

def assemble():
    a = load(HERE / "judge-a-blinded.json", "23f2af458ff051d68217200b033d0450a5a515df")
    b = load(HERE / "judge-b-blinded.json", "65eca1783b7ff0bb644d79d1aa99f017b9f6e3ed")
    key = load(HERE / "sheet-key.json", "e3f6760ebbf6b17086671c013040f4937e970d8e")["pairs"]
    rows = load(HERE / "rows.json", "d2bee7702ea94591b798ac732cedfd83c842bc92")
    cohort = load(HEAD / "cohort.json", "280bebf1cbdecc40eb463ac7173e1d35f5ec20a1")
    ids = [p["pair_id"] for p in cohort["pairs"]]
    if len(ids) != 20 or len(set(ids)) != 20 or set(key) != set(ids) or len(a["judges"]) != 1:
        raise ValueError("unexpected frozen cohort or judge count")
    ja = a["judges"][0]
    judges = [ja["judge"], b["judge"]["actor"] + " " + b["judge"]["session"]]
    if len(set(judges)) != 2:
        raise ValueError("judges must be distinct")
    am = index(ja["votes"], "pair_id", ids)
    bm = index(b["votes"], "sheet_id", ids)
    rm = index(rows, "pair_id", ids)
    pairs = []
    for pair in cohort["pairs"]:
        pid = pair["pair_id"]
        k, av, bv, row = key[pid], am[pid], bm[pid], rm[pid]
        if row["baseline_id"] != pair["baseline_id"] or av["presented_order"] != ["A", "B"] or set(k) != {"A", "B"} or set(k.values()) != {"candidate", "baseline"}:
            raise ValueError(f"invalid frozen pairing: {pid}")
        out = {"pair_id": pid, "baseline_id": pair["baseline_id"], "presentation": [k["A"], k["B"]]}
        for field, row_field in (("hair_hairline", "hair_votes"), ("neck_seam", "seam_votes")):
            raw = [av[field], bv[field]["prefers"]]
            reasons = [av.get("reason"), bv[field].get("reason")]
            decoded = []
            for judge, pref, reason in zip(judges, raw, reasons):
                if pref not in ("A", "B", "tie", "abstain") or (pref == "abstain" and not reason):
                    raise ValueError(f"invalid preference: {pid}, {judge}")
                decoded.append({"judge": judge, "vote": k.get(pref, pref), "presented_first": k["A"], "raw_preference": pref, "reason": reason})
            out[field] = decoded
            row[row_field] = {"status": "measured", "value": decoded, "method": "Decode the two independently fixed blinded A/B records with the frozen sheet key (IDS-12G)."}
        pairs.append(out)
    votes = {
      "schema": "ids12g-decoded-votes-v1",
      "rubric_revision": "IDS-12A@096f0796",
      "source_records": {
        "judge_a": {
          "path": "judge-a-blinded.json",
          "blob": "23f2af458ff051d68217200b033d0450a5a515df",
          "commit": "eef3fa84e0b93edc4021ba1f4ccb1ca3e24e9296"
        },
        "judge_b": {
          "path": "judge-b-blinded.json",
          "blob": "65eca1783b7ff0bb644d79d1aa99f017b9f6e3ed",
          "commit": "3582f8502bc311b7127d603aed81b34215accbc9"
        },
        "sheet_key": {
          "path": "sheet-key.json",
          "blob": "e3f6760ebbf6b17086671c013040f4937e970d8e"
        },
        "rows": {
          "path": "rows.json",
          "blob": "d2bee7702ea94591b798ac732cedfd83c842bc92"
        },
        "cohort": {
          "path": "../../cohort.json",
          "blob": "280bebf1cbdecc40eb463ac7173e1d35f5ec20a1"
        }
      }
    }
    votes["pairs"] = pairs
    return votes, rows

if __name__ == "__main__":
    votes, rows = assemble()
    for name, result in (("votes.json", votes), ("rows-with-votes.json", rows)):
        if json.loads((HERE / name).read_text(encoding="utf-8")) != result:
            raise SystemExit(f"committed {name} differs from deterministic assembly")
        print(f"{name}: matches")
