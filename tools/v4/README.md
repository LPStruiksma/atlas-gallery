# V4 People UX preview (separate from the native Android app)

**Owner acceptance / safe preview gate:** [`PREVIEW_ACCEPTANCE.md`](PREVIEW_ACCEPTANCE.md) provides an exact-artifact SHA-256 pin, independent private People backup/recovery prerequisites, browser smoke path and separate script-vs-database rollback. This is a **not-yet-installed** preview, not a safe native APK upgrade.

## Confirmed suggestions: one atomic approval decision

The V4 **Review suggestions → Accept selected** operation now requires
the approved face to **still be suggested for that exact Person** when
its SQLite write transaction begins. Previously, a separate preliminary
lookup read the suggested IDs before `assign_faces` acquired the write
lock. If background proposals or another reviewer changed a suggestion
between these steps, it was possible to accept a stale choice as a
permanently confirmed identity.

Approval now delegates to the regular audited, undoable assignment
transaction with an additional required suggestion-person predicate.
The `BEGIN IMMEDIATE` lock covers validation, selected-row updates,
cluster reconciliation and Undo recording. Mixed, missing, ignored,
already tagged, reassigned, or archived-profile suggestions fail closed:
nothing in the selected batch is confirmed. UPDATE rowcounts are
verified before commit, so an abort midway through a multi-face request
rolls back the preceding face edits. The separate **manually assign
unassigned faces** action remains available without needing a suggestion.

The prior preliminary SELECT also fetched *every* suggestion ID for
a Person into Python. That unbounded intermediate roster is gone:
approval validates only explicitly selected IDs (at most 2000). Machine
proposals are never approved by this fix without the existing user
confirmation.

`python3 tools/v4/test_accept_atomic.py` constructs only fictional
faces and People in temporary SQLite files. It forces an interleaved
suggestion change just before assignment, checks that another SQLite
connection cannot modify proposals while confirmation owns the write
lock, verifies mid-batch trigger abort rolls back all changes, and
exercises wrong-person/hidden/archived/missing conditions, independent
manual assignment and one-use Undo.

No on-device People DB, original photo, live Termux process or installed
APK is touched by the GitHub changes. The owner's verified backup is
still required before any private-phone acceptance testing.


## Discover: malformed face-vector evidence abstains

V4 group-level named-person hints are **unconfirmed resemblance cues**.
A 512-byte face embedding can still contain non-finite float32 values
(NaN or infinity), even when its BLOB byte length passes the SQLite
filter. Feeding such a reference into a nearest-of-12 profile comparison
can propagate NaN through `numpy.max`, accidentally hiding an
otherwise valid known-person hint, or count an invalid group observation
among the sampled faces.

Discover group detail now discards invalid group or confirmed-profile
vectors **before** computing scores and support counts. Missing good
evidence returns an empty set of hints. No rows, tags, original photos,
identity profiles, or review history are altered. This is not a new
automatic confirmation rule; visible suggestions still demand human
approval and retain same-photo corroboration exclusions.

The existing synthetic candidate regression injects a highest-quality
NaN cluster exemplar and an infinity-filled confirmed profile exemplar
among valid fictional faces, then verifies that the remaining valid
ranking, support counts, portraits and read-only behavior are unchanged.
The Discover-list hint scorer already had a finite-vector filter;
this makes individual group detail equally fail-closed.


## Existing-person picker: lightweight names, scoped portraits

The explicit People chooser is used when attaching reviewed faces,
merging profiles, and confirming a same-name identity. It previously
loaded the legacy full `/api/people` endpoint, forcing a score pass
over every assigned face in the whole private gallery before the
dialog could even open.

The picker now uses **metadata-only** `/api/people?covers=0` for
the normal active roster, while preserving the same **100 visible
matching names and IDs**. Its locally filtered options render
immediately with placeholders where necessary. A 140 ms debounce
groups keyboard strokes, then fetches just those 100 candidates'
portraits in up to **three read-only, 48-ID-capped batches**. Late
responses cannot overwrite a newer name filter, reopen a closed
dialog, or interrupt the separate confirmation panel. The existing
scroll position and focused option survive portrait hydration.

A user can choose a still-loading portrait: the chooser requests
that **one selected profile** first, then opens the deliberate
confirmation card with its portrait when available. A failed
thumbnail-metadata request cannot tag any person; it only shows an
explicit warning to verify the person's name and ID manually.
All assignments still require the existing explicit Confirm button,
and all server-side review/Undo validation remains unchanged.

**Archived-profile restore pickers** explicitly supply their own
history-backed People records and bypass this active-roster lookup
entirely. The unmodified `/api/people` legacy endpoint remains
available and unchanged for callers that expect every cover at once.

`node tools/v4/test_picker_scoped_covers.cjs` exercises actual
chooser code over 2,001 fictional named People, tests a 100-option
search, three bounded cover requests, debounce and old-result races,
focused-button preservation, per-person click-before-hydration,
explicit confirmation sequencing and archived-source isolation.
No private photos or IDs are read, and no People assignments occur.

This does not establish real S24+ Android picker responsiveness.
Phone acceptance requires owner backup and an explicitly approved
preview deployment, separate from the repository merge.


## Large People roster: visible portrait lookup, not a full-gallery rescore

The People search UI previously requested the old full `/api/people`
roster, which computed and ranked *every assigned face crop* just to
display at most **48** visible portrait tiles. In a private gallery with
many thousands of tagged faces, this needlessly delayed browsing or
searching for one named person.

The V4 browser now requests `/api/people?covers=0`, retaining the
complete small name/count metadata roster in memory for **instant local
name filtering**. Before the initial page is drawn, it fetches covers
only for its 48 visible profiles with `/api/people/covers?ids=…`.
Further search/page changes render immediately with optional placeholders
and fill their still-missing portraits asynchronously. A later response
from an older search cannot overwrite the current visible results; a
navigation away invalidates the old request. Portrait filling preserves
keyboard focus on the same visible Person tile. The search input is
never replaced during asynchronous portrait updates.

The server validates and deduplicates at most **48 positive** profile
IDs per request and omits archived or unknown IDs. It uses the existing
candidate-scoped cover scorer, retaining hand-picked portraits,
deterministic scoring, and stale-cover fallback. Results are keyed by
profile ID and do not include image bytes, file paths or embeddings.
Unassigned/no-cover profiles retain an explicit visual placeholder.

Compatibility: legacy `/api/people` with no query parameters still
returns exactly the same full roster including portraits and all
existing profile-picker behavior; `?covers=0` is an optional
metadata-only form. No name-search, grouping, review or confirmation
policy changes. These are index metadata optimizations only.

Synthetic tests:

- `test_person_targeted_summary.py` uses 2,001 fictional People and
  16,008 synthetic face metadata rows to assert identical metadata,
  correct capped visible covers, legacy full-roster parity, read-only
  queries, unknown/duplicate IDs, and invalid-request rejection.
- `test_visible_roster_covers.cjs` executes actual browser People
  functions against 2,001 fictional profiles. It tests only 48
  initial portrait IDs requested, instant cached name filtering,
  out-of-order portrait responses, leaving the People tab, focus
  restoration and empty-results behavior.

This is not a measured S24+ browser frame-time or memory reduction.
Merging code does not deploy to the running Termux V4 server or access
any private photo, People database or installed app.


## Discover candidate portraits without scanning everyone

Opening a V4 Discover group already compares a bounded number of fictional
face feature examples and returns at most five **unverified** named-person
resemblance candidates. Previously its portrait decoration called the
same full-library portrait scorer as the People roster, looping through
*every assigned face* to select covers for merely 1–5 candidates.

Discover now scores covers only for those actual returned profile IDs
using the existing indexed face-person relationship. The scoring formula,
deterministic earliest-face tie-break, clear-image prerequisite, manually
chosen portrait precedence and stale-cover fallback are **unchanged**.
An empty candidate list performs no cover query; duplicates collapse.
This is read-only. Named-person candidates still require deliberate
user review and never become automatically confirmed identities.

The original unfiltered `choose_profile_covers(c)` and existing
`/api/people` roster remain backward compatible, so browse/search and
explicit profile selection still see their original People names, face
counts and covers.

Synthetic regression `python3 tools/v4/test_targeted_discover_covers.py`
constructs 2,001 fictional People and 16,008 generated face metadata
records. It compares a three-person subset against the all-People
portrait scores, checks manual overrides and stale invalid portraits,
tests empty/unknown IDs, and ensures no index mutations. Existing
Discover candidate tests now additionally reject accidentally rescoring
all People just to decorate the few returned hints.

CI timings are an illustrative synthetic SQLite comparison only.
Neither on-device memory nor S24+ navigation latency has been measured.
This GitHub change does not access or modify actual private photos,
the running Termux server, or the installed native app.


## Person picker: safe Escape and keyboard focus return

The existing-person picker already requires a separate **Confirm** step
before changing assignments or merging profiles. The V4 preview now
handles **Escape** as a deliberate reversible navigation action:

- While browsing profiles, Escape cancels the dialog without making an
  assignment. Once dismissed, focus returns to the original button
  when that button still exists; this avoids forcing keyboard users
  back to the beginning of the screen.
- While inspecting a candidate's confirmation card, Escape moves back
  to the portrait/name chooser; it never confirms a face assignment.
  Pressing Escape again cancels the picker entirely.
- During an active confirmation network request, Escape, overlay
  dismissal and Cancel are blocked until the request finishes. The
  Cancel click handler now passes an explicit no-force call, rather
  than accidentally forwarding its truthy DOM event into the
  internal forced-close parameter.

The dialog also captures its opening screen, Person/group and render
generation. A slow `/api/people` response is discarded if the
reviewer navigated to a different context before it arrived: it cannot
pop a stale picker over another person's review. No user-visible
identity choice or face assignment is made by the code automatically.
The confirmation card uses the same graceful missing-cover placeholder
as the browse list, avoiding the invalid `<img src="">` path.

The new Node synthetic regression `test_picker_escape_focus.cjs`
executes real picker functions with a mock DOM and fake profiles,
covering Escape browse/review, no write before approval, missing
portraits, safe in-flight submission, stale People roster requests and
return focus only to connected elements. Device accessibility and
Android Back gesture behavior remain owner-acceptance work; this
test does not simulate Android UI navigation.


## Review-page keyboard continuity after paging

The V4 Discover, Unsorted, provisional group and named Person screens
now move keyboard focus to the first *newly rendered* tile after a
Previous/Next page change, instead of leaving focus attached to a
discarded pager button from the old DOM. The page scrolls to its top,
and the focused first group/photo/face button uses
`focus({preventScroll:true})`, so keyboard users can continue reviewing
immediately without repeated Tab presses through the entire toolbar.

The shared navigation function still honors disabled Next at the last
page, and never goes before page 1. It clears only the old page's
selection, preserving the explicit page-local review policy. A render
generation plus screen/Person/group identity check prevents older
responses or animation callbacks from scrolling or focusing a
different screen after quick navigation. If a shrinking queue has no
tile left, focusing is skipped safely.

`node tools/v4/test_page_focus_continuity.cjs` executes the
production pager helper against a synthetic delayed-render DOM,
covering first/last page, successful next/previous, stale tab and
Person changes, later render generations and empty pages. The existing
pagination tests keep checking exact 40/60-row page boundaries.
No new storage, network calls, identity assignments or writes are
introduced. Actual Android keyboard/touch performance awaits the
owner-approved backed-up preview.


## Full-photo People inspection: a clearer, bounded local rendition

