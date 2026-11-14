# ATLAS Gallery

**ATLAS is a private, on-device Android gallery: a faithful Samsung Gallery-class replacement with its own intelligence (People, visual search, Photo DNA, Stories).** This public repository, `LPStruiksma/atlas-gallery`, is the only active home of ATLAS. `LPStruiksma/atlas-native` is a private, read-only archive of the history before 2026-11-13. Its issue numbers are context only.

- **How ATLAS should look and behave:** [docs/OWNER_INTENT.md](docs/OWNER_INTENT.md) and the [Samsung parity spec](docs/product/SAMSUNG_PARITY_SPEC.md).
- **Where it's going, and what exists today:** [docs/product/ATLAS_VISION.md](docs/product/ATLAS_VISION.md) (capability map, long-range vision, roadmap).
- **Live work:** [open issues](https://github.com/LPStruiksma/atlas-gallery/issues) and [pull requests](https://github.com/LPStruiksma/atlas-gallery/pulls). AutoDev owns development, and seats follow the AutoDev rules and the route labels (`next:dev`, `next:review`, `hold`).

## Install and update

Signed releases are published on [GitHub Releases](https://github.com/LPStruiksma/atlas-gallery/releases) from `main` by [release-signed.yml](.github/workflows/release-signed.yml), and the phone updates through Obtainium. See [docs/RELEASE_PHONE_UPDATES.md](docs/RELEASE_PHONE_UPDATES.md). Older signing documents (`docs/STABLE_SIGNING.md`, `docs/P0_SIGNING_AND_FRESH_START.md`) describe earlier debug-key and Mac-paste routes and are history, apart from the Mac fallback named in that page. Package `nl.atlas.gallery`. The version code is derived from the build time.

## Privacy (public repository)

No personal photos, videos, face embeddings, People databases, private evaluation material, credentials or signing keys go into commits, test fixtures, CI logs or artifacts. Tests use synthetic or licensed media. Measurements on the owner's phone report aggregates only.

## Build and CI

- Kotlin, Jetpack Compose, Coil 3, Media3, ONNX Runtime; Android API 36; Gradle 8.13.
- [build-apk.yml](.github/workflows/build-apk.yml) builds and runs JVM tests on pushes that change app or build files. The API35 emulator UI shards run only when a PR is labelled `next:review` ([review-emulator-trigger.yml](.github/workflows/review-emulator-trigger.yml)), so they run once per review candidate instead of on every development push.
- [autodev-ci.yml](.github/workflows/autodev-ci.yml) runs static checks and the additive `.mjs` source contracts.

Offline checks (no personal media needed):

```sh
python3 scripts/verify_contract.py
python3 scripts/test_native_people_backup.py
python3 scripts/test_native_people_recovery.py
```

## Further reading

- [Unified gallery integration blueprint](docs/ATLAS_UNIFIED_GALLERY_INTEGRATION.md), [visual search notes](docs/visual-search/), [People bench](docs/people/PEOPLE_BENCH.md).
- Older documents (V4 Termux tools, the v0.2 foundation README, the live handoff from early November) are history. Check live `main`, issues and CI rather than assuming they are current.
