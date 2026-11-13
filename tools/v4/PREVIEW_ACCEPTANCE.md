# ATLAS V4 People — owner preview acceptance card

**Scope:** optional local Termux/Python browser preview on `127.0.0.1:8767`.
**Not:** a native APK upgrade, not a migration of the installed Gallery app,
and **not** approval for automatic identity assignments. The currently running
Termux instance and private `people.sqlite3` are intentionally untouched by
the preparation of this card. Owner decides whether and when to trial.

## No-go conditions (before any local install or server restart)

- A scan, auto-evaluation, identity job, or V4 server is still writing: finish
  or safely pause it first. Do not kill a database-writing process blindly.
- No fresh, owner-controlled online People SQLite backup, or the backup has
  not passed read-only integrity verification **and** independent restore
  rehearsal in a separate disposable location.
- You cannot identify the current V4 command/process, safe previous script,
  or exact intended V4 artifact commit SHA.
- You have not chosen a local rollback plan for **database changes**. Rolling
  back the Python script alone **does not undo** accepted identity actions or
  schema changes.
- A native S24+ APK/signing/People restore is being considered: use the
  separate Lane E native release gate. This card does **not** authorize
  installing/uninstalling Android apps or replacing the native People store.

## Candidate integrity before touching the live process

Review the dedicated GitHub Actions **ATLAS V4 Termux Preview Bundle**
artifact from a successful, intended `main` commit. Artifacts expire and
are **not People backups**. Android may append ` (1)`, ` (2)` to ZIP
filenames. In a new Termux shell, owner may *optionally* run these local
checks after selecting the downloaded artifact:

```sh
latest=""
for f in ~/storage/downloads/ATLAS-V4-Preview*.zip; do
  [ -f "$f" ] || continue
  if [ -z "$latest" ] || [ "$f" -nt "$latest" ]; then latest="$f"; fi
done
[ -n "$latest" ] || { echo "NO-GO: no downloaded V4 bundle"; exit 1; }
bundle_dir="$(mktemp -d "$HOME/atlas_v4_bundle.XXXXXXXX")"
unzip -q "$latest" -d "$bundle_dir"
(cd "$bundle_dir" && sha256sum -c SHA256SUMS.txt) || {
  echo "NO-GO: bundle integrity mismatch"; exit 1;
}
cat "$bundle_dir/BUILD.txt"
expected="$(awk '$2=="monochrome_studio_v4_people_ux_preview.py" {print $1}' "$bundle_dir/SHA256SUMS.txt")"
[ "${#expected}" -eq 64 ] || { echo "NO-GO: missing preview hash"; exit 1; }
```

Compare `BUILD.txt` commit against the successful Actions run and the
intended source revision. `SHA256SUMS.txt` catches accidental byte
corruption or mixing files; it is **not** an independent digital
signature if the ZIP and checksums are both maliciously replaced.

After the backup and restore gates below, **only if you elect to install
the copied script**, the current helper supports pinning both its source
and exact digest:

```sh
ATLAS_V4_SOURCE="$bundle_dir/monochrome_studio_v4_people_ux_preview.py" \
ATLAS_V4_EXPECTED_SHA256="$expected" \
bash "$bundle_dir/atlas_v4_install_latest.sh"
```

It syntax-checks the script, verifies the digest and copies only
`~/atlas_v4_latest.py`, retaining `~/atlas_v4_previous.py` when
present. It **does not** start or stop a server, read/write the
private People database, or install an APK. A mismatched digest, missing
source or invalid Python must leave the currently installed copy unchanged.
The legacy unpinned newest-download invocation still works, but is
**not** the preferred owner acceptance route.

## Private data proof (owner-only; not a CI claim)

1. Finish the current V4 job and make an online SQLite backup using the
   **currently known-good** V4 CLI. The existing documented command is
   Use a **new, unique destination** so the tool can never overwrite an
   earlier recovery snapshot:

   ```sh
   backup_file="$HOME/storage/downloads/atlas_v4_before_people_ux_$(date +%Y%m%d_%H%M%S).sqlite3"
   [ ! -e "$backup_file" ] || { echo "NO-GO: backup destination already exists"; exit 1; }
   python ~/monochrome_studio_v4.py backup --output "$backup_file"
   ```

   Use the actual currently known-good script path on your phone. Run the
   verification below in the **same Termux shell** so `$backup_file`
   still identifies that specific finalized online snapshot. The
   **new preview** backup command refuses existing destination files,
   but the **old currently installed script may not**; the explicit shell
   precheck above is essential. Do not delete or overwrite an older
   backup to make the command succeed. Never upload the DB
   or copy faces/embeddings/names into GitHub.
