# ATLAS phone updates signed by GitHub (G402 U17, #497)

**Owner decision, 2026-11-09 14:42Z:** "GitHub signs, one tap on phone". The permanent ATLAS key goes once into GitHub's locked secret storage; after that every merged ATLAS change on `main` becomes a signed release that the S24+ installs as an update. This replaces the earlier "key never in GitHub" stance by Lukas's explicit choice. The Mac pastes in [P0_SIGNING_AND_FRESH_START.md](P0_SIGNING_AND_FRESH_START.md) stay as the fallback. The Mac fallback uses the same nine-digit UTC release numbering and advances beyond the newest release tag.

## How it works

`.github/workflows/release-signed.yml` runs on **every push to `main`** that changes `app/**`, `build.gradle.kts`, `settings.gradle.kts`, `gradle/**` or the workflow itself. It has no PR, manual, tag or schedule trigger, and `scripts/audit_stable_signing_workflow.py` (run by `signing-safety.yml`) fails if one is added.

1. **build** (no secret, no environment, read-only token): checks out the pushed `main` commit, picks the version, runs `gradle :app:assembleRelease` with no signing configuration, `zipalign`s the unsigned APK and hands it to the next job with a checksum.
   - `versionCode` = UTC `yyMMddHH` × 10 + minute ÷ 6 (for example `261109147` at 14:42), at least one above the newest `v0.3.<code>` release tag, so it only ever goes up and stays above the Mac builds (`26110913`). `versionName` = `0.3.<versionCode>`. The stamp is written in the runner's working copy only, never committed.
   - Release notes = the merged PR titles since the previous release tag.
2. **sign** (environment `atlas-release`, the only job with `contents: write`): never checks out or runs repository code and uses the runner's own Android SDK (no third-party action). It decodes the keystore into a private temp folder (`umask 077`), signs with `apksigner` (passwords only through its environment), and deletes the keystore at once and again in an `always()` step. It then refuses the APK unless:
   - exactly one signer, equal to `5f15596090e3cfa339c2fd73a2039a23ecca9d02ae4e3506e22dabe976ce170b` (the M1 key);
   - package `nl.atlas.gallery`, the expected versionCode/versionName, aligned, not debuggable.
3. Publishes GitHub release `v0.3.<code>` with `ATLAS-0.3.<code>.apk` and `SHA256SUMS`.

All actions are pinned by full commit SHA. If the secrets are missing the sign job fails with a pointer to this page and publishes nothing.

## Lukas: one-time setup on the Mac (about 3 minutes)

1. In Terminal, copy the key as text (nothing is printed):
   ~~~bash
   base64 -i ~/ATLAS-signing/atlas-permanent.p12 | pbcopy
   ~~~
2. Open <https://github.com/LPStruiksma/atlas-native/settings/environments/new>, name it `atlas-release`, press **Configure environment**.
3. Under **Deployment branches and tags** choose **Selected branches and tags** → **Add deployment branch or tag rule** → `main`.
4. **Add environment secret**: name `ATLAS_RELEASE_KEYSTORE_B64`, value: paste (Cmd+V).
5. **Add environment secret**: name `ATLAS_RELEASE_KEY_PASSWORD`, value: the key's password (the one you type in the Mac pastes).
6. Clear the clipboard: `pbcopy < /dev/null`.

Keep the USB backup of the key; GitHub secrets cannot be read back.

## Lukas: phone updater (S24+, once)

1. Make a read-only token: <https://github.com/settings/personal-access-tokens/new> → name `Obtainium ATLAS`, expiry 1 year, **Only select repositories** → `atlas-native`, **Repository permissions → Contents: Read-only**. Generate and copy it.
2. Install **Obtainium** from <https://github.com/ImranR98/Obtainium/releases> (the `app-arm64-v8a-release.apk`) or F-Droid.
3. Obtainium → **Settings** → GitHub → paste the token into **GitHub personal access token**.
4. **Add app** → URL `https://github.com/LPStruiksma/atlas-native` → **Add**. It finds the newest `v0.3.*` release and offers `ATLAS-0.3.<code>.apk`.
5. Tap **Install**. Android shows "Update"; because the signer is the same key as the Mac build, ATLAS keeps its data.

After that, Obtainium checks in the background and notifies you when a new ATLAS release exists; one tap installs it.

## When something goes wrong

- Sign job says secrets are missing → redo the Mac setup, then re-run the failed workflow run from the Actions tab.
- "signer … is not the ATLAS key" → the secret holds another keystore; nothing was published. Redo step 1–4 with `~/ATLAS-signing/atlas-permanent.p12`.
- No release after a merge → the merge did not touch app or build files (by design), or the run is still going.
- Fallback without GitHub: the `owner_mac_release.sh` paste in [P0_SIGNING_AND_FRESH_START.md](P0_SIGNING_AND_FRESH_START.md).
