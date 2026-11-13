# ATLAS · One gallery, one experience

**Working integration blueprint · 14 October 2026.** Live GitHub `main`,
CI evidence, open issues and device state outrank this plan.

## The product we are actually making

ATLAS is **one fast, private Android photo and video gallery** whose
intelligent features enrich the *same* media catalogue:

> Find the pictures you remember — by album, size, time, person, or eventually
> description — and move effortlessly through them. Let AI suggest useful
> links, but never silently turn uncertain guesses into facts.

People, B&W, search, sorting, scrubbing and AI are not separate apps that ask
the user to export and re-find a photo each time. They are different *views*
over stable local image identity and reviewed evidence.

### End-to-end paths users should be able to follow

**Journey A: Find someone.** Type a name in Search → recognize the correct
person in a compact, clearly labeled **reviewed People** shortcut →
enter their existing People album → optionally show **B&W folder** only →
open a photo → scrub the full-screen filmstrip → Android Back returns
to the person and then Search with the previous query/location.

**Journey B: Explore the archive.** Open Photos → select an exact album or
All → sort by date/size or reproducibly reshuffle → open at *that exact*
position → rapid filmstrip swipe or main-photo swipe → zoom/pan/video →
Back returns to the prior grid position. The same photo must never change
identity merely because the sort mode changes.

**Journey C: Turn recognition into discovery.** V4 scans on-device and
makes *review-only* People proposals → the human confirms/rejects a face
under an explicit identity ID → V4 publishes checksum-sealed confirmed
face memberships → Native loads the last complete export transactionally →
the person becomes searchable in native Search and browsable in People.
Changing or interrupting an export must not erase last-known-good labels.

**Journey D (future): Identify a person from behind.** A tentative face,
body, clothing and event-session observation appears in Discover, with
evidence provenance ("same short session, similar jacket") and **Unknown**
as a first-class outcome. The person can be confirmed by the user but is
never auto-merged based only on clothing or context. The confirmed
membership then follows Journey C into Native.

## One canonical link across all features

```text
Android MediaStore (read-only photo/video URIs, stable gallery item)
      │                        └─ Native sort: date / size / seeded random
      ├─ Native Photos + filmstrip (Compose Pager + LazyRow, Coil, Media3)
      ├─ Native Search: filename / album / confirmed named People
      └─ Native People virtual albums / exact B&W folder filter
                                      ▲
                            app-private People SQLite
                                      ▲
                 complete checksum-sealed V4 CSV (SAF read-only link)
                                      ▲
               V4 confirmed face ↔ stable person ID ↔ exact photo path
                                      ▲
             local face suggestions + human review + reversible audit
                                      ▲
         future V5 time/session/body/clothing evidence (NOT ground truth)
```

**Rules that stop integration from becoming dangerous:**

- **MediaStore owns media discovery**, never V4. Gallery originals are
  never relocated, deleted or edited by inferred labels.
- **User-confirmed V4 labels own People identity** initially. Provisional
  groups and auto-matches are not independently authoritative. Identity
  IDs, not visible names, distinguish people with duplicate names.
- **Native owns interaction and cached views**, not new biometric
  classifications. A failed or partial V4 export cannot wipe the prior
  successfully imported People database.
- **Path reconciliation must be exact**, with explicit inaccessible/moved
  status. A filename match is not a valid identity join.
- **Inference stays local and provenance-tagged**. Future multimodal
  suggestions must be reviewable, undoable and able to abstain.
- Keep B&W **folder membership**, a location observation, separate from
  a future **AI monochrome-confidence classification**. Do not relabel
  folder photos as verified monochrome.

## Current state (verified source/CI, not phone installation)

