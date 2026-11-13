# Lane E — Android emulator /dev/kvm validation gate

**Status: Android recovery integration UNVERIFIED; installed S24+ upgrade NO-GO.** This runbook describes an existing CI infrastructure blocker, not permission to access the phone, restart Termux, install anything, modify credentials, or pay for larger GitHub runners.

## What the exact-head PR #172 run proved — and did not prove

- [Synthetic signer-mismatch People recovery run #39033887886](https://github.com/LPStruiksma/atlas-native/actions/runs/39033887886) for head `9b0b0fbf2e1ab1cedf8698a4e12aa507177e99b7`: **offline-contract SUCCESS** (synthetic Python checks and Bash syntax).
- The `synthetic-recovery` job failed during **“Fail fast without Linux KVM”** (before emulator startup). `reactivecircus/android-emulator-runner@v2` never executed; it was **skipped**. That run provides **no Android recovery pass or app regression finding**. The separate [signing-safety run #39033887930](https://github.com/LPStruiksma/atlas-native/actions/runs/39033887930) succeeded for its *own* lightweight scope only.
- The [existing workflow](../.github/workflows/signer-mismatch-recovery.yml) **already includes** the upstream udev KVM permission workaround: `99-kvm4all.rules`, `udevadm control --reload-rules` and `udevadm trigger --name-match=kvm`. The final `test -r /dev/kvm && test -w /dev/kvm` returned failure. The logs do **not** distinguish absent device node, denied permissions, or another inaccessible-device condition. Never claim a narrower cause without inspection.
- No People names/photos/private SQLite or phone state were used in this synthetic CI procedure.

## Cost-safe next actions, ordered

1. **Zero-cost triage:** Keep that failed job **red/unverified**. Inspect its existing recorded logs and the exact head SHA before any further CI. No blind rerun on the same hosted runner configuration; the udev workaround is already present and another failure may consume Actions minutes without producing Android evidence.
2. **Propose, do not apply, a KVM-capable executor:** Upstream [ReactiveCircus Android Emulator Runner](https://github.com/ReactiveCircus/android-emulator-runner#running-hardware-accelerated-emulators-on-linux-runners) documents Linux KVM, including **GitHub larger Linux runners** as one route. A separately provisioned self-hosted Linux executor with safely available `/dev/kvm` is another *possible* route. Both require **owner authorization** for costs/resources and a separate secrets/privacy review, not a speculative workflow edit.
3. **On an approved disposable runner, before downloading Android images/building APKs:** check the actual runner architecture, `/dev/kvm` node presence/type and read/write access. Record only safe generic boolean results and job URLs. Fail early if unavailable; do not attempt privilege escalation, assume permissions, or silently run a software-only emulator.
4. **Only if the approved runner passes KVM preflight:** execute the existing synthetic signer-mismatch recovery workflow on the **exact PR head**. Require a green real emulator `synthetic-recovery` job plus green offline-contract, preserved synthetic People row checks, sanitized UI/evidence artifacts, and zero real phone/private-gallery sources.
5. **Then return to the five-gate [S24+ decision card](S24_OWNER_UPGRADE_CARD.md):** green disposable Android CI does not prove that the existing installed phone APK has the same signer, that complete People/settings were backed up, or that photo SAF grants restore. All real owner-origin proofs and explicit approval are still mandatory.

## Minimal evidence record (safe to put on coordination issue #83)

| Evidence | Acceptable record | Forbidden inference |
| --- | --- | --- |
| Exact source | PR number and immutable head commit SHA | “Latest main” implies same APK |
| Fast checks | Exact workflow run/job links and conclusions | Python tests alone establish Android restore |
| KVM preflight | Node accessible yes/no, architecture, runner class, timestamp | A failed `test -r && test -w` proves no KVM hardware |
| Android integration | Executed emulator job PASS on exact head, only synthetic data | Skipped step counts as PASS |
| Release decision | Keep `release_go=false` until all five independently witnessed owner proofs | Emulator PASS authorizes phone upgrade |

**Do not create a GitHub workflow to rerun this automatically.** In particular, switching `runs-on` to a paid large runner, enabling third-party credentials/host resources, turning off the KVM gate, using `-accel off` as a silent substitute, skipping Android assertions, or treating a runner failure as green requires separate deliberate review. Physical S24+ installation and private People/settings remain untouched.

Sources: [GitHub-hosted runner classes](https://docs.github.com/en/actions/reference/runners/github-hosted-runners); [ReactiveCircus Linux KVM prerequisite](https://github.com/ReactiveCircus/android-emulator-runner#running-hardware-accelerated-emulators-on-linux-runners). These documents describe available options, **not** a guarantee that any particular unprovisioned runner currently has working KVM.
