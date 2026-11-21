# ATLAS vision, capability map and roadmap

Owner direction from Lukas, 2026-11-14 ("ATLAS — Expand the Intelligence Vision and Integrate Two New Features", plus the atlas-gallery migration addendum). This page records the long-range product vision and where each part stands. It does **not** replace [`OWNER_INTENT.md`](../OWNER_INTENT.md), which still wins on how ATLAS looks and behaves, or the [Samsung parity spec](SAMSUNG_PARITY_SPEC.md). Live state (who owns what, open PRs) is in GitHub issues, not here. Last checked against `main` a9ec2511.

## Two ambitions, one app

1. **Foundation:** a faithful, fast, polished Samsung Gallery-class replacement (OWNER_INTENT).
2. **Differentiator:** a private, on-device, increasingly intelligent archive. It understands what is depicted, who appears, where and when, and how memories connect.

They don't compete. Intelligence lives where Samsung would put it: Search, People, Stories, Albums, the viewer and its menus. It never adds chrome to core screens.

**Final principle.** ATLAS is an extraordinary gallery and more than a gallery. It is a familiar interface to a private, growing understanding of a lifetime of photos and videos, so the owner can rediscover the people, places, experiences and stories in them.

## Standing rules for every intelligence feature

- **Background intelligence stays private and on-device.** Automatic indexing, scanning, face/People analysis, embeddings, visual and natural-language search, Photo DNA, Stories and future background analysis run locally: no automatic photo uploads, no external inference APIs, and on-device models for those features. Configuring AI Edit never changes this boundary.
- **Explicitly user-started AI Edit is a scoped exception.** After the user chooses AI Edit, selects a provider and presses Generate, the app may send **only the selected photo, prompt and optional user-chosen mask/references** to that provider for generative editing. Other library photos, People, embeddings, index and search data never accompany the request; GPS/EXIF location is stripped by default. Give a short one-time explanation at first remote use, not a per-edit consent ceremony. Never silently switch providers or upload in the background. See [AI Edit architecture](../ai-edit/ARCHITECTURE.md).
- **Public-repository hygiene remains absolute.** This repository is public: no personal photos, videos, face embeddings, People databases, private evaluation material, credentials or signing keys go into commits, fixtures, CI logs or artifacts. Development uses synthetic or licensed data. Measurements on the owner's own library report aggregates only.
- **No content censorship.** It is the owner's private library. Index and retrieve all lawful, locally accessible content, whatever the subject (intimate, adult, political, violent, unconventional). Don't add category exclusions, moralised query filters or silent result suppression. Android access controls and private or hidden album boundaries still apply.
- **Evidence over claims.** Every retrieval or identity feature reports precision, recall and ranking quality on positive and hard-negative sets. Don't claim human-level understanding. Confirmed facts, inferences and hypotheses stay distinguishable.
- **Reuse, don't fork.** One scanner, one visual index (photos plus timestamped video keyframes), one People review system, versioned evidence and incremental updates.
- **Corrections teach.** A user correction becomes durable evidence: a constraint, a label, a feedback row.
- **Cost-efficient CI.** Develop on JVM, static and synthetic tests. Emulator runs happen at review on a stable candidate head (the `next:review` gate), once per candidate. Don't add paid runners or extra emulator matrices.

## Capability map

Status: **Shipped** (on main, tested, in a release) · **Partial** (works but incomplete or not fully integrated) · **Active** (open unit with an owner) · **Planned** (issue or goal exists) · **Missing** (not started) · **Research** (aspirational, feasibility unknown).

