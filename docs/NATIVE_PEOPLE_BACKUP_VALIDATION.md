# ATLAS Native — offline People-backup validation (issue #32)

This is a **read-only integrity guard for an already captured, consistent standalone backup** of the native app's private `atlas_native_people.db` SQLite file. It neither extracts phone data nor restores it. It is **not an authorization to uninstall** an installed ATLAS app.

## Optional read-only feasibility probe (no backup or APK change)

A small Android/Termux helper in
`scripts/probe_existing_native_install.py` checks whether an already
connected Shizuku `rish` bridge can see the installed ATLAS package, execute
`run-as` as the original debuggable application, and test the **existence/
readability** of its known private People database and settings directory.
It does not open or copy their contents, enumerate people, print private paths,
request permissions, uninstall, stop the app, generate signing keys, or make
changes to Android state. The only response is a fixed-schema diagnostic JSON
with booleans. Run voluntarily in Termux after obtaining the script locally:

```sh
python3 ~/probe_existing_native_install.py
```

A successful result makes a future local-only backup strategy *plausible*, not
proven; an unavailable `run-as` may indicate permissions or app signing/build
mode, not missing photos or labels. `consistent_backup_created` and
`safe_to_uninstall` deliberately remain false in every outcome. This helper
does **not** retrieve the installed APK's signing certificate either. Synthetic
offline regression: `python3 scripts/test_probe_existing_native_install.py`.

## Optional offline WAL consolidation — captured copy ONLY

`scripts/consolidate_native_people_capture.py` can turn an **already captured,
coherent, stopped-source copy** of `atlas_native_people.db` (including any
same-moment `-wal` and `-shm` files) into a standalone validated SQLite
file. It is **not** a phone extractor and does not run Shizuku, `run-as`,
`adb`, `am force-stop`, or any install/uninstall. It neither reads original
photographs nor opens, edits, or exports individual identity labels.

Example after a separately verified, owner-controlled, local-only capture
has been obtained with all writers stopped (never use a live DB):

```sh
mkdir -p -m 700 "$HOME/.atlas_native_backups"
python3 scripts/consolidate_native_people_capture.py \
  "$HOME/private_captured/atlas_native_people.db" \
  "$HOME/.atlas_native_backups/people-standalone.db" \
  --min-rows 1 --confirm-offline-capture
```

The example's `--min-rows 1` is **not** sufficient evidence of a complete
backup: substitute an independently observed expected face-assignment count.
The helper refuses group/world-readable destination directories, pre-existing
outputs, symlinks, unpaired SHM, rollback journals, invalid schemas, and
missing affirmative offline-capture acknowledgment. It copies SQLite files
into a private temporary staging directory before opening them, so SQLite's
WAL-index handling cannot alter the input capture. The verified snapshot is
created with a no-overwrite filesystem operation and permissions `0600`.
Only aggregate counts and SHA-256 are emitted; actual private records stay
inside the protected SQLite file.

**Crucial limitation:** No automated tool can infer that copied `.db`, WAL
and SHM were acquired simultaneously or after the running app stopped.
An apparently valid snapshot may be incomplete/stale. This helper alone
does not establish a real S24+ backup, restore coverage of app settings/SAF
grants, a valid signing identity, or approval for phone migration. Continue
to require an independently restore-tested second backup plus owner consent.

CI regression: `python3 scripts/test_consolidate_native_people_capture.py`
uses invented People rows, actual uncheckpointed SQLite WAL content and
strict no-source-write/no-overwrite tests, without Android or private media.

---

## Emulator-only proof for a mismatched APK signer

ATLAS has a **separate, opt-in GitHub Actions workflow**:
`.github/workflows/signer-mismatch-recovery.yml`. It runs when the
test-only recovery components change, not on every gallery feature PR,
to avoid needless emulator minutes. It uses only an isolated Android
emulator and a fictional People identity.

Its actual Android round-trip is deliberately more demanding than CSV
format unit tests:

1. Install the old debug APK signed by ephemeral fixture key A. Seed one
   invented person into app-private SQLite using instrumentation.
2. Install the newer APK signed by the same key A and prove its People
   row survives. This is the standard in-place upgrade gate.
