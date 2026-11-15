# USAGE-0 — private usage export (schema v1)

Source: https://github.com/LPStruiksma/atlas-gallery/issues/173

Opt in under Settings → Advanced options → Record usage for tuning (off by default). App-private preferences, backup disabled, rolling current and preceding 89 local days; Clear removes stored aggregates.

Export usage opens a user-initiated ACTION_SEND chooser with MIME application/json and EXTRA_TEXT containing JSON. The suggested filename is atlas-usage-YYYYMMDD.json, but recipients may not preserve it because the export uses EXTRA_TEXT rather than a FileProvider URI. No automatic network transport or INTERNET permission is added.

Example:

```json
{"schema_version":1,"days":[{"day":"2026-11-15","actions":{"A22":2},"screens_seconds":{"D17":12},"performance":{"A3":{"p50_ms":30,"p90_ms":45,"jank_percent":1.5}},"scanner":{"minutes":2,"photos_per_minute":18}}]}
```

Only fixed action IDs A1–A22 (#119) and screen IDs D1–D19 (#120) are allowed. All other values are numeric, except the schema version and a date with day precision. No photos, names, file paths, URIs, search terms, locations, ordered events or fine timestamps are recorded. Timing percentiles use nearest-rank statistics over at most 256 samples per ID and day.

**Known incomplete coverage:** the schema and opt-in storage controls are implemented, but production instrumentation is not complete. Rows without a verified hook are absent; absence is not evidence of zero use. PERF-0 (#148) remains process-local and has only partial markers. A Gradle/JVM run, emulator Settings render and share-chooser evidence are still required for independent acceptance.
