# ATLAS Native 0.2 — Quality evidence and remaining gates

Date: 2026-10-13

## Executed in the artifact-creation runtime

- Pure-JVM source compiled with Kotlin `kotlinc 1.9.0` and the existing 1,000-record scenario passed.
- Offline static safety and CI contract checks: `python scripts/verify_contract.py`.
- GitHub Actions workflow syntax checked via YAML parser.
- Maven/Android compatibility reviewed from official Google documentation; stable `Compose BOM 2026.06.01` chosen because API 36 + AGP 8.13.2 cannot compile Compose 1.12 / September 2026 BOM.
- AutoDev `SECURITY.md`, roadmap and CI checked read-only: design reuses exact-head validation, credential-light workflows, reversible changes, evidence-based quality gates. No AutoDev authority or source code modified.

## Implemented, NOT executed on Android yet

- `GalleryViewer` test tags and Compose `ViewerInteractionTest` that creates 64 synthetic colored bitmaps in app cache; asserts initial selection, filmstrip tap, Back destination, near-end selection; captures two PNG screenshots.
- Debug APK build and JUnit tests on every push/PR. Emulator tests only on PR or explicit manual dispatch.
- Zoom-aware pager scroll disable; separate grid scroll positions and album Back handling.

## Outstanding blockers and boundaries

1. No Android SDK / Gradle dependency downloads or emulator in the artifact-creation runtime, so no APK and no Android UI screenshot have been generated here. The CI workflow still needs its FIRST execution; do not assume compile or runtime passes.
2. No ATLAS-specific GitHub repository exists in the connected account. One-time creation from the packaged script is required before CI can execute. This is separate from `LPStruiksma/autodev`.
3. No real Galaxy S24+ latency, video transitions, gesture conflicts, accessibility, power consumption or jank evidence yet. Emulator interaction success is not proof of Samsung-equivalent polish.
4. Existing V4 / V5 recognition backends remain separate and untouched; integration has not been attempted in 0.2.
5. The UI test image assets are generated synthetic photos. No personal photos or biometric labels are uploaded to GitHub.

## AutoDev-style promotion gates

- **Gate A** — offline source & JVM unit tests pass (yes in current runtime).
- **Gate B** — exact-head real Android compilation + JUnit in GitHub Actions (pending).
- **Gate C** — emulator tap/Back/screenshot tests (pending).
- **Gate D** — visual review of screenshot evidence and regressions (pending).
- **Gate E** — one S24+ canary vs Samsung Gallery for scrubbing latency and jank (pending).

When GitHub CI fails, fix the exact observed compilation/test failure rather than weaken a gate or claim the release is complete.
