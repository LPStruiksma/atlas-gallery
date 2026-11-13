# V4 People review feedback — accessibility and no lost Undo (2026-10-20)

## Observation

The V4 single-page gallery uses a global toast for review writes, Undo and
network errors. Originally the toast hid after a standalone 3.5-second timeout
for *every* message. A new Undo success notification arriving 3.4 seconds after
an earlier message could disappear almost immediately. Also, the Person chooser
uses z-index 100 while the toast had z-index 20, so identity-assignment errors
could be covered by the picker dialog. The toast had no explicit live-region
semantics for Android TalkBack/screen readers.

## Repair

- Each new message cancels the previous hide timer and receives a full 3.5 s.
- The toast appears *above* the Person picker at z-index 120.
- `role="status" aria-live="polite" aria-atomic="true"` announces success/error
  without taking keyboard focus away from the selected face or from the modal.
- Messages remain text-only; their contents cannot inject markup.
- No photo, People database, token or selected face is persisted client-side.

`node tools/v4/test_toast_review_feedback.cjs` extracts the real browser
function and checks consecutive Undo results, hide-timer cancellation,
text-only rendering, modal stacking and live-region markup with synthetic
fixtures. This is an assistive-markup *regression*, not proof that a specific
TalkBack/Android version announces every update or that 3.5 s is the ideal
reading interval. Real TalkBack touch and visual contrast still need opt-in
owner-controlled S24+ acceptance. Unlike source-only CI, the owner must verify
that browser refresh and font scaling do not clip the toast.

**Scope:** `tools/v4/**` only. No new background jobs, models, implicit
identity approvals, real People DB reads/writes, Termux process changes,
installed APK upgrades, credentials or permission changes.

## Acknowledged portrait vs. uncertain assignment — late follow-up

Two additional feedback paths were misleading even with the live-region fix:

1. A manual Person cover POST could commit and then lose its response. Its
   old catch displayed **"Cover unchanged"**. Worse, a *confirmed* cover
   could be saved successfully but fail while repainting the page; the same
   catch again falsely reported that nothing changed.
2. A suggested-Person Group attachment could commit, then reject in the
   delegated click handler. Its catch said **"No changes"**, despite no
   reliable rollback evidence.

Cover writes now distinguish **server-acknowledged saves** from **unverified
outcomes**. A failed repaint after a confirmed save reports that the portrait
was saved but the view could not refresh; a lost POST acknowledgement reports
that the portrait state needs inspection before retrying. The suggested-group
handler likewise reports an unverified attachment and requests inspection of
People. Neither path retries, guesses the current database state, or fabricates
an Undo token. Original photos remain untouched.

The real-handler synthetic tests
`node tools/v4/test_cover_search_context.cjs` and
`node tools/v4/test_suggested_group_uncertain_feedback.cjs` cover these
distinctions without private photographs or identities. Accessibility and
S24+ touch performance still require the owner-controlled acceptance gates.

## Background job / folder-command acknowledgements

A background scan/group/propose/start/stop or configured folder POST can be
accepted while the mobile browser either loses the response or fails to
repaint its old tab. The shared V4 `action()` helper now distinguishes
these outcomes instead of showing a generic error after a confirmed write:
an acknowledged command followed by a failed local refresh says **"Command
accepted; could not refresh view"**, whereas an unsuccessful POST says
**"Could not verify command; check status before retrying"**. The helper
never retries automatically and still ignores stale view generations.
This is not evidence that a long-running scan has *finished*; inspect the
Overview status separately.

`node tools/v4/test_background_action_render_guard.cjs` executes the
production helper with fictional accepted/rejected commands and a synthetic
render failure. It verifies no second request and no repaint across changed
tabs. No background job was started on the user's phone.

## Direct review write acknowledgement vs. committed state — 21 October

**Accept suggestions**, **Reject suggestions**, **Hide selected faces** and
**Remove selected Person tags** are deliberate SQLite transactions. A dropped
POST reply is *not evidence of rollback*: the server may have committed faces
and Undo history before the browser received a network error. Previously all
four actions reached a generic `Error: …` toast, without telling the reviewer
to check existing tags rather than blindly repeating the mutation.

The preview now routes these four actions through one constrained helper:

- A **rejected/lost acknowledgement** reports `Could not verify review
  change` and tells the reviewer to inspect People or face tags. The
  selected faces remain selected, no assumed server state is rendered, no
  automatic replay occurs, and no Undo token is fabricated.
