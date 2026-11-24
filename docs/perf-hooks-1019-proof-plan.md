# PERF-HOOKS-1b real-action proof plan

Issue: https://github.com/LPStruiksma/atlas-gallery/issues/1019

Acceptance: instrumented tests drive the real viewer open, preview-ready (including cached preview and shared-photo transition), and return paths, then assert that PerformanceSession aggregates for A3, A4 and A7 gain samples. Direct `recordActionMs` calls alone are insufficient.

Only fix hooks demonstrated broken by the tests. Report action, hook, test and fix/already-working status in the PR. Verify exact-head JVM CI; independent Review runs instrumented tests via `emulator:run`. No physical-device proof is claimed.
