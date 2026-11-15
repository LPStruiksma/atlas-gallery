# USAGE-0 — private usage export (schema v1)

Source: https://github.com/LPStruiksma/atlas-gallery/issues/173

Opt in under Settings → Advanced options → Record usage for tuning (off by default). App-private preferences, backup disabled, rolling current and preceding 89 local days; Clear removes stored aggregates.

Export usage creates a short-lived app-private cache file `atlas-usage-YYYYMMDD.json`, shared only on a user-initiated ACTION_SEND chooser with MIME `application/json`, EXTRA_STREAM and a read grant through a non-exported FileProvider. A new export replaces cached exports. Clear removes both private stored aggregates and cached export files. Prior copies held by recipients cannot be revoked. No automatic transmission, new INTERNET permission or analytics SDK is added.

Example:

```json
{"schema_version":1,"days":[{"day":"2026-11-15","actions":{"A22":2},"screens_seconds":{"D17":12},"performance":{"A3":{"p50_ms":30,"p90_ms":45,"jank_percent":1.5}},"scanner":{"minutes":2,"photos_per_minute":18}}]}
```

Only fixed action IDs A1–A22 (#119) and screen IDs D1–D19 (#120) are allowed. All other values are numeric, except the schema version and a date with day precision. No photos, names, file paths, URIs, search terms, locations, ordered events or fine timestamps are recorded. Timing percentiles use nearest-rank statistics over at most 256 samples per ID and day.

**Unknown versus measured zero:** `scanner: {}` means no verified scan sample for that day; `scanner: {"minutes":..., "photos_per_minute":0}` means time was measured but no photo was completed. A `performance` action with no frame-count sample omits `jank_percent` rather than inventing 0%. Missing action or screen keys likewise mean not measured, not zero. The JSON parser accepts these omitted optional measurements without converting them to zeros.

**Capture coverage:** The production hooks capture A1 app launch (when already opted in), A2 grid scroll starts, A3 photo opening, A7 viewer returns, A9 People tab, A10 person opening, A11 Search tab/submission, A15 tab switches, A16 selection entry, A18 People review, A19 Stories, A21 Photo DNA and A22 sort/Settings. Foreground whole-second dwell is attributed to D1, D2, D5–D9, D13, D15, D17 and D18. Other IDs, scanner throughput and PERF latency/jank require audited source hooks. Missing rows mean **not measured**, not zero.

**Known incomplete coverage:** the schema, opt-in store, real named export and listed navigation hooks are implemented, but production action/performance coverage is incomplete. Rows without a verified hook are absent; absence is not evidence of zero use. PERF-0 (#148) remains process-local and has only partial markers. A Gradle/JVM run, emulator Settings render and share-chooser evidence are still required for independent acceptance.