- A **confirmed server acknowledgement** records the real one-use Undo and
  retires only the originally submitted selected faces. If the subsequent
  browser repaint fails, feedback explicitly says `Review saved; could not
  refresh view` instead of implying SQLite rolled back.
- A **late response after navigation** is still bound to its initiating
  Person/page/render generation. It does not clear the newer selection or
  redraw a newer page; already-confirmed Undo remains available.

`node tools/v4/test_review_write_uncertain_feedback.cjs` executes the **real
browser event dispatcher** with 60 rapid synthetic taps on each of four
review actions, simulates server commit then lost reply and a confirmed save
followed by failed repaint, and verifies stale Person isolation and explicit
cancel semantics. Real transactions/face labels are not modified by tests.
The existing backend audited all-or-nothing validation is unchanged; this is
a UX outcome-classification fix, **not exactly-once retry support** or a
claim of device-level acceptance.

## Manual Undo acknowledgement ambiguity — 21 October

Undo uses a one-use SQLite transaction token. Like other review mutations,
the server can **successfully restore faces and consume the token** before a
network interruption drops its reply. The old browser catch displayed only
a generic `Error`, which might encourage blind repeated Undo. Conversely,
an acknowledged Undo followed by a failed page repaint is **known to have
completed** and must not be reported as an unknown server error.

The V4 Undo click path now distinguishes these without changing transaction
or token rules:

- **No verified reply:** `Could not verify Undo` with an explicit instruction
  to inspect People or face tags before attempting anything else. Keep the
  local token as *unverified evidence*; do not falsely assert it is still
  usable, replay it automatically, manufacture a new token, clear the
  reviewer's selected faces, or render optimistic restored tags.
- **Verified completion but failed repaint:** retire **only the token that
  was consumed** (if no newer token replaced it), say `Undo confirmed; could
  not refresh view`, and preserve the current face selection.
- **Late result after navigation:** never redraw the newer Person or consume
  a newer Undo token. The existing direct-review single-flight protection
  continues to handle physical double-taps or key repeats.

`node tools/v4/test_undo_uncertain_feedback.cjs` executes the production
Undo click handler with 60 rapid fictional taps, synthetic commit-then-lost
acknowledgement, server already-used response, acknowledged rollback followed
by failed repaint, and late navigation to a different Person. This is not a
server-side Undo status endpoint or automatic retry mechanism. Owner-controlled
backup/restore and physical S24+ acceptance remain independently required.

## Explicit inspection gate after an unacknowledged direct review write

A direct reviewed write whose reply is lost may already have committed. Without
an intermediate check, the reviewer can immediately tap a DIFFERENT write (or
the older visible Undo token) and unknowingly edit labels based on stale
assumptions. The browser now keeps **one ephemeral action-kind warning**
(no face IDs, Person IDs, names, payloads, photos or persisted storage) when
Accept/Reject/Hide/Unassign has an unverified reply.

Before the **next** direct reviewed mutation **or manual Undo**, the browser
requires explicit acknowledgement that the reviewer inspected the current
face tags / People after the uncertain request. **Cancel** does not send a
write, retire a selection, dismiss the warning or fabricate Undo. The existing
single-flight guard coalesces fast repeated taps, so a burst cannot bypass
the acknowledgement. Agreeing to proceed only clears the warning; it does
not replay the previous POST or assume that it committed/rolled back.
Backend atomic eligibility checks and one-use Undo remain authoritative.

`node tools/v4/test_review_retry_after_unknown.cjs` executes the production
event dispatcher against fictional HTTP writes: simulate a commit then lost
Accept response, 60 rapid Reject attempts with Cancel, subsequent deliberately
acknowledged Reject, a lost Hide acknowledgement blocking an **older Undo**,
and a separately confirmed Undo preserving selected faces. This is a
browser-session safety reminder, not a replacement for checking the current
SQLite state or a durable direct-review idempotency protocol.

## One-use Undo status — read-only recovery (21 October follow-up)

When a manual Undo POST loses its response **after SQLite committed**, the
reviewer previously had to infer whether face tags were restored. The browser
now offers **Verify earlier Undo status** after such an ambiguous reply. It
sends only the volatile opaque 32-hex Undo token to the authenticated local
`POST /api/review/undo/status` endpoint; it does **not** resend the Undo
mutation, approve suggestions, store faces or names, or create a new token.

The server reports deliberately cautious, identity-free states:

- **`undone`:** the one-use Undo record is present with a committed
  `undone_at` marker. The browser retires only that matching pending token;
  if it is still the current Undo, its outdated button disappears. A newer
  independently confirmed Undo is never cleared.
