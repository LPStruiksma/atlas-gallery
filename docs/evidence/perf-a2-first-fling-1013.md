# PERF-A2 first-fling investigation (#1013)

The four-column emulator benchmark intermittently reports `before=0.0, after=0.0` on its first real-uptime fling. The seven-column case passes. This document records the investigation contract, **not** a diagnosis or a passing test result.

## Evidence to collect

- Capture the injected DOWN/MOVE/UP event timestamps and screen coordinates for the first throw, including injection success.
- Observe whether the grid receives DOWN and subsequent MOVE events, and whether a scrollable pointer consumer starts dragging; distinguish an unreceived gesture from a gesture consumed by another detector.
- Capture the scroll axis range and scroll delta before and after every throw, with four- and seven-column cases compared on the same emulator.
- Establish a deterministic, measurable readiness condition before the first gesture if the evidence shows input registration races. Avoid sleeps, retries, ignored throws, assertion weakening, and quarantines.

## Acceptance

Keep the first-throw scroll assertion and all five measured throws. Add a regression guard that fails on the diagnosed cause. Require three consecutive exact-head emulator runs of `fourColumnFling`; report run URLs, head SHA, and failures as observed, not inferred.

## Diagnostic carrier (not a fix; do not merge)

The current head deliberately removes the proposed readiness barrier and extra window-delivery assertions, restoring the original five throws and strict scroll/frame checks. A bounded, debug-only and synthetic-test-opt-in observer records injection acceptance, screen-space window history/current events, grid-local Initial/Final pointer samples, actual LazyGrid drag interactions, and per-throw scroll delta even on assertion failure. It does not consume events. Observer installation and recording may perturb timing; absence of reproduction is inconclusive. A missing drag interaction is evidence, not a standalone proof of which detector won. Review the diagnostic code after hosted build/JVM checks, then use the normal PR emulator trigger once. Return exact-head evidence to Strategy; remove the temporary probe before final acceptance.

## Changed sampling experiment (second and final diagnostic round)

The first diagnostic run [45490214857](https://github.com/LPStruiksma/atlas-gallery/actions/runs/45490214857) did not reproduce the first-fling failure; all five four-column throws scrolled. The unrelated search-history failure is tracked in #1036. Per [Strategy's bounded follow-up](https://github.com/LPStruiksma/atlas-gallery/pull/1014#issuecomment-7147480549), run the original four-column and seven-column methods plus exactly three predeclared four-column diagnostic methods. Each JUnit method has its own activity/fixture/density rule lifecycle; this is not a retry or a guarantee of cold disk/provider state. Install the window observer once before the original settling sequence and restore it in an outer finally; retain bounded per-throw samples and the strict original checks. Every throw log includes the method identity; additional complete reports have a diagnostic-only prefix, not PERF-A2A. The observer still may perturb timing. One normal emulator run after independent diagnostic review only. If every planned sample passes, report inconclusive and park pending observable recurrence; if a failure occurs, compare its recorded delivery and drag evidence to a successful sample. Do not merge this carrier or count any run toward final acceptance.
