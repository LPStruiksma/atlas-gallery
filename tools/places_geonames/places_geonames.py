#!/usr/bin/env python3
"""PLACES-0 (#579): offline nearest-place index and benchmark over public GeoNames data.

Subcommands:
  prepare  build a compact gzip TSV of populated places (feature class P) with EN/NL names
  bench    measure accuracy on synthetic queries, lookup latency and peak memory

GeoNames data is CC BY 4.0 (https://www.geonames.org/export/): any shipped use needs attribution.
No network is used unless `prepare --download` is given; nothing here reads a user's photos,
coordinates or location history. Numbers are only reported when the run actually happened.
"""
import argparse, bisect, gzip, hashlib, io, json, math, os, random, resource, sys, time, tracemalloc, zipfile
from datetime import datetime, timezone
from pathlib import Path

BASE = "https://download.geonames.org/export/dump/"
GRID_DEG = 0.1
MAX_KM_DEFAULT = 15.0


def haversine_km(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    a = (math.sin((p2 - p1) / 2) ** 2
         + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2)
    return 6371.0088 * 2 * math.asin(math.sqrt(a))


def parse_places(lines):
    """Yield (geonameid, name, lat, lon, population) for populated places from a GeoNames dump."""
    for line in lines:
        f = line.rstrip("\n").split("\t")
        if len(f) < 15 or f[6] != "P":
            continue
        yield int(f[0]), f[1], float(f[4]), float(f[5]), int(f[14] or 0)


def parse_alternates(lines, wanted_ids, langs=("en", "nl")):
    """Return {geonameid: {lang: name}}; a preferred name wins, else the first one seen."""
    out = {}
    for line in lines:
        f = line.rstrip("\n").split("\t")
        if len(f) < 8 or f[2] not in langs:
            continue
        gid = int(f[1])
        if gid not in wanted_ids or f[7] == "1":  # skip historic names
            continue
        slot = out.setdefault(gid, {})
        if f[2] not in slot or f[4] == "1":
            slot[f[2]] = f[3]
    return out


def _sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _open_member(zip_path, member):
    with zipfile.ZipFile(zip_path) as z:
        return io.TextIOWrapper(io.BytesIO(z.read(member)), encoding="utf-8")


def cmd_prepare(a):
    src = Path(a.source)
    src.mkdir(parents=True, exist_ok=True)
    cc = a.country
    files = {"places": (f"{cc}.zip", BASE + f"{cc}.zip", f"{cc}.txt"),
             "alt": (f"alt-{cc}.zip", BASE + f"alternatenames/{cc}.zip", f"{cc}.txt")}
    if a.download:
        import urllib.request
        for name, (fn, url, _) in files.items():
            if not (src / fn).exists():
                urllib.request.urlretrieve(url, src / fn)  # exact error propagates if blocked
    manifest = {"country": cc, "licence": "CC BY 4.0, attribution to GeoNames required",
                "built_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%MZ"), "inputs": {}}
    for name, (fn, url, _) in files.items():
        p = src / fn
        manifest["inputs"][name] = {"url": url, "file": fn, "bytes": p.stat().st_size, "sha256": _sha256(p)}
    places = list(parse_places(_open_member(src / files["places"][0], files["places"][2])))
    ids = {p[0] for p in places}
    alts = parse_alternates(_open_member(src / files["alt"][0], files["alt"][2]), ids)
    out = Path(a.output)
    with gzip.open(out, "wt", encoding="utf-8", compresslevel=9) as fh:
        for gid, name, lat, lon, pop in sorted(places):
            n = alts.get(gid, {})
            fh.write("\t".join([str(gid), name, f"{lat:.4f}", f"{lon:.4f}", str(pop),
                                n.get("en", ""), n.get("nl", "")]) + "\n")
    manifest.update(rows=len(places), rows_with_en=sum(1 for p in places if "en" in alts.get(p[0], {})),
                    rows_with_nl=sum(1 for p in places if "nl" in alts.get(p[0], {})),
                    filtered_bytes=out.stat().st_size,
                    filtered_bytes_uncompressed=sum(len(l.encode()) for l in gzip.open(out, "rt", encoding="utf-8")))
    Path(str(out) + ".manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest, indent=2))


class PlaceIndex:
    """Grid-bucket nearest-place lookup. Nearest point only: no polygon or border knowledge."""

    def __init__(self, rows):
        self.cells = {}
        for gid, name, lat, lon, pop, en, nl in rows:
            self.cells.setdefault((math.floor(lat / GRID_DEG), math.floor(lon / GRID_DEG)), []).append(
                (lat, lon, gid, name, pop, en, nl))

    @classmethod
    def load(cls, path):
        rows = []
        with gzip.open(path, "rt", encoding="utf-8") as fh:
            for line in fh:
                f = line.rstrip("\n").split("\t")
                rows.append((int(f[0]), f[1], float(f[2]), float(f[3]), int(f[4]), f[5], f[6]))
        return cls(rows)

    def nearest(self, lat, lon, max_km=MAX_KM_DEFAULT):
        """(distance_km, row) of the closest place within max_km, else None. Ties: lower geonameid."""
        ring_limit = int(max_km / (111.0 * GRID_DEG * max(0.2, math.cos(math.radians(lat))))) + 2
        cy, cx = math.floor(lat / GRID_DEG), math.floor(lon / GRID_DEG)
        best = None
        for ring in range(ring_limit + 1):
            for y in range(cy - ring, cy + ring + 1):
                for x in range(cx - ring, cx + ring + 1):
                    if max(abs(y - cy), abs(x - cx)) != ring:
                        continue
                    for r in self.cells.get((y, x), ()):
                        d = haversine_km(lat, lon, r[0], r[1])
                        if d <= max_km and (best is None or (d, r[2]) < (best[0], best[1][2])):
                            best = (d, r)
            # Once a hit is closer than the inner edge of the next ring, no later ring can beat it.
            if best is not None and best[0] <= ring * GRID_DEG * 111.0 * 0.5:
                break
        return best


def pct(sorted_vals, q):
    return sorted_vals[min(len(sorted_vals) - 1, int(q * len(sorted_vals)))]


def cmd_bench(a):
    queries = json.loads(Path(a.queries).read_text())
    tracemalloc.start()
    t0 = time.perf_counter()
    index = PlaceIndex.load(a.index)
    load_s = time.perf_counter() - t0
    _, peak = tracemalloc.get_traced_memory()
    tracemalloc.stop()
    results = []
    for q in queries["cases"]:
        hit = index.nearest(q["lat"], q["lon"], q.get("max_km", MAX_KM_DEFAULT))
        got = None if hit is None else {"name": hit[1][3], "en": hit[1][5], "nl": hit[1][6], "km": round(hit[0], 2)}
        names = set() if got is None else {got["name"], got["en"], got["nl"]}
        ok = (not q["expect_any"] and got is None) or bool(set(q["expect_any"]) & names)
        results.append({"id": q["id"], "kind": q["kind"], "ok": ok, "expected": q["expect_any"], "got": got})
    rng = random.Random(579)
    lat0, lat1, lon0, lon1 = 50.75, 53.55, 3.35, 7.2  # Netherlands bounding box, synthetic points only
    lat_lon = [(rng.uniform(lat0, lat1), rng.uniform(lon0, lon1)) for _ in range(a.samples)]
    times = []
    for lat, lon in lat_lon:
        s = time.perf_counter()
        index.nearest(lat, lon)
        times.append((time.perf_counter() - s) * 1000)
    times.sort()
    per_kind = {}
    for r in results:
        per_kind.setdefault(r["kind"], []).append(r["ok"])
    report = {"index": str(a.index), "index_bytes": os.path.getsize(a.index),
              "places": sum(len(v) for v in index.cells.values()),
              "load_seconds": round(load_s, 3), "python_heap_peak_bytes_during_load": peak,
              "process_max_rss_kb": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
              "host": "python %s (desktop-class; NOT a phone measurement)" % sys.version.split()[0],
              "lookups": len(times), "latency_ms": {k: round(pct(times, q), 4) for k, q in
                                                    (("p50", .5), ("p95", .95), ("p99", .99))},
              "accuracy_by_kind": {k: f"{sum(v)}/{len(v)}" for k, v in per_kind.items()},
              "cases": results}
    print(json.dumps(report, indent=2, ensure_ascii=False))
    return 0 if all(r["ok"] for r in results) else 1


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("prepare")
    p.add_argument("--source", required=True, help="directory holding (or receiving) the GeoNames zips")
    p.add_argument("--output", required=True)
    p.add_argument("--country", default="NL")
    p.add_argument("--download", action="store_true", help="fetch from download.geonames.org")
    p.set_defaults(fn=cmd_prepare)
    b = sub.add_parser("bench")
    b.add_argument("--index", required=True)
    b.add_argument("--queries", default=str(Path(__file__).with_name("places_queries.json")))
    b.add_argument("--samples", type=int, default=20000)
    b.set_defaults(fn=cmd_bench)
    a = ap.parse_args(argv)
    return a.fn(a) or 0


if __name__ == "__main__":
    sys.exit(main())
