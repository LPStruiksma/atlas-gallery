# PERF-A2 first-fling investigation (#1013)

The four-column emulator benchmark intermittently reports `before=0.0, after=0.0` on its first real-uptime fling. The seven-column case passes. This document records the investigation contract, **not** a diagnosis or a passing test result.

## Evidence to collect

- Capture the injected DOWN/MOVE/UP event timestamps and screen coordinates for the first throw, including injection success.
- Observe whether the grid receives DOWN and subsequent MOVE events, and whether a scrollable pointer consumer starts dragging; distinguish an unreceived gesture from a gesture consumed by another detector.
- Capture the scroll axis range and scroll delta before and after every throw, with four- and seven-column cases compared on the same emulator.
- Establish a deterministic, measurable readiness condition before the first gesture if the evidence shows input registration races. Avoid sleeps, retries, ignored throws, assertion weakening, and quarantines.

## Acceptance

Keep the first-throw scroll assertion and all five measured throws. Add a regression guard that fails on the diagnosed cause. Require three consecutive exact-head emulator runs of `fourColumnFling`; report run URLs, head SHA, and failures as observed, not inferred.
