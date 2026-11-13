# ATLAS Native — Galaxy S24+ safe upgrade go/no-go

**P0 release-path correction, 21 October 2026:** The five gates below apply to **preserving the installed native v0.2 app-private state**. A second owner-elected [one-time fresh-start pathway](P0_SIGNING_AND_FRESH_START.md) may discard only expendable *old native v0.2* People/settings **after** the new permanent signer is securely isolated, two independent signed builds verify the same public cert, a real synthetic in-place A→B future People/settings retention test passes, photo/grant implications are understood, and the owner separately approves native data loss/installation. This is **not** authorization for uninstall, app clearing, keystore setup or bypassing missing native candidate/Android acceptance. Existing V4 Termux database and MediaStore photos remain untouched. **Current status on both pathways: NO-GO.**

**Lane E shortest-path proof:** Use the independent local A/B signer-emulator script described in [P0 signing and fresh start](P0_SIGNING_AND_FRESH_START.md) only from reviewed source, with a protected owner-held key outside GitHub. Script presence or static audit passes are not signing evidence. Current main secret boundary **FAIL**; key custody, independently signed APKs, People+real settings upgrade test, exact release candidate and separate S24+ installation approval **PENDING**. **GO only after all technical evidence and explicit final phone authorization; otherwise NO-GO.**

**Owner-first decision card:** [Galaxy S24+ release GO/NO-GO — five independently evidenced proofs](S24_OWNER_UPGRADE_CARD.md). This short card governs the actual-device decision; the remainder of this document is technical supporting detail.

**Status, 16 October 2026: NO-GO for replacing the installed S24+ app.** A successful same-runner synthetic emulator upgrade does not establish that a candidate is signed like the actual v0.2 installed on the phone. A fully recoverable People + settings phone backup has not been demonstrated. No phone action is authorized by this document.

## Five owner-controlled release gates

| Gate | GO requires | Stop immediately if |
| --- | --- | --- |
| Installed APK identity | The original **actually installed** nl.atlas.gallery base APK is obtained or its provenance independently established | Only an unrelated CI APK or remembered download is available |
| Public signing/version | Offline signer preflight passes: same single SHA-256 cert, same package, strictly higher versionCode | Signer mismatch, unsupported rotation, missing SDK tool, identical/lower version |
| Recovery | Consistent local People snapshot, known independent row minimum, independently retained copies, settings inventory and test restoration | Database copied live without WAL, checksum alone, settings incomplete, unknown SAF/document grants |
| Candidate quality | Exact candidate SHA passes Android CI emulator in-place People retention, viewer navigation/video/Back tests | Tested build differs, flaky red CI, data/UX regression |
| Human decision | Owner elects in-place installation after the above evidence and backup contingency | Any proposed uninstall, app-data clearing or destructive migration workaround |

**Unknown in any gate means NO-GO. Never uninstall the existing ATLAS app to work around a signature mismatch.**

## Offline APK comparison

The only authoritative old APK is the currently installed package's binary (when safely readable). Locating with **adb shell pm path nl.atlas.gallery** does not itself prove it can be pulled. Do not attempt to obtain private signing credentials. On a trusted computer, with both APKs locally available:

~~~sh
python3 scripts/check_apk_upgrade_signing.py installed-atlas.apk candidate-atlas.apk \
  --apksigner "$ANDROID_HOME/build-tools/36.0.0/apksigner" \
  --aapt "$ANDROID_HOME/build-tools/36.0.0/aapt"
~~~

Exit **0**: necessary package/signer/version proof only. Exit **1**: incompatible. Exit **2**: cannot establish safety. Same versionCodes now fail by default; --allow-same-version is a **test-only** override, not release authorization. Signer rotation lineages are not inferred.

## Independently compare offline People + settings recovery

App manifest has **android:allowBackup=false**. A running main SQLite file may omit WAL transactions. The existing validators require a consistent standalone snapshot or a demonstrably stopped and coherent offline capture; no script in this lane extracts app-private phone data.