The full-photo view reached from a named Person's gallery previously
stretched the **750px** grid thumbnail to the full display. Fine details
and smaller faces were unnecessarily soft at a moment when visual
confirmation is critical. This can undermine deliberate review even if
the original has sufficient pixels.

V4 now requests a separate locally generated **review** rendition with
a maximum long edge of **1600 pixels**, using a bounded 2400px
intermediate decode cap and JPEG quality 88 (the photo grid remains
750px / quality 83). Rendering never upscales small originals; actual
resolution is limited by source resolution, supported decoders and any
reduced-resolution OpenCV decode. This is a review-quality image, **not
an original/full-resolution export** and not a change to the native
Android full-screen filmstrip.

The endpoint `/thumb/review/<image_id>` serves a JPEG from the same
private loopback server. It uses the existing two-slot cold decoder
semaphore, per-rendition single-flight lock, timestamp-keyed atomic
cache writes and shared 512MiB cache pruning as other thumbnails.
Different variants have separate cache filenames, so a new inspection
rendition cannot silently replace or upscale the grid thumbnail.
The full-photo browser URL includes a source-modification timestamp
obtained from the existing read-only photo metadata endpoint: after
rescan, a changed original gets a different browser cache key even
though the HTTP image route remains exactly the same safe local handler.
The server still returns warm on-disk JPEG bytes when available.
Neither path mutates source photos, faces, labels or People tags.
Any unsupported variant fails closed.

Synthetic tests:
- `python3 tools/v4/test_photo_review_rendition.py` uses inert bytes
  and a fake image decoder to assert 750 vs 1600 output, 900/2400
  input caps, JPEG quality, separate warmed files, timestamp
  invalidation, temporary-file cleanup and unchanged original bytes.
- `node tools/v4/test_photo_review_markup.cjs` executes the actual
  full-photo HTML generator and checks it requests the bounded review
  rendition without changing the returned face list or Back action.

The tests don't quantify real device memory, sharpness or decode time;
those require a backed-up, owner-approved S24+ preview session.


## People roster: truthful pagination from the first paint

The named People browser uses a 48-profile lazy portrait page and filters
metadata locally as the search field changes. Previously Previous and Next
were initially rendered as *enabled* buttons regardless of whether a next
or previous page existed. Only a later query, or manually clicking a page
button, corrected their disabled state.

Now the initial HTML computes both controls from the same clamped
`peopleSearchPage` result as the visible 48 portraits. Previous is
disabled on page 1; Next is disabled on the last page, including a single
or empty roster. The controls declare `aria-controls="people-grid"`, and
retain semantic button keyboard behavior and existing instantaneous
search updates. There are no extra API calls or index writes.

The pre-existing 2,001-fictional-person browser regression now asserts
the *first rendered state*, last page and empty roster, as well as existing
search-specific updates. This is a browser UX correctness check rather
than a measured S24+ performance result.


## Return to the same photograph in a named Person's gallery

Opening a full photo on a later Person-gallery page previously called the
general `go('photo')` router, which resets `page=0`. The Photo viewer's
**Back to person** button used `go('person')`, resetting the person's
gallery to page 1 a second time, losing the reader's place. With thousands
of photos, this breaks uninterrupted manual review.

The V4 preview now remembers the exact **person, photo mode, page, scroll
offset, and originating photo ID** in a one-use browser-memory object
before opening the full photo. On return it fetches the same Person page,
restores the scroll after rendering, and focuses the original photo
button with `preventScroll:true` for keyboard review continuity.
If the source photo was removed from the current results meanwhile,
the optional focus target is simply absent; server-side stale-page
recovery still applies. If the user navigates to another screen or
person before the return animation callback runs, the render-generation
guard prevents stale scroll/focus changes to that newer destination.

No photo path or personal identifier is saved to localStorage or sent to
an external service; this ephemeral context vanishes with the tab.
Person selection, confirmation and reversible Undo policy is unchanged.
The independent Node regression
`node tools/v4/test_photo_return_context.cjs` checks page-8 return,
scroll restoration, keyboard focus, one-use cleanup, safe fallback and
late-navigation races with fictional IDs and mock DOM actions. The test
does not claim measured S24+ UX performance; real phone acceptance
requires a verified backup and approved preview deployment.


## Out-of-order browser requests cannot rewind review navigation

Rapidly switching between Discover, an existing Person, review modes and
face groups generates overlapping local GETs. The existing render generation
guard already blocked a late response from *drawing* over the current
page, but some asynchronous page functions still mutated global page,
the saved Discover cursor or the cached People roster before the render
guard could discard their HTML.

Each review fetch now receives its originating render generation. After
every awaited HTTP operation that can lead to a mutation, it checks that
the tab, page and relevant Person/group/mode still match its request.
Superseded requests finish silently; they cannot clamp the *new* tab's
page, record obsolete Discover return state or replace the People cache
with stale data. The latest request retains the exact-end pager and the
bounded page-1 recovery when a queue has genuinely shrunk.

The Node test `node tools/v4/test_stale_review_state.cjs` executes the
actual browser render and review-page functions with deliberately
delayed fictional HTTP responses. It covers:
- old Discover count returning after navigation to a Person;
- stale empty Person and group-detail pages after a tab switch;
- out-of-order responses to two pages of the same Person;
- an old People-roster fetch completing after leaving that tab and
  correctly refetching when returning.

Existing isolated browser fixtures continue to call page functions
without a generation token; live rendering always provides one.
This patch modifies only ephemeral browser navigation state. There
are no writes to People tags, thumbnails or original photo files,
and no deployment or access to the real S24+.


## Review-page boundaries: no phantom empty Next pages

Discover groups, Unsorted, group face review, and each named Person mode
(photos, confirmed tags, pending suggestions) use **exact one-row
lookahead**. Instead of assuming a full page means another page exists,
the server reads up to 41 rows for a 40-card Discover page, or up to 61
rows for a 60-card review page. Only 40/60 are rendered; the extra item
sets `has_more`. Thus Next is disabled on the actual last page,
including exact multiples of 40 or 60; Previous is disabled on page 1.
Both controls are semantic, focusable buttons.

This is **opt-in API behavior**, activated by `with_next=1` on these
read-only GET routes. Requests without it still return the original JSON
array, preserving older V4 scripts and integration callers. Paged GET
responses use `{items:[...],has_more: boolean}` only when requested.
Critically, the one extra row does **not** alter subsequent page offsets:
the underlying SQL and deterministic shuffle are still stepped by
40/60, never by 41/61. No extra private data is returned to the browser.

After a reviewed queue shrinks, an empty stale page returns directly to
page 1 instead of showing an empty dead end; the existing saved Discover
cursor is clamped against the indexed group count. Earlier/newer page
responses are still guarded by the existing render generation check.
Selections remain page-local and cleared by deliberate page navigation.
The roster's separate 48-portrait in-browser pagination remains unchanged.

Two dedicated regressions exercise fictional data only:

- `python3 tools/v4/test_review_pagination.py`: compares every visible
  lookahead page against legacy full-size API pages, across 81 Discover
  groups (three sorts/two filters), 130 group faces, 121 confirmed
  Person photos/faces, 121 pending suggestions, and 331 unsorted faces;
  checks for skipping, duplicates, and incorrect exact-end Next.
- `node tools/v4/test_review_pager_browser.cjs`: executes actual
  browser page/pager functions with mock local responses, checking
  disabled controls, per-mode metadata requests, and stale page recovery.

This is not a measured S24+ interaction/frame-time benchmark. Nothing
is installed or changed on the private phone; real-device verification
requires a backed-up owner-approved preview run.


## Already-tagged lookalikes: inspect without accidental reassignment

Find lookalikes may surface a photo of a face that is **already confirmed**
under an existing Person. Those faces remain visible and openable for
inspection, but now display **Already tagged to profile #… · inspect only**
rather than offering a misleading Select checkbox for assigning them again.
The same guard applies to already-confirmed members in historical Discover
groups and to ignored faces. Bulk Select page continues to operate **only on
visible, eligible checkboxes**, never on every face in the entire archive.

The named Person's own **Review tags** mode is deliberately different:
faces still assigned to *that exact profile* can be checked for explicit,
reversible Remove selected tags. Another person's tagged face cannot be
selected for removal from the wrong profile. Proposed unassigned faces
remain selectable in Review suggestions, but no proposal becomes confirmed
until the owner approves it. Portrait selection is independently available
only in the person's own tagged-face review mode.

The face API now includes its existing ignored flag; it does not disclose
any new identity evidence. Backend assignment, rejection and undo handlers
retain their fail-closed validation as a second layer; UI eligibility is not
a replacement for server authorization.

`node tools/v4/test_face_selection_eligibility.cjs` verifies assignment
eligibility and inspect-only states across the fictional Lookalikes,
Discover, Unsorted, Review tags and Review suggestions contexts. The
accessible tile test also uses an explicitly owned fictional face.

This preview is not installed on the phone by merging; real-device
testing requires the owner-approved backup/upgrade gate.


## Lookalikes at library scale: a bounded-memory, read-only scorer

**Find lookalikes** and a named Person's **Find more photos** previously
materialized all candidate faces and all 128-dimensional face embeddings
into Python memory before ranking cosine similarities. At 150,000 candidate
faces the embeddings alone require roughly 73 MiB (150000 × 512 bytes)
*per copy*, in addition to SQLite row objects and intermediate NumPy
arrays. This risks memory pressure on the S24+ and makes the review UI
less predictable as the indexed library grows.

Both searches now stream SQLite rows in batches of **384 faces** and keep
only the best **48 lookalikes** or **80 suggested appearances** in a bounded
top-k heap (adjustable caller limit capped at 100). The reference set for
a named Person remains bounded to the existing 300 manually confirmed
face embeddings and 12 diverse comparison exemplars. Missing, malformed
or non-finite candidate vectors are ignored; ties are consistently sorted
by face ID. No server-initiated face assignments occur. This reduces
peak *candidate embedding array* size independently of overall library
size, but the search remains linear in candidate count and may take more
CPU time for large indexes. It is **not** an ANN index or a calibrated
identity classifier.

`python3 tools/v4/test_streaming_lookalikes.py` builds 20,000
**fictional** 128-float face vectors, injects corrupt/nonfinite faces,
and compares each query against a separate all-candidate mathematical
oracle. The test explicitly fails if a production candidate cursor calls
`fetchall`, if batch size exceeds 384, if labels are written, or if
the highest-scoring IDs/scores differ from the oracle. CI timings are
illustrative CPU timings, **not a benchmark of the installed phone**.

These changes affect only read-only V4 preview search. Actual photo
files, private on-phone People indexes, the running server and installed
APK are unchanged by the GitHub PR. Private S24+ tests require the
owner-controlled, verified-backup upgrade gate.


## Accessible People cards and independently selectable faces

People portraits, Discover groups and photo thumbnails are now **real
buttons**, so keyboard Enter/Space and assistive tools can activate them
without guessing that a clickable generic div is interactive. Named-person
cards, candidate groups, portrait choices and face-open controls receive
visible keyboard focus outlines. Buttons retain the existing mouse/touch
handlers, review position and page-local selection limits.

A face card now keeps **three separate actions** instead of nesting a
checkbox and portrait-setting button inside a generic click target:
(1) an explicit Find lookalikes button, (2) the independently keyboard-
operable Select face checkbox, and (3) a separate Use as portrait button
when reviewing that person's tagged faces. Tapping the checkbox still
does not open similarity search; tapping the portrait button does not
select or reassign a face. Model suggestions remain unconfirmed until
the user explicitly approves them.