| Layer | Exists in `main` | Still to prove |
| --- | --- | --- |
| Photos/media | MediaStore inventory, album selection, date/size/seeded-random sort, full-screen image/video pager with fast filmstrip | Repeated cold/warm real-S24+ latency and jank evidence |
| Viewer polish | Center-marker UI regression, cache-aware opt-in timing samples; isolated Telephoto + Coil3 test-only zoom spike | Huge-image tiling/gesture A/B before shipping Telephoto |
| Reviewed People | Private SQLite, one-time import, checksum-sealed live V4 CSV, person virtual albums, B&W folder filter | Complete real-device signing-safe upgrade from installed v0.2 |
| Search | Exact filename/album and confirmed person membership photo results | Explicit shortcut into the *same* person album (PR #48) |
| V4 intelligence | Reviewable local person profiles, conservative face auto-linking, read-only threshold frontier and unknown-person holdout | Calibrated novel-person/session false-link risk; no general unattended 25k backfill |
| V5 identity fusion | Design objective / open issue #10 | Versioned observations, body/session/clothing pilot and abstention UX |
| Release migration | Same-signer real emulator SQLite-preservation test, read-only probe and offline backup validator/exporter | Actual incompatible-signer synthetic Android restore gate (PR #47); real phone signing/backup/SAF permission plan |

**No row in this table implies a new version is installed on the user's S24+.**
PRs #47/#48 remain proposals until their own CI gates pass.

## Milestone sequence: cohesive vertical slices, not a feature grab bag

### I. Integrated native discovery demo (next)

Join **Search → named People → virtual album → B&W folder → filmstrip
→ Back**, on the existing 64-image synthetic emulator fixture. Preserve
duplicate names as separate IDs. Keep current MediaStore sorting and
last-known-good V4 sync unchanged. Add a navigation test that proves
the entire path rather than a screenshot of each disconnected screen.

**Gate:** one instrumented end-to-end synthetic demo plus native
unit tests; no personal gallery/media needed.

### II. Release the same experience safely

Finish test-only incompatible-signer restore proof. Check the installed
v0.2 public certificate, agree durable signing strategy and verify
complete **local** People/settings backup recovery where necessary.
A routine in-place APK upgrade is preferred if certificates match.
Any phone uninstall/migration requires informed owner consent.

**Gate:** proven signed upgrade or independently restorable, consented
migration; never ship an APK that strands the existing People archive.

### III. Smart, private discovery

Index approved local media descriptions/embeddings without uploading
faces/photos. Begin with *suggestions* ("concert", "by the water",
"standing behind the group") and explicit query/facet provenance,
not hallucinated identities. Benchmark competing open-source
embedders/ANN indices on anonymized fixtures for memory, latency and
power; make lazy indexing restartable, thermally bounded and cancelable.
The native Search page is the single surface for exact + future AI
results, clearly differentiating the two.

**Gate:** measurable top-k retrieval quality, honest unknown handling,
and bounded S24+ background load. No massive first-launch blocking scan.

### IV. Contextual People over many years

Introduce versioned observations for faces, people regions, body
proportions, hair/clothing and short photo sessions. Compare
face-only reference against face+session and face+body ranking on
a small *owner-reviewed local* holdout. Avoid correlated confidence
inflation ("same outfit, same place, same minute" is one session,
not three independent confirmations). Display why evidence matters.

**Gate:** demonstrably better coverage at an acceptable wrong-person
rate and explicit Unknown/abstention; no hidden cross-year identity merges.

## Quality bar

- **Correctness:** zero silent media mutations, no lost confirmed
  People identity from transient errors, stable IDs, correct Back
  and selection after resort/sync, preserved last successful imports.
- **Responsiveness:** cold/warm p50/p95/p99 selection→preview,
  frame jank, memory/bitmap budget and pager/filmstrip alignment.
  An emulator is a *functional* gate, not a Samsung performance verdict.
- **Human experience:** one obvious route from any result to the photo;
  fast reversible browsing; suggestions labeled as such; no duplicate
  Settings/People/search state or forced repeated import ceremonies.
- **CI economics:** synthetic quick JVM tests first, emulator for
  changes affecting actual Android behavior, separately scoped expensive
  signer-mismatch gate. Do not poll or rerun failing hosted emulators
  blindly when image downloads/KVM are unavailable.
- **Privacy:** no real face embeddings, names, files, signer keys
  or app-private databases in GitHub artifacts, tests or telemetry.

## Immediate autonomous next steps

1. Finish and validate #48 as the first deliberately **joined**
   native Search→People vertical slice.
2. Diagnose #47 only from actual emulator run evidence; CI may be
   blocked by unavailable KVM or corrupted SDK downloads, not app logic.
3. Add one **journey-level** synthetic UI test for returning through
   B&W People album to exact Search state once #48 lands.
4. After release-safety gates, carry out physical-device benchmark A/B
   and evaluate whether Telephoto's open-source subsampling genuinely
   beats the current viewer.
5. Begin V5 as bounded, reversible, research-mode evidence attached to
   existing images/person IDs, not as a parallel photo app.

The goal is not the most features: it is the most coherent, delightful
*trustworthy* gallery for a huge, personal, decades-spanning archive.