- **`recorded_not_undone`:** a record exists but `undone_at` is empty
  *at the instant of the read*. This does **not** prove Undo is currently
  eligible: the selected faces or associated evidence may since have changed,
  or an earlier outstanding request could still commit. Keep the reminder.
- **`unknown`:** no record could be found. The existing Undo snapshot
  history is intentionally limited to **12 recent records**, so an older
  success or failure may already have rotated away. Unknown is **never
  evidence of rollback**. Keep the reminder and inspect face tags.
- Failed status requests likewise retain the pending reminder; there is no
  automatic retry. A repeat of the old one-use mutation after uncertainty
  requires an explicit inspection confirmation.

The browser keeps unresolved Undo tokens **only in current-page memory**,
never persistent browser storage, so reload/close may erase the reminder.
An older status check can reconcile its own token after navigation without
stealing newer Undo, face selection, focus or page position. An in-flight
**Verify** batch is single-flight across 90 rapid taps. Normal on-screen
refresh uses the existing scroll/focus restoration logic. No server schema,
snapshot retention policy, original media or private data changed.

The real SQLite synthetic test `test_undo_status.py` verifies malformed
tokens, committed Undo, not-yet-undone record, changed-profile stale Undo
refusal, the 12-entry rotation case, and absence of database writes or
identity-bearing fields in status responses. The actual browser dispatcher
test `test_undo_status_browser.cjs` covers rapid taps, lost acknowledgements,
stale Person/navigation and newer Undo preservation, still-ambiguous statuses,
and explicit inspected retry. These tests **do not** constitute real-device
TalkBack/Chrome verification or independent on-phone backup recovery.

## Mandatory reviewed-write receipt validation (21 October follow-up)

The existing Accept/Reject/Hide/Unassign error treatment distinguishes a
dropped POST acknowledgement from a confirmed save followed by failed local
repaint. One additional failure was uncovered: a local HTTP request could
*resolve successfully* with malformed or incomplete JSON (e.g. `null`,
`{}`, a missing one-use Undo token, or a face count different from the
explicitly submitted selection). Previously that alone made the browser
treat the write as confirmed, clear selected faces and report completion.

Each of these four audited endpoints always returns a JSON object with
**`count` equal to the number of deliberately submitted faces** and a
real **32-character lowercase hexadecimal `undo_token`** from the
same SQLite write transaction. The client now verifies **both** before
mutating its visible selection, recording Undo, or repainting. A malformed
HTTP-200 acknowledgement is classified as **unverified**, because the
server might have committed even though its response is unusable. The
reviewer's selection remains intact; no Undo is invented, no automatic
retry occurs, and the pre-existing explicit tag-inspection gate applies
before another reviewed write or old Undo. This is bounded client response
validation, **not** proof that a malicious network peer cannot forge
the receipt or an exactly-once protocol for those actions.

The synthetic `test_direct_review_ack_shape.cjs` invokes the **real**
four-route V4 review dispatcher on 15 malformed success bodies **per
route**, verifying no apparent completion, discarded selection, fake
Undo, navigation repaint or blind follow-up. It also verifies a proper
count+Undo receipt remains fully usable for every route. Three existing
review/browser fixtures were updated with realistic synthetic one-use
Undo strings. No real face tags or photos are read or changed.

## Mandatory one-use Undo acknowledgement shape

Manual Undo receives only `{count: N}` when SQLite commits the prior-review
rollback and consumes its one-use token. A transport-level HTTP 200 with
an empty, malformed or contradictory JSON response is **not enough** to
retire the Undo button. The browser now requires `count` to be a
**nonnegative safe integer** (zero is valid for undoing creation of an empty
Person). If the count is invalid, it takes exactly the same cautious path
as a lost acknowledgement: keeps the token as *unverified* rather than
assuming still usable, does not repaint or report restored faces, and
requires inspection before another Undo attempt.

The existing **Verify earlier Undo status** feature can independently
confirm that the one-use token was consumed, without resubmitting the
mutation. `test_undo_ack_shape.cjs` uses the actual embedded browser
handler against **15 incomplete HTTP-success bodies**, then checks read-only
status resolution and the valid zero/positive count paths. No actual
profile labels, originals or live database records are accessed. This
validates response completeness, not cryptographic HTTP authenticity.

## Late reviewed-write response must not steal a newer Undo token

A deliberate Accept/Reject/Hide/Unassign request can be delayed while the
reviewer navigates and completes a DIFFERENT later reviewed action (for
example, creating another named Person). The old direct review response
previously unconditionally replaced the global **Undo last review action**
token even when a newer one-use token had already arrived, potentially
hiding the Undo action that the reviewer expected to be available.

