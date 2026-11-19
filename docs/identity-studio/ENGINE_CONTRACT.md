# Identity Studio engine contract

Unit IDS-6 ([#471](https://github.com/LPStruiksma/atlas-gallery/issues/471)) of goal [#467](https://github.com/LPStruiksma/atlas-gallery/issues/467). Code: `app/src/main/java/nl/atlas/gallery/featureidentity/engine/`. Tests: `IdentityCandidateSessionTest`.

Every later Identity Studio unit builds on this contract: the local ONNX engine (IDS-9) and the full-head engine (IDS-12) implement it, and the studio UI (IDS-8) and save flow (IDS-10) drive a session and read its candidates. Until a real engine lands, they use `FakeIdentityEditEngine`.

## Why a separate contract

`ImageEditProvider` (AI Edit) is an instruction-edit contract: a text instruction, an optional mask, and a poll-based job. An identity edit needs different inputs (a target face with landmarks, a person's reference set, a face or whole-head mode) and a different lifecycle (one loaded model reused across many candidates). Forcing it into the instruction contract would hide what matters. So the identity engine has its own small interface and reuses what fits, unchanged: `EditImage` for every image and `CostEstimate` for cost, both from `featureaiedit.provider`. Nothing in `featureaiedit/**` is edited.

## Request

`IdentityEditRequest` is one candidate's worth of work:

| Field | Meaning |
|---|---|
| `source` | The photo, as an `EditImage` handle (pixels only for synthetic tests). |
| `target` | `TargetFace`: the face box in source pixels plus 5 landmarks (eyes, nose tip, mouth corners). |
| `references` | `IdentityReferenceSet`: profile id, chosen appearance variant, and the reference image handles chosen for this candidate. Never face templates or embeddings. |
| `mode` | `FACE` (inner face) or `HEAD` (also hair, hairline, head shape, ears, facial hair). |
| `preferences` | Keep original hair or use the person's hair, keep expression, more natural. |
| `variation` | Seed, the indices of the chosen references, and blend strength in (0, 1]. |

## Capabilities

`IdentityEngineCapabilities` declares supported modes, maximum references per candidate, whether one load serves many candidates (`reusesWarmState`), the route (`LOCAL`, `SELF_HOSTED`, `PAID`), cost per candidate, whether it makes network calls, and the largest source it accepts. The constructor enforces that a `LOCAL` engine makes no network calls and costs nothing per candidate.

## Engine lifecycle

1. `IdentityEditEngine.load()` loads model state and returns a `WarmIdentityEngine`, or `Unavailable(kind)` (for example not installed).
2. `WarmIdentityEngine.generate(request, workspace, cancellation)` makes one candidate. All files it writes go inside `workspace`, a folder the session creates and owns. It checks `cancellation` between steps and may throw `IdentityCancelledException` or `OutOfMemoryError`.
3. It returns `Success(image, file, fingerprint)` or `Failure(kind)`. The fingerprint is an 8x8 luma summary of the edit region, so the session can compare candidates without decoding files.
4. `close()` releases model state.

## Candidate session

`IdentityCandidateSession.open(engine, input, workspaceRoot, approvedRoute)` starts a session for one photo, face and person.

- **Batches.** `generate(3 or 4)` makes the first batch; `more(1..4)` adds more, up to 16 per session. Candidates arrive one by one through `onCandidate`.
- **Variation.** `IdentityVariationPolicy` plans each attempt: the curator's best references first, then a rotating subset when there are more references than the engine takes, a fresh seed per attempt (SplitMix64 of the session seed plus the attempt number), and a blend strength from a fixed ladder (0.85, 0.7, 1.0, 0.6, 0.95, 0.75). Attempt numbers are session-wide, so "More" never repeats a plan.
- **Near-duplicates.** A result whose fingerprint distance to any kept candidate is below 0.03 (mean absolute luma difference as a fraction of 255) is discarded and the next plan is tried. Each run has a budget of 3 attempts per requested candidate, so it never loops; it may then complete with fewer candidates and reports how many were rejected.
- **Warm reuse.** The engine loads once and serves every candidate and every "More". The host calls `releaseIfIdle()` on a timer; it releases after 90 s without use by default. An engine that declares `reusesWarmState = false` is loaded and released per candidate. Out-of-memory also releases it.
- **Cancel.** `cancel()` (any thread) stops the running candidate at the engine's next check and deletes that candidate's folder. Finished candidates stay so the user can still pick one. `close()` cancels, releases the engine and deletes the whole session folder.
- **Cleanup.** Each attempt has its own folder `session-<uuid>/attempt-<n>/`. Failure, cancel, a rejected duplicate, or an output file outside the folder deletes it. Deletion never follows symbolic links. `open` first sweeps `session-<uuid>` folders under the root that no live session in this process owns: leftovers from a crash, kill or restart. Other names under the root are never touched.
- **Route.** The session runs an engine only when its declared route equals the approved route, checked before loading and before every candidate. There is no fallback to another engine. `switchEngine(engine, newApprovedRoute)` is the only way to change route, and the UI must only call it after the user chose that route.
- **Problems.** Every failure becomes an `IdentityProblem` with a plain sentence and a next action, without technical words:

| Kind | Message | Next action |
|---|---|---|
| NO_FACE_FOUND | We couldn't find a face there. | Tap the face you want to change, or choose another photo. |
| FACE_TOO_SMALL | That face is too small to change well. | Choose a photo where the face is bigger. |
| ENGINE_NOT_INSTALLED | Swap person isn't set up on this phone yet. | Finish setting it up, then try again. |
| OUT_OF_MEMORY | Your phone ran out of room to finish this. | Close other apps and try again. |
| PHOTO_TOO_LARGE | This photo is too large to change on this phone. | Try a smaller copy of the photo. |
| MODE_UNAVAILABLE | Changing the whole head (or just the face) isn't available yet. | Try the other mode. |
| NO_REFERENCES | We need a few photos of this person first. | Add photos of this person, then try again. |
| ROUTE_NOT_APPROVED | This would use a different service than the one you chose. | Check your Swap person settings. |
| UNKNOWN | Something went wrong while making this. | Try again. |

Each `IdentityCandidate` carries what the save unit needs for lineage: engine id and version, route, mode, the variation (seed, references, blend) and the session id.

## Fake engine

`FakeIdentityEditEngine` is deterministic: the same request always gives the same pixels. It copies every pixel outside the edit region unchanged (the face box, or the box grown 1.6x for `HEAD`) and blends an 8x8 grid of synthetic colours inside it at the planned strength. It writes intermediate `.partial` files and a real PNG (`candidate.png`) so cleanup and decode paths are exercised. Config knobs simulate not installed, other routes, mode limits, failures on a given call, out-of-memory, duplicates and a per-step hook for cancel tests. It has no model, network, clock or randomness.

## What is proven

`./gradlew :app:testDebugUnitTest --tests 'nl.atlas.gallery.featureidentity.engine.*'` covers: candidate count and variation; duplicate rejection and its bounded retries; cancel mid-candidate; cleanup after failure, out-of-memory and an unexpected exception; the orphan sweep; warm reuse (one load for N candidates and "More"), idle release and reload; no silent route change (other route refused without loading, mid-session change refused, explicit switch allowed); the local capability declaring no network and no cost; a source scan that the engine package uses no network APIs; plain-language problems; and that the fake leaves pixels outside the region bit-identical.

Not covered here: real inference quality, timing and memory on a device. Those belong to IDS-2, IDS-9 and IDS-12.
