# PLACES-0: GPS visibility and offline place-name feasibility

Status: **evidence pending**. This file is a bounded research scaffold, not a claim that an emulator was run or that reverse geocoding is feasible. Unit: [#579](https://github.com/LPStruiksma/atlas-gallery/issues/579). Vision C [#100](https://github.com/LPStruiksma/atlas-gallery/issues/100), Vision F [#103](https://github.com/LPStruiksma/atlas-gallery/issues/103).

## Scope and evidence rules

Prove or disprove the GPS path through `ViewerExtendedMetadataReader` with **generated** geotagged media inserted into MediaStore by **another package**. The manifest currently does not request `ACCESS_MEDIA_LOCATION`; do not change it in this unit. Record API level, emulator build, app/package identity, insertion method, original fixture coordinate, resulting coordinates (or redaction) observed by ATLAS, and test commands/logs. If emulator execution is unavailable, explicitly state **not verified** and the precise obstacle; source inspection alone is not proof.

Benchmark a **public, attributable** GeoNames-derived offline dataset, using documented dataset/version/download URL and license/attribution (GeoNames CC BY 4.0 requires attribution). No network geocoding; no real user coordinates, photographs, secrets, or personal location history. Generate a fixed synthetic coordinate set with Dutch town-scale targets, international borders, coasts and near-ties. Measure exact filtered bytes on disk, lookup latency distribution, memory peak and correctness, including English and Dutch labels where available. A script that can reproduce the measurements should live under `tools/`. Do not fabricate timings or dataset sizes if download or execution fails.

## 1. Android location-read result

**Result: PENDING.** Capture before/after evidence from a foreign-package MediaStore item through the actual ATLAS metadata reader, not only `ExifInterface` on a filesystem path.

| Evidence | Recorded value |
| --- | --- |
| Emulator/API and Android build | Pending |
| Synthetic JPEG generator and foreign package insertion | Pending |
| Known fixture latitude/longitude | Pending |
| ATLAS reader invocation and observed value | Pending |
| Android 10+ redaction conclusion | Pending |
| Required future manifest/runtime permission path, only if supported by evidence | Pending |

## 2. Fully offline reverse-geocoding benchmark

**Result: PENDING.** Dataset selection must support Netherlands towns (avoid a cities-only population cutoff unless coverage loss is measured); separate a nearest-point heuristic from border-aware administrative classification.

| Evidence | Recorded value |
| --- | --- |
| Exact dataset, download URL, timestamp, attribution | Pending |
| Filtering rules and row count | Pending |
| Original and packaged on-disk bytes | Pending |
| Nearest-place lookup structure, measured p50/p95/p99 ms | Pending |
| Peak RAM and measurement host | Pending |
| Synthetic coastal/border/NL-town error examples | Pending |
| EN/NL alternate-name coverage | Pending |

## 3. Index integration sketch

At scan time, derive an optional place result by stable media key from the available location metadata, cache its dataset version and confidence, and update incrementally on new/changed files and dataset revisions. A place name is a derived, possibly ambiguous annotation; preserve the source coordinate's visibility/permission state and avoid inventing a locality when GPS is absent or redacted. One underlying record should serve Search place facets, viewer details, and later event/trip candidate grouping; do not fork the gallery's existing media index. Specify schema, invalidation, migrations, memory budget, and permission-denied behavior after feasibility measurements.

## 4. Decision and follow-up units

**Decision: PENDING.** Record a go/no-go against explicit latency, footprint, locality correctness and permission-dependency constraints, with measured evidence links. Any `AndroidManifest.xml` or permission-flow change requires a **separate protected-path PR merged by Lukas**. Maps, map tiles, location network calls, and reading real user photographs are outside this unit.

## Reproduction checklist

- [ ] Reader test uses foreign-package insertion and synthetic EXIF GPS.
- [ ] Emulator command + observed output recorded, or capability gap documented without claiming PASS.
- [ ] GeoNames download/licensing provenance and reproducible Python scripts recorded.
- [ ] Dataset size, latency, memory, EN/NL labels and synthetic edge cases measured.
- [ ] `docs/product/ATLAS_VISION.md` Places row updated with only verified findings.
- [ ] Follow-up list, go/no-go and unknowns written.