Direct reviewed writes now remember the visible Undo token at submission.
On an acknowledged result, they install their own audited Undo only if
no other reviewed operation replaced that token while the write was in
flight. If there is already a newer one-use Undo, it stays active and the
feedback explicitly says **newer Undo preserved**. A stale result cannot
redraw a newer Person, clear that Person's face selection, or change their
page position. If no newer Undo exists, the normal reviewed write still
installs its real token, clears just its submitted selected faces and
preserves review focus/scroll. The SQLite review transaction, all
manual approvals, and bounded backend Undo history are unchanged.

`node tools/v4/test_review_late_undo_order.cjs` executes the real
embedded V4 reviewed-write dispatcher with synthetic overlapping
completion sequences: older Accept after Person navigation/newer Create
Undo, older Reject after same-view newer Undo, and normal single reviewed
write. No real identities, original photographs or private databases
are accessed. This is a one-use-button **recency** safeguard, not
transaction-history reordering or an assertion that the older Undo is
automatically still available after newer state modifications.

## Existing-Person face/group attachment retains newer Undo

The explicit **Add selected faces to Person** and **Attach group to existing
Person** confirmation flows also send audited local SQLite writes whose
HTTP replies can arrive out of order with a different reviewed operation.
Like direct review and Person creation, the previous callbacks installed
their Undo token **unconditionally**, even if another, later action had
already supplied a newer one-use Undo to the current screen.

All four existing-Person assignment routes (selected faces via direct
chooser or duplicate-name redirection; whole group via explicit existing
Person chooser or naming redirection) now capture the visible Undo
before submitting the POST. If an independent reviewed action has updated
that token in the meantime, the older response **cannot replace it**;
the UI says **Face assignment saved · newer Undo preserved** or **Group
attachment saved · newer Undo preserved**. Otherwise the assigned faces
still receive their normal server-issued Undo. The existing exact-face
selection, confirmation, server eligibility, navigation and history
constraints remain unchanged.

The actual-embedded-browser `test_attach_late_undo_order.cjs` simulates
all four routes with fictional face IDs, independent newer reviewed Undo,
cross-Person navigation, response lag and a normal successful group
attachment. It verifies no newer-selection clearing, stale-page render,
wrong Undo button or automatic identity approval. Original photos and
private People data are untouched.

## Archive merge/restore and newer Undo precedence

A Person profile merge returns an audited one-use Undo token; restoring
the archived source explicitly consumes the former merge state. Until now,
an old merge response could overwrite a newer review Undo, and an old
restore response could **clear** a newer, unrelated Undo—even when the
reviewer had navigated to another Person in the meantime.

Both explicit owner-confirmed chooser actions now capture the current
Undo token at the moment their POST begins. A confirmed merge installs
its own Undo only if no later operation replaced that token. A confirmed
restore retires its consumed former Undo only if it is **still** the
visible token; an independent newer Undo remains active. When necessary,
the UI announces **newer Undo preserved** and keeps the new Person/page
selection, focus and navigation untouched. Ordinary merge and restore
behavior, private SQLite audit, archival provenance and deletion
prohibitions remain unchanged.

`node tools/v4/test_merge_restore_late_undo.cjs` runs the real browser
chooser callbacks against four fictional delayed HTTP scenarios: merge
with/without another reviewed action, restore with/without another
reviewed action, and navigation during the in-flight request. It checks
the one-use token and selected faces are not overwritten. No real user
photos or profile history were touched.

## Mandatory explicit-attachment receipt before retiring selection

The four explicit existing-Person attachment flows also depended on an
unvalidated HTTP-success body: **Add selected** (direct picker or
same-name resolution) and **Attach group** (direct picker or
same-name resolution). A resolved but incomplete JSON reply could still
install no usable Undo and misleadingly return to the Group overview or
clear face selection as if the SQLite assignment had been verified.

V4 now requires the attachment response to include both the **exact
selected/group face count** and a **32-character lowercase one-use Undo
token**. An invalid HTTP-200 receipt is treated as an **uncertain
commit**, *not* a failed transaction: the attachment callback refuses
completion, leaves face selection and previous Undo intact, and requires
inspection of current People/face tags before another deliberate
assignment. Existing picker confirmation safety remains in force; there
is no optimistic navigation, automatic retry or unreviewed suggestion
confirmation. On valid receipts, existing reviewed assignment, return
position and newer-Undo precedence continue unchanged.

