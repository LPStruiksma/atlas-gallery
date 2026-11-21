---
name: atlas-emulator-evidence
description: Use for ATLAS screenshot or rendered-proof requests, UI pull requests, emulator CI evidence, and KVM or screenshot job failures.
---
# ATLAS emulator evidence
1. Read `scripts/ci_ui_evidence.sh`, `docs/EMULATOR_KVM_GATE.md`, and [design rubric](https://github.com/LPStruiksma/autodev/blob/main/docs/design/rubric.md) before preparing UI proof.
2. Capture actual Android instrumentation screenshots using the `saveUiEvidence` / `screenshot` helpers in `app/src/androidTest`; use synthetic media only.
3. Declare each screenshot in the feature's own `scripts/ui-evidence.d/*.txt` manifest, not a shared list.
4. For emulator shards run `bash scripts/ci_ui_evidence.sh pull-some`; aggregate with `verify`. An unsharded run uses `pull` then `verify`; verification requires the exact declared PNG set and emits base64 log groups.
5. Link the exact PR head SHA, CI run/job and S24+ 1080 × 2340 renders; record screen state, insets, font scale, and keyboard where applicable.
6. Cover light and dark themes and required empty, loading, error, large-library, and one-handed states; a single happy-path screenshot is insufficient.
7. Missing `/dev/kvm`, a skipped emulator, or green offline-contract checks are not Android integration proof: report unverified and inspect the job log.
8. Re-run an unchanged head at most once, only for a documented runner/infra flake; fix in-test failures instead of blindly re-running.
9. Never bypass the KVM gate, buy larger runners, touch the owner's phone, or upload real photos/People data; CI is not install approval.
10. This is a helper: exact-head CI, independent Review, and protected-path gates still apply.
