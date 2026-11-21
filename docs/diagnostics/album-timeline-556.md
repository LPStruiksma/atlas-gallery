# Diagnostic task: #556 timeline-on album end-of-list failure

Issue: https://github.com/LPStruiksma/atlas-gallery/issues/556
Evidence to reproduce: https://github.com/LPStruiksma/atlas-gallery/actions/runs/44504304887/job/135495150535
Comparison application: https://github.com/LPStruiksma/atlas-gallery/pull/554 (head d732f3831288e863afd082d6e2e9e81d5864e6e6)

## Diagnostic only (first stage)

Instrument `app/src/androidTest/java/nl/atlas/gallery/featuregrid/OriginalProportionsAlbumJourneyTest.kt` so that, when the 30-second one-colour wait fails during `albumAtOriginalProportionsTimelineOn`, evidence includes:

1. A real `captureToImage` capture of the *failing* tile and a capture of the whole gallery, persisted to the existing CI-compatible UI-evidence output.
2. Geometry logs: exact selected tile identity and bounds, gallery bounds, floating tab pill top/bounds, sticky date-header bounds (or explicit absence), and the scroll axis value/max and whether a fling is still progressing at capture.
3. The pixel mismatch diagnostic (at least sampled coordinate and colour versus centre) if feasible, without changing the one-colour predicate or tolerance.

Keep test semantics and assertions unchanged. Do not skip or retry the test, change wait duration, relax the colours/ratio or implement a speculative fix. Avoid stale node indices after recomposition: identify the failing fixture by stable semantics, and preserve original timeout as failure after evidence is captured. Use only synthetic media. A clear evidence path/log tag matters more than decorative output.

This diagnostic must eventually run with #554's app code on a throwaway proof branch; do **not** push to #554's branch. The first diagnostic full-suite dispatch is pre-approved in the issue; further proof runs are reserved for the evidence-based fix.

## Codex patch contract

Return exactly one `git format-patch -1 --stdout` commit based on the full draft-PR head named in the mention, with a matching `Parent:` mail header. The only intended code change is the instrumented Android test file; change this brief only if required to clarify evidence. The Codex assistant does not push, claim, label, or merge. Record exact attempted commands, failures and test availability. Never read, search or print `eval/held-out/**`.
