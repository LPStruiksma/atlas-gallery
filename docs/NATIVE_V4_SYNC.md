# Native ATLAS ↔ V4 local People synchronization

This is an **opt-in**, local-only, one-time Android Storage Access Framework (SAF) document link. No Internet permission, original-photo writes, cloud identity upload, or direct access to Termux private SQLite.

## V4: create a live People document once

After a fresh online backup of V4 and when no indexing job is running, install the updated V4 preview and run:

    python ~/atlas_v4_latest.py native-sync --output "$HOME/storage/downloads/atlas_v4_people_live.csv"

The command writes the first checksum-sealed document and remembers its path in V4 settings. Later successful scans, automatic identification, manual People changes, and undo/batch catch-up update the SAME file automatically. This file contains actual person names and absolute photo paths; keep it on your own device and don't share it.

To stop automatic exports without deleting previously imported People or the CSV:

    python ~/atlas_v4_latest.py native-sync --disable

The regular V4 manual CSV export remains unchanged for older installed native APKs.

## ATLAS Native: link once

In the People tab, tap **Link live V4** and select the generated atlas_v4_people_live.csv from Downloads. Android grants ATLAS persistent read-only access to this one document. On future foreground resume, native ATLAS reads the selected CSV, verifies its final row count and SHA-256 checksum, and re-imports only if the complete contents have changed. The imported People database is always transactionally replaced, leaving the previous good version if the file is invalid, absent, or being written. Links to photographs are exact-path matches, so moved photos can still be reported unmatched.

**Import once** continues to support older CSV files and intentionally disables any previous live document connection, so a former live export cannot unexpectedly overwrite your manual import.

## Why a checksum footer?

V4 deliberately writes the same file path to preserve the persistent Android document URI. An Android reader may sometimes open it during a write. V4 appends a final marker:

    #ATLAS_SYNC_END,<rows>,<sha256-of-all-prior-csv-bytes>

Native never accepts a live document lacking the correct complete marker, row count, and byte checksum. This is an integrity guard for accidental partial writes, not cryptographic authentication against a malicious local app.

## Gates

- Python offline test generates a synthetic People CSV, validates row-count/checksum, name updates and stable path.
- Pure Kotlin JUnit test rejects truncated, count-mismatched or corrupted exports and accepts intact ones.
- Android Gradle compilation/emulator checks; real S24+ SAF URI persistence and resume behavior remain unverified until tested.
- **Stable APK signing issue #2 remains a separate release blocker.** Do NOT uninstall the old native app to try this feature. An updated native APK must pass signing-compatibility and data-preservation checks first.

No photo originals, biometric embeddings, secrets or real names are part of the repository or CI tests.
