# IDS-5 Codex task brief

Issue: https://github.com/LPStruiksma/atlas-gallery/issues/473

Implement the automatic reference curator from the issue, in:
- `app/src/main/java/nl/atlas/gallery/featureidentity/curate/ReferenceCurator.kt`
- `app/src/main/java/nl/atlas/gallery/featureidentity/curate/FacePoseEstimate.kt`
- `app/src/test/java/nl/atlas/gallery/featureidentity/curate/ReferenceCuratorTest.kt`
- `docs/identity-studio/CURATION.md`

Pure offline deterministic selection; no writes to People, network, UI, or new model. Candidates include face geometry, YuNet landmarks, available quality fields (null-safe), capture date, similarity to profile centre. Use explicit pose, expression and lighting diversity and a similarity floor. Include outlier report, recency and optional date/age window, deterministic tie-breaking and tests for empty/fewer-than-k inputs. Read the issue's full acceptance criteria. Code must compile under existing Gradle dependencies and tests must run in exact-head CI. No production personal imagery or weights. This task brief is only the carrier for a single Codex Cloud (Legacy) patch request; it is not evidence of implemented functionality.