People profiles without a selected cover now render an intentional
portrait placeholder instead of `<img src="">`, which could request the
loopback page as an image. Portraits use native browser lazy loading,
asynchronous decoding hints and declared image dimensions to avoid
unnecessary synchronous image paint and layout shifts. These are
progressive browser hints, **not a claim of measured S24+ speedups**.
See MDN HTML image lazy loading / decoding documentation.

The Node regression `node tools/v4/test_accessible_review_tiles.cjs`
executes the real JavaScript tile generators using fictional People,
groups and faces. It checks semantic structure, focus styles, no nested
buttons, independent checkbox and face open actions, safe name escaping,
and absence of empty-image URLs. The Android browser still needs later
manual accessibility/touch verification after backed-up opt-in deployment.


## Cold portraits: a small, bounded on-phone decoder budget

The browser already shows a maximum of 48 People portraits per page and marks
them as lazy-loaded. However, one cold page can still generate many overlapping
HTTP image requests. V4's threaded loopback server previously let all uncached
thumbnail requests load/resize/encode an original at once. Portrait decoding can
hold a roughly 3200px color image and intermediate crops, so this is a
potential memory-pressure problem on the S24+.

**New behavior:** at most **two** workers may actively decode/encode cold
thumbnails at once. Cached JPEG hits bypass this limit entirely. If two
requests miss the *same* thumbnail, a short-lived per-key lock ensures only
one thread decodes it; the others reuse the first generated JPEG. Locks
are evicted as soon as that key has no active callers, so the in-memory
registry cannot accumulate one entry per photograph. Failed
decodes release capacity automatically and the existing temporary-file
cleanup and atomic cache replacement remain intact. This limits peak
simultaneously live **thumbnail decoder** work; it does **not** impose a
hard RAM limit across SQLite, background scanning, OpenCV native threads or
other app activity, nor does it guarantee faster cold-page completion.

The synthetic benchmark `python3 tools/v4/test_thumbnail_decode_budget.py`
launches 24 concurrent requests with tiny fictional image rows and an
instrumented fake image decoder, compares unbounded vs bounded concurrency,
then verifies duplicate-miss coalescing, warm-cache bypass, metadata-triggered
redecode and error recovery. Its elapsed times reflect an artificial
sleep-based mock, **not a real S24+ latency measurement**. A private phone
performance trace requires owner-approved deployment **after** a verified
People database backup and no conflicting Termux jobs.

Implementation has no new image libraries, cloud traffic, photo mutations
or persistent profile metadata. Original files remain read-only.



## Rapid navigation: latest render wins

Opening another People profile, changing review modes/pages or switching tabs
starts asynchronous local requests. On slower phones or after a large gallery
index, earlier requests can finish **after** later ones. The browser now
assigns a generation to each render and only allows the *latest still-active*
tab/page response to replace the visible screen. Older responses **and
their errors** are ignored; genuine errors on the active view remain visible
with HTML escaping. It does not cancel underlying indexing jobs or database
writes, alter confirmed identities, save private IDs in browser storage or
change thumbnails.

The synthetic Node regression deliberately resolves fictional network
responses out of order across tabs, within one Person's different pages,
during failed requests and when returning to an earlier tab. It confirms
the latest view remains visible and old errors cannot replace it.

