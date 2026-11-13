# ATLAS Native — One private Android photo gallery

> **Read first: [docs/OWNER_INTENT.md](docs/OWNER_INTENT.md).** ATLAS = an exact Samsung Gallery replica plus sort by file size, fast over cautious. Every Dev and Review checks against it.

**ATLAS Native is the Android gallery in `LPStruiksma/atlas-native`.**
It is **not** `LPStruiksma/autodev` (the separate autonomous-development project).
The native gallery and the V3/V4/V5 local Termux intelligence tools work together
toward one fast, private photographic archive. This is an actively developed
Android application, not merely a mockup or a web-wrapper.

> **Current installed release (3 Nov 2026):** The owner reports ATLAS is installed
> and its gallery loads correctly on the real Galaxy S24+. The personally signed
> installed APK uses package `nl.atlas.gallery`, versionCode **4**, built from
> [production RC2 source](https://github.com/LPStruiksma/atlas-native/tree/atlas/release-rc2-wave4-recovery-20261102)
> at `1f078d59`. Separate owner-local emulator evidence established the
> same-signer v3→v4 in-place upgrade on synthetic People/settings data.
> This does **not** prove every feature, 150k real items, full Samsung parity,
> or actual v4→v5 phone retention.
>
> **Critical branch distinction:** repository `main` is **older than the
> installed RC2 source**. Work toward the next release must start from
> [post-install version-5 source](https://github.com/LPStruiksma/atlas-native/tree/atlas/postinstall-next-update-base-20261103)
> at `a55b716c`. The [Samsung Gallery-class product programme](docs/ATLAS_SAMSUNG_PARITY_PRODUCT_PROGRAM_20261103.md)
> governs the full experience; [AutoDev intake handoff](docs/ATLAS_AUTODEV_OWNERSHIP_HANDOFF_20261103.md)
> is **not yet activated**. Never auto-install, uninstall, reset, sign or
> modify the owner's phone/photos/V4 People data without deliberate owner action.
> Historical release notes and status descriptions below may refer to earlier stages.

## What the working source already includes

| Layer | Implemented in the repository | Not yet established |
| --- | --- | --- |
| **Native Photos** | Read-only Android MediaStore catalogue; exact physical albums; newest, oldest, largest and deterministic shuffled sorting; lazy Compose grid; cached full-screen pager and bottom filmstrip; local Media3 video playback; zoom and Android Back context | Samsung-level cold/warm scroll and scrub latency on the physical S24+ |
| **Native People and Search** | Transactional confirmed-V4 CSV import and live SAF document sync; person virtual albums; exact B&W-folder filter; photo-backed reviewed-People shortcuts from Search; duplicate names stay separate identities | Signing-safe installation of the latest native code and physical-device acceptance of live V4 sync |
| **V3 B&W sorting** | Separate conservative on-device classification, review/move journal and collision handling | Optional near-grey/toned manual review; no automatic mass move from a model guess |
| **V4 People** | Local face indexing; naming, portraits, visual identity picker, reversible conservative automatic match proposals; provisional Discover groups with batched existing-person hints and stable Surprise me shuffle | Large-scale identity accuracy and S24+ Discover latency; actual phone runs use whichever V4 script was explicitly installed |
| **V5 intelligence** | Separate local semantic search preview and versioned identity-evidence store; read-only import of confirmed V4 face observations from a consistent offline snapshot | Reliable person-from-behind/body+clothing identification, calibrated pilot, complete native semantic-search integration |

All real face data, portraits, photos, private People databases, and on-device
embeddings remain on the owner's phone. Synthetic repository tests use
fictional names/images and mock embeddings. The native app itself requests
no INTERNET or all-files access and does not modify original media.

## Recent integrated milestones (14 October 2026)

- [#51](https://github.com/LPStruiksma/atlas-native/pull/51): instrumented Search → reviewed Person → exact B&W folder → full-screen filmstrip → Back journey.
- [#55](https://github.com/LPStruiksma/atlas-native/pull/55): read-only confirmed V4 snapshot → independent V5 evidence adapter.
- [#57](https://github.com/LPStruiksma/atlas-native/pull/57): visual photo-backed reviewed People shortcuts in native Search; emulator CI green.
- [#59](https://github.com/LPStruiksma/atlas-native/pull/59): seeded Surprise me ordering for Discover candidate groups, stable through pagination and group review; synthetic CI green.
- [#58](https://github.com/LPStruiksma/atlas-native/pull/58): precomputed keys make native random sorting faster while preserving exactly the old seed-to-photo ordering; JVM and emulator CI green.

These describe **source integrated into GitHub main**, not a claim that the
latest versions have been installed on the physical phone.

## Build and evidence

- Android project: Kotlin, Jetpack Compose, Coil 3, Media3, Android API 36, Gradle 8.13. Native application ID `nl.atlas.gallery`.
- [Android build and UI evidence](.github/workflows/build-apk.yml): compiles tests and APK on app changes; PRs additionally run emulator interaction tests with synthetic media and real validated PNG evidence. Source changes are not promoted simply because compilation passes.
- [V4 People UX checks](.github/workflows/v4-people-ux.yml): on-PR lightweight fake-identity behavior/undo, Discover candidate ranking, stable 2,000-group shuffle pagination and JavaScript syntax checks.
- [V4 Termux preview bundle](.github/workflows/v4-preview-bundle.yml): publishes a checksum/commit-manifested `ATLAS-V4-Preview` artifact. A merged script is **not** an automatic update of a running Termux process.
- [V5 evidence safety](.github/workflows/v5-evidence-safety.yml): synthetic local evidence-schema, snapshot/identity-boundary checks.
- [Dedicated signer-mismatch recovery](.github/workflows/signer-mismatch-recovery.yml): **emulator-only** fictional-data proof, not a phone-migration authorization.

Offline checks (no personal photographs required):

```sh
python3 scripts/verify_contract.py
python3 scripts/test_apk_upgrade_signing.py
python3 scripts/test_native_people_backup.py
python3 scripts/test_native_people_recovery.py
python3 tools/v4/test_group_discover_shuffle.py  # requires NumPy
```

To inspect currently green runs and artifacts, use the repository's
[GitHub Actions](https://github.com/LPStruiksma/atlas-native/actions).
Do **not** install a freshly built APK over v0.2 until signing and private-data
preservation are verified.

## Architecture, plans and on-device safety

- [Unified gallery integration blueprint](docs/ATLAS_UNIFIED_GALLERY_INTEGRATION.md) — the single experience across photos, People, B&W, Search and later AI.
- [Live engineering handoff](docs/ATLAS_LIVE_HANDOFF.md) — dated evidence; always reconstruct live main/PRs/CI instead of assuming the document is current.
- [Filmstrip benchmarking and reusable components](docs/GALLERY_FILMSTRIP_BENCHMARK_AND_REUSE.md) and [acceptance benchmarks](BENCHMARK_PLAN.md).
- [V4 preview and safe installation](tools/v4/README.md), [live native V4 sync](docs/NATIVE_V4_SYNC.md), and [signing/preflight](docs/STABLE_SIGNING.md).
- [Roadmap/flight plan](https://github.com/LPStruiksma/atlas-native/issues/12), [original B&W → reviewed People mission](https://github.com/LPStruiksma/atlas-native/issues/6), and [multilayer identity research](https://github.com/LPStruiksma/atlas-native/issues/10).

The [original foundation v0.2 README](docs/FOUNDATION_0_2_README.md) is
preserved for history, including obsolete repository-bootstrap instructions.
Do not follow those old `gh repo create` commands: this repository already exists.
Work on this gallery belongs in `atlas-native`, **never in `autodev`**.