3. On the **emulator only**, export a standalone consistent snapshot via
   Android SQLite `VACUUM INTO`, extract via debug `run-as`, validate
   schema/integrity/count, and convert it with the existing lossless
   SQLite-to-CSV exporter.
4. Generate a distinct, ephemeral **test-only signing key B** in GitHub's
   disposable runner (never a release identity, never committed or uploaded),
   rebuild the candidate, and verify its public fingerprint differs.
5. Confirm Android really rejects `adb install -r` with a signature mismatch.
   Then, **only after verifying `ro.kernel.qemu=1`, exactly one emulator and
   GitHub Actions environment**, remove this *fictional emulator fixture*,
   install test APK B, import the validated synthetic CSV, and recheck the
   full identity/path membership. The emulator image is disposable.
6. Run the ordinary Compose viewer/filmstrip tests as well, retaining only
   three synthetic PNG screenshots as workflow artifacts. Database, CSV,
   ephemeral signing key and passwords stay in runner temporary directories.

The standard Android build/emulator job does **not** run this deliberately
destructive alternative: the test is activated only by
`ATLAS_SYNTHETIC_MISMATCH=1` in the dedicated workflow.

**Passing this gate is not authorization to migrate a phone.** It cannot
recover revoked SAF document grants, other application-private settings,
uncommitted SQLite WAL data copied unsafely, all account permissions, or any
missing source files. It does not provide a backup of the installed Galaxy S24+,
compare that phone's actual signer with new APKs, or allow uninstalling it.
The real phone remains strictly owner-controlled and untouched. See #2 / #32.

## Run only with a securely obtained local snapshot

```sh
python3 scripts/validate_native_people_backup.py /path/to/offline/atlas_native_people.db --min-rows 1
```

Only a structural summary, row count, distinct person and image-path counts, backup size, and SHA-256 checksum are printed. Names, face IDs, image paths and embeddings are never printed. The validator operates with SQLite `mode=ro&immutable=1` and does not upload or create any files. `--min-rows` should be set to an independently known minimum count for the phone's confirmed imported People; it defaults to one, not a guarantee that *all* records were captured.

A live SQLite DB is **not** safely backed up merely by copying its main `.db` file. WAL/journal transactions may not be included. A consistent snapshot should be produced via SQLite's online backup API, or from a stopped/checkpointed database, with explicit verification. This validator refuses local `-wal`, `-shm` or `-journal` sidecars to avoid suggesting that a casual multi-file copy is a valid standalone backup. An absent sidecar **does not prove** the input was collected consistently. A successful `PRAGMA integrity_check` likewise does not establish that the snapshot is current or complete.

Before any owner-approved migration from an APK with an incompatible signer, issue #32 additionally requires a demonstrated emulator restoration round-trip, backup coverage of other private settings, a second independent copy retained outside the app sandbox, a known recovery plan and explicit owner confirmation. Never upload the backup or its sensitive People table to GitHub or ChatGPT; avoid keeping an unencrypted copy in Android Downloads.

Synthetic-only validation regression: `python3 scripts/test_native_people_backup.py`.

## Local-only recovery format for a *future, explicitly authorized* migration

A validated **offline, consistent** private People SQLite snapshot can be exported into the existing ATLAS Native **Import once** CSV format, keeping multiple people per photograph and Unicode names without inventing recognition confidence:

```sh
python3 scripts/export_native_people_recovery.py \
    /path/to/offline/atlas_native_people.db \
    /path/to/protected/atlas_people_recovery.csv --min-rows 1
```

The destination must not already exist; the original backup is never modified. The recovery CSV contains **actual names and photo paths**, so keep it securely on the phone/computer, not in GitHub, chat or unencrypted shared storage. The confidence field is deliberately blank, representing *unknown*, not a fabricated perfect classifier score. This recovery CSV is for the **native app's** Import once UI, not V4's training labels or private SQLite schema. The export preserves rows; a subsequent full import on an isolated test installation must also be tested before considering any phone migration.

Crucially, this script **does not extract** any inaccessible app-private database from the phone, does not give permission to uninstall ATLAS, and does not establish that a backup includes all settings or SAF grants. If signing identities match, prefer a routine in-place upgrade instead. See #32.

