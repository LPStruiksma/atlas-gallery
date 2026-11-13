# ATLAS 150k MediaStore refresh — isolated diagnostic reproduction

**Scope:** stabilization of first-release candidate **`78be8fe9597343d76a342fe1b40717e86834e490`** (app code `91439e05`), **not** experimental P/X/C/V. Candidate remains unchanged. See the [Claude integration handoff](CLAUDE_INTEGRATION_SPRINT_20261024.md) and [issue #83](https://github.com/LPStruiksma/atlas-native/issues/83).

## Observed evidence versus hypothesis

- Claude's local **API 35 emulator**, with **150,157 synthetic MediaStore rows**, displayed the photo timeline after **21.9, 30.0 and 31.4 seconds** on three cold loads. Emulator rendering is not representative of the S24+.
- One earlier cold launch immediately after a `scan_volume` operation displayed **“Couldn't refresh authorized photos.”** The caught exception was **not captured**, so we have **not identified its cause**.
- `GalleryRepository.load` intentionally refuses a provider version/generation mismatch, as well as malformed cursors, missing volume mappings, bad row IDs or access failures. The observed failure could belong to any of these categories. **Do not invent a diagnosis or mask it by swallowing exceptions.**

## Candidate-preserving patch to test

The separate proposal branch `atlas/lane-i-150k-refresh-diagnostics-20261024` is based on the exact accepted candidate. It changes only the ViewModel scan-failure log and provides fixed typed markers for the **two in-repository** consistency failures. It **does not** auto-retry, change catalogue reads, relax version checks, log private data or move the protected candidate.

Safe tag **`ATLAS_SCAN`** has these bounded fields only:

- Failure: `event=failed ticket=N code=SNAPSHOT_CHANGED|VERSION_UNAVAILABLE|SECURITY|PROVIDER_BUSY|SQLITE|PROVIDER_CANCELLED|PROVIDER_IPC|PROVIDER_STATE|INVALID_ROW|IO|UNKNOWN observer_changed=true|false grant=SAME|DIFFERENT|UNKNOWN latest=true|false revoked=true|false elapsed_s=N`
- Success: `event=published ticket=N elapsed_s=N`
- `elapsed_s` is monotonic, truncated to integer seconds and capped at 600. An enum is derived only from fixed Java/Android exception types. **No** exception `message`, `toString`, Throwable, stack trace, media key, URI, path, filename, private roster, content source or grant token is sent to Logcat.

## Reproduction on the *existing synthetic emulator*, not a physical device

Use the **known free local Android environment** from Claude's handoff, only if permitted by the machine owner/policy. Build the diagnostics proposal from its **exact SHA**; never overwrite the candidate and do not launch hosted Actions. With `env.cmd` active on Windows, use `adb -s emulator-5554` for **every** command, and require:

```bat
adb -s emulator-5554 shell getprop ro.kernel.qemu
adb -s emulator-5554 shell pm path nl.atlas.gallery
```

The first result must be `1`, and the second must find the *synthetic emulator* package. If not, **STOP**; never fall back to an implicit ADB device. Do not clear MediaStore or uninstall any app from a physical phone.

Use the existing **150k fictional-only** emulator catalogue. Capture the safe tag while reproducing the previously observed provider-scan contention; the MediaProvider scan command and permitted scope should be confirmed on that emulator (`adb -s emulator-5554 shell cmd media help`) before re-running the same `scan_volume` operation used in Claude's experiment. Cold-launch the synthetic ATLAS app immediately thereafter, and compare against an ordinary cold launch **without** provider rescan. The selected emulator is always explicit. A stopped or crashed emulator is not a gallery-success result.

After each attempt, read **only the safe tag**, not a full phone/system log:

```bat
adb -s emulator-5554 logcat -d -s ATLAS_SCAN:I
```

Retain only `event`, `ticket`, `code`, `observer_changed`, `grant`, `latest`, `revoked` and `elapsed_s`. Record whether the screen displayed the failure/retry state or the **actual** photo timeline. Do **not** paste raw Android logs, exception strings, private paths or MediaStore identifiers into GitHub.

## Decision tree (Lane M + Lane I)

1. **`SNAPSHOT_CHANGED` + unchanged live grant:** likely in-scan provider generation/version churn; confirm with repeated controlled synthetic attempts. Only then consider **one** bounded delayed fresh scan with strict latest-request and grant-generation admission; never publish a mixed or partial catalogue.
2. **`VERSION_UNAVAILABLE`:** verify mounted-volume/provider readiness; do not silently assume version 0 or publish an empty list. Any retry must first recheck current permission/mount scope.
3. **`PROVIDER_BUSY`/`SQLITE`/`PROVIDER_IPC` or `UNKNOWN`:** Lane M isolates the exact operation and safe subtype, with an emulator witness. Do not auto-retry an unknown category or log raw messages.
4. **`SECURITY` or `grant=DIFFERENT|UNKNOWN` or `revoked=true`:** **never** retry old-authorization work automatically. Require a new explicit access-scoped request.
5. A green **success** requires `event=published` for the current ticket, a real nonempty gallery and no source/permission regression; a log line alone does not establish UI acceptance.

Before promoting any fix, execute **real** `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` and synthetic API35 `connectedDebugAndroidTest` on the free emulator, then repeat controlled 150k cold-load tests. Keep Claude's **572/572 + 60 pass/5 skip** candidate evidence separate: it belongs to `78be8fe9`, not to the diagnostic proposal. Do not merge into `main`, sign permanent APKs, spend on hosted CI or operate the owner's S24+ without separate authorization.

**Lane M ownership:** provider-query stage isolation, typed transient cause and controlled retry design. **Lane I ownership:** preserve proven candidate, grant-/epoch-safe orchestration, release acceptance and exact-head promotion after evidence.
