# IDS-10 Codex task: identity edit save (#476)

Goal: https://github.com/LPStruiksma/atlas-gallery/issues/467
Unit: https://github.com/LPStruiksma/atlas-gallery/issues/476

Implement `IdentityEditSave` by reusing `AiEditSaver` and `AiEditLineageStore`, not a second saver. Save the chosen candidate as a new `<stem>_AI_<n>` MediaStore photo, never mutate the original. Persist a backward-compatible nullable, versioned local identity lineage block with `face`/`head` kind, engine id/version, local/self-hosted/paid route, profile id/variant id (no display name), candidate index/seed and refinement parent. Deleted profiles leave saved lineage intact and display as deleted profile; existing edited-copy provenance reads must still work. For identity output only, `ImageDescription` identifies an ATLAS AI edit and `Software` stays `ATLAS AI Edit`. Store no face data, embeddings, profile names or credentials in EXIF.

Required unit files: `app/src/main/java/nl/atlas/gallery/featureidentity/save/IdentityEditSave.kt`, `app/src/main/java/nl/atlas/gallery/featureaiedit/save/AiEditLineageStore.kt`, `app/src/test/java/nl/atlas/gallery/featureidentity/save/IdentityEditSaveTest.kt`, `app/src/androidTest/java/nl/atlas/gallery/featureidentity/save/IdentityEditSaveAndroidTest.kt`, `docs/ai-edit/SAVE_POLICY.md`. Minimal additive changes to `featureaiedit/save/AiEditModels.kt` and `AiEditSaver.kt` are acceptable if necessary to pass new provenance through its single journal/publish transaction; preserve old callers. No changes to `featureaiedit/provider/**` or `featureaiedit/network/**`.

Tests: `./gradlew :app:testDebugUnitTest --tests 'nl.atlas.gallery.featureidentity.save.*'` and emulator synthetic `FakeIdentityEditEngine` candidate save: unchanged source bytes, published new row, identity lineage present, EXIF privacy, migration from previous schema. Treat any unavailable Android tooling as unverified; report exact failures. Never access `eval/held-out/**`.

This document is a staged task input. Codex's patch should replace it with actual implementation and tests; it is not a completed feature.