`node tools/v4/test_attach_ack_shape.cjs` exercises the actual
embedded browser callbacks for four existing-Person attachment routes
against **40 malformed HTTP-200 bodies**, plus four valid audited
receipts. It asserts no phantom Undo, selected-face clearing,
Group return or second write after unverified outcomes. The server's
actual transaction, original photographs and private identity records
are not accessed by this synthetic test.

## Profile Merge / Restore requires a complete acknowledgement

A prior V4 merge/restore browser path accepted **any HTTP-200 JSON**
after a reviewed profile-archive or archive-restoration request, even
`null`, `{}` or a mismatched face count. After such an incomplete
response, the backend may have **already committed** its SQLite
transaction. The browser must not silently advance the profile, install
an invalid Undo or clear a legitimate older Undo.

V4 now requires the following from the frozen, explicitly selected
source profile:

- **Merge:** `{ok:true,count:source.faces,undo_token:"<32 lowercase
  hex>"}`. The backend archives the selected source, transfers exactly
  this many reviewed faces and provides the genuine reversible merge
  token. Any malformed success body remains *unverified*, with the
  current selection and existing Undo untouched, **no automatic retry**
  and an instruction to inspect People and archived profiles.
- **Restore:** `{count:source.faces}`. Only this verified response
  retires the Undo token for the archived merge, and only if no newer
  independently completed action replaced it. A malformed success body
  leaves the previous Undo as **unverified evidence**, not proof it
  remains usable. The restore may already have committed.

Both **zero-face profiles** and profiles with reviewed faces are valid
archive/restore cases; a numeric `0` is accepted as a legitimate exact
count, not confused with absence of evidence. A later reviewer navigating
to another Person remains protected by the existing render-generation
guard and newer-Undo precedence. This change adds **no** new merge,
restore, automatic identity approval, file moves or SQLite schema.

`node tools/v4/test_merge_restore_ack_shape.cjs` exercises the actual
embedded-browser merge/restore callbacks on fictional zero-face and
two-face profiles, **64 deliberately malformed HTTP-200 bodies** plus
four proper completions. The test asserts no phantom Undo, lost
selection, false success repaint or automatic resubmission.
Physical S24+ interaction and private database recovery remain
separate owner-controlled acceptance work.

## Rename Person: validated acknowledgement and refresh certainty

The V4 `/api/person/rename` endpoint returns exactly
`{ok:true}` only **after the reviewed SQLite name change commits**.
The browser previously accepted any resolved HTTP-200 body as success,
including missing, null, or `{ok:false}` replies. Conversely, a failed
browser repaint after a verified rename could throw a generic error
without communicating that SQLite had already changed.

The Person rename handler now accepts only the exact `ok:true` marker.
Malformed HTTP-success bodies enter the conservative
`Could not verify rename … check People before retrying` path,
without optimistic navigation or further automatic writes.
After a genuine successful server reply, a failed local repaint is
announced as **`Person name saved; could not refresh view`**.
The existing NFC/NFKC-style name comparison, deliberate separate
same-name identity confirmation, single-flight rapid-tap guard,
stale Person navigation guard and backend transaction are unchanged.

`node tools/v4/test_rename_ack_shape.cjs` executes the actual
embedded-browser rename handler with **13 malformed HTTP-success
bodies**, one valid receipt, ACK+repaint failure, explicit homonym
cancel/approval, and a valid late response after navigating to
another Person. It uses fictional names and HTTP responses only.

## Manual profile portrait: explicit boolean commit receipt

The `/api/person/cover` SQLite transaction returns `{ok:true}` after
a confirmed face is selected as the profile cover. The old browser checked
only that `ok` was *truthy*: malformed HTTP-success bodies such as
`{ok:"false"}` or `{ok:1}` would optimistically repaint the portrait
and announce **Profile portrait updated** even though the reply was
not the actual server contract.

The V4 browser now requires the **exact boolean `ok:true`** before
calling a portrait change confirmed. Any missing, null, nonboolean or
otherwise incomplete HTTP-success response is **unverified**, not
necessarily failed: SQLite might have committed before the reply was
corrupted. The UI asks the reviewer to inspect the Person portrait
before making another deliberate selection, never automatically retries
or invents a saved face. The existing independent per-Person
single-flight, stale-navigation and acknowledged-save/failed-repaint
guards remain unchanged.

`node tools/v4/test_cover_search_context.cjs` executes the production
cover handler with **12 malformed fictional HTTP-success bodies**,
rapid taps, different People, lost replies, acknowledged writes with
repaint failures, and cross-Person similarity search. No actual
photograph, face assignment or private profile cover was modified.
