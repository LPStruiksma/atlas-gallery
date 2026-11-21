# USAGE-0 export format (#173)

"Record usage for tuning" (Settings → Advanced) is off by default. While it is on, ATLAS keeps **per-day aggregates on this phone only**: no network code, no upload, no third-party SDK. "Export usage" writes one file, `atlas-usage-<yyyymmdd>.json`, to the app cache and opens Android's share sheet (`ACTION_SEND`, user-initiated). It reuses the `<applicationId>.diagnostics` FileProvider with an added `usage/` cache path. `scripts/usage_rank.py` (USAGE-1, [USAGE_RANKING.md](USAGE_RANKING.md)) reads the file offline. **Never commit a real export to GitHub.**

## What is recorded, per local day
- A count per action ID **A1–A22** ([#119](https://github.com/LPStruiksma/atlas-gallery/issues/119)). Scrolls (A2, A10) count once per scroll, not once per frame.
- Dwell seconds per screen ID **D1–D19** ([#120](https://github.com/LPStruiksma/atlas-gallery/issues/120)); stops while the app is in the background.
- For actions with a PERF-0 marker: latency p50 and p90 over the last 128 samples of the day, and for A2/A10 the share of frames over budget.
- Scanner minutes and photos per minute.

Nothing else: no timestamps finer than a day, no sequence of actions, no photo, face, name, file name, URI, album, search text or location. Rolling 90 days; older days are deleted. "Clear usage" deletes every day at once. Turning the switch off keeps existing days until Clear.

## Schema (version 1)
```json
{"schema_version":1,"days":[{"day":"2026-11-21",
  "actions":{"A1":3,"A15":12},
  "screens_seconds":{"D1":420,"D17":15},
  "performance":{"A1":{"p50_ms":412,"p90_ms":690},"A2":{"p50_ms":9,"p90_ms":18,"jank_percent":1.2}},
  "scanner":{"minutes":2.0,"photos_per_minute":300.0}}]}
```
- Every key is a fixed field name or an enum ID; the only string values are the schema's `YYYY-MM-DD` day. Everything else is a number. `UsageAggregatesTest.exportHasNoFreeText` fails if any other string can appear.
- **Not measured is absence, not zero.** An ID with no recorded count or timing is omitted. `performance` has no entry for an action without a timing hook.

## Coverage today
Counts and timings come from the PERF-0 markers: A1, A2, A3, A4, A5 (scanner), A6, A7, A9, A10, A11. Also counted: A15 (bottom-tab switch) and A22 (opening Settings). Dwell covers the main tabs (D1, D5, D6, D7), the viewer (D2), a person page (D8), Stories (D15), Photo DNA (D18) and Settings (D17). The other IDs are not instrumented yet, so their absence is not evidence of no use. A new feature adds an enum ID in `UsageAggregates.kt` and a hook; it never reuses free text.

## Settings copy
Toggle "Record usage for tuning" ("Daily counts, timings and scan speed. No photos, names or places."), "Export usage" (disabled with "Nothing recorded yet"), "Clear usage" (snackbar "Usage cleared"). Failures: "Couldn't prepare usage file. Try again." and "Couldn't open sharing. Try again." Data is kept on failure; cancelling the sheet changes nothing.
