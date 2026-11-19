# Identity profiles (IDS-4, #472)

Goal: [#467 Identity Studio](https://github.com/LPStruiksma/atlas-gallery/issues/467). Unit: [#472](https://github.com/LPStruiksma/atlas-gallery/issues/472).

An identity profile is the person the studio can put into a photo. It is the
studio's own record and lives next to People, never inside it.

## What a profile holds

| Part | Meaning |
| --- | --- |
| Profile | Local id, plus **either** a link to an ATLAS People person (its opaque person key) **or** a display label for a hand-made profile. A People profile has no label: its name is read from People each time it is shown, so it cannot drift. One profile per People person. |
| Appearance variant | One look of the person, for example "Current", "Younger", "Short hair", "Glasses". Each variant is one identity with an ordered list of references. Every profile starts with "Current". Names are unique per profile, ignoring case. At most 16. |
| Reference | One face in one photo: the canonical media key and source revision (the same `FaceMediaKey` pair People uses), the face box (normalised 0..1), optional landmarks (normalised, stored as text), its origin, and, for a People face, the native face uid. At most 64 per variant. |

Origins:

- `people_suggested`: ATLAS proposed one of the linked person's People faces and the user kept it.
- `user_added`: the user picked a face themselves (from People or any photo with a detected face).
- `manual`: a hand-picked photo outside People; no People face uid.

## Where it is stored

- Rows: `noBackupFilesDir/atlas_identity_profiles.db` (`IdentityProfileStore`), schema v1. The app has `allowBackup="false"` and the file sits in the no-backup folder, so it is never copied to a cloud backup or a device transfer. Unknown schema versions are refused, never wiped.
- Derived data (aligned crops, local identity features, written by later units): `noBackupFilesDir/identity_profiles/<profile id>/<reference id>/`, or `.../<profile id>/_profile/` for data about the whole profile. File names cannot leave that folder.
- Nothing is written to `atlas_face_discovery.db`, `atlas_native_people.db` or any People table, and nothing goes into EXIF, logs, GitHub or CI artifacts.

## Reading People (read-only)

`PeopleReferenceSource` reads a person's faces through the existing read APIs of `FaceIndexStore` (`activeEpoch`, `peopleReview`, `faceLocation`, `reviewObservationState`), behind the active permission epoch:

- confirmed faces first, then faces the engine added on its own (#580), each in face-uid order;
- rejected and taken-out faces are excluded ("not this person" facts win over a stale row), as are faces marked "not a face" and video keyframes;
- when People is revoked, superseded or not yet published, or the epoch moves during the read, it reports `Unavailable` rather than a partial list.

The source only holds the read interface (`PeopleFaceReads`); it has no write path, and the emulator test proves a content checksum of every People table is unchanged across reading, every profile operation and delete.

## Staleness

`PeopleReferenceSource.check(profile, mediaProbe)` checks each reference against its source; `IdentityProfileStore.applyChecks` writes only the stale results. A stale reference stays where it was, keeps its face, and is not used (`usable == false`) until the user removes it. It is never silently swapped for another face.

| Reason | When |
| --- | --- |
| `face_gone` | People no longer has that face (the photo changed and was rescanned, or the face was dropped). |
| `media_revised` | The face is at a different revision than the reference recorded. |
| `left_person` | A People suggestion that the user has since taken out of, or rejected for, the linked person. A face the user picked themselves does not depend on People membership. |
| `media_gone` | A manual reference whose photo the media probe reports gone. |

A result the store cannot check now (People revoked, media outside the grant) is `Unknown` and changes nothing. The host supplies the `ReferenceMediaProbe` for manual photos from its media grant.

## Deleting

- `removeReference` and `removeVariant` delete the rows and that reference's derived folder. The last variant of a profile stays; delete the profile instead.
- `deleteProfile` deletes the profile, its variants and references in one transaction, then its derived folder, and returns what it removed and what is left (`complete` is true only with zero rows and zero files left).
- `deleteAll` removes every profile and the whole derived folder.
- `sweepOrphanedDerivedData` removes derived folders whose profile no longer exists (a delete interrupted between the rows and the files). Hosts call it when the studio opens.

## Proof

- JVM: `./gradlew :app:testDebugUnitTest --tests 'nl.atlas.gallery.featureidentity.profile.*'` (models, People selection rules, staleness, source over a fake read-only People).
- Emulator (at `next:review`): `IdentityProfileStoreAndroidTest`, with People built from synthetic one-face photos (no pixels): create from People, add, reorder and remove references, variants, taken-out and revised faces marked stale in place, delete leaves no rows or files, and the People content checksum is unchanged.

## Not in this unit

No UI, no choice of which references are best (the curator, #473), no engine, no cloud backup.