\`node tools/v4/test_render_race.cjs\`

No real phone performance guarantee is implied. Installation on S24+
requires the separate backup/owner-controlled update gate.



## Person review: targeted, parallel loading instead of full-library rescoring

Opening an individual Person and switching between **Show photos**,
**Review tags**, and **Review suggestions** now issues only two independent
local requests: that Person's count/name summary and **only the visible mode's
page**. They execute concurrently. Previously every profile render fetched
and rescored the **entire People roster and all named portraits**, then
sequentially fetched photos even when reviewing face tags or suggestions.
This eliminated work does not alter any identity suggestion, tag, album,
person portrait, gallery original or manual review behavior.

The single-Person summary uses an indexed, Person-filtered SQLite aggregate,
excludes archived merged profiles, and returns the same name/face/photo totals
as the existing People list. No new caches of private identity metadata and
no browser-storage changes are introduced.

Synthetic tests:
\`python3 tools/v4/test_person_targeted_summary.py\` fills a *fictional*
2,001-person / 16,008-face database and reports timings for whole-roster
versus per-profile queries. The numbers are **CI measurements only**, not
a claimed S24+ benchmark. \`node tools/v4/test_person_targeted_loading.cjs\`
verifies all three tab modes issue exactly the relevant two requests *in
parallel*, with no unnecessary hidden-mode photo query.

The running Termux process and installed Android app are unaffected by a
GitHub merge. A private database backup and owner-controlled preview update
remain prerequisites for real-device acceptance testing.



## Reject selected suggestions: verify the person and preserve Undo

**Reject selected** in a named Person's **Review suggestions** tab now refuses
a stale or mixed selection before any identity change. Every requested face
must still be unassigned, visible and suggested for **that specific person**.
It cannot create a rejection record for a face that was never suggested,
belongs to another person or has already been confirmed.

Rejecting a valid page-local selection records a one-use **Undo last review
action** token. Undo restores the prior suggestion flags/scores and the
exact former rejected-pair set, including pairs that already existed before
the rejection. If face review or related rejection history subsequently
changes, Undo fails closed rather than erasing later decisions.

No automatic identity assignment is introduced; photos and videos stay
read-only. Undo evidence contains only bounded review IDs and flags, no
paths, source photos or face embeddings. Installed phone data remains
untouched until a backed-up, owner-controlled V4 preview upgrade.

Synthetic check: \`python3 tools/v4/test_reject_undo.py\`.


## Remove selected tags: reversible, with automatic-match history preserved

On a named Person's **Review tags** page, **Remove selected tags** now requires
that every chosen face is still assigned to that exact active Person. Missing,
hidden or reassigned IDs cause the whole batch to fail before changing any
identity. This protects against stale tabs and forged or outdated selections.

The action supplies the normal **Undo last review action** button. Its bounded,
one-use transaction snapshot covers affected face review/group state and
related rejected-person pairs and historical \`auto_matches\` records, including
their previous reverted state. Older \`review='auto'\` tags still record a
rejection when explicitly removed; Undo restores that history only if no
related changes occurred subsequently. Newer changes refuse Undo rather than
being silently overwritten. The archive of audit \`events\` is retained.

The browser submits only the currently selected face IDs and the intended
profile ID; unconfirmed model suggestions are not silently approved. No
original photos or videos change. The updated preview must be explicitly
installed by the owner after a verified private People SQLite backup.

Synthetic check: \`python3 tools/v4/test_unassign_undo.py\`.


## Hide selected: reversible, review-only, page-local

**Ignore selected** in Unsorted faces and Find lookalikes now hides only
still-unassigned, visible faces. An already named, previously hidden or
missing face in the request causes the **whole batch** to be refused before
any change; a stale browser selection cannot erase someone's confirmed tag.

A successful Hide action now supplies the normal **Undo last review action**
button, including within Find lookalikes. Undo restores face status and
affected provisional Discover-group membership and covers exactly as they
were before hiding. If later review changed any affected face or group, Undo
refuses to overwrite it. The short-lived in-app undo history holds 12
actions; keep your regular private People SQLite backups as well.

Only the selected visible-page face IDs are submitted, never the full
library. Originals and source files remain read-only. New Undo behavior
applies only when the updated V4 preview is owner-installed after backup;
merging a GitHub PR does not change the running phone process.

Synthetic check: \`python3 tools/v4/test_ignore_undo.py\`.

## Duplicate-profile merges: archive first, restore whenever safe

The **Merge profiles** action now transfers confirmed face assignments into
the chosen target without deleting the source identity row. The original
source name/note, historical rejection decisions, auto-match provenance,
pending suggestions and manually chosen portrait remain stored locally.
Archived sources disappear from normal People search and cannot be selected
for new tagging or model reference matching, avoiding accidental reuse.

From the surviving target, choose **Restore merged** to see archived sources
and explicitly separate a source again. Each archived source has a **durable**
record of the face IDs and review states transferred, independent of the
browser's last-action Undo button (whose token list retains only 12 actions).
Restoration returns precisely those faces to their original source and
reactivates that source identity; other People and photo files stay untouched.
If a moved face has since been reassigned/edited, or the inherited portrait
has been changed, restoration refuses the entire transaction to avoid
silently overwriting newer work. Change those later edits through explicit
review instead. Multi-hop merges are intentionally blocked.

The migration adds \`people.merged_into\`, \`person_merges\` and
\`person_merge_faces\` using SQLite's existing connection initialization.
Merge evidence stores **no original paths, image pixels or face embeddings**;
it stores IDs, review flags and necessary portrait metadata. Archived identity
data remains on-device until a deliberate future deletion policy is designed.
Merging does not identify anyone automatically, and no proposed match is
confirmed without the user's approval.

**Before upgrading the local preview**: finish active indexing, verify a
backup of your private People SQLite database and confirm the installed
preview version. Merging this PR on GitHub does **not** update a running
Termux process, your installed Android APK, or your private data. New
reversibility applies to merges performed by the updated preview; it cannot
recover profiles hard-deleted by older preview versions.

Synthetic check: \`python3 tools/v4/test_merge_archive.py\`.

## Discover: resume the review queue after a browser refresh

Discover remembers **only four non-identifying navigation values** in the
local browser storage for the V4 loopback origin: sort mode
(Largest/Smallest/Surprise me), Needs review vs All groups, the page
number and seeded shuffle value. It stores **no names, face IDs, People
IDs, photos, file paths, embeddings, matches, selection, private session
tokens or settings**. The browser may deny storage (private/incognito
mode); Discover then works normally without persistent restoration.
Clearing this website's browser storage resets these four preferences.

Opening Discover again after a reload returns to the saved page and sort
without jumping to page 1. The latest indexed group count is checked
before fetching that page; if manual review or a new scan has reduced
the queue, the saved page is **clamped in one request** to the last
existing page. A concurrent queue change still safely falls back to
the previous populated page. Group details remain review-only.

The synthetic regression checks the active and complete group counts
against fictional manually assigned/hidden faces, browser JavaScript
syntax, stable pagination, and a Node-tested strict cursor decoder.
The decoder accepts only bounded integer pages (0..499), uint32 seeds,
the three permitted sort names and a boolean active-queue mode.
Unexpected fields such as private names/paths are discarded, not stored.

As with other V4 browser changes, GitHub merging this feature does **not**
restart the on-phone server or modify the actual SQLite People database.
Update the locally running preview only after finishing its jobs and
verifying a private database backup.

## Discover: show only faces still needing review

The newest V4 preview opens Discover in **Needs review** mode. Unlike the
historical cluster count, the live **remaining** number counts only faces
that have **no assigned person and are not ignored**. Active group tiles
also choose their cover from these remaining faces, prioritizing the
highest-quality candidate. A historical cover that has since been named
or ignored is never misleadingly displayed as the active-group portrait.
A fully completed cluster in **All groups** still retains its original
historical cover (read-only fallback). Naming or ignoring a
group's last face automatically removes that completed group from the active
queue without deleting any record; **All groups** restores every historical
cluster instantly, with a clear zero-remaining label. Largest/Smallest now
refer to *remaining work* while Needs review is active, and to original
cluster sizes while browsing All groups. Seeded Surprise me continues to
page stably through the chosen set.

If a review action empties the current Discover page, navigation steps
back only to the nearest populated page, never to page 1 unexpectedly.
Existing per-page resemblance hints remain **unverified** and no person
is assigned, merged or inferred by viewing this queue.

All these changes are **local, read-only queries**. The synthetic regression
covers assigned/ignored faces, live pending counts, identical seeded
pagination, complete historical access, and zero SQLite writes from
Discover navigation. Only separately owner-approved review actions change
labels. Code merged in GitHub does not replace the live Termux V4 process
or inspect your actual faces. Finish jobs, verify a local backup and
explicitly choose the bundle before updating the live preview.

## Discover: a stable "Surprise me" order

The provisional Discover gallery now offers **Largest first** (the existing
default), **Smallest first** (surface tiny/overlooked clusters), and
**Surprise me** (seeded shuffle), plus **Shuffle again** for a
different order. Unlike re-running clustering, this only reorders the
already-existing candidate-group tiles. The order stays stable across
Next/Previous pages and opening/returning from group review; paging never
silently loses groups. Names, face embeddings, clusters, confirmations and
original photographs remain unchanged. "Possible already named" hints are
still computed only for visible groups and remain unverified.

The preview keeps this choice in browser memory while it is open, not across
server restarts; it does not yet persist a snooze or filter out known people.
There is no implied automatic group-to-person assignment. Surprise me
simply helps surface smaller clusters that can be buried in largest-first.

Synthetic regression (2,000 fictional clusters, 50 pages, multiple seeds,
legacy largest-first ordering, no SQLite writes):

```sh
python3 tools/v4/test_group_discover_shuffle.py
```

No phone DB or V4 server is updated by merging this code. Install the next
preview bundle and restart V4 **only after** finishing jobs and taking a
verified local SQLite backup, as described below.

## Discover groups: possible existing People, *before* opening each group

On the Discover listing, V4 can now show a **small, explicitly unverified**
"Possibly already named" hint when at least two different photos from a
provisional group resemble the same manually confirmed Person. This avoids
repeatedly treating every candidate group as an entirely unfamiliar identity.

The hints are produced **in one bounded batch per page** (up to 80 groups,
five candidate faces per group and four confirmed exemplar faces per Person).
A single matrix computes cosine resemblance and excludes same-photo
references; multiple detections from one photograph do not create artificial
support. The test fixture covers 40 groups × 500 named People and reports
CI-only timing, not S24+ performance. Extra result fields are informational
and never edit People, face assignments, provisional clusters or photos.

Mixed groups may show *two* possible existing People; **all groups stay
visible** even when no hints qualify. The group detail page retains its own
deeper matching and face-level review. A hint is NOT a probability, verified
identity, instruction to auto-attach, or reason to bulk-confirm a group.
Human review and the Unknown option remain mandatory.

This first step does not implement a global Discover "already-known only"
filter: evaluating that category for every one of hundreds of groups would
be a different workload, and filtering only the current page without
saying so would misrepresent the library.

## Read-only policy frontier — diagnostic experiment

The safest next recognition improvement is **measurement**, not immediately
lowering confidence thresholds. On the **latest** local V4 preview, when no
critical Termux tasks are running, compare predeclared threshold combinations
on exactly the same held-out manually confirmed face identities:

```sh
python ~/atlas_v4_latest.py auto-calibrate --limit 500
```

This command creates an **in-memory masked SQLite snapshot**; it does not
modify the source People database, profile labels, native sync CSV, auto-match
checkpoint, application config or photos. It does not upload anything.
The report contains only aggregate numerical statistics for the existing
baseline and five stricter/more exploratory candidate policies. It deliberately
does **not** switch the threshold. Experimental score policies are refused
by code if any caller asks to commit a link.

Compare **known-person coverage** and **wrong-person links** together with the
separate **unknown-person false-link count/rate**. These unknown trials contain
confirmed profiles with only one or two source photos. Observations are
correlated (e.g. photo sessions, similar faces, mislabeled references), and
rare errors are not ruled out by a zero-count holdout. Some policy rows may
show no apparent errors simply because the evaluation is small. Therefore
**none is validated for unsupervised 25k-face backfill**. Choose further
measurements and a reversible monitored pilot rather than implicitly adopting
the highest-coverage setting.

The command adds modest local CPU/memory work beyond `auto-eval`; for a
large 500-face holdout it repeats read-only scoring six times on the same
snapshot and does not run inference on new photos or download models.
Run it only after installing the updated preview when convenient, not in the
middle of an active Termux indexing session. The existing `auto-eval` command
and all default matching thresholds are unchanged.

## Continuous People recognition — first experimental implementation

ATLAS should behave like Google Photos here: after each incremental image scan, V4 automatically links clear matches to **existing named People**, with no per-person ceremony. Unknown, ambiguous, low-quality or conflicting faces stay in Discover. Find Face Groups remains optional discovery, not a mandatory follow-up after every scan.

Strict criteria in this preview: three **manually confirmed source photos** per person, adequate face quality and size, cosine >= 0.78 to one face plus >= 0.73 to a second distinct-photo reference, margin >= 0.14 against the next competing identity, no previously rejected link, and no same-person conflict in the photo. These are engineering thresholds, **not calibrated probabilities** or guarantees. They will miss genuine hard matches; validate on local data before broad rollout. Automatically labeled faces have a distinct provenance state and **never teach the recognizer further automatic matches**.

In the optional *new* preview, continuous auto-linking is enabled for subsequent scans but can be disabled in Overview. The running prior Termux server is unchanged until you explicitly switch scripts after backing up the database. The new local SQLite table stores per-face score, model version and batch identity. No photo files are written or uploaded.

The Overview buttons **Match old photos automatically** (up to 25,000 previously indexed, unassigned faces per run), **Undo last automatic batch** and the enable/disable toggle are available. The catch-up job checkpoints every 500 faces, resumes from the last examined face after interruption, and automatically rechecks old uncertain faces if confirmed People examples change. Preview mode never advances this cursor. CLI equivalents:

~~~sh
python ~/atlas_v4_latest.py auto-match --limit 25000 --preview
python ~/atlas_v4_latest.py auto-match --limit 25000
python ~/atlas_v4_latest.py auto-undo
# Optional full recheck against today's confirmed reference labels
python ~/atlas_v4_latest.py auto-match --restart
~~~

Before allowing broad unattended linking, evaluate the conservative matcher against a local holdout of your own **manually confirmed** identity labels:

~~~sh
python ~/atlas_v4_latest.py auto-eval --limit 500
~~~

This creates a temporary **in-memory** database copy and tests two distinct failure modes: **known-person assignment** (balanced holdout across people with >=4 confirmed photos, keeping >=3 other confirmed photo references) and **un-enrolled-person false linking** (profiles with only 1–2 manually confirmed photos, withheld as unknown identities). The output distinguishes `correct/wrong/abstained/precision/coverage` on enrolled profiles from `unknown_held_out/false_unknown_links/unknown_abstained/unknown_false_link_rate`. A person with zero manually confirmed photos is **not represented** by either holdout; neither metric establishes true novel-person precision. The sample is limited, manually selected, and may contain correlated photos or duplicate identities. Stable photo-hash sampling and round-robin profile balancing avoid giving the entire test budget to early person IDs; all withheld same-person detections from a sampled photograph are removed as model exemplars. The original People database, original photos and labels are **not modified**. A numerical zero-error upper bound assumes unrealistic independent representative samples and is descriptive only. **Any false unknown-person link is a reason not to enable broad automatic assignments.**

An undo never overwrites manually confirmed identity tags, and it adds a negative label so the same wrong match is not silently repeated. Manually removing an auto-linked face also records this correction. The provenance log and conservative tests provide safeguards; the correct long-term threshold needs a private held-out benchmark on actual S24+ photos. Until then, this is an **experimental implementation**, not a proven Google Photos-quality matcher.

## Latest preview download — GitHub Action

The repository can now publish **ATLAS-V4-Preview**, a small GitHub Actions artifact containing the current Python preview, the stable Termux installer, a source-commit manifest and SHA-256 checksums. This solves the recurring problem where a ChatGPT attachment becomes stale while the source code improves.

1. On your phone, open the private repository's GitHub **Actions → ATLAS V4 Termux Preview Bundle** and choose its latest successful run (or Run workflow). Download the **ATLAS-V4-Preview** artifact ZIP while signed in to GitHub.
2. In Termux, unzip the latest downloaded bundle into Android Downloads. The glob also catches a ZIP saved as `ATLAS-V4-Preview (1).zip`:

~~~sh
latest=""
for f in ~/storage/downloads/ATLAS-V4-Preview*.zip; do
  [ -f "$f" ] || continue
  if [ -z "$latest" ] || [ "$f" -nt "$latest" ]; then latest="$f"; fi
done
if [ -n "$latest" ]; then
  unzip -o "$latest" -d ~/storage/downloads/
  cp ~/storage/downloads/atlas_v4_install_latest.sh ~/
  bash ~/atlas_v4_install_latest.sh
else
  echo "Download ATLAS-V4-Preview from GitHub Actions first."
fi
~~~

3. First run a **read-only benchmark** to inspect matching quality after stopping the prior V4 server:

~~~sh
python ~/atlas_v4_latest.py auto-eval --limit 500
~~~

The script installer does **not** restart a running server, modify photo files or change People labels. The evaluator copies the DB in memory and does not persist predicted labels. Before launching the auto-recognition-enabled server on port 8767, create a fresh V4 online SQLite backup using the previous stable script.

GitHub artifacts are short-lived and require repository read access; do not treat them as backups of the user's database. Neither the artifact nor repository contains images, names or face embeddings.

## See exactly what V4 is doing right now

On your Galaxy S24+, open **http://127.0.0.1:8767 → Overview → Background jobs**. This reads the state of the *running local V4 web server*: active phase, progress and ETA. V4 does not launch scheduled scans; the user starts scanning, grouping or suggestions through Overview buttons or Termux commands. A job can keep running after leaving the browser as long as its Termux process survives.

The preview also makes the CLI status command query the live web server:

~~~sh
python ~/atlas_v4_latest.py status --port 8767
~~~

If the web server is unavailable, the CLI reports job phase **unknown**, not an inaccurate **idle** from a newly created Python process. A scan run in a *different Termux process* has its own status; inspect the relevant Termux session if needed.

## Android Downloads suffixes: permanent install helper

The browser may save a filename like **monochrome_studio_v4_group_candidates_preview (3).py** rather than overwriting an earlier download. Install the helper **once**, then every future script update takes one stable command:

~~~sh
bash ~/atlas_v4_install_latest.sh
~~~

The helper source is **tools/v4/install_latest_download.sh**; save a copy to the phone as **~/atlas_v4_install_latest.sh** (one-time setup). It examines all matching downloaded V4 preview Python files, chooses the newest by file modification time even when its filename contains (1) or (2), prints the selection, verifies Python syntax and installs atomically as **~/atlas_v4_latest.py**. It retains the prior version as **~/atlas_v4_previous.py**. Missing downloads or invalid Python cannot overwrite the installed copy.

The helper updates only the script file. It does not terminate or start a running server, access original photographs, or migrate People data. **Finish jobs and back up V4 before restarting the server with a new script.**

~~~sh
python ~/atlas_v4_latest.py serve --port 8767
~~~


This is an opt-in local Python web-server update to Monochrome Studio V4. It uses the existing `~/.monochrome_studio_v4/people.sqlite3` on **your Android device**. No photos, face data, or private names are included in the GitHub source.

## October 14 Discover: suggest *already-known people* in each group

The **Possible same person** group screen now presents up to three existing People profile portraits ranked by a **read-only** SFace resemblance check. Tap **Review & attach** to inspect the destination profile and face count, then explicitly confirm before changing labels. You can always attach via the full searchable person picker instead.

Model scores are **cosine similarities, not identity probabilities**. Candidate generation samples up to 24 high-quality faces in the group and 12 confirmed reference faces per named person (preserving memory). Faces from the same photograph are excluded as independent identity evidence, and mixed-person groups may have competing suggestions; **use Add selected to person** for mixed groups. Never automatically attach an entire group just because the top suggestion looks plausible.

No private photos or biometric data are uploaded; the API reads local existing embeddings and doesn't mutate the face database. Tests use synthetic 128-dimensional vectors and exercise mixed groups, empty groups, exclusion of same-photo evidence, and zero database mutation.

## October 14 usability and portrait-quality upgrade

- **Wrong-person protection:** tapping a target portrait opens an explicit review pane showing the recipient and face count. Only pressing **Confirm / Assign faces** or **Merge profiles** commits the transaction; Cancel or Back writes nothing. The server also rejects attempts to reassign any already-confirmed face in a bulk selection.
- **Android keyboard:** opening the picker focuses the non-editable dialog, not the Search field, so the on-screen keyboard stays closed until requested.
- **Better People portraits:** choose the best confirmed face by usable crop size, detector confidence and scan-quality estimate—not the earliest face in the database. The profile uses a higher-resolution crop; tiny faces in the original photo can still appear blurry.
- **Manual override:** inside a person's **Review tags** tab, tap **Use as portrait** on any of that person's confirmed faces. A separate `person_covers` SQLite table remembers the choice; all original pictures stay untouched. The app rejects a portrait from another person's faces and falls back to an automatic cover if the selected face is later unassigned. Profile merges retain a manually chosen source cover only if the target has no override.
- **Migration:** connecting with the updated preview adds one small `person_covers` table to the existing SQLite database. **Take a V4 online backup first.** Original face/people rows and all source photographs remain unchanged.

## Changes

- Searchable portrait picker for assigning selected faces and merging People profiles, replacing the list of numeric IDs.
- **Name as NEW person:** one native name prompt → create and assign that reviewed group, without opening a People picker or requiring a second confirmation. Creating an accidental duplicate is recoverable through a later reviewed merge. Check that the group actually belongs together before naming.
- **Attach group to EXISTING person:** a deliberately separate button opens the portrait picker and requires explicit destination confirmation. **Merge profiles** and **Add selected to person** remain confirmation-gated.
- Whole-group attachment uses a transaction, verifies the group size hasn't changed since the review, refuses unknown target profiles, leaves already-confirmed face assignments untouched, and records an audit event.
- **Select page / Clear selection** in every face selection context; only displayed faces are selected.
- Opening and leaving a Discover group preserves the source group-list page and its scroll offset, including after attaching or naming a group.

**Known limitations:** A group can contain unrelated people. The current UX allows whole-group attachment only with an explicit user confirmation; for a mixed group use **Select page** and **Add selected to person** instead. In browser state is volatile across process refresh/restart. The picker is searchable and shows at most 100 matches at a time, so very large libraries remain manageable. Group renames are not automatically cross-profile deduplicated.

## Safe trial on your own Termux device

1. Finish any running V4 job. Back up your newly curated database using its built-in online backup command:

```sh
python ~/monochrome_studio_v4.py backup --output "$HOME/storage/downloads/atlas_v4_before_people_ux.sqlite3"
```

2. Stop the running V4 `serve --port 8767` process by Ctrl+C in **that server session** (don't kill your V5 search server on 8775). Copy the preview file downloaded from ChatGPT Library to your Termux home:

```sh
cp ~/storage/downloads/monochrome_studio_v4_people_ux_preview.py ~/
python ~/monochrome_studio_v4_people_ux_preview.py serve --port 8767
```

3. Open `http://127.0.0.1:8767` on the same phone. Test a group near the end of the Discover list; try Back, visual selection, a known existing person's name and Cancel before making any live identity changes. Verify that the group-list page and scroll position are retained.