Run the synthetic local converter test: `python3 scripts/test_native_people_recovery.py`.


## Lane E 16 October 2026: independent offline restore parity

Run **python3 scripts/test_verify_native_restore_evidence.py** for invented-file coverage of a second database and private settings tree. For an owner-obtained protected snapshot and a *separate* protected restored tree with the same relative locations, **scripts/verify_native_restore_evidence.py** checks two valid standalone People SQLite files and compares every logical assignment (face ID, person ID, name and image path) without revealing labels. It then compares exact contents in the supported persistent settings namespaces (shared_prefs/*.xml and files/datastore/*.preferences_pb or *.pb) and rejects missing, linked or conflicting files. It requires at least one actual settings file; an installation with zero persisted settings needs separate observed-zero and UI-state evidence, not a fabricated file. The test explicitly detects forged equal row counts with changed names.

These tests are deliberately **offline only**. Passing them proves parity between two owner-provided trees, NOT phone capture provenance, Android in-app restore, independent backup media, SAF grants or every possible private setting. The current Android manifest has allowBackup=false; there is still no owner-verified Galaxy S24+ full restore. Consult [owner release card](RELEASE_GO_NO_GO.md); until its other gates pass, **NO-GO** for replacing or removing the installed app.


### Snapshot namespace containment and evidence-independence hardening (16 October, Lane E)

The pure offline People/settings restore comparator now forbids *all symlink ancestors inside its two snapshot trees*, including \`files/\` preceding \`files/datastore/\`, rather than merely refusing final-file or immediate-directory symlinks. It also refuses a purported restore tree nested inside the backup tree or vice versa. A resolved path outside a snapshot would invalidate its provenance even if bytes appeared equal. Existing checks for valid independently captured SQLite data, logical identity equality and separate settings inodes remain required. These changes do not make phone acquisition/restore automatic and cannot attest to SAF grants. Regression: \`python3 scripts/test_verify_native_restore_evidence.py\`.


## Strict app-private inventory coverage — independent offline gate (16 October, Lane E)

The existing People/settings parity checker validates **known** locations only. It cannot detect arbitrary additional app-private files elsewhere in a capture, so its positive result alone cannot be described as *full* recovery. New companion \`scripts/audit_native_restore_inventory.py\` deliberately fails closed unless BOTH separately captured directory trees contain only the explicitly supported People DB and SharedPreferences/DataStore files, with matching file manifests and matching persisted setting bytes.

~~~sh
python3 scripts/audit_native_restore_inventory.py \
  /secure/private-backup /secure/isolated-restored
python3 scripts/verify_native_restore_evidence.py \
  /secure/private-backup /secure/isolated-restored --min-rows 123
~~~

Use the *independently observed* number of confirmed native face assignments instead of 123. The inventory audit refuses extra private files/folders (even identically present in both trees), WAL/SHM/journal leftovers, hidden setting files, symlinks and multiply linked files, differing settings, and non-private permissions on supplied snapshot files/folders (owner-only mode 0700 for folders, 0600 for files). It returns **only aggregate counts and fixed booleans** without disclosing filenames, personal names or source photo paths. It is fully read-only; don't upload private copies or raw logs. If the snapshot contains caches, unknown data files or an actual zero-settings state, this strict check must report NO-GO until the app-specific retention/migration policy is independently established. Do **not** delete unfamiliar files to make the audit pass.

This is *not* a substitute for real Android restoration: the tool does not parse People records (the second command does), authenticate capture from the original phone, detect files omitted **from both copies**, recover SAF grants or certify \`safe_to_uninstall\`. A different SQLite byte layout following a valid backup API is acceptable to the inventory audit because the separate parity tool checks the People **logical rows**. An on-phone backup still requires owner-controlled permission and full isolated restore evidence. Regression uses completely fictional trees: \`python3 scripts/test_audit_native_restore_inventory.py\`, invoked by the existing fast signer/release CI; no phone or credentials touched.


## Read-only capability probe: strict response parsing (Lane E, 16 October)

\`scripts/probe_existing_native_install.py\` is still optional and must be run **only by the owner**, if desired, from an already authorized Shizuku/rish environment. It was **not** executed against the actual S24+ during development. To avoid false positives from shell warnings or error text, probes now accept only an exact one-line success token; the \`pm path\` probe requires properly shaped \`package:/.../base.apk\` output (and permits split APK lines). Unexpected, prefixed, truncated or mixed command output is treated as **unverified**, not success. Returned APK paths and private data are never printed.

A new separate Boolean \`private_datastore_directory_present\` checks **directory existence only** for \`files/datastore\`, complementing the old SharedPreferences directory check. Neither existence bit inventories files, reads persisted settings, checks their integrity, captures a backup, tests a restore, establishes an APK signing fingerprint, or proves upgrade safety. A missing directory is not evidence that settings are absent or recoverable. Tests are fully mocked: \`python3 scripts/test_probe_existing_native_install.py\`. The report's \`consistent_backup_created\` and \`safe_to_uninstall\` always remain false.


### Composite private restore evidence gate (Lane E)

The two independent audits can now be invoked together through \`scripts/check_offline_recovery_bundle.py\` on already acquired **offline** owner-controlled protected trees. It requires exact, independently observed People and persisted-settings file counts plus an independently recorded snapshot SHA-256; then executes strict private-file inventory (including unknown-file and permissions refusal), complete logical People/settings parity, and a final unchanged-backup checksum. It neither accesses Android nor exports names or paths. This is safer than manually treating individual green checks as one recoverability verdict, but it is **not a replacement for actual Android restoration or source provenance**. Both copies could omit the same unseen file or settings, and no existing script can prove otherwise from two copies alone. Keep full phone upgrade status NO-GO. Synthetic-only test: \`python3 scripts/test_check_offline_recovery_bundle.py\`, also routed through the lightweight release signing CI.


### Native CSV Import Once is transactional **replacement**, not additive recovery

Read-only review of the native \`PeopleRepository.replaceFromV4Export\` source confirms the import parses and checks the CSV first, then deletes **all previous native assignment rows** and inserts the CSV set in a single SQLite transaction. A failed import rolls back; a successful import with **missing previously confirmed people** loses those missing assignments. The UI's “Import once” action may be used while the destination People table is populated; its name does **not** mean append-only, merge-on-name or automatically one-time-only. The same risk applies to an apparently valid but incomplete export CSV. The offline CSV exporter validates an existing source database, **not** all labels stored in the target app.

The existing signer-mismatch **synthetic disposable-emulator** recovery works against an empty new install. Never extrapolate that result to safe import into a populated S24+ installation. Before any authorized real recovery, independently verify a complete source and destination snapshot, exact assignment membership, and a lossless conflict/merge plan; ideally use a fresh empty isolated target. Do **not** attempt import on the installed phone without that evidence and explicit owner approval. This operational warning cannot itself prevent a wrong tap; Lane A owns any native preview/confirmation/merge UI changes.

### Standalone validator and recovery-export schema/stability gate (Lane E, 16 October)

The **earliest** offline checkpoint, \`scripts/validate_native_people_backup.py\`, now checks the same known People data boundary as the later two-tree parity checker. A purported four-column snapshot is **rejected** when it has extra assignment fields, an unsupported \`user_version\`, an additional user table, view or trigger, or changed Android metadata schema. The Native schema contract comes from the actual version-1 \`PeopleRepository\`: \`face_id\`, \`person_id\`, \`person_name\`, \`image_path\`, plus ordinary rebuilding indexes, and optional Android locale metadata. This also blocks \`export_native_people_recovery.py\`, which calls the validator first, from silently discarding an unknown future identity field while exporting its four known CSV columns. Schema upgrades require an explicit new recovery contract and synthetic test—not deletion of unknown fields.

The validator independently SHA-256 hashes the exact **existing standalone DB** before and after its read-only SQLite integrity/schema/row inspection, checks stable file identity/metadata, refuses symlinked/hardlinked DBs and unexpected WAL/SHM/journal files, bounds input size, and closes the SQLite connection without writing. A copied DB that mutates during validation is **NO-GO**, regardless of whether it still parses. This is a race-detection sanity gate on owner-controlled offline input, not protection from an adversarial process with privileged ability to replace files, and it still does **not** attest to the source's freshness, phone origin, completeness or Android restore success. Never apply this directly to the running app's live database.

The existing default \`--min-rows 1\` is only a smoke test, not a count of all confirmed records; obtain a trustworthy independent minimum. All regression fixtures contain fictional names and paths. Synthetic tests: \`python3 scripts/test_native_people_backup.py\` plus \`python3 scripts/test_native_people_recovery.py\`; both already run in the lightweight signing/release CI.


### Independent recovery CSV membership proof before any Native import (Lane E, 16 October)

The Native People screen's **“Import once”** is a **replace-all** operation, not a merge. A CSV containing a valid header and the correct *number* of records may still have wrong face/person relationships, omitted rows replaced by duplicates, altered names or paths, or fabricated confidence. New \`scripts/verify_native_people_recovery_csv.py\` compares **every** row of a previously produced recovery CSV against a separately pinned **standalone offline Native People SQLite source** in the documented \`face_id\` order. It enforces the exact five-column header, no fabricated confidence, exact person names and paths, identity memberships, known row count, existing file mode \`0600\`, nonlinked CSV and stable CSV/DB bytes across the read-only audit.

~~~sh
python3 scripts/verify_native_people_recovery_csv.py \
  /secure/private/atlas_native_people.db \
  /secure/private/recovery.csv \
  --expected-rows 123 \
  --expected-db-sha256 <independently-retained-64-hex-DB-checksum>
~~~

Replace the sample count/hash with independently retained evidence from the original, already protected source—not values computed from an untrusted CSV. No CSV or database is modified, and no names or paths are printed. The command detects logical corruption between the recognized source and CSV but **cannot** certify that this source was a complete phone-origin backup or that the import target has no existing assignments. A PASS always reports \`safe_to_import_into_populated_app=false\`, \`safe_to_uninstall=false\` and \`release_go=false\`. It is a prerequisite for manual *authorized* isolated-target recovery, **not permission to tap Import once in the existing S24+ app**.

The existing source exporter on main may produce OS-default filesystem permissions until the protected-output PR #105 passes full Android CI and merges. If the CSV fails the new mode check, do not change its privacy permissions to public or use an insecure output directory; keep source/private files protected and coordinate release approval. Regression: \`python3 scripts/test_verify_native_people_recovery_csv.py\`; invented names only.

### Private recovery CSV output (16 October, Lane E)

The native People SQLite-to-CSV exporter contains **personal names and filesystem paths**. Its output is now created atomically with exclusive mode **0600** under an existing nonsymlink directory with permissions **0700**, independently of the process umask. A shared Downloads directory or other group/world-accessible destination is intentionally rejected. Use an owner-controlled protected local directory (for example one established with \`mkdir -m 700\`); do not work around the check by weakening filesystem permissions. The original SQLite input is unchanged, and the exporter never accesses the phone. Synthetic regression \`python3 scripts/test_native_people_recovery.py\` checks lossless rows, both permissions, output overwrites and linked or public destination refusal. This protection does **not** encrypt the CSV at rest, provide Android restore capability, or authorize uninstall.


### Export-time People-source stability and protected error cleanup (17 October, Lane E)

The protected local \`scripts/export_native_people_recovery.py\` exporter now repeats the standalone SQLite checksum, schema, integrity and assignment-count validation **after** all CSV rows have been written. This closes a time-of-check/time-of-use gap: a source file whose assignments changed mid-export without changing row count must not produce an apparently validated CSV. If the source, row count, output inode or restrictive permissions change during export, it refuses the result and attempts to remove **only the file inode it created itself**. If another process replaced the destination path after creation, the exporter will not delete the replacement by name. A successful return is still an offline-only conversion, not a phone restore or safe populated-target import.

The additional synthetic regression mutates a fictional source person name after the CSV write, substitutes a different regular file at the output pathname, and relaxes output permissions during export. All must fail closed, with no unrelated file deletion. This cannot defend against a privileged concurrent actor swapping a file outside observed validation windows: use quiescent, private, separately backed-up source and destination directories. Do not run on ATLAS's live app database. Before **any authorized isolated recovery**, independently cross-check the completed CSV against the pinned source with \`scripts/verify_native_people_recovery_csv.py\`. This is still no authorization to install/uninstall or replace confirmed People on the actual S24+.