| Area | Status | What exists | Gap |
|---|---|---|---|
| Samsung grid, timeline, albums, viewer, sort, selection, settings | Shipped / Partial | Zoom ladder, sort sheets, floating pill, details sheet, video chrome, Ultra HDR photos shown in HDR while focused in the viewer (API 34+, gain map; #572, on-phone look unverified), Settings, signed releases via Obtainium | Parity round 3 items (#12, #16, #20, #23, #24) on hold; Settings Back (#79) |
| Stability and persistence | Partial | OOM fixes (#44, #74), WAL (#53), restore after relaunch (#60), Photo DNA guards, Last crash | #33, #41, #75 flash, #82 flaky emulator tests |
| Face detection and grouping | Shipped | YuNet + SFace int8 on-device, charging-only scanner, conservative clustering, auto-add, `people_matched`, names survive model changes (#48), names backup (#62) | Phase 1 gate (top 20 people each one group) not yet met, #51 |
| People review | Shipped | Name, assign, merge, "Same person?" queue, manual `SplitPerson` / `SplitUnknownCluster` by face selection, cannot-link and rejection facts, Undo | **Assisted split proposals: Missing** (new goal) |
| Whole-person identity (body, attire, context) | Research | `EvidenceLayer` FACE/BODY/ATTIRE/CONTEXT slots in `SamePersonHypotheses`; re-ID studies in `tools/identity_fusion` | No body or attire model on device |
| Face search | Shipped | Find a person by a reference face (`featuresearch/face`), including video faces | Large age gaps (SFace limit) |
| Visual "find similar" | Shipped | SigLIP 2 B/16 image-tower index, video keyframe moments, BlazePose "emphasise pose" rerank | — |
| **Natural-language scene search** | **Missing** | The index is in SigLIP 2's shared image-text space, but **only the image tower ships**. No text encoder, so typing a description finds nothing yet | Goal [#94](https://github.com/LPStruiksma/atlas-gallery/issues/94): text tower, relational rerank, Dutch and English, video moments, feedback |
| Faceted search | Shipped | Date, people names, folders, suggestions | Combine with descriptive search |
| Things / automatic tags | Partial | 159-tag vocabulary encoded once on the host with the shipped text tower, thresholds calibrated on licensed Open Images (117 on by default, median precision 0.80, recall 0.62), `featurevisual/tags` scorer and `assets/models/siglip2_tags` (TAGS-0 [#667](https://github.com/LPStruiksma/atlas-gallery/issues/667), [`TAGS_0.md`](../visual-search/TAGS_0.md)) ; TAGS-1 app-private tag store, scan hook and charging-only backfill sweep ([#732](https://github.com/LPStruiksma/atlas-gallery/issues/732), `featurevisual/tags/store`) | Not shown yet: TAGS-2 Search "Things" UI (design gate); receipts uncalibrated |
| Photo DNA | Partial | Exact byte groups, perceptual near-duplicates, manual trash review | Crops, edits and exposures lineage, conservative keeper suggestions (`tools/photo_lineage` research) |
| **AI Edit (user-initiated generative editing)** | **In build** | Approved product direction [#183](https://github.com/LPStruiksma/atlas-gallery/issues/183) and [architecture](../ai-edit/ARCHITECTURE.md); the editor runs Google Gemini and fal.ai with your own key ([#431](https://github.com/LPStruiksma/atlas-gallery/issues/431)); ordinary system Edit remains | First edit on the phone with a real key, model picker in Settings, custom server in the editor. **Not yet confirmed on a phone** |
| Stories | Partial | On this day, month recap, favourites, inline video, opens over Albums | Event and trip detection, people-themed Stories |
| Places and maps | Missing | GPS shown in viewer details only | Map, journeys, residences |
| Life Graph / Memory Atlas | Research | — | See vision B |
| Family Archive link | Research | Separate tenant (`family-archive`) | See vision F |

## Long-range vision (kept explicit so it doesn't get lost)

These are product ambitions, not blockers for the next release. Each has a backlog issue labelled `vision`: A [#98](https://github.com/LPStruiksma/atlas-gallery/issues/98), B [#99](https://github.com/LPStruiksma/atlas-gallery/issues/99), C [#100](https://github.com/LPStruiksma/atlas-gallery/issues/100), D [#101](https://github.com/LPStruiksma/atlas-gallery/issues/101), E [#102](https://github.com/LPStruiksma/atlas-gallery/issues/102), F [#103](https://github.com/LPStruiksma/atlas-gallery/issues/103). Live goals: daily driver [#97](https://github.com/LPStruiksma/atlas-gallery/issues/97), People Intelligence [#95](https://github.com/LPStruiksma/atlas-gallery/issues/95), assisted split [#96](https://github.com/LPStruiksma/atlas-gallery/issues/96), natural-language search [#94](https://github.com/LPStruiksma/atlas-gallery/issues/94).

- **A. Whole-person intelligence across a lifetime.** A six-layer identity model: face, physical appearance, clothing, pose and behaviour, context and social evidence, and longitudinal learning. The goal is to find every moment a person appears, not only where their face is visible: decades apart, partly hidden, from behind, in video. Builds on People Intelligence and the evidence-layer slots.
- **B. Personal Memory Atlas / Life Graph.** An evidence-backed graph of people and relationships, places and journeys, periods and life stages, events, and the media that show them. You could explore a map of your life, or open a person and see their events, places and decades. Confirmed, inferred and uncertain links stay distinguishable.
- **C. Intelligent events, Stories and rediscovery.** Detect trips, gatherings and meaningful periods from time, place, visual similarity and participants. Build Stories, event collections and timelines. Never invent memories or present speculation as fact.
- **D. Semantic understanding of the whole archive.** Search by meaning: activities, compositions, poses, a person in a setting or beside someone else. Natural-language search, People, visual search, Stories, Places and the Life Graph converge into one experience.
- **E. Photo DNA as a connected collection.** Exact and near duplicates, crops, alternate edits, exposures, sequences, explainable lineage and conservative keeper suggestions. Provenance never gets confused with semantic similarity.
- **F. Personal geography, history and Family Archive.** Maps of journeys and residences, historical timelines, and links to relatives, family history and genealogical records. Research until data sources, feasibility and UX are established.

## Roadmap (evolutionary; each step is usable on its own)

| Milestone | Delivers | Depends on |
|---|---|---|
| M0 Daily driver | Stability P0s closed, parity round 3, People restore and grouping reliable | Active work |
| M1 Assisted split | "Split person" proposes 2+ identities with previews; accept, adjust or reject; transactional, undoable, durable constraints | People review system; SPLIT-0 ([#105](https://github.com/LPStruiksma/atlas-gallery/issues/105)) |
| M2 Words to photos | Text tower in the existing index; English and Dutch queries; results grid; video moments open at the timestamp | Feasibility unit NLS-0 ([#104](https://github.com/LPStruiksma/atlas-gallery/issues/104)) |
| M3 Relational search | Bounded rerank with counts, people, pose and regions; hard-negative benchmark; combine with person, date, album; result feedback | M2 |
| M4 Whole-person evidence | First non-face layer (attire or body, same-session only), with measured false-merge rate | People Phase 1 gate |
| M5 Events and Places | Event and trip detection, map view, people-themed Stories | M3, location data |
| M6 Life Graph | Evidence-backed graph explorer across people, places, periods and events | M4, M5 |
| **M-AI User-started AI Edit (independent track)** | Select a photo, instruct a configured hosted/self-hosted editor, preview and accept a new copy; eventual optional local model | [#183](https://github.com/LPStruiksma/atlas-gallery/issues/183) feasibility and design; provider/capability contract; tested non-destructive save flow; protected manifest/network gate [#191](https://github.com/LPStruiksma/atlas-gallery/issues/191) with Lukas's merge; no paid experiments or personal uploads in development without approval |

AI Edit's scoped cloud exception follows Lukas's 2026-11-15 clarification in [#183](https://github.com/LPStruiksma/atlas-gallery/issues/183); see [architecture](../ai-edit/ARCHITECTURE.md). The local-only baseline continues to govern indexing, People and all background intelligence.

Owner source messages: Lukas, 2026-11-14 19:48Z and 19:52Z (project chat). Earlier goals in the archived `atlas-native` repo (#402, #468, #509, #579, #580) are context only. Their live successors are linked from the goal issues in this repository.
