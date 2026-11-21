# PLACES-0: GPS visibility and offline place-name feasibility

Status: **Part 1 verified on an emulator; Part 2 runnable but unmeasured** (GeoNames download was blocked in the cloud seat). Unit: [#579](https://github.com/LPStruiksma/atlas-gallery/issues/579). Vision C [#100](https://github.com/LPStruiksma/atlas-gallery/issues/100), Vision F [#103](https://github.com/LPStruiksma/atlas-gallery/issues/103).

## Scope and evidence rules

Prove or disprove the GPS path through `ViewerExtendedMetadataReader` with **generated** geotagged media inserted into MediaStore by **another package**. The manifest currently does not request `ACCESS_MEDIA_LOCATION`; do not change it in this unit. Record API level, emulator build, app/package identity, insertion method, original fixture coordinate, resulting coordinates (or redaction) observed by ATLAS, and test commands/logs. If emulator execution is unavailable, explicitly state **not verified** and the precise obstacle; source inspection alone is not proof.

Benchmark a **public, attributable** GeoNames-derived offline dataset, using documented dataset/version/download URL and license/attribution (GeoNames CC BY 4.0 requires attribution). No network geocoding; no real user coordinates, photographs, secrets, or personal location history. Generate a fixed synthetic coordinate set with Dutch town-scale targets, international borders, coasts and near-ties. Measure exact filtered bytes on disk, lookup latency distribution, memory peak and correctness, including English and Dutch labels where available. A script that can reproduce the measurements should live under `tools/`. Do not fabricate timings or dataset sizes if download or execution fails.

## 1. Android location-read result

**Result: VERIFIED (one emulator, API 35).** `ViewerGpsRedactionTest` passed in the hosted emulator-ui shard 1 of [run 44897640430](https://github.com/LPStruiksma/atlas-gallery/actions/runs/44897640430) at head `3ac61a4f` (testcase time 3.99 s; the same run's only failure, `OriginalProportionsAlbumJourneyTest`, is unrelated and also fails on other branches). Earlier red runs on this PR were compile and test-harness fixes, and one of them printed the observation below.

| Evidence | Recorded value |
| --- | --- |
| Emulator/API and Android build | API 35, `Android/sdk_phone64_x86_64/emu64x:15/AE3A.240806.019/12368160:userdebug/test-keys` |
| Synthetic JPEG generator and foreign-package insertion | The test generates a 64x48 JPEG, writes EXIF GPS 52.0907 N, 5.1214 E (fictional) and inserts and writes it as the **shell uid** with `content insert` / `content write`. `OWNER_PACKAGE_NAME` is not readable by ATLAS (null), so foreign ownership rests on the shell doing the insert and write |
| Known fixture latitude/longitude | 52.0907, 5.1214 (control read of the same bytes through `readViewerExif` returns them) |
| ATLAS reader invocation and observed value | `PlatformViewerMetadataReader.read` on the MediaStore content URI, ATLAS holding `READ_MEDIA_IMAGES` but not `ACCESS_MEDIA_LOCATION`: **latitude 0.0, longitude 0.0** (run 44895524788, head `dbdcf9e7`, assertion message) |
| Android 10+ redaction conclusion | The platform redacts the coordinate for a foreign-owned file. Other EXIF still reads, so it is selective, not a failed read. `setRequireOriginal` without `ACCESS_MEDIA_LOCATION` is refused |
| Required future permission path | `ACCESS_MEDIA_LOCATION` (manifest plus runtime grant) is required to see real coordinates of other apps' photos, such as the camera's. This unit does not change the manifest. That is a separate protected-path unit for Lukas |

**Finding for a follow-up fix:** the redacted read comes back as `0.0 / 0.0`, not null, and `readViewerExif` treats `getLatLong` returning true as a real position. Today's Details sheet can therefore show a fake "0.0, 0.0" for photos ATLAS does not own. The follow-up must treat an exact (0.0, 0.0) as "no GPS" and never feed it to Places.

**Not covered:** one emulator and API level only, a shell-owned fixture (camera-owned files should behave the same, but that is not shown), and no physical-phone run. Videos and HEIC were not tested.

## 2. Fully offline reverse-geocoding benchmark

**Result: NOT MEASURED.** The cloud seat could not download GeoNames (the download tunnel returns HTTP 403), so no dataset size, latency, memory or coverage numbers exist. Nothing below is a measurement. The tooling is in place and tested offline on a synthetic miniature: `tools/places_geonames/`.

Run on any machine with internet access (public data only, CC BY 4.0, attribution to GeoNames required for any shipped use):

```
python3 tools/places_geonames/places_geonames.py prepare --source /tmp/geonames --output /tmp/nl-places.tsv.gz --download
python3 tools/places_geonames/places_geonames.py bench --index /tmp/nl-places.tsv.gz
python3 tools/places_geonames/test_places_geonames.py
```

`prepare` keeps populated places (feature class P) with no population cutoff, records source URLs, byte sizes and SHA-256 of the inputs, row count, EN and NL alternate-name coverage and the filtered on-disk bytes in a manifest. `bench` reports accuracy per case kind (NL towns, coast, border, EN/NL labels), lookup latency p50/p95/p99 and peak memory. The query set `places_queries.json` is synthetic points beside public landmarks; the border and offshore cases must return no Dutch place. The lookup is nearest point within a 15 km cutoff and has no administrative-border knowledge, so a point just across the border can still snap to a Dutch town. Border-aware classification would need boundary polygons and is out of scope here. Desktop Python numbers say nothing about phone latency, so a Kotlin check on the emulator or a phone is a follow-up.

## 3. Index integration sketch

At scan time, derive an optional place result by stable media key from the available location metadata, cache its dataset version and confidence, and update incrementally on new/changed files and dataset revisions. A place name is a derived, possibly ambiguous annotation; preserve the source coordinate's visibility/permission state and avoid inventing a locality when GPS is absent or redacted. One underlying record should serve Search place facets, viewer details, and later event/trip candidate grouping; do not fork the gallery's existing media index. Specify schema, invalidation, migrations, memory budget, and permission-denied behavior after feasibility measurements.

## 4. Decision and follow-up units

**Decision: conditional.** On Android 10+, GPS of photos ATLAS does not own is not available without `ACCESS_MEDIA_LOCATION`, so Places for the camera roll is a **no-go until that permission is approved**. Offline place names are **undecided** until the benchmark above has run. Photos ATLAS itself saved (for example its own edits) carry their coordinates today. Any `AndroidManifest.xml` or permission-flow change requires a **separate protected-path PR merged by Lukas**. Maps, map tiles, location network calls and reading real user photographs are outside this unit.

Follow-up units, in order:
1. Treat exact (0.0, 0.0) GPS as absent in `readViewerExif` and Details, with a failing test first. Small, independent of the permission question.
2. Run the GeoNames `prepare` and `bench` commands somewhere with access and paste the manifest and report here.
3. Owner decision: ask for `ACCESS_MEDIA_LOCATION` (manifest plus runtime prompt, protected path) and what to say when it is declined.
4. If 2 and 3 are favourable, build the per-media-key place index from section 3, with a Kotlin latency and memory check.

## Reproduction checklist

- [x] Reader test uses foreign-package (shell uid) insertion and synthetic EXIF GPS.
- [x] Emulator result recorded (API 35, hosted run link above).
- [x] GeoNames provenance and reproducible Python scripts in `tools/places_geonames/` (offline unit tests pass).
- [ ] Dataset size, latency, memory, EN/NL labels and synthetic edge cases measured: **not done**, download blocked.
- [x] `docs/product/ATLAS_VISION.md` Places row updated with only verified findings.
- [x] Follow-up list, conditional go/no-go and unknowns written.
