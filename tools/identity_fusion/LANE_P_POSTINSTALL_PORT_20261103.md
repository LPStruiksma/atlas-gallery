# Lane P — native face engine reintroduced on post-install v5 source (3 Nov 2026)

## Status and boundaries

This isolated branch imports *existing*, previously reviewed Lane P source from
`atlas/lane-p-face-discovery-contract-20261022` onto the **actual owner-installed
RC2-derived** post-install source baseline `a55b716ce3418c8203542c8230fc9d95e587df9f`.
It does not change host screens, build settings, manifest, system permissions,
current owner's phone, V4 data, PeopleRepository, or install a new scanner.

The imported production pieces provide: explicit opt-in and battery/storage/
grant conditions; versioned revision hashing; 150k-scale permission-scoped
URI lookup; bounded read and image downsample; 1-slot inference admission and
long-task pixel-lifetime ownership; a single-photo cancellable scan contract;
and capped batch scheduling. Observations are **Unknown** until explicit human
review; detector output is never a confirmed person's identity. Old Lane P
is retained for traceability, not merged wholesale because its optional X/ML
Kit imports cannot compile without coordinated integration.

## New correctness change

`FaceScanEngine.handleDecodeFailure` now respects a rejected X index-failure
transaction (e.g. old permission epoch, edited source, superseded lease),
returning `RELEASED_STALE` rather than falsely saying the photo failed and
should be retried. A fictional-index regression was added to the existing
`FaceScanEngineSyntheticTest.kt`.

`FaceVisibleMedia` is now an independently compilable read-only metadata DTO,
formerly nested in X-dependent `FaceCatalogPublisher.kt`, so the M->P
adapter and hashed URI directory are usable *without* prematurely introducing
X SQLite source into the release baseline. Do not duplicate this DTO when
porting the remaining publisher.

## Lane I + X + U integration handoff

1. **X**: provide current app-private `FaceIndexStore` adapter for grant-scoped
   staging, processing leases, actual detector version and reviewed Unknown rows.
   Keep V4 confirmed-label importer separate. Reconcile the old Lane P
   `FaceCatalogPublisher.kt`, `NativeFaceIndexPort.kt` and
   `FaceUnknownInboxBridge.kt` with **current X APIs**, moving the
   `FaceVisibleMedia` declaration out of the publisher to avoid collision.
2. **I**: under an explicit user action and one dedicated IO worker, wire the
   index, reader and detector. Add the **bundled** `com.google.mlkit:face-detection:16.1.7`
   dependency *if approved on host integration*, and port the already drafted
   `BundledMlKitFaceDetector.kt`; don't use thin network-downloaded models.
   Recheck current MediaStore grant/provider scope at dispatch and publication.
3. **U**: accept only freshly authorized Unknown pages, keep preview URIs
   ephemeral and recheck action-time scope. No auto identity naming/merge.
4. **Quality**: execute exact-head Android/ML Kit compilation, emulator
   opt-in/revoke/regrant/edit tests, actual face-detection on fictional or
   licensed sample images and performance at ~150k. **Not yet validated here.**

## Portable evidence

The existing source-only Kotlin fakes and tests have been carried forward
unmodified except for the new stale-failure admission regression.
`tools/identity_fusion/android_fake_probe/run.sh` tests the actual Kotlin
Android adapter sources against fake Android classes and is *not* proof of
Android runtime behavior. Avoid claiming phone/ML Kit test coverage.

## Follow-up: exact-once release during in-frame cancellation

A deterministic *fail-first* host regression proved that if the source revision
provider throws cancellation on the **second** lookup inside a decoded frame,
`leaseCurrent()` releases the lease and the surrounding catch releases it again
(two releases for one claim). The in-frame source check now calls the pure
`stillCurrent()`, leaving the surrounding cancellation catch as the **sole**
release owner for that stage. Other checks retain their own release policy.
The synthetic test now explicitly checks one release, closed frame, zero
publication and propagated cancellation. No Android decoder/real media was used.
