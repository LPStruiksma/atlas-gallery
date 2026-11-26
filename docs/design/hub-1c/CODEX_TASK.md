# HUB-1C (#212): Codex implementation handoff (draft only)

This file is a **task handoff**, not implemented behavior, a new design approval, or synthetic render proof. The pre-build design is already approved. Do not mistake this scaffold for a completed user-facing feature.

- Work issue: https://github.com/LPStruiksma/atlas-gallery/issues/212
- Owner intent: https://github.com/LPStruiksma/atlas-gallery/issues/168
- Design v3 (authoritative privacy/count/navigation adjustment): https://github.com/LPStruiksma/atlas-gallery/issues/212#issuecomment-7054996434
- Design v2 (typed named/unnamed sides and atomic action): https://github.com/LPStruiksma/atlas-gallery/issues/212#issuecomment-7052329567
- Design v1 (visual hierarchy, tested states and references): https://github.com/LPStruiksma/atlas-gallery/issues/212#issuecomment-7051624252
- Independent pre-build Design PASS: https://github.com/LPStruiksma/atlas-gallery/issues/212#issuecomment-7055048934
- Stage 2 dependency: #211 (merged #600). Work on current `main` via this draft PR; no changes to HUB-2 #170 or HUB-3 #171.

## User-visible acceptance

Keep the familiar two face-led sides and **Yes / No / Skip** answers. On *either* named or unnamed group, show an unobtrusive **Not one person** affordance (48dp touch target). Contextual **This group / Both groups** must commit exactly one atomic typed mixed-mark action (no double write), advance only after successful acknowledgment, recalculate question/count, and permit **Undo** of that whole committed marking via the prior `PeopleReviewCheckpoint` without undoing unrelated pending answers. Failed/stale commits never imply success.

In People overflow, **Needs splitting (N)** must show only active **visible** marks (named + unnamed) from one authorized current snapshot, with face-led rows to existing group/manual split pages and working Back. Hidden named marks must remain exclusively within **Hidden → Needs splitting (H)**; the ordinary People menu/list/search/accessibility/cache must never leak their existence, count or covers, even during permission transitions, loading and errors. Hidden entry itself must not display H.

Persist skipped `SamePersonQueueItem.key` identities as **scoped lightweight preferences**, not review facts. Either side's changed actual-membership fingerprint invalidates its skip identity. Reload preferences before queue items render, exclude skips from actionable queue only (not all-open count); add People overflow **Show skipped** to reset only the skip set. Preserve the progressive cursor and no transient false complete/negative counts.

## Validation required for Review

- Add repeatable synthetic JVM/Compose tests for named-only, unnamed-only, both sides, atomic failure and Undo, skip-relaunch/reset/invalidation for either changed side, grant/hidden boundary, navigation/Back, counts and progressive queue.
- Attach exact-head hosted CI results and **actual synthetic S24+ 1080 × 2340 emulator screenshots**, light and dark, covering one-side, both-side/contextual menu, Undo, visible and Hidden Needs splitting, Skip reload/reset, empty/error/loading, larger text and TalkBack target semantics. A schematic is not a device screenshot.
- Update touched model comments and People/Hidden/Skip help in the same patch; do not change Quiet Frame v3 or owner design.
- No real people, names, photos, embeddings or private library materials. Never read, search or print `eval/held-out/**`.
- Maintain bounded writable scope: `app/src/**`, `test/**`, `docs/**`, `tools/**`; do not modify `.github/**`, security/authority files, dependencies, deleted/renamed/binary files, or unrelated screens. GitHub's Codex auto-apply accepts at most **12 changed regular-text files in a single commit**. If full correctness exceeds the bound, report it explicitly rather than disguising an incomplete PR as finished.

## Codex patch protocol

Respond to the one PR mention with the verified checkout parent/full SHA, one `git format-patch -1 --stdout` patch enclosed in exactly one fenced `diff` block, with `Parent: <full existing PR-head SHA>` in the patch's mail header. Report tests run, limitations and any unmet acceptance item. Do not push, claim, label, approve or merge. The driver will inspect a trusted auto-apply receipt, then require **CI on that exact applied SHA**, independent Review and all ordinary gates.
