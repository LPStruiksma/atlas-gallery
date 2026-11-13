# ATLAS P0 — phone-first G3 cloud APK handoff (NOT YET EXECUTED)

Lukas has only a Galaxy S24+ / Termux and a managed work laptop. **No personal Linux PC or GitHub permanent signing secret is required.** Read coordination [#83](https://github.com/LPStruiksma/atlas-native/issues/83).

## Manual source-bound cloud generation

Workflow `.github/workflows/p0-cloud-g3.yml` has **workflow_dispatch only** and refuses non-main GitHub refs. Operation **generate** archives reviewed immutable Android source `36bd476530abad6ebe794bd2d00128d83940518a` (green API35 app parent `a17204e2c484813d3c812d9cc637658ed183564e`) twice. Variant B changes **only** its literal Gradle `versionCode = 3` to `4`; app and instrumentation APKs are independently built, with disposable runner debug signatures only. Neither private keystores nor passwords enter Gradle or Actions.

The artifact `ATLAS-P0-G3-preview-apks` contains:

| Code | App preview | Instrumentation preview |
| --- | --- | --- |
| 3 / A | `A-app.apk` | `A-test.apk` |
| 4 / B | `B-app.apk` | `B-test.apk` |

Plus `source-commit.txt` (the exact 40-hex source pin) and **four-line `SHA256SUMS`**, both consumed DIRECTLY by Lane E's `scripts/p0_termux_owner_sign.sh` check/sign methods without any renaming or translation. Extra `artifact-manifest.json` has schema `atlas.p0.preview-apks.v1`, immutable pinned **Android app source SHA**, the separate **generator workflow Git commit SHA** (`generator_sha`), package, versions 3/4 and the exact same four SHA256 values. During Verify, the generator SHA must equal the independently authenticated successful Generate Actions run's `headSha`, not a user-typed or manifest-only assertion. **These APKs are not owner-signed.** AOSP `apksigner sign --out` replaces existing signatures when later run locally.

## Lane E ownership: Termux signer and custody

After separate Lukas approval and backup/restore review, E's phone-only signer must check source pin and all four preview hashes, re-sign **each** app AND instrumentation APK with one owner-held backed-up PKCS12 identity, and produce:

- `A-app.apk` and `A-test.apk` in a NEW owner-private signed-output directory
- `B-app.apk` and `B-test.apk` in that separate signed-output directory

Preserve private passwords/keystores/backups ONLY in owner custody, never in GitHub, command arguments, CI, logs or chat. E independently verifies package, versionCodes, signatures, hashes and public SHA256 certificate. No signing or key operations are carried out by this Lane A source.

## Later manual cloud G3 proof (only after owner assent)

Only the **four signed APKs**, no key or backup, may be placed as assets of an owner-created **internal PRERELEASE in the PRIVATE repository** named `atlas-p0-g3-...`. GitHub's release listings restrict drafts to push-access users; a private-repository published prerelease permits the cloud's read-only token. GitHub private prerelease storage is a proposed handoff, not an automatic upload/public distribution. Run **verify** on `main` with the originating successful generate run ID, private prerelease tag and owner's **public** certificate SHA256.

Cloud authenticates the original successful Generate run against GitHub's **workflow numeric ID**, event, branch, result and 40-hex `headSha`. It uses an `actions:read, contents:read` token **only in the download step**; the separate APK ZIP/aapt/apksigner verification step gets **no GitHub token**. All public source hashes, exact non-signature APK ZIP entries, all four identical public owner signing cert fingerprints, app package `nl.atlas.gallery` with strictly increasing **3→4**, and test package `nl.atlas.gallery.test` are checked before emulator installation. Neither metadata validation nor private prerelease checks depend on removable Python `assert` statements; failures always exit nonzero. The public fingerprint supplied for cloud Verify must be independently cross-checked against the fingerprint recorded by the owner in Termux—this workflow does not itself certify the legal identity of the operator. It then requires a single **fresh KVM API35 emulator** (no real phone), installs signed A app/test, executes `PeopleUpgradeTest#seedBeforeUpgrade`, upgrades via `adb install -r` to signed B app/test and executes `#verifyAfterUpgrade` without reseeding.

Only an actual emulator success warrants G3 PASS. The existing test asserts **fictional People SQLite**, synthetic test setting and the actual production preference namespace `atlas_v4_live_sync/sha256` with invented digest; it does NOT prove SAF permissions, UI link behavior, Android OS revoke/regrant, API34, backup correctness or any S24+ installation.

## Cost and verification status

Source implementation alone costs **$0** and produces **no APKs yet**. No workflow was dispatched, no owner credentials created, no internal private prerelease created, and no physical phone used. Generate is capped at 30 GitHub-hosted runner-minutes and Verify at 40; nominal standard Linux $0.006/minute implies **up to $0.42** for both **if executed to full caps**, before other P0 usage, reruns or applicable billing adjustments. **Do not launch either without checking the cumulative shared $1 owner cap and seeking renewed approval if needed.** Repository job histories are *not* an authoritative incremental billing ledger; if a reliable account-level remaining balance cannot be retrieved, do not infer spending approval from elapsed job minutes alone. A private-repository **published prerelease** remains accessible to repository readers and is not cryptographically immutable; the four signed APKs are public-certificate-bearing debug builds, never an authorized phone-install package. The owner must explicitly approve their release-asset return and independently verify the expected certificate fingerprint.

Offline checks: `python3 scripts/test_p0_cloud_g3_contract.py` and `bash -n scripts/p0_cloud_g3_emulator.sh`. No source-only check should be misrepresented as actual Android compile, APK signing or G3 runtime proof.


## Pre-key signed-input trust contract (Guard #6338969877)

**Do not sign using only `SHA256SUMS`, `source-commit.txt` or `artifact-manifest.json` supplied inside the same downloaded artifact.** They are attacker-editable together with all four APKs. The sole canonical on-phone provenance implementation is **Lane E's `scripts/p0_termux_preview_provenance.py` on draft #397**, called *inside* E's `scripts/p0_termux_owner_sign.sh sign GENERATE_RUN_ID KEY BACKUP NEW_OUTPUT`. A's previous alternative downloader was removed from draft #396 to eliminate two parallel trust-critical implementations and avoid extra phone steps; its historical fictional tests remain in the earlier PR commits for independent reference, not in the release workflow.

E's signer must authenticate the specific successful manual-main Generate run using the canonical private GitHub repository and immutable repository ID **1417745611**, real workflow numeric ID/path/name, successful `workflow_dispatch` on `main`, the generator SHA and exact run-bound artifact. It must retrieve **the GitHub Actions API's independently reported SHA-256 of the ORIGINAL ZIP** and compare the complete downloaded archive *before* parsing/extracting its strictly seven allowlisted members. Source and JSON generator SHA must match the authenticated run, all four APK hashes must match, and the signer must sign only the newly staged private-home bytes in that same invocation. A command accepting a caller-supplied APK folder as signing authority is forbidden. E's existing backup, output canonical-parent symlink checks, package/version constraints and apksigner v2 verification remain enforced. Never require Lukas to copy/paste raw hash values or manually rename ZIP members.

**Separate source-authorization pin (22 October independent review):** A successful run on `main` does **not** prove the workflow and executable helper scripts at `run.head_sha` were the *reviewed* source. The E pre-key verifier must compare authenticated GitHub blob identities for the generating workflow and every Generate-executed Python helper at that immutable commit against independently frozen reviewed Git blobs, and fail closed if anything differs. See [E's requested source-pinning regression](https://github.com/LPStruiksma/atlas-native/pull/397#issuecomment-6340265174). Freeze these reviewed blob identities before any owner-key action; revise only after a fresh independent review. This supplements, not replaces, the pinned Android app source `36bd476530abad6ebe794bd2d00128d83940518a`.

**Evidence boundary:** Both #396 and #397 remain drafts and no newly built real Android versionCode 4 artifact, on-device official Termux cryptography or approved owner-key emulator update exists. E's updated 18-case fictional suite has not yet been demonstrated complete PASS on the exact head. No CI expenditure, signing, phone or private upload is permitted by this document. The previous A standalone 20-case fictional test evidence and Guard-source review are not substitutes for E's actual integrated sign-path tests. No redundant standalone A provenance script should be used in production.