2. In the same Termux shell where you verified and unpacked the
   checksummed preview artifact, run the packaged **read-only** backup
   verifier against that finalized backup — **never** against the live
   `~/.monochrome_studio_v4/people.sqlite3`:

   ```sh
   python3 "$bundle_dir/verify_preview_backup.py" \
     --backup "$backup_file"
   ```

   The required output is a single JSON object with `"result": "PASS"`,
   `"source_read_only": true`, `"isolated_restore_rehearsed": true`,
   `"all_user_rows_equal": true` and only aggregate table counts.
   A `NO-GO` exit, implausibly low counts or unexpected missing review/Undo
   rows means **stop**, investigate privately, and do not start the trial.
   The verifier checks SQLite integrity/foreign keys, every user-table
   row by streamed content hashing, then rehearses restoration into an
   isolated restrictive temporary directory and checks the same
   evidence again. Names, photograph paths, review notes and embeddings
   are never printed or uploaded. It does **not** write to or migrate
   the source backup, the current People DB, or originals.

   The private rehearsal directory needs temporary disk capacity; the
   tool checks disk headroom and deletes its copy after normal completion.
   A forced Termux/process kill could leave a sensitive
   `atlas-v4-rehearsal-*` temporary directory behind: inspect it
   locally and clean up deliberately, not via blanket deletions.
   A refused or unavailable verifier is **NO-GO**, not tacit backup
   approval. This only establishes recoverability of the backup's
   **current** rows; it cannot attest to freshness or to changes made
   after the backup.
3. Retain a separate previous script and a protected backup until the
   candidate passes inspection. If any table or recovery proof is
   uncertain, **NO-GO**.

An online backup/quick check is not proof that arbitrary future merges can
be undone. Undo is transactional and bounded to specific review actions.
Do not exercise destructive or automated People operations merely to test
this preview.

## Owner-controlled browser smoke test

Only after the above gates: let the active V4 server stop cleanly in its
*own* Termux session, then explicitly start the verified
`python ~/atlas_v4_latest.py serve --port 8767`. Leave any V5 server
(e.g., port 8775), native Gallery installation, and other Termux processes
untouched. Open `http://127.0.0.1:8767` locally.

- **People:** find an existing name in a large roster, use Previous/Next,
  open the Person and return to the same search/page/scroll position.
  Same-name profiles stay distinct and show identifying portrait/count/ID.
  When a *new* Person is deliberately created, the People roster should
  immediately expose **Undo last review action** without forcing navigation
  to another tab; the button is absent if no valid undo token exists. Do not
  create a throwaway private Person solely for testing before backup gates.
  Repeated fast presses of **Create a person** while a request is pending
  must not open multiple name prompts, duplicate homonym pickers, or send
  competing create requests. The existing-profile chooser stays in control
  until dismissed, and a cancelled request must permit a later
  deliberate attempt. A **failed create acknowledgement** is different:
  the server might already have saved the Person. The next create attempt
  must demand explicit acknowledgement to inspect the People roster first;
  Cancel must send no write, and a fresh duplicate-name check must offer the
  existing profile instead of silently duplicating it. A rejected request
  without a confirmed response must not generate an Undo token. Do NOT
  deliberately interrupt a real private write to test this uncertainty
  behavior; synthetic tests simulate both commit and response loss.
  If a preview browser reloads after a lost reply, the **People**
  roster may show **Verify earlier creations**. That check is read-only:
  it can confirm a completed Person creation and recover its original
  still-valid Undo token without storing a name or photo in browser
  storage. An **unknown** result is NOT proof of failure; keep the
  warning and inspect People before any new creation. No automatic
  resubmission occurs. Closing the browser tab may erase sessionStorage,
  so absence of the button is not proof that a prior request failed.
  Rapid-tap proof uses only synthetic faces and People.
- **Discover:** a fresh browser starts in **Stable review pass**, which
  sorts by original group size and uses an anchored forward cursor, rather
  than allowing previously reviewed groups to shift unseen work across
  numbered pages. Existing saved choices (Largest, Smallest, Shuffle) remain
  unchanged; those modes can still shift as pending groups disappear. Reach a
  middle/late page, enter a group, use Back and confirm page/scroll return.
  Try rapid Next taps: no unseen page skip or stale focus. Browser restart
  may intentionally reset an unreconstructable deep stable cursor to page 1;
  identity state is not saved into browser storage.
- **Picker (nonmutating):** choose *Attach group to EXISTING person*,
  search, preview a matching named destination, use Go back/Cancel/Escape.
  No tag may change merely from opening/searching/previewing. Confirm
  remains a separate action. Test keyboard Tab focus within the dialog
  when available; actual TalkBack requires deliberate user inspection.
- **Faces:** use Select page, inspect the selected count, change page and
  verify hidden selections never remain. Inspect suggestions: they stay
  unconfirmed until manually approved.
- **Real write only if explicitly chosen:** for a known safe reviewed
  selection, confirm the correct Person and inspect the audit/Undo result.
  Never test with ambiguous group-wide identity or private data you
  cannot afford to revert.

For every smoke step, record only a local **pass/fail/unknown** note and
artifact SHA, not personal names/photo paths. A failed or uncertain step is
not a release pass. No CI or synthetic result substitutes for the owner's
S24+ browser/touch/accessibility experience.

## Rollback boundaries

If the browser/preview fails, stop **only** the trial V4 server gracefully
and restart the preserved old server script on the same port when safe.
Already committed tags and schema migrations **persist** across script
rollback. Database restore from the independently verified pretrial
snapshot is a separate explicit owner action that can replace legitimate
newer edits: never perform it automatically or over a running database.
Do not delete originals or unassign unknown identities as a workaround.

**Current sign-off:** synthetic V4 tests verify code paths, not real
S24+ deployment, backup recovery, frame-time, mixed-face accuracy, or
TalkBack acceptance. Until all owner-specific checks are done, status is
**preview unverified / NO-GO for changing the active Termux V4 instance**.