For **already obtained owner-controlled protected copies**, compare two distinct trees structured as follows (only settings actually present can be backed up):

~~~text
private-backup/
  databases/atlas_native_people.db
  shared_prefs/*.xml
  files/datastore/*.preferences_pb
isolated-restored/
  databases/atlas_native_people.db
  shared_prefs/*.xml
  files/datastore/*.preferences_pb
~~~

~~~sh
python3 scripts/verify_native_restore_evidence.py \
  /secure/private-backup /secure/isolated-restored --min-rows 123
python3 scripts/test_verify_native_restore_evidence.py
~~~

Replace 123 with the **independently observed minimum** number of confirmed native face assignments, not an arbitrary value. The verifier rejects missing/changed settings, non-independent hardlinks, symlinks, changed identity assignments or invalid databases. It checks logical person/face/name/path equality without printing labels, and hashes supported SharedPreferences/DataStore files without revealing their contents.

**A PASS proves only offline parity for these two namespaces.** It does not prove the old APK's signer, phone-origin capture, completeness of other private app files, true Android import/recovery, SAF permission grants, or safety to uninstall. An actual app with no persisted settings files needs a separately recorded observation and UI verification; do **not** invent a fake file to make parity pass. The test-only signer-mismatch workflow proves People restore on a disposable emulator, **not** phone settings/permissions restoration.

For a real recovery demonstration, use a separately authorized **isolated Android test environment**, inspect settings in-app and exact identity memberships after restore, test permission reauthorization, and maintain a second protected copy independent of the emulator. Never put private backups/CSVs in GitHub or chat.

## After all gates are independently green

The owner may elect a normal in-place upgrade of the exact verified APK, retaining the old installation and backups. Check People identities, settings, gallery, filmstrip/Back, videos, search, and photo permissions. If installation fails, **stop**, preserve the app data, and investigate compatibility. If a new build malfunctions, an older versionCode is not a safe rollback; prefer a properly signed corrective higher-version build.

References: [stable signing](STABLE_SIGNING.md), [backup caveats](NATIVE_PEOPLE_BACKUP_VALIDATION.md), [viewer timings](viewer-scrub-benchmark.md), [coordination #83](https://github.com/LPStruiksma/atlas-native/issues/83).


### Binding the signer result to the exact build (Lane E)

The signing preflight reports both public certificate fingerprints and the **SHA-256 digest of each complete inspected APK**. It rejects a changed file between signing/manifest inspections. Prefer to record and independently verify the candidate hash from the intended **exact-head successful CI artifact** rather than the filename alone; the optional \`--expected-candidate-sha256\` and \`--expected-installed-sha256\` flags will fail closed on different bytes. The old value must originate from an independently established copy of the **truly installed** S24+ APK, not a guessed historical download. A successfully pinned candidate artifact does *not* prove that old package's real-device origin, permission grants, People DB restoration or owner consent. No automatic APK installation is performed.


### Unknown private app files: independent completeness check (Lane E)

After validating logical People assignments and known settings, additionally run \`python3 scripts/audit_native_restore_inventory.py /secure/private-backup /secure/isolated-restored\` on owner-controlled **offline protected copies**. A PASS is a necessary strict inventory sanity check, not evidence that both copies were captured completely from the installed app. This check refuses unaccounted private files or directories—including extra future files, lingering SQLite WAL sidecars or hidden settings—rather than silently calling the four known People columns a whole-app recovery. It also refuses permissive filesystem access to sensitive backup copies. Never delete unrecognized files merely to satisfy the checker; review the future schema/migration policy instead. The result always declares \`safe_to_uninstall=false\`. The real S24+ upgrade remains NO-GO until the independently verified installed signer, complete backup/restore procedure, permissions and owner decision gates are satisfied.


### One-command offline People/settings recovery evidence check (Lane E)

For **two separately obtained, already protected and offline** snapshot trees, run the combined read-only checker. This avoids accepting one isolated green subtest while silently overlooking a missing private directory, lost setting, changed identity assignment or unpinned source file:

~~~sh
python3 scripts/check_offline_recovery_bundle.py \
  /secure/private-backup /secure/isolated-restored \
  --expected-rows 123 \
  --expected-settings-files 2 \
  --expected-backup-sha256 <independently-recorded-64-character-hash>
~~~

Replace the example counts with the **exact** numbers independently observed before the capture, and obtain the expected People SQLite SHA-256 from a separately retained, trusted capture receipt—**not** merely by computing the hash of the same untrusted input immediately before running the check. Do not print, upload, or share any raw People data or backup files. The supplied paths must already exist; no directory, phone, or database is created or changed by this command. Synthetic regression \`python3 scripts/test_check_offline_recovery_bundle.py\`.

It requires *all* of the following simultaneously: strict private file inventory/permissions, matching settings file contents, known People SQLite logical assignment equality, exact recorded People/setting counts, and independently supplied hash parity of the backup DB. It also verifies that the People database remains unchanged across the combined process. A negative result is **NO-GO** without partially accepting the remaining subchecks.

A passing offline bundle means only that **these two provided trees** agree; it cannot authenticate the phone-origin capture or reveal a file omitted from **both** trees. The report always says \`phone_capture_origin_authenticated=false\`, \`real_android_restore_verified=false\`, \`saf_permissions_verified=false\`, \`candidate_apk_signer_verified=false\`, and \`safe_to_uninstall=false\`. The actual S24+ remains NO-GO until all five independent release gates above are genuinely satisfied and owner permission is given.


### Critical warning: Native “Import once” is **replace-all**, NOT a merge

As verified directly in \`app/src/main/java/nl/atlas/gallery/PeopleRepository.kt\` (current \`replaceFromV4Export\` implementation), a successful CSV import executes \`db.delete("assignments", null, null)\` inside a transaction **before inserting the supplied rows**. The native People screen keeps the **“Import once”** button available even when named people exist. A valid **partial** recovery CSV can therefore overwrite all previously confirmed assignments in a populated installation. The transaction protects against *failed import*, not deliberate loss of records absent from a *successful* CSV.

**Release procedure:** Never use “Import once” as a merge/sync or as a shortcut for restoring a subset into the owner's running app. Treat it as a destructive **replace-all operation**. Its existing emulator signer-mismatch recovery proof assumes a fresh **disposable empty** installation, and does **not** establish safety of merging with existing People data. Prior to any separately authorized real migration, retain independently verified full copies of both the target and source, verify exact membership counts and content, use a fresh/empty recovery target whenever practical, and independently inspect the post-import People state. If the target already contains assignments, **stop and coordinate a reviewed lossless merge/preview feature with Lane A** instead of overwriting them. The one-use consent dialog introduced in PR #150 prevents an accidental immediate replacement when simply choosing a CSV, but **does not make the replacement additive or preserve assignments omitted from an approved import**.

This is separate from APK in-place installation (\`adb install -r\` with matching signing certificate) and unrelated to Gallery media photo deletion. Never uninstall the existing S24+ app as a workaround for mismatched signatures; the overall release decision remains **NO-GO** without independently satisfied gates and the owner's informed authorization.

### Stable schema checkpoint before CSV recovery (Lane E)

Before an offline People snapshot is used for manual import/recovery, its **standalone** validator now checks a recognized current Native SQLite schema and immutable-in-practice file evidence (hashes before and after read-only inspection). Unknown identity columns/tables, changed files, link aliases or unsupported SQLite schema versions force **NO-GO**; the current four-column recovery CSV format cannot safely preserve fields it has never been designed to represent. Do not "fix" unknown schemas by removing fields or guessing a conversion. Passing remains *necessary but not sufficient*: a logically consistent SQLite file may still be stale or incomplete, and this does not establish phone-origin collection, private settings restore, SAF grants, signer compatibility or permission to uninstall.


### Anti-race audit of BOTH offline recovery snapshots (16 October, Lane E)

The unified \`scripts/check_offline_recovery_bundle.py\` now retains a private SHA-256 inventory of **every supported file in both supplied trees** before the logical comparison and re-inventories **both trees** afterward. It also repeats standalone SQLite integrity/schema and byte-digest validation for both People DB copies. This detects a late modification of the **restored** database, unexpected new private file, or changed settings after the earlier equality pass—not only mutations of the original source DB. Counts and result flags are emitted; source filenames, real People labels, paths and settings values remain private and unpublished. Fictional regression cases simulate exactly those changes *after* a successful logical comparison, and assert NO-GO.

It is still a bounded best-effort race-detection guard, **not** an atomic phone snapshot or anti-tamper guarantee against a privileged actor changing bytes outside the observation windows. The only safe source is an already captured, quiescent, independently retained offline set. Extra hashing can take longer for large private backups; no script should be pointed at a live SQLite DB or used as a reason to clear the phone app. \`release_go=false\` and \`safe_to_uninstall=false\` remain unconditional.


### Required logical recovery CSV membership verification (Lane E)

Before any **separately authorized** isolated Android import, run \`scripts/verify_native_people_recovery_csv.py\` against an independently pinned, already acquired offline People SQLite snapshot and its complete recovery CSV. It streams exact person/face/name/path membership rather than trusting a row count alone; it refuses missing/duplicated/reordered entries, tampered labels, fabricated confidence, unknown source schemas and unsafe CSV file permissions. Synthetic regression is included in the existing fast release safety CI. The verifier is **read-only** and always reports \`safe_to_import_into_populated_app=false\`. Because current Native “Import once” transactionally **replaces** all People assignments on successful import, even a perfectly matching CSV does not protect preexisting assignments in the target. Do not import into your S24+ until a non-destructive, reviewed populated-target migration procedure and owner-authorized complete backup/restore gates are verified. PR #150 added explicit import approval, **not** a verified merge with existing assignments.


### Recovery CSV checked file identity during parsing (Lane E, 17 October)

The existing *read-only* \`scripts/verify_native_people_recovery_csv.py\` now decodes the private CSV through an \`O_NOFOLLOW\` file descriptor whose inode, size and modification/change timestamps are explicitly matched against the **previously SHA-256-hashed** CSV. Reopening the same *pathname* without this check could previously allow a swapped file to be read after the initial hash, invalidating a source-to-CSV membership claim. A second identity comparison occurs after decoding; the existing final digest still checks all bytes. Synthetic fault-injection covers a same-byte *different inode* substituted after hashing and an in-place append during decoding; either is NO-GO.

The check is read-only and private-path-silent. It is a strong routine race-detection guard for already quiescent owner-controlled offline files, **not** a defense against privileged concurrent attackers or a guarantee of authentic phone capture. Native manual “Import once” remains replace-all; this does not authorize any import to a populated installation, APK replacement, or uninstall. All release fields stay NO-GO.


### Native People import consent verified; merge semantics unchanged (17 October)

Lane A's merged [PR #150](https://github.com/LPStruiksma/atlas-native/pull/150) now treats an Android SAF-selected CSV as an **inert proposed import** until the user separately approves a one-use dialog. Cancel/Back leaves the existing People state unchanged. The warning describes complete assignment replacement and backup requirements. Manual imports, linked V4 refreshes, and link setup are serialized under one coroutine gate to prevent certain overlapping writes; stale background roster results are discarded. This behavior was validated on the PR's exact head by [Android CI #38976136701](https://github.com/LPStruiksma/atlas-native/actions/runs/38976136701), including fictional People tests.

**Important distinction:** the consent flow reduces accidental or concurrent replacement, but \`PeopleRepository.replaceFromV4Export\` still **deletes all previous assignments on successful approval** and inserts the CSV contents. An incomplete CSV may therefore delete confirmed assignments even when the dialog worked perfectly. No user-approved real-phone migration or reversible merge operation was tested by PR #150. Treat an existing populated destination as NO-GO until a genuine lossless merge or independently approved full replacement plan has been validated. The old S24+ app, signer, SAF grants and actual People backup remain unverified. Do not install, uninstall or clear app data to overcome this gate.
