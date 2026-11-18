# PEOPLE_REVIEW_FLOW — reference-first Unknown faces study

**DESIGN-STUDY-1 · 15 November 2026 · design only.** Owner request: [#265](https://github.com/LPStruiksma/atlas-gallery/issues/265); design goal: [#120](https://github.com/LPStruiksma/atlas-gallery/issues/120). This is a build specification, **not** a screenshot sign-off, benchmark of ATLAS on a phone, or a change to face-matching thresholds.

## Decision and hard constraints

**Choose: face-first, name-in-context, three (at most) evidence-ranked candidate people, and a separate on-demand search.** Never render the reviewed-person catalogue as a suggestion feed. Keep the group's own faces visible while the user types, chooses a destination, or corrects a mistake.

The owner's non-negotiables:

- **R1 — faces first and persistent.** The reviewed group's own portraits occupy the first content area. At least the representative portrait and a small horizontal face strip stay in view while naming/assigning, including with the keyboard raised. A larger grid opens below or in a sheet without replacing that context.
- **R2 — few ranked suggestions with faces.** At most three suggestions are shown for this particular group, each containing a representative face, name, and confirmation/rejection actions. Order by a documented, validated similarity signal; never alphabetical/random as a substitute. If no defensible score is available, show **zero suggestions** and an explicit search affordance instead. Search results are not suggestions, must be query-filtered, and must have a face preview. No pagination in either default state.

These constraints deliberately outrank a literal Samsung clone: the Samsung archive documents useful primitives, not an observed current screen layout. No private photos/screenshots, Samsung decompiled code, app assets, private identifiers, or identities are part of this document.

## Why the current screen fails

The source implementation on main at the start of the study ([UnknownFacesReviewScreen.kt](../../../app/src/main/java/nl/atlas/gallery/featurepeopleui/UnknownFacesReviewScreen.kt) and [PeopleDestinationSelector.kt](../../../app/src/main/java/nl/atlas/gallery/featurepeopleui/PeopleDestinationSelector.kt)) is reproducibly ordered as follows:

1. In each Unknown group card, a possible-match explanation precedes a new-name text field; then a destination selector appears; **only then** do the group's own first 20 face rows appear (UnknownFacesReviewScreen around lines 345–412).
2. The destination selector searches reviewed people by name but defaults to **all** reviewed people; it displays six per page, each tagged with an ordinal and a page counter (PeopleDestinationSelector around lines 83–149). Its order is the provided catalogue, **not proven similarity ranking**. These are choices, not machine-ranked matches.
3. The parent screen mixes Unknown groups, reviewed albums and face-detection review candidates in one long vertical feed. Long warnings and internal terms precede the main task. Its null-queue fallback is the persistent, unhelpful message separately tracked in [#260](https://github.com/LPStruiksma/atlas-gallery/issues/260).
4. The existing one-off group suggestion does show a reviewed face and reject control, but that does **not** make the full selector a ranked suggestion list.

**Evidence status:** source-order verification, and the owner's reported phone observation in #265; no actual owner photo or private capture was inspected. Preserve the good parts: explicit destination selection, neutral thumbnails when access is unavailable, stale-snapshot gating, durable decisions and existing Undo.

## Reference table

**Reading the table.** “Not stated” means the cited public documentation does not establish that behaviour, not that the app lacks it. Counts and scoring are **not** inferred from visual screenshots. “Face/name” describes where the face context is available relative to the naming action, not a claim that the virtual keyboard always keeps it visible. Apple and other products can vary by OS/version.

| Product / source | Face context relative to naming | Suggested choices: count and order | Merge / split / “not this person” | Undo / recovery |
|---|---|---|---|---|
| [Google Photos](https://support.google.com/photos/answer/6128838?hl=en) | Enter a face group first; its photos are the context for **Add a name** at the top | Name suggestions when labeling the second group; separate **Same / Different / Not sure** pair proposal. Max candidate count and score/order **not stated** | Same label can prompt group merge; wrong photos removed with **Remove results**; explicit group split is not documented | **Merged groups cannot be unmerged** per Google; removing a photo does not delete the original |
| [Apple Photos](https://support.apple.com/en-gb/108795) and [Mac review help](https://support.apple.com/en-asia/guide/photos/phtad9d981ab/mac) | Tap a face/person thumbnail, view its photo collection, then **Name This Person**; Mac **Review More Photos** proceeds from that person | Name entry may use Contacts name suggestions, not ranked look-alikes; Mac review uses **Yes / Not** per proposed photo. No public ranked-candidate count | Merge duplicate People groups; selected wrong photos can be marked **This is Not [person]**; no dedicated arbitrary split flow documented in these guides | Merge undo not established by cited help; wrong-photo correction is available |
| [Immich](https://docs.immich.app/features/facial-recognition/) | Explore grid of people → person detail with photos → name/manage from person context | Face clustering uses nearest-neighbour similarity; public help does **not** give a UI candidate-count/order promise | Person-detail app-bar action merges people; can hide people; no explicit group split or per-photo negative-choice UI established in this help page | Merge undo not documented here |
| [Ente](https://ente.com/help/photos/features/search-and-discovery/face-recognition) | Open a face grouping; **Add name**/edit in that context | Desktop name dialog lets user **search existing people or create new**; not an evidence-ranked look-alike list; cap/order not stated | Assigning the same existing name merges groups. No dedicated split / negative-choice contract established by this page | Merge undo not established by this page |
| [Synology Photos](https://kb.synology.com/DSM/tutorial/Quick_Start_Synology_Photos) and [mobile guide](https://kb.synology.com/en-uk/DSM/help/SynologyPhotos/iPhone) | People grid → open album with that person's photos → **Who's this?** or name on cover | Mobile name box searches and can create a name; similarity ranking/count not documented | **Merge people** (or choose same name); **Remove or reassign this photo** corrects wrong groupings; hide people. No independent split wizard documented | Synology Photos help does not promise an exact merge Undo; old **Moments** irreversible-merge warning must not be misattributed to Photos |
| [digiKam](https://docs.digikam.org/en/left_sidebar/people_view.html) | Face **thumbnail** has its own **Who Is This?** field directly in the People/Unknown icon grid; face remains adjacent | Suggestions after recognition appear in a separate **Unconfirmed** view; **Confirm / Reject / Delete** on the face. No fixed ranked top-N candidate picker | Assign/confirm a face tag, reject a wrong suggestion back to Unknown, remove a false face, mark **Ignored**; no same-as-ATLAS cluster split documented | **Ignored** is reversible; **Reject** does not remember the negative and a later recognition pass may suggest it again |

### Samsung reference — what is and is not observed

Read-only archive: [Appendix D, People and Search](https://github.com/LPStruiksma/atlas-native/blob/main/docs/research/samsung-rea/report/samsung-gallery-findings-v2.md), [Samsung parity spec](https://github.com/LPStruiksma/atlas-native/blob/main/docs/product/SAMSUNG_PARITY_SPEC.md), and [People bench](https://github.com/LPStruiksma/atlas-native/blob/main/docs/people/PEOPLE_BENCH.md). Appendix D is **static analysis of Samsung Gallery 15.9.00.45**, not a captured, verified Samsung naming-screen walkthrough.

In our words: Samsung's face groups, persons and candidate relationships are supplied by the device's private media service. Gallery presents face-cropped portraits, lets the user name or merge groups, presents “same/different/not sure” decisions, remembers **different** answers to suppress a repeat, and provides hide/cover and unmerge operations. An unnamed group needs at least five distinct photos to appear in the normal People list, but naming and review are separate actions. Useful design transfers are *face portrait near the decision*, *small pairwise confirmation*, *remembered rejection*, and *reversible correction where possible*. Samsung's CMH scoring, service interfaces and private assets **do not transfer**, and the archive does **not** substantiate an exact number of suggestion rows, their visible order, or where the keyboard sits.

ATLAS's public [People bench](../../people/PEOPLE_BENCH.md) measures grouping and auto-add conservatively (including the mean-0.45 auto-add rule). It does **not** validate a UI ranking of arbitrary reviewed people against an Unknown group. Do not turn that score into a fabricated confidence percentage or label. Ranking needs its own fixture-backed, deterministic scoring contract and evidence that near-lookalikes are not treated as certain.

### Synthesis

Adopt **digiKam's adjacency**, **Apple/Google's person-context correction**, **Ente/Synology's existing-name search**, **Google/Samsung's explicit negative answers**, and **ATLAS's stronger local Undo**. The suggested maximum of **3** is an ATLAS design choice to reduce cognitive load, **not** a documented benchmark count. Avoid copying anyone's art, layout pixels or proprietary wording.

## Chosen task flow

1. **People → Unknown faces → one group.** Show the group's representative portrait and a small sample of diverse face crops at the top. The first screenful contains the name field and primary action; no reviewed-person list or explanatory essay. The group photo strip is sticky while the content beneath scrolls or the keyboard opens.
2. **Name as new person.** Enter a name, choose **Create person**. Before commit, detect an exact existing-name collision, show that person **with face** and offer distinct **Add to [name]** vs **Create another [name]** choices. A repeated name must not silently merge identities. Clear stale edit state on every new committed snapshot.
3. **Assign to a suggestion.** Only when ranked evidence exists, show up to 3 candidate rows with face crop, name, and **Add to [name]** / **Not [name]**. Explicit commit, never auto-apply from the UI. A rejected pair disappears and its negative decision persists through reopening.
4. **Find anyone else.** **Find a person** opens a compact name-filtered chooser, initially **empty** rather than showing every person. Return at most five query matches (face + disambiguating count/context, not an ordinal); refine query for more; optional **Show more matching people** only as an explicit action if necessary. Selection brings a preview of destination next to the source strip and requires **Add to [name]**. Duplicate names never resolve by string alone.
5. **Mixed group.** **Some faces are different** enters a face-selection grid with the same source strip/header. Select which faces belong apart and use **Separate selected faces**; for a single face, allow **Move selected face**. Leave unselected photos untouched; cancel preserves state.
6. **Review / Undo.** Each committed name, assignment, rejection, split or merge shows a concise Undo snackbar where supported by the store. On a long-lived history view, use existing durable Undo; do not promise an exact undo for actions the backing model cannot actually reverse. A merge sheet displays source and destination faces, counts, and explicit target before commit.
7. **Stale or absent access.** While a photo grant or current review snapshot is missing, render **no old face pixels** and disable actions. Display a meaningful inline state from the *host* (scan, refresh, permission or no scan) with one appropriate recovery action. Do not disguise an indefinitely null queue as progress; #260 owns the diagnostic and fix.

### Wireframe A — default group, small phone

~~~text
<  Unknown faces                          (⋮)
+-------------------------------------------+
|  [GROUP FACE]   12 faces in this group     |  <- sticky face context
|  [face 1][face 2][face 3][+9 more]         |
+-------------------------------------------+
|  Who is this?                             |
|  [ Name this person                 ]      |
|  [Create person]                          |
|                                           |
|  Looks like someone you know?             |  <- only if scored
|  (face) Alex                  [Add] [No]  |
|  (face) Sam                   [Add] [No]  |
|  (face) Robin                 [Add] [No]  |
|                                           |
|  Find a person                            |  <- explicit search, closed
|  Some faces are different                 |
+-------------------------------------------+
~~~

**R1:** the group face and strip precede the input and remain visible during name/assign. **R2:** 0–3 scored, face-bearing rows; no catalogue pagination. When no rank evidence exists, omit “Looks like…” entirely.

### Wireframe B — typing or choosing an existing person

~~~text
+-------------------------------------------+
| [GROUP FACE]  [face 1][face 2][+more]     |  <- persistent top
+-------------------------------------------+
| Who is this?                              |
| [ Alex                               ]    |
|                                           |
| Existing person with this name:           |
| [Alex face] Alex · 18 photos              |
| [Add to this Alex] [Create another Alex]  |
+---------------- software keyboard --------+
~~~

**R1:** the source portrait/strip survives keyboard resize; the text field is not under a pager. **R2:** the existing-name result is a *query exact-match*, with a face, not an unranked “suggestion”; the general suggestion feed is hidden while typing.

### Wireframe C — assign confirmation / merge

~~~text
+-------------------------------------------+
| [GROUP FACE] [source 2]    12 faces       |
+-------------------------------------------+
| Add these faces to Alex?                  |
| [source face]  +  [Alex face]             |
| 12 faces         Alex · 18 photos         |
|                                           |
| [Cancel]                [Add to Alex]     |
+-------------------------------------------+
| Undo available after save                 |  <- snackbar, not modal
~~~

**R1:** the group's own face remains directly beside the destination. **R2:** exactly one explicitly chosen face-bearing destination, never a list or silent merge. Clarify if the action joins two already named identities before offering **Merge people**.

### Wireframe D — separate / move selected faces

~~~text
+-------------------------------------------+
| [GROUP FACE] [face 1][face 2]  12 faces   |
+-------------------------------------------+
| Select faces that belong elsewhere        |
| [x face][  face][x face][  face]           |
| [  face][  face][  face][  face]           |
|                                           |
| 2 selected                                |
| [Cancel]   [Separate selected faces]      |
+-------------------------------------------+
~~~

**R1:** the original group and selected faces appear together throughout selection. **R2:** no suggestions in split mode; if moving to a person, open the same face-bearing, explicit chooser from B/C, never a paged catalogue.

### Wireframe E — scoped/loading/empty state

~~~text
<  Unknown faces
+-------------------------------------------+
| [neutral placeholder]                     |
| Finding faces… 42 of 120 photos           |
|                                           |
| (actions hidden until current access)     |
+-------------------------------------------+
~~~

**R1:** there is no safe group face to keep visible until an authorized group exists; never reuse the previous group's image. **R2:** no suggestions or catalogue shown. Replace the status/action with the exact host state from the copy table below, not a guessed percentage.

## Component and integration specification for build units

| Component | Behaviour and invariant | Existing integration / owner |
|---|---|---|
| **UnknownGroupHeader** | First, compact, sticky source face hero + 3–5 diverse crops + total, tap to see all; crop slots use authorized preview; no private identifiers in semantics or saved-state keys; 48 dp touch targets | Replace lead of UnknownIdentityGroupCard; reuse host clusterFacePreview; follow PeopleHomeScreen portrait idiom |
| **NamePersonInline** | One 80-character input, IME Done, initial empty, error under field; exact-name duplicate detection without forced merge; keyboard-safe height; no jargon banner | Existing NameCluster action; host-state identity comparison and explicit PersonKey semantics |
| **RankedPersonCandidates** | Zero to three distinct visible people only; sorted descending by validated per-group similarity, stable deterministic tie-breaker; each row includes crop + name + actions. Hide if scores absent/invalid, ambiguous or stale; never resort alphabetically | New scored projection required; current group.suggestion is one item, existing PeopleDestinationSelector is **not** a scorer; Strategy must sequence data-backed ranking before showing multiple suggestions |
| **FindPersonSheet** | Query-first, no list at zero-length query; results show image and secondary disambiguator (e.g. confirmed face count), filter by name, explicit select/confirm; no radio-button catalogue pager | Refactor PeopleDestinationSelector for on-demand filtered search; resolve destination only by its stable current position/object under current grant |
| **GroupFacePicker** | Compact 3–5 preview strip with “See all” opening full grid; multiselect on separate screen, selection count; preserve all faces (current first-20 limitation needs a true full view) | Existing SplitUnknownCluster and ReassignFace actions; can reuse the split work under #96 |
| **MergeDecision / Undo** | Source and destination face portraits and counts; merge target explicit, no implicit same-name merge; undo route for all proven reversible commands | Existing PeopleReviewAction, PeopleClusterReviewState.undoHistory; independent same-person hub #168 |
| **ReviewGateState** | Typed host states: access missing/limited, never scanned, scanning, refreshing, ready, read-only/recoverable failure. Never show an old grant's pixels; disable every stale action, including search and Undo | currentNativeFaceReviewQueueOrNull, currentPeopleReviewActionOrNull and liveReviewStateAtAction; root-cause work in #260 |

**Ranking contract (must be measured, not guessed):** compute a group-to-named-person similarity from grant-current, confirmed face embeddings; never score against unchecked auto-adds. Calibrate an eligibility threshold and ambiguity gap against the [People bench](../../people/PEOPLE_BENCH.md) and held-out/look-alike fixtures; restrict candidates to the active library/grant and exclude rejected pairings and the source identity. Sort by score descending, stable identity key only for tie-breaking, and cap at 3 **after** filters. Do not expose raw similarity percentages or “likely” wording until measured calibration supports them. Reject via durable negative action, not a transient UI hide. If the existing state exposes only a single unranked suggestion, show at most that one **only if** its provenance and ranking eligibility are established; otherwise fall back to Find a person. No automatic assignments from this component.

**Snapshot and privacy boundary:** retain permission-epoch + source-generation checks, reference-identity reset on every committed review object, at-action live validation, transaction-side expectedState check, regrant clearing, safe empty thumbnail placeholders, numeric ephemeral UI positions only. Never embed raw face IDs, URIs, filenames, personal faces or logs into screenshot tests or GitHub. On a changed photo, invalidate stale confirmation and return to review as the current model requires. This is design only: do not weaken identity or access checks to keep a pretty screen visible.

**Interaction/visual:** system font scale and TalkBack accessible labels, 48 dp minimum taps, light/dark tokens, neutral outlines, no page counters, no giant Material cards for each face, no duplicated “detection is not recognition” banner; use [polish checklist](../../product/DESIGN_POLISH_CHECKLIST.md) for S24+ evidence. Keyboard covers the lower list but not the group face and field; affordance to collapse keyboard or expand group. Loading is inline rather than a blocking full-page overlay.

**Acceptance / proof for follow-on PRs:** synthetic groups of 1, 3, 20 and 68 faces; 0, 1, 3 and 70 named people; two identical names; look-alike candidates; tied/no scores; negative pairs; 80-character Unicode name; soft keyboard on S24+; font scaling/TalkBack; stale page/grant/regrant; error/recovery; Undo/name/merge/split. Assertions: (a) group face and name/primary button visible together in portrait with keyboard, (b) at most three candidate rows, all have faces and scores descending, (c) zero candidates renders no fake list, (d) a search starts empty, (e) no stale image/action survives a grant change. Capture actual light/dark emulator PNGs and interaction evidence for the **specific review state** before Review; ASCII alone is not proof.

## Final copy inventory — supersedes proposed copy in #260

This is **the string contract for the redesigned screen**, not an assertion that the current backend can already supply each state. Use a single set of string resources in the implementation, with count plurals and accessibility variants. Names below are placeholders, not real people. Ellipses are for ongoing status only.

| Context / key | Proposed exact UI text | Show/trigger |
|---|---|---|
| Top title | **Unknown faces** | Route title |
| Back | **Back** | Navigation; accessible label “Back to People” |
| Group heading, 1 | **1 face in this group** | Current authorized group |
| Group heading, plural | **{count} faces in this group** | Current authorized group |
| Face-strip expansion | **See all faces** | Opens full group grid |
| Name field label | **Who is this?** | Shown beside/past group strip |
| Name field hint | **Name this person** | Empty field |
| Create action | **Create person** | Nonempty valid new name |
| Name too long | **Use a shorter name.** | >80 character edit attempt (input remains unchanged) |
| Blank input validation | **Enter a name.** | Submission without a name |
| Same-name collision | **There's already a person called {name}.** | Exact normalized name match |
| Add to existing collision | **Add to this {name}** | Face-bearing, explicit destination choice |
| Separate homonym | **Create another {name}** | User explicitly retains separate identity |
| Ranked heading | **Looks like someone you know?** | 1–3 eligible candidates only |
| Candidate action | **Add to {name}** | Explicit attach, never auto-commit |
| Negative candidate action | **Not {name}** | Persist rejection for group-person pair |
| Search entry | **Find a person** | Opens on-demand search |
| Search input | **Search by name** | Initial results empty |
| Search empty after query | **No people match that name.** | Nonblank query, zero results |
| Search more | **Show more matches** | Only if a nonblank query has more than five matches |
| Search destination summary | **{name} · {count} photos** | Disambiguation text under portrait; count must be real, not guessed |
| Choose before assigning | **Choose a person first.** | No destination selected |
| Assign confirmation | **Add these faces to {name}?** | Source and destination face preview visible |
| Assign confirm | **Add to {name}** | Explicit commit |
| Merge title | **Merge these people?** | Two already named identities |
| Merge explanation | **Their photos will appear together under {name}.** | Source + destination shown |
| Merge action | **Merge people** | Confirmation only, target explicit |
| Split entry | **Some faces are different** | Opens group selection |
| Split instruction | **Select faces that belong elsewhere.** | Selection mode |
| Split count | **{count} selected** | In picker |
| Split action | **Separate selected faces** | Valid proper subset, not all |
| Move one | **Move selected face** | Exactly one selected with explicit destination |
| Cancel | **Cancel** | Leaves data untouched |
| Done | **Done** | Close expanded face grid |
| History action | **Undo** | Snackbar or existing reversible history |
| Name confirmation | **Person created** | With Undo when action is reversible |
| Assignment confirmation | **Faces added to {name}** | With Undo |
| Rejection confirmation | **Won't suggest {name} again** | Only if durable negative persisted |
| Split confirmation | **Faces separated** | With Undo |
| Merge confirmation | **People merged** | With Undo **only if** exact merge undo supported |
| Permission missing | **Allow photo access to find faces.** | Button: **Choose photos** or **Allow access** wired to existing permission flow |
| Never scanned | **No faces found yet.** | Button: **Find faces** only when scan can start |
| Scan progress | **Finding faces… {done} of {total} photos** | Show numbers only if host has trustworthy totals; else **Finding faces…** |
| Refresh in progress | **Updating faces after changes to your photos…** | Auto-clearing; never keep old cropped previews |
| Ready but no groups | **No unknown faces to review.** | No suggestions; optional **Back to People** |
| Temporarily unavailable | **Faces are updating. Try again shortly.** | Only for diagnosed temporary state; do not use for perpetual null queue |
| Recoverable failure | **Couldn't load faces.** | Button: **Try again** if host exposes genuine retry |
| Read-only without action backend | **You can view faces, but can't make changes right now.** | If temporary, actionable recovery; if permanent wiring bug, fix in #260 instead |
| Accessible candidate | **{name}, suggested match; add or reject** | Semantics for candidate row, source/destination distinguished |
| Accessible face count | **Face {index} of {total} in this group** | Non-sensitive position only |
| Accessible destination | **Choose {name}, {count} photos** | Screen-reader label, also disambiguate duplicate names through face |

**Copy exclusions:** remove “Unknown identity groups”, “reviewed choice 1 of 68”, “choice page 1 of 12”, “durable review backend”, “face observations”, “source generation”, “permission epoch”, and the indefinite “Review is unavailable until the current photo selection and face index are confirmed.” The data gate is preserved; the developer terminology is not shown. The detector/identity safety distinction belongs in help or People settings **only if users need it**, not as an explanation blocking the naming task. Strings in the existing non-identity face-detection review card should be separately aligned if that independent feature remains; do not silently conflate “Not a face” with “Not this person.”

**Coordination:** #260 now owns the persistent unavailable root-cause and its state/permission wiring. This study's copy table supersedes #260's earlier *copy proposal* only; it does not supersede its diagnostic, data-safety, regression-test, or rendering responsibilities. #145 owns later visual implementation after People hub/split dependencies settle; Strategy should split the two actionable follow-ups below rather than concurrently edit the same UI file.

## “Silly screens” audit — D1–D19

**Method and limits.** Walked the [D1–D19 inventory in #120](https://github.com/LPStruiksma/atlas-gallery/issues/120), [existing polish ledger](../../product/DESIGN_POLISH_CHECKLIST.md), and [Samsung parity spec](../../product/SAMSUNG_PARITY_SPEC.md). The ledger explicitly has **171/171 applicable cells uninspected** and missing action-state evidence. Therefore **do not turn a missing render into a proved failure**. Below, **K** = source/owner-confirmed design defect; **P** = potential invented/unreferenced interaction requiring rendered confirmation; **R** = named reference already exists, but its actual UI match is unverified. “F×L” reuses #120's **provisional** frequency × leverage prior, except the confirmed D13 flaw gets an explicit local-leverage hypothesis of 5 (2×5=10). Numbers are prioritization hypotheses, not measurements.

| ID | F×L prior | Audit result / reference to compare | Next review or build unit |
|---|---:|---|---|
| **D1** Pictures grid/pinch/date/scroll | **5×4=20** | **R.** Samsung GRID:G1–G7/T1–T2/PERF:S4–S6. Missing large zoom/fast-scroll paired proof; no new “silly” defect asserted. | Existing grid/parity #131 + action-state evidence |
| **D2** Viewer chrome/video | **5×4=20** | **R.** Samsung VIEW:V20–V23. Hidden chrome and video overlay unverified; avoid adding extra stacked bars. | Existing viewer polish #142, capture hidden controls |
| **D3** Grid ↔ viewer | **5×4=20** | **R/P.** Samsung shared-element return. No frame-timed evidence; a pop/jump would be off-reference but is not proven here. | Existing transition #23, capture actual frame sequence |
| **D4** Bottom tabs | **4×3=12** | **R.** Samsung compact floating pill; ATLAS keeps Photos/Albums/People/Search. Current motion not measured. | Existing #137, tab switch recording |
| **D5** Albums overview/detail | **4×3=12** | **R.** Samsung GRID:A1–A8 / Apple album grids; paired images declared, pixels not checked. | Existing #143, side-by-side validation |
| **D6** People tab | **3×4=12** | **R.** Samsung round face crops, names below; Google/Immich People grids; no verified mismatch here. | Existing #132, visual face-grid review |
| **D7** Search | **3×4=12** | **R/P.** Samsung PPL:15–26, Google recent searches, chips, contextual results. Avoid invented explanation banners, but search-result evidence still missing. | Existing #134, capture actual chips/results before a build |
| **D8** Person detail | **3×3=9** | **R/P.** Apple/Google/Immich group content before management. Named-person paired screenshot NOT_CAPTURED. | Existing #133, pair captures and crop/label audit |
| **D9** Selection/action bar | **3×3=9** | **R.** Samsung GRID:S1–S4, bottom action pill; drag/range action still not proven. | Existing #144; selection action capture |
| **D10** Dialogs/Undo/toasts | **3×3=9** | **P.** Google/Apple confirmation, digiKam direct reject; ATLAS wants lightweight Undo, not a confirmation at every tap. Actual paired feedback states absent. | Existing #144; screenshot/snackbar evidence |
| **D11** Empty/loading/error | **3×3=9** | **K.** Unknown faces shows an indefinite generic “Review is unavailable” message per #260 and source. Other screens **not diagnosed**. Reference: Samsung inline states and Apple/Ente task recovery. | Existing **#260**: diagnose null queue, typed inline states and recovery with tests |
| **D12** Motion/press/sheets | **3×3=9** | **R/P.** Samsung 200–350 ms motion reference exists; no timed action captures. | Existing #137; motion timings before changes |
| **D13** People name/merge/same/split | **2×4=8** in #120; **2×5=10** local | **K.** Source-verified unranked paginated chooser sits before group's own faces, several jargon blocks. digiKam + Apple/Google/Ente offer stronger precedents. | **Study build A/B** below, then #145 visuals |
| **D14** Details sheet | **2×3=6** | **R/P.** Samsung VIEW:V26–V28; current true details pair NOT_CAPTURED. | Capture proper sheet before filing delta |
| **D15** Stories | **2×3=6** | **R/P.** Samsung Stories parity route; current full page pair missing. | Capture Stories card/entry states |
| **D16** Full-screen video | **2×3=6** | **R/P.** Samsung VIEW:V16–V18; full-screen seek overlay missing from evidence. | Capture playback/seek state |
| **D17** Settings | **1×3=3** | **R/P.** Samsung Menu/settings hierarchy; possible redundant explanations must be inspected, not presumed. | Inspect settings + submenu pair |
| **D18** Trash/Photo DNA | **1×3=3** | **R/P.** Samsung DCL cluster keeper + restore, not an invented scary delete wizard. Trash and interaction captures missing. | Existing review/declutter track; capture keeper/restore |
| **D19** Sort sheets | **1×2=2** | **R/P.** Samsung sort sheet, ATLAS adds size sort; only unpaired capture exists. | Capture paired sort sheet and selected mode |

**Rank interpretation:** High F×L rows D1–D3 top the *evidence queue*, not a list of certified silly screens. D13 is the strongest **directly established** layout failure; D11 is a second **directly established** text/state failure. A screen's “P” means audit candidate, **not** an accusation that the implemented UI is definitely wrong. Do not score missing visuals as zero or automatically open one speculative build PR per row.

### Top items → bounded build units for Strategy

| Rank for action | Concrete unit | Owner/dependency and acceptance |
|---|---|---|
| **1 — D13, known task failure** | **Study build A: face-first Unknown group card.** Move group strip before name controls, keep top context visible under keyboard, collapse indexed/all-people pagination into query-first destination search, remove numbered choice and developer jargon. Reuse existing commands and snapshot gates; no matching algorithm change. | After #260's state diagnosis or on non-overlapping files. #145 can consume components later. Synthetic light/dark keyboard renders, TalkBack and 68-person case required. |
| **2 — D13, safety/quality leverage** | **Study build B: eligible scored top-three candidates.** Implement validated group-to-person candidate projection, negative persistence, stable ranking and face previews; zero suggestions when no score; exhaustive search separately. No threshold cargo-culting from bench, no auto-name. | After A and scoring validation; coordinate with People bench and #168. Tests include relatives/look-alikes, ties, negatives and stale grant, plus rendered proof. |
| **3 — D11, known persistent state issue** | **#260 PEOPLE-COPY/STATE: persistent null-queue root cause.** Host supplies typed scan/permission/regrant/failure states and one real recovery action; apply this document's copy contract. | Already assigned—**do not create duplicate issue**. JVM/Compose state cases + source wiring regression, and two-theme renders. |
| **4 — D1/D2/D3, high use but unverified design gap** | **Reference-conformance evidence first.** Extend existing #131/#142/#23 with paired zoom/scroll, viewer hidden-controls, and transition frame captures. File a *separate narrow build unit only for a confirmed deviation*. | High-frequency evidence priority; do not declare failure or copy Samsung implementation from research. |

For D4–D19 other than D11/D13, the ledger above states the required comparison surface; Strategy can schedule each row against its existing unit without duplicating work. **The first actual action after this study is build A, not a broad visual rewrite of all nineteen screens.**

## Study acceptance checklist

- [x] Six public competitor references + Samsung clean-room archive, distinguishing documented from undocumented counts/undo.
- [x] Both owner hard requirements explicit and checked against **every** wireframe.
- [x] Five wireframes, component contract, safety boundary, ranking prerequisites and state/copy strings.
- [x] D1–D19 audit with frequency × leverage and evidence/uncertainty, and build-sized proposals for top action items.
- [ ] Actual rendered ATLAS light/dark before/after images: **out of scope for this documentation unit**, mandatory in subsequent UI build Reviews.
- [ ] Implementation, matching ranking calibration, null-queue diagnosis: assigned to future units/#260; **not completed here**.

**Sources and verification:** read-only archived Samsung static report and bench, atlas-gallery main source and design ledger, and the six linked official public guides above, accessed 15 November 2026. Where help does not document a behavior, this spec records “not stated” rather than inventing a product capability. All drawings and text here are original; no private imagery.