4. To roll back: Ctrl+C the preview server and restart the original `python ~/monochrome_studio_v4.py serve --port 8767`. The original script is never overwritten. Both scripts use the same existing SQLite database so accepted label actions persist; use the pre-test backup if you wish to recover prior assignment state.

## Offline regression

```sh
python3 tools/v4/test_people_ux.py
node tools/v4/test_quick_create.cjs
```

The test uses only SQLite's in-memory/synthetic fixtures and lightweight mocked CV imports. It checks strict identity assignment boundaries and browser JavaScript syntax. No private photos or pretrained model weights are required.

**Not yet validated on Galaxy S24+ browser.** Treat as preview pending user acceptance; do not auto-promote to installed V4.

## 16 October 2026 — Lane D reviewed People actions

UX research: [digiKam's People management](https://docs.digikam.org/en/left_sidebar/people_view.html), [PhotoPrism's face assignment](https://docs.photoprism.app/user-guide/organize/people/) and [Immich's reviewed face merge](https://immich.app/docs/features/facial-recognition/) all distinguish suggested identity from confirmed human review. ATLAS emphasizes stronger explicit destination choices and refuses silent duplicate-profile linking.

- **Existing names:** a Unicode-compatible name match opens a searchable, portrait-backed chooser. The destination's immutable profile ID and face/photo count help distinguish genuine same-name people. Confirm an existing destination explicitly or separately acknowledge that a new person shares the same name. The normal unique-name route stays a single prompt and one write.
- **Safe selection:** Select page affects visible faces only; switching pages clears any prior selection, so faces on earlier pages never remain silently selected. Back to Discover restores queue page and scroll.
- **Undo:** selected-face assignments, accepted suggestions and explicit entire-group attachments expose an immediate **Undo last assignment** option. A short-lived in-memory token identifies the write; the server transactionally captures and verifies touched face IDs, assignment flags, and provisional group membership. Undo restores earlier group records when no later changes intervened; changes or token replay fail closed. Twelve bounded history records contain only IDs and flags, never images, paths or embeddings.
- **Boundaries:** undo does not cover new-person creation, profile merges, rejection/ignoring or automatic matcher batches. Browser refresh clears the undo control. No similarity score by itself confirms identity; proposed candidates remain unassigned until specifically reviewed.

Validate offline with: python3 tools/v4/test_duplicate_name_review.py; python3 tools/v4/test_manual_review_undo.py; node tools/v4/test_quick_create.cjs; and the complete V4 People UX synthetic workflow. The repository preview is opt-in and does not touch a running Termux server, a private face database or a native installed APK.

## 16 October 2026 — review-first identity gate

Lane D identified that the older experimental V4 scanner treated confident model candidates as confirmed assignments without selecting each face. This is incompatible with the new review-only requirement. In the optional updated preview, **scan indexes faces but never auto-confirms an identity**. Tap **Suggest profile matches** to populate an unconfirmed suggestion queue, inspect the faces in a person profile, then explicitly **Accept selected** or reject them. Accepted faces retain a bounded, transaction-verified Undo action.

Review-first behavior remains in force even when an older settings.json contains auto_assign_enabled=true. The browser no longer offers an auto-identification toggle or a background bulk-confirm button; legacy settings updates that try to enable it are rejected without changing settings. The old auto-match CLI accepts only the --preview read-only mode, and the legacy background auto job aliases to staging suggestions rather than assigning People. Direct auto-identity model functions remain intact only for synthetic experiments and read-only calibration; no normal preview entrypoint invokes them to confirm identities.

Historical automatically linked faces are not silently unassigned or reinterpreted. The existing explicit auto-undo recovery operation is preserved. The user's running Termux process and actual data are **unchanged**; this policy applies only if a future preview is deliberately installed and started. Verified with offline scan regression even with the previous auto-on configuration and a static entrypoint safety test.

## Lane D — searchable People roster and return-position continuity

The People landing page now supports an on-device **Search named people** field with exact-name and prefix-first ordering (then substring matches), Unicode NFKC normalization, and clear same-name profile IDs. The full People metadata list is fetched once on entering the roster, but the DOM creates **at most 48 lazy-loaded portrait cards** per page instead of rendering thousands at once. Subsequent keystrokes search cached metadata without a network round-trip or replacing/focusing the Android search field. Source names are HTML-escaped; no telemetry, face embeddings, photos or People metadata are uploaded.

Opening a named profile and using **Back to People** restores the roster's search term, result page and vertical scroll while refreshing the latest confirmed counts/portraits. The existing Discover return context and anonymous persisted Discover cursor stay independent of the named People roster. Names, profile identifiers and search terms are never stored in localStorage.

An offline Node regression builds 2,001 fictional named profiles, verifies that 42 pages cover all IDs exactly once, bounds each page at 48 portraits, tests exact/prefix/substring ranking, Unicode normalization, HTML escaping, cached searches and return-state restoration. Run `node tools/v4/test_people_search_resume.cjs`; the full V4 People UX workflow also runs it.

Limitation: the initial roster still fetches metadata for all named People once per visit; only portrait nodes and search round-trips are bounded. Browser/Galaxy S24+ acceptance and image decode/memory profiling require an opt-in preview after a backup; the repository does not touch the running V4 process.

## 16 October: face inspection is a reversible review detour

Tapping a face while reviewing page 24 of a Discover group or page 12 of a person's confirmed faces must not teleport the reviewer to page 1 after leaving Find lookalikes. The preview now captures the origin tab, numeric page and scroll offset in volatile browser memory before entering similarity inspection, and restores that exact view on Back. The same logic covers the Find more appearances button from a named person's profile. No face or person identifiers, paths, or query inputs are persisted in browser localStorage. A canceled/failed detour has a safe previous-tab fallback.

Offline Node regression: `node tools/v4/test_similarity_return.cjs`. This remains distinct from Discover's anonymous page/ordering cursor and the named People search state; both are preserved independently.

## Same-name rename safety

Renaming a People profile now follows the same duplicate-name boundaries as creating a profile: a server-side NFKC/casefold comparison rejects accidental collisions in the same transaction as the update. If the name is already used by another profile, the browser displays a clear extra confirmation with the existing profile ID and face count. Cancel leaves all names unchanged; confirming deliberately allows two independent people to share a name. This operation is never an implicit profile merge. The old name is recorded in the local audit events for manual correction.

Offline regressions: `python3 tools/v4/test_rename_safety.py` and `node tools/v4/test_rename_safety.cjs`. No private face data or Termux session touched.

## Lane D — Undo new People creation (16 October 2026)

After deliberately naming a new person from a reviewed face selection, provisional cluster, or empty People profile, **Undo last review action** can remove the newly created profile and restore its previous unassigned faces and provisional group metadata in one SQLite transaction. The old Undo already covered explicit assignments to existing People; this extends it to new profiles without modifying a photograph. The normal `create_person(...)` Python interface still returns a person ID unless the dedicated browser route requests an undo token.

The one-use token is retained only in the current browser session; twelve bounded server-side snapshots capture face/review/group IDs and flags plus a SHA-256 fingerprint of the originally created person's metadata (not the name itself). Undo **fails closed** if the profile was renamed, gained or lost faces, gained a portrait override, had a rejection/auto-reference added, or any affected group/face changed since creation. The profile is deleted only after all preconditions pass and its original faces are restored; there is no cascade deletion of photographs or original image files. The action is not an unconditional destructive profile delete and is *not* a general undo for older independent renames/merges.

Synthetic regression commands: `python3 tools/v4/test_new_person_undo.py` and `node tools/v4/test_new_person_browser_undo.cjs`. These also run in the V4 People UX workflow. Synthetic success is not a Galaxy S24+ browser/storage acceptance claim. Existing running Termux preview and private People database are untouched until an explicit owner-controlled upgrade and backup.

### Person chooser keyboard focus (18 October 2026)

The explicit existing-person chooser is a two-step **review, then confirm**
modal. It now keeps `Tab` / `Shift+Tab` inside the *visible* browse or
confirmation step (excluding hidden and disabled controls). The browsing
heading takes initial keyboard/screen-reader focus rather than the Search
input: on Android this avoids opening the soft keyboard over the portrait
list. The confirmation step focuses **Go back** rather than Confirm. Back
returns to the previously selected Person tile; Cancel/Escape restores the
invoking review tile without page-position changes. If controls are
temporarily disabled while saving, focus is held on the visible step heading
instead of escaping behind the overlay. The existing in-flight write guard
and explicit confirmation contract remain authoritative.

This follows the W3C WAI-ARIA modal dialog keyboard pattern
(https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/) and its guidance
to return focus to the invoking control. `node
tools/v4/test_picker_focus_trap.cjs` exercises both directions of wrapping,
non-tabbable headings, hidden browse controls, disabled buttons,
confirmation cancellation, and zero identity writes on keyboard-only review
using *fictional* people. This is synthetic browser testing, **not** a claim
of TalkBack/S24+ acceptance or deployment into the currently running V4
server. No original photos or private identity database are touched.

### Changing a chooser search cancels pending Person selection (18 October 2026)

An asynchronous one-Person portrait request now binds the pending choice to
the **current normalized search**. Any subsequent query change invalidates
older tapped choices before drawing new results; when the old request
eventually finishes, it may populate safe portrait metadata but **cannot**
replace the user's new list with an unrelated identity confirmation.
A new deliberate tap still opens the existing explicit review-and-confirm
step. This avoids a late-response surprise without auto-assigning or
merging any faces. `node tools/v4/test_picker_scoped_covers.cjs` exercises
the fictional slow tap → change search → late portrait → fresh tap order.
The live phone preview and private V4 database remain untouched.

### Lightweight Person-name actions for large libraries (18 October 2026)

Name collision detection for **Create separate Person** and **Rename**, and
the current profile lookup for **Merge**, use `/api/people?covers=0`
instead of triggering portrait quality scoring for the complete active
roster. The chooser still displays on-demand, bounded portrait thumbnails
when a reviewer deliberately selects an existing destination. These
metadata-only reads provide the same IDs, normalized-display names and
counts required for safety prompts; a duplicate name remains a **separate
identity unless explicitly accepted**. The backend performs an independent
name collision check when writing, so the browser roster is not an
authorization boundary. The merge action still requires the separate
explicit destructive-style confirmation; no silent merge.

The synthetic create/rename cases enforce the metadata endpoint, while
`node tools/v4/test_merge_metadata_chooser.cjs` exercises an explicit
merge of two among 2001 fictional profiles with no portrait scoring,
self-merge refusal and retained Undo token. This reduces needless query
work; it is **not** a measured mobile latency/frame-time speedup and has
not been run against the private phone library. Open-source comparison:
PhotoPrism (https://docs.photoprism.app/user-guide/organize/people/)
emphasizes named review with explicit corrections; Immich
(https://docs.immich.app/features/facial-recognition/) exposes distinct
People merge and feature-photo controls. ATLAS keeps their deliberate
confirmation pattern but adds defensive separation for homonyms and
stale reviews.

### Fast review pager taps: single-flight per visible screen (18 October 2026)

A physical double-tap, key-repeat or stale button event can activate the
old **Next** / **Previous** action multiple times before its replacement
page has finished rendering. Previous behavior incremented the shared
page index on every activation, so the user could jump over an **unseen**
page of 40/60 faces or groups. The handler now holds an *in-memory*
single-flight turn token tied to its exact `renderEpoch` and review
subject. Further page-turn activations against that same in-flight screen
are ignored until paint completes. Unrelated navigation (different
tab/Person/group, newer generation) is never blocked; a superseded
request cannot release a newer screen's guard. A failed render also
releases its own guard, allowing an explicit retry. This does not add
persistent storage or affect database assignment semantics.

`node tools/v4/test_review_turn_singleflight.cjs` executes the actual
browser handler against 2,001 **fictional** group IDs: eighty stale rapid
button events advance exactly once; fifty more deliberately completed
turns cover every ID exactly once when the queue is unchanged; a
240-item queue shrink, old-result completion after Person navigation,
in-flight new-Person tap and exceptional render are isolated. The
existing page-focus and stale-response tests remain in the same V4 CI.

**Limit:** This is not a snapshot-consistent cursor over a *mutating*
offset-based queue. If external scanning/other browser activity removes
items earlier in the sorted list between independently completed turns,
offset pages can still shift. Never interpret one pass over a mutable
queue as exhaustive identification; a later Needs review pass is
appropriate. The synthetic fixture and CI cannot establish S24+ touch
latency, screen-reader behavior, or full 150,000-image throughput.

### Same-screen overlapping Person chooser requests (18 October 2026)

A double tap or two queued **open chooser** requests can start separate
asynchronous `/api/people?covers=0` reads while the browser stays on the
same group/Person. Previous stale protection checked tab, profile and
render generation, but the *older* request could still finish after a
newer one and overwrite its title/action. The chooser now uses a
monotonic **in-memory** request generation: the latest deliberate open
wins, dismissing invalidates pending opens, and an in-flight confirmed
write cannot have its dialog replaced. This does not change which
identity is attached; it ensures the reviewer sees only the intended
explicit confirmation. Fictional regression coverage is in
`node tools/v4/test_picker_escape_focus.cjs`, alongside Escape and
focus restoration. Actual S24+ browser interaction is still unverified.

### 19 October: forward-safe Unsorted pages when earlier faces leave the inbox

An **existing Unsorted** review queue is ordered newest-face-ID first.
Suppose page 1 shows 60 faces, you assign 35 of them, then press Next.
An ordinary `OFFSET 60` jumps over **35 faces you never saw** because
the remaining rows shifted up. The preview's Unsorted Next now optionally
uses a stable **last-visible-face-ID** bound (`before_id`), so it starts
strictly *below* the last face displayed on the preceding page.
No names/paths/embeddings/face IDs are written to browser storage; the
bounds exist only for the current in-memory review session. No identities
are inferred or automatically assigned.

The new `/api/untagged?cursor=1&with_next=1` response adds
`next_before`, but unchanged legacy callers still receive their
original array or `items/has_more` contract. Missing cursor metadata
on a legacy-mocked response uses the prior offset behavior rather than
guessing identifiers. Malformed, zero, negative or over-64-bit cursor
inputs refuse the request. The optional bound comes from the last
**visible** result, never the lookahead row, with a parameterized SQL
predicate and ordinary 60-result size limit.

Synthetic tests: `python3 tools/v4/test_untagged_forward_cursor.py`
(2,001 fictional faces, 35 earlier assignments, alternating Hide, newly
inserted face, strict no-write navigation, legacy result equality) and
`node tools/v4/test_untagged_forward_cursor.cjs` (real browser handler,
saved Back boundaries, leaving/reentering Unsorted, legacy deep-page
fallback). Exact SQLite read-only cursor evidence is not a timing
benchmark on 150k private photos.

**Important limitations:** This fixes *forward continuation after changes
above the current face-ID boundary*, not a full immutable database
snapshot. Newly scanned higher-ID faces will appear after restarting
Unsorted at page 1. Deletion/modification of later faces naturally
changes what remains to review; going back to earlier pages may show
overlapping rows after previous removals. Provisional Discover groups,
suggestion-score sorting and all Person-photo queues still use their own
existing pagination semantics. Never claim that a one-pass mutable
queue is exhaustive; revisit Needs review / Unsorted once processing
finishes. An actual S24+ trial remains owner-gated.

### 19 October: safe forward paging in a named Person's Review tags

**Review tags** is also ordered by immutable face IDs. When a reviewer
removes 35 incorrect tags on page 1 and proceeds to page 2, legacy
`OFFSET 60` could skip 35 *unseen confirmed faces*, just as the
Unsorted inbox previously could. The same opt-in, strictly
descending face-ID boundary is now supported by
`/api/person/faces?cursor=1&with_next=1`. The browser stores the last
visible ID per Person's confirmed-face review session in volatile memory
and sends `before_id` on the next page. Unassign/Undo still require
separate deliberate actions and preserve their existing transactional
guards. No newly scanned or suggested identity is auto-confirmed.

The server's old `/api/person/faces` array and normal
`items/has_more` responses remain unchanged unless the new cursor
parameter is requested. The photo grid and similarity-ranked
**Review suggestions** intentionally retain their prior APIs:
a face-ID cursor would be incorrect for score-sorted suggestions.
`node tools/v4/test_person_targeted_loading.cjs` tests visible-mode-only
requests, next-page bounds, and changing modes; the independent SQLite
`python3 tools/v4/test_person_face_cursor.py` proves 180 fictional
confirmed faces remain visitable after 35 earlier unassignments, with
no navigation writes. Group detail and Discover queues remain separate.

This is **forward continuation**, not frozen snapshot semantics:
newly admitted higher-ID faces appear on a new first-page visit;
removing later records may reduce subsequent visible results;
back-navigation can overlap after review mutations. The owner
must still validate real S24+ interaction following a protected local
People backup and isolated recovery proof.

### 19 October — Discover "Stable review pass" under changing group counts

**Needs review** normally ranks groups by how many unassigned faces remain.
That is useful for prioritizing, but the number changes when the reviewer
assigns or hides faces: an ordinary page-2 `OFFSET 40` can then skip
unseen groups. The earlier Unsorted and Review tags face-ID cursor is
*not* a correct solution for dynamically ranked Discover groups.

The optional **Stable review pass** button instead sorts by each group's
**historical original cluster size (descending), then immutable group
ID (ascending)**. This preserves approximate large-group-first
prioritization while freezing the sort key as faces are reviewed.
The current remaining-face count still decides whether a group
belongs in Needs review and is displayed live in every card; the
historical count is not misrepresented as work remaining.

When Stable review pass is selected, only its opt-in
`/api/groups?order=stable&cursor=1&with_next=1` shape adds
`next_count` and `next_group`. The next page queries groups ranked
strictly after the **last visible** historical-size/ID pair,
not after a numeric offset. SQL parameters are validated, bounded
to signed SQLite integers, and used in a parameterized keyset predicate.
No database writes, private photos, personal names, or persistent
browser group-ID storage are introduced. Legacy Largest first,
Smallest first and seeded Shuffle retain exactly their established
dynamic-sort/API semantics; **they are not claimed to be skip-safe
when review mutates their ordering**.

Browser keyset boundaries live only for the active session. Returning
from group detail preserves them. Switching Discover filters/sort modes
clears them. After a browser restart with only an anonymous saved page
bookmark, Stable review pass **starts again at page 1** rather than
invent a cursor or silently skip review work. You may see previously
reviewed groups again; this is preferable to omitting unseen groups.
A newly added group *ahead* of the current cursor is discovered on a
fresh pass, not injected mid-pass. Groups no longer pending are
correctly omitted. Historical All groups still permits viewing them.

Test coverage:
`python3 tools/v4/test_discover_stable_pass.py` inserts 2,001
fictional clusters/faces, marks 27 earlier page-one groups complete,
and verifies a 51-page keyset traversal has **no missed or duplicate
unseen groups**. It also reproduces the old offset skip, checks tied
historical counts and malformed cursor refusal, and confirms no
navigation DB writes. `node tools/v4/test_discover_stable_pass.cjs`
executes the real browser renderer and tests fresh-start behavior and
legacy sort mode separation. Timing in synthetic CI is diagnostic;
this is not 150k-photo on-device FPS or recognition accuracy evidence.

### 19 October — reproducible V4 review-query scale evidence

Run `python3 tools/v4/benchmark_review_scale.py` in a checkout with
NumPy to populate an **isolated, temporary SQLite database** containing
**150,000 entirely fictional one-face photographs**, 2,500 provisional
groups and 500 named synthetic People with four confirmed distinct-image
reference faces each. None of the paths are read as image files and no
actual People database, photographs, SFace weights or Android process is
accessed. The temporary DB is automatically removed on completion.

The benchmark uses the **real current V4 implementation**, not a copied
query: it measures the first/second Stable review pass pages, a dynamic
largest-remaining Discover page, the first Unsorted faces page and the
metadata-only People roster. Each operation returns a bounded first
page; the sample report includes per-operation median and worst-sample
elapsed milliseconds, Python allocation peaks measured by
`tracemalloc`, synthetic SQLite file size, SQLite/NumPy/Python/runtime
versions and a checked **no-identity-write** invariant.
Fixture creation time is reported separately, not disguised as normal
page navigation latency.

**Quick CI smoke** executes `python3 tools/v4/benchmark_review_scale.py
--smoke` with 12,000 synthetic photos / 200 groups / 80 People and two
query samples; it validates execution and response cardinality without
setting hardware-dependent speed gates. For a more reproducible manual
comparison, run the full default several times on the same machine,
record the Git commit, CPU/RAM and whether the SQLite cache was warm.
Do not compare raw milliseconds across unrelated GitHub runners as an
Android regression claim.

**Evidence boundaries:** 150,000 indexed synthetic photo rows does not
mean 150,000 real decoded thumbnails or dense biometric matching on
an S24+. Python `tracemalloc` excludes SQLite's native heap and Android
image decoder caches. One-hot fictional 128-dimensional features are
not a measured representation of real faces. A real release still
requires private owner-approved backup/restore and phone-specific
frame-time, memory, thermal and accessibility checks (see
`PREVIEW_ACCEPTANCE.md`). This benchmark cannot authorize automatic
People assignments or native upgrades.

### 19 October — bounded, vectorized Discover-to-known-People hints

The read-only **Possible already named** hint pipeline used to allocate
and loop through separate small NumPy matrices for every
visible-group/existing-Person pair. In a 40-group page with hundreds of
People and four confirmed reference faces each, those nested Python
loops cost far more than needed even though the actual face similarity
matrix is already calculated once.

The optimized V4 preview now reduces each row of that matrix across
**contiguous confirmed-reference columns per Person** using NumPy's
vectorized `maximum.reduceat`. Same-photograph evidence remains
masked, only **distinct group photograph IDs** contribute to support,
each candidate still requires at least two independent photo IDs above
the existing **unverified cosine resemblance** cutoff, and top-three
mean scoring, three-decimal rounding, ordered tie-breaking and a
maximum of two hints per group are unchanged. No suggested identity
is confirmed, and no original photograph, tag, or private database
is modified. Its memory remains bounded by the currently displayed
Discover groups and confirmed-reference profiles rather than all
unassigned faces.

`python3 tools/v4/test_hints_vectorized_parity.py` runs the exact
preview implementation against a **frozen independent scalar reference**
using fictional mixed-person groups, repeated face detections from one
photo, same-photo references, nonfinite feature vectors, near-threshold
scores, ties and empty candidate sets. The existing 40-group/500-profile
synthetic regression also runs unchanged. The reproducible query
benchmark `tools/v4/benchmark_review_scale.py --smoke` can quantify
runtime changes on the same class of runner; any gains are **only
synthetic server query timings**, not an S24+ browser FPS measurement
or recognition-accuracy claim.

### 19 October — controlled same-process Discover hint A/B

The separate GitHub runner observations before and after #226 (roughly
292 ms versus 78–86 ms for a 12,000-row fictional Discover page) were
**not** a controlled experiment. Hardware scheduling, SQLite cache and
runner contention could differ between builds. The reproducible scale
benchmark now also runs the actual current vectorized hint scorer and
a **frozen independent pre-optimization scalar implementation**
(`hint_scalar_reference.py`) against the *same exact 40 fictional
candidate groups* and the same SQLite connection.

`benchmark_review_scale.py` alternates **scalar → vectorized** then
**vectorized → scalar** on successive pairs, requiring *exactly equal
suggested People IDs, distinct-photo support counts, rounded cosine
scores and order for every pair*. It refuses any database write. The
JSON includes separate same-process median and max elapsed milliseconds,
limited Python peak allocations, the fixture dimensions, an observed
speedup ratio and explicit caveats. This measures combined SQL read +
CPU ranking (not only NumPy compute) and never enables automatic
identity matching. With `--smoke`, the small CI fixture runs two
counterbalanced paired comparisons. Full `--faces 150000 --groups 2500
--people 500 --samples 4` is available for controlled opt-in evaluation
on one device, but must not be represented as S24+ performance unless
actually executed on the S24+ with owner approval.

The scalar reference is **test/benchmark-only**: production V4 continues
to use the optimized scorer, and both retain the existing requirement
of at least two *different* candidate photographs per named-Person hint.
Real photo decode, device thermal limits, real biometric correctness and
Android UI frame pacing remain outside this test.

### 19 October — direct People review writes: one pending network action

A fast double tap, keyboard repeat or interrupted connection could
previously send multiple independent `Accept suggestions`,
`Reject suggestions`, `Remove tags`, `Ignore selected` or
`Undo` requests before the first server write had finished. Server-side
atomicity still prevents many invalid edits, but duplicate failed
requests and inconsistent Undo feedback are confusing.

These **direct review mutations only**, including the explicitly
confirmed latest-auto-batch Undo, now share an **in-memory single-flight
guard**. Rapid repeated/conflicting writes are ignored until the
outstanding action succeeds, is cancelled or fails; a failure releases
the guard so the user can retry. Ordinary navigation, selection and
photo inspection are not blocked. Server-side checks remain the
authoritative safety boundary.

After a confirmed result, only the originally submitted face IDs are
removed from the current page selection; newly selected faces are
preserved. If the user has navigated to a different Person/tab/page (or
a newer render happened) while a POST was in flight, the old response
records its Undo token and shows a brief **previous view** completion
message but does **not** clear the new selection or steal focus via
render. A delayed Undo response cannot erase a newer Undo token. The
feature does not persist request state across reloads; unknown network
outcomes must still be verified against the live review state before
resubmitting.

`node tools/v4/test_review_write_singleflight.cjs` runs the actual
browser handler against fictional deferred POSTs: 50 Approve + 10
conflicting Reject activations result in **one** request; selection
during a pending write survives; leaving the screen never erases
another view's selection; deliberate retry after rejected network
promise works; Undo is one-shot and preserves newer tokens; cancelled
confirmation writes nothing. This does not imply real S24+ touch
behavior or authorize automatic identity assignment.

### 19 October — owner-only finalized People backup verification

The downloadable private **ATLAS-V4-Preview** artifact now additionally
includes the checksum-pinned, Python-standard-library-only
`verify_preview_backup.py`. The script is **not** a backup creator and
will not connect to the active V4 server. It accepts an explicitly named
**finalized online backup**, opens that file using an SQLite read-only URI
and refuses the obvious live `people.sqlite3` name, symlinks, WAL/SHM
sidecars, missing required People/photos/faces tables, corruption,
foreign-key violations, empty photo/face collections, oversize inputs
or insufficient temporary disk headroom.

It makes an independent restore copy in a randomly created restrictive
temporary directory, compares all user tables via bounded streaming
row-content SHA-256 internally, and checks integrity/foreign keys
again on the restored copy. Only **aggregate counts and PASS/NO-GO**
are displayed. Private names, photo paths, embeddings, event details,
undo snapshots and raw content hashes are **never printed, sent to
GitHub, or uploaded**. The original input is never opened for writes,
and the temporary copy is deleted on normal completion. A forced
process kill can leave sensitive scratch content: review privately.

See `PREVIEW_ACCEPTANCE.md` for the exact one-command Termux invocation
after obtaining a known-good **online backup** and verifying the entire
bundle checksum manifest. The verifier's success is independent
source-to-scratch **recoverability evidence**, not freshness or proof
that restoring onto an actively modified live database is safe. Do
not automatically restore anything or restart the current V4 session.
Fictional regressions in `test_verify_preview_backup.py` exercise
corrupt/missing/orphan/symlink-or-live-like files, wrong disk limits,
source immutability and data-privacy guarantees in dedicated V4 CI.

### 19 October — no silent overwriting of V4 recovery backups

The V4 preview's `backup --output NEW_PATH` action now reserves its
new target with an **exclusive create** (`O_CREAT|O_EXCL`) before
opening the active People database. If the destination already
exists — including a prior backup or the active database itself — the
command refuses it. A failed backup removes only the newly created
incomplete file when still owned by that invocation; existing
recovery snapshots are never replaced. Newly created backup files
request private `0600` permissions on a filesystem that supports
them. Completion also runs an SQLite quick-check; final owner
acceptance still requires the independent `verify_preview_backup.py`
source-to-temporary-restore proof, not just the backup command's
success message.

A hard process crash can leave a **partial new** backup: never infer
recoverability from file existence, and never overwrite an earlier
trusted snapshot. Choose a unique timestamped filename and verify it
before installing/restarting the V4 preview. Note that an **older,
still-installed** V4 script may not have this new no-overwrite guard:
the shell filename existence precheck in `PREVIEW_ACCEPTANCE.md`
remains mandatory when creating the *pre-upgrade* backup.

`test_backup_no_overwrite.py` uses fictional SQLite People/Undo
records to confirm verified recovery after backup, collision refusal,
old snapshot retention after new edits, refusal to write to a live DB
path, safe removal of a just-created incomplete output on simulated
failure, and no original photo reads.

### 19 October — fast SQL for Stable Discover page candidates

Stable review pass already provides a persistent-in-session **historical
size + group ID** cursor, preventing unseen groups being skipped when
earlier faces are reviewed. The original read-only SQL nevertheless
counted *all faces in all clusters* on every page turn, then selected
41 candidate groups. For Stable order only, V4 now selects at most
41 eligible, historically ordered group candidates **first**, then
counts their active faces and chooses their representatives using the
existing `face_cluster` index. Existing dynamic Largest/Smallest and
seeded Shuffle SQL is deliberately unchanged. No index migration,
private People write, model threshold or auto-confirmation is involved.

`python3 tools/v4/test_stable_query_fastpath.py` compares the
implementation against a separately frozen full-aggregate SQL
reference, including 2,001 fictional groups, count ties, pending-only
and All groups, selected-face removal/Ignore, representative priority,
and both cursor and offset navigation. CI requires exact card parity
with zero identity writes. The 150,000-image **isolated synthetic**
benchmark, approximate timings, platform and evidence boundaries are
recorded in [SCALE_EVIDENCE_2026-10-19.md](SCALE_EVIDENCE_2026-10-19.md).
This is not proof of a deployed S24+ performance improvement.

### 19 October — avoid late Person Rename/Merge/Restore targeting a new profile

People edits can await a metadata-only roster or archived-People lookup
before showing a confirmation. If the reviewer navigates from Person A
to Person B during the request, using the global `person` after the
await could silently rename or merge **B**, not the original target A.
The browser now captures the original Person ID and full view-generation
context **before** each async lookup for Rename, Merge or Restore.
A superseded screen fails closed before opening confirmations or writing
identities. Confirmed requests use the immutable captured Person ID,
even when navigation occurs after a POST starts; a delayed response
may preserve an Undo token but never repaints another Person's screen.

`node tools/v4/test_stale_person_actions.cjs` runs actual browser
handlers with deferred fictional roster/merge/restore network replies,
switching between two synthetic profiles before and after confirmation.
It verifies that stale requests create no extra writes, cannot open a
chooser on a new Person, and cannot steal the new view's render.
Server-side identity authorization and atomicity remain authoritative.

This only addresses the explicitly targeted profile-edit actions.
A real device acceptance run is still needed to assess touchscreen
behavior; zero private People/photos or Android app changes occurred
as part of this development.

### 19 October — freeze source group across delayed Create/Attach actions

Group naming and attaching first load a mutable pending-face count and,
when choosing a Person, may also wait for a large People-name roster.
Previously these async reads could finish **after** the reviewer opened
a different group, then use the new global `group` for the payload.
A same-sized wrong group could pass an expected-count check, risking a
review assignment to an unintended group.

V4 now captures the original **cluster ID** and exact review context
before requesting a group count. A changed tab/group/page/render
generation cancels stale count/roster responses before a name prompt
or destination chooser appears. Explicitly confirmed attachments
always post the captured group ID and original expected face count.
The shared `createReviewedPerson` helper also refuses a stale
People-roster response or changed-screen confirmation before making a
new profile or attaching to an existing one. Once the server has
received a deliberately confirmed POST, a late response preserves
its Undo token but never navigates the reviewer away from their
newer screen.

`node tools/v4/test_stale_group_actions.cjs` exercises the actual
browser handlers against deferred fictional network reads for two
different groups, switching screens before count resolution, during
duplicate-name lookup, before picker confirmation and after a valid
POST starts. It verifies zero stale identity writes, captured group
IDs on allowed writes, no stale return navigation and retained Undo.
Existing quick-create/duplicate-name tests still run against the
real view-generation guard. This is synthetic browser evidence, not
a substitute for owner-controlled S24+ review acceptance.

### 19 October — selected-face chooser cannot clobber later review

**Add selected to Person** already captures the explicitly selected face
IDs before opening the People chooser. Previously, a delayed
confirmation could nevertheless send those IDs after the reviewer
navigated elsewhere, then call `selected.clear()` and `render()`
against a *different* Person/group page. A delayed New Person /
existing-name redirect followed the same risky selection-clearing path.

The V4 browser now also captures the original tab, group/Person,
page, mode and render generation. A stale chooser's **confirmation**
or **New Person** route refuses to submit after navigation. A
deliberately confirmed POST that finishes after navigating still
retains the undo token, but does not repaint the newer screen or
erase its selections. When the original view remains current, only
the face IDs **actually submitted** are removed from that page's
selection; faces newly selected during network latency are preserved.
The chosen destination Person and selected face IDs are explicit in
the original confirmation.

`node tools/v4/test_scoped_face_assignment.cjs` exercises the
real browser handler with fictional deferred requests: changed
group before confirmation (no write), changed group after explicit
POST (no stale repaint), mid-flight new selection retention,
one-use Undo token preservation and the nested New Person /
existing-person assignment route. No face matching threshold,
automatic assignment, original media, SQLite server transaction,
native Android app or private People store is changed by this fix.

### 19 October — asynchronous Back scroll restoration stays in its own view

Several V4 review detours preserve page and scroll continuity after
returning from a Person, a provisional group, a full-screen photograph
or a similarity view. These restore the scroll only **after** their
asynchronous DOM render, usually in a queued animation frame.
Previously, an older Back restoration could run after the reviewer
already navigated to a newer tab or started a different search,
stealing their new scroll position. The full-photo path already checked
the target Person but captured its render generation *after* an await,
which could also misidentify a newer render as the original one.

Back-to-Discover, Back-to-People, Back-from-photo and nested/outer
similarity returns now capture the **initiating render generation
immediately**, then verify tab, page, Person/face/search and generation
as applicable before restoring scroll or keyboard focus. A stale RAF
does nothing; ordinary uninterrupted Back returns still preserve
the original review position without persisted private photo IDs.

`node tools/v4/test_stale_back_scroll.cjs` executes all four
production Back handlers with fictional async paints and delayed
animation callbacks. It simulates navigation between tabs, changing
People search without a full repaint, a superseding photo render and a
new similarity face; it checks no stale scroll/focus while preserving
normal Back behavior. The existing 2,001-People roster, full-screen
photo and similarity-return regressions continue to run. Device touch
latency, TalkBack and visual smoothness still need owner-approved
S24+ validation.

### 19 October — Undo preserves review selections made during latency

The one-use **Undo last review action** request operates on the server's
previously confirmed review snapshot, not on whichever face tiles are
currently selected. Previously its delayed success handler called
`selected.clear()` even when a reviewer had selected unrelated faces
while waiting, silently erasing pending review choices. V4 now keeps
that current selection intact when Undo completes, while retaining the
existing one-pending-write guard, token-consumption check, deliberate
Undo feedback and render-generation guard. A result arriving after
navigation never paints another screen, and a newer Undo token is not
overwritten by the older result.

The actual browser handler is exercised in
`test_review_write_singleflight.cjs`: 25 rapid Undo activations still
produce one fictional POST; an earlier selected face **and** another
face selected while the request is pending both remain selected after
successful Undo. The test also verifies a newer Undo token is
preserved. This is a browser-only selection fix, not an automatic
assignment, live private DB operation or change to server rollback
semantics.

### 19 October — background job acknowledgements cannot redraw newer review screens

V4's Scan, Group, Propose, Pause and Add photo folder actions send
an explicit request to the local server and then refresh the screen.
The server operation is independent of the current tab. Previously,
a delayed acknowledgment would always call `render()` against whatever
new Person, search or review page the user had opened meanwhile,
unnecessarily replacing the new DOM and potentially disturbing
scroll position or keyboard focus.

The generic action helper now snapshots its originating tab/page,
Person/group, mode and render generation **before** the request,
and refreshes only if that precise view is still current when it
completes. The background command is still sent and status/failure
feedback is still shown. Navigating away never silently cancels a
previously submitted Scan, Pause or folder change. This is a client
render-continuity fix only: no background job scheduler, permission,
database or photo-processing behavior changes.

`node tools/v4/test_background_action_render_guard.cjs` runs the
actual V4 action helper with delayed fictional POSTs: unchanged-screen
Scan refreshes once, delayed Pause/add-root/Group cannot redraw a
newer tab or render generation, and failed Propose still reports its
error without disturbing the newer screen. Exact-head dedicated V4
CI includes this test; an owner-approved real S24+ acceptance run is
still required to verify browser touch and focus behavior.

### 20 October — Person portrait and lookalike actions respect review context

**Use as portrait** is an explicit, reversible aesthetic choice for a
confirmed face in a named Person's Review tags. The server already
rejects a face not assigned to that Person. The browser previously
allowed rapid repeat taps to overlap multiple writes for the *same*
profile, potentially letting an older response overwrite a newer
choice; every delayed response also rerendered whatever screen the
reviewer had subsequently opened. V4 now permits only **one pending
portrait request per Person**, with other profiles independent. The
captured target Person ID and clicked face ID are used for the
request, and only a still-current Person view is rerendered. Failed
requests release the guard so the reviewer can deliberately retry.
A late success from a previous screen reports completion without
clearing selections, resetting scroll or painting a newer profile.
This is not automatic face identification or a new background job.

The **Find lookalikes for this person** action also now remembers
which Person and review render started its bounded first-page face
lookup. If the reviewer switches profiles while that request is
pending, the older response cannot open similarity for a face from
the previous profile, even if the new Person happens to have a
matching thumbnail.

`node tools/v4/test_cover_search_context.cjs` executes the actual
browser helpers with deferred fictional server requests: 51 rapid
competing cover taps coalesce to one per Person, changes on two
different People remain independent, stale responses do not repaint
the new profile or erase its selection, error/false responses allow
retry, and a stale Person face lookup cannot open the wrong person's
similarity inspector. Server-side confirmed-face checks remain
authoritative. The installed S24+ app and private People database are
not touched; real touchscreen and accessibility acceptance remain
owner-gated.

### 20 October — People-roster same-name choice opens an existing profile

The **New person** command in the People roster intentionally detects
existing names before creating a separate identity. Previously,
choosing the right existing profile from that duplicate-name chooser
mutated the global `person` ID inside its confirmation callback.
That made the helper's protective original-view check think the
reviewer had navigated away, suppressing the actual next step and
displaying an inaccurate "Previous-view attachment completed" message.
No actual faces were attached, but the newly selected existing profile
failed to open.

The roster action now records the chosen existing ID in a **local**
variable, lets the review context check finish, and then navigates
directly to the existing profile. It preserves the People list's
search, page and scroll so Back returns to the original roster position.
For a same-name selection with **no faces or group being attached**,
the confirmation says **Open existing profile** rather than suggesting
a face-assignment write; the selection performs **zero server writes**.
The Group naming and selected-face workflows retain explicit
**Attach to existing profile** confirmation and their server-side
assignment protections. Creating a genuinely new profile, including
an explicitly confirmed same-name separate Person, still uses the
existing real `/api/person/create` endpoint and Undo behavior.

`node tools/v4/test_roster_existing_profile.cjs` executes the real
V4 roster and duplicate-name handlers with synthetic People and
deferred API responses. It verifies correct existing-profile
navigation with zero writes, Back-to-roster continuity, distinct
new-profile creation, group attachment wording and callbacks,
cancellation after navigation during metadata loading and no
redirect of an unrelated screen after a pending creation completes.
No photos, private People names, live database or installed Android
app are accessed in the test.

### 20 October — one group-review intent while the count or chooser loads

V4 Group detail previously allowed repeated **Name group** or **Attach
group** taps while their asynchronous unassigned-face count was
loading. Rapid taps could create many independent pending lookups,
duplicate name prompts or a replacement destination chooser. The
existing server expected-count guard remains authoritative for
identity correctness but should not be needed to repair duplicate
UI intents.

Both Group actions now share a single-flight entry guard while
loading group counts, opening a prompt and preparing a chooser. A
visible destination chooser retains ownership of the current
review intent until dismissed, preventing a competing Group action
from replacing the confirmation. A rejected count request, canceled
name prompt, or completed lookup releases the launch guard; the
reviewer can deliberately retry. The last reviewed group ID, face
count and explicit confirmation rules are unchanged; no face is
assigned automatically. This coalescing affects only the UI's Group
entrypoints, not background scan jobs or cross-tab navigation.

`node tools/v4/test_group_entry_singleflight.cjs` evaluates the real
name/attach handlers with deferred fictional group-count queries:
80 rapid competing activations submit **one** lookup; only one name
prompt or chooser appears, an open chooser blocks replacement,
subsequent actions after dismissal work, a rejected lookup releases
the guard, and a late response after changing groups never opens
a prompt for the wrong group. Real S24+ touch performance remains
an owner-gated acceptance check.

### 20 October — explicit Create separate Person failure is visible

The People chooser's **Create separate person instead** control closes
the chooser before executing the explicit separate-person callback.
Previously, a rejected asynchronous API request escaped that click
handler as an unhandled Promise rejection, leaving no reliable
feedback. The picker now catches a rejected callback and reports
that the action **could not finish**, with a reminder to inspect
the People roster before retrying. It does not falsely claim that
the server performed no write when an interrupted network response
might have hidden a successful commit. Successful creation still
runs once and closes the chooser normally.

`node tools/v4/test_picker_escape_focus.cjs` now additionally
exercises the real Create separate callback through the fictional
picker DOM, both successful and rejected outcomes, and checks the
visibility of recovery guidance. This only changes browser error
handling; server-side duplicate-name confirmation, Undo history,
private photos, and the installed APK are unaffected.
