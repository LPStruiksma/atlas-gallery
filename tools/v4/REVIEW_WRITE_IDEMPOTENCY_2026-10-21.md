# V4 atomic face-review request ledger (synthetic-only milestone)

## Scope and intent

This is a local review-confirmation and lost-acknowledgement recovery feature,
**not** an Android release deliverable or an automatic identity-assignment
pipeline. The V4 browser sends an optional opaque idempotency key when secure
Web Crypto is available, and retains an inspection-required fallback otherwise.
No request is automatically retried after an ambiguous acknowledgement.

An opted-in client can supply a securely generated opaque lowercase 32-hex
`request_id` with one deliberate reviewed write. The server persists
only an operation key, SHA-256 digest of canonical operation details,
generic action name, result count, one-use Undo token and timestamp in
`review_write_requests`. It stores **no plaintext Person names, face IDs,
photograph paths, embeddings, suggestions or photo bytes** in the ledger.

Supported existing transactions with optional `request_id`:

- `/api/person/assign` — explicit selected-face attachment
- `/api/person/accept` — confirm selected suggested matches
- `/api/person/reject` — reject selected suggestions
- `/api/face/ignore` — hide selected unassigned faces
- `/api/face/unassign` — remove confirmed selected face tags
- `/api/group/attach` — explicitly attach a whole reviewed candidate group to an existing Person

(The first two use different canonical operation names despite sharing
`assign_faces`; keys cannot be reused across distinct operations.)

## Atomicity and replay

Existing face-write functions already own `BEGIN IMMEDIATE` and use
`with c` to commit or roll back. The replay check is executed **inside
that same serialized transaction, before eligibility checks**. New results
are recorded **inside the exact same transaction** as changes to
`faces`, `rejected`, `auto_matches`, `clusters`, `events` and
`manual_review_undo`. There is no separate "ledger after commit"
window. A failing ledger insertion rolls the whole reviewed operation back.

An identical `request_id` + canonical payload replays the recorded
count and original one-use Undo **without** reapplying tags/audit/Undo.
A matching ID used with different action, Person, selected faces or
Undo semantics fails closed. The digest sorts the list of distinct
validated face IDs so a reordering alone is equivalent. Whole-group
intent additionally hashes explicit `cluster_id` and `expected_count` fields
with the Person ID; exchanging group and count values is not equivalent.

If the original Undo has been consumed, an exact replay is **refused**,
rather than pretending the tags are still saved. If its Undo has expired
from the bounded 12-entry history, replay is likewise refused, because
an older consumed Undo might also have expired. An inapplicable old token
does not resurrect an earlier approved identity.

## Read-only status

Authenticated local `POST /api/review/write/status`, with opaque
`request_id` only, returns:

- `unknown`: no ledger key was visible in the SQLite read snapshot;
  a prior in-flight transaction might still commit
- `recorded_not_undone`: durable commit record and an unconsumed Undo
  entry remain; **does not establish current tag eligibility or state**
- `undone`: the Undo record exists and indicates it was consumed
- `recorded_unknown`: the write ledger survived but its one-use Undo
  record was rotated out; it may have been used before rotation

No Person identifiers, face IDs, original paths, Undo snapshot, names,
embeddings or private labels are returned. `count` and an unconsumed
Undo token are returned only for an exact-key holder. Only an active local
session with the V4 CSRF token can access this endpoint.

## Validation

`python3 tools/v4/test_atomic_review_write_idempotency.py` executes the
actual server functions with an entirely **fictional, disposable SQLite**
dataset and synthetic images/faces. It verifies five action signatures,
seven exact replays per action, cross-operation key collision, invalid
keys, 2-connection concurrency under SQLite's write lock, forced failure
of the ledger insert after tags/Undo/audit were staged, one-use Undo
consumption and bounded-history expiry, read-only status and absence of
plaintext identifying fields in ledger rows. Existing legacy callers
remain backward-compatible when they omit `request_id`.

## Browser recovery (Lane D follow-up)

The four direct-review callbacks (Accept suggestion, Reject suggestion,
Hide face, Unassign tag) and both explicit **selected-face → existing-Person**
chooser paths, plus **both whole-group → existing-Person attach paths**,
generate a fresh cryptographically random 128-bit request key
when Web Crypto is available. The browser saves only `{id,count}`
in sessionStorage (eight-entry bound), **before** its normal reviewed POST.
An exact-count success receipt with genuine one-use Undo retires the key;
a lost/malformed reply retains its key, selected faces and previous Undo.
**No automatic write retry.**

The reviewer may open People or a review screen and choose
**Verify earlier reviews** to query the read-only `/api/review/write/status`.

- `unknown`, transport failure or malformed status: retain key, never infer rollback.
- `recorded_not_undone`: only matching count + valid Undo token retires the
  reminder. Recover Undo into the UI only for one pending request, same
  originating screen and **no currently available Undo** (existing Undo is
  always preserved).
- `undone` / `recorded_unknown`: matching count retires reminder but
  does **not** establish current face assignments.

No names, face IDs, Person IDs, photo paths, embeddings or operation payloads
enter browser storage. Without browser Web Crypto, the legacy inspect-before-
retry guard remains active; no weak randomness substitutes for a secure key.
**Dismiss one review reminder** is deliberate and browser-only; it cannot
cancel an outstanding SQLite write. Current tags require human inspection.

Selected-face and whole-group attachments to an existing Person now both use
review status recovery, whether started directly or redirected from a
new Person's existing-name homonym. The whole-group transaction hashes the
confirmed group identity and exact count under the same SQLite write lock as
face assignment, audit and one-use Undo. New-Person creation remains a
separate existing idempotency mechanism.

Browser regressions: `test_review_write_status_browser.cjs`,
`test_person_chooser_review_recovery.cjs`,
`test_group_attach_review_status_browser.cjs`. Group SQLite race, replay,
rollback and Undo regression: `test_group_attach_review_idempotency.py`.
**Outstanding:** owner-run disposable V4 browser canary, future optional
confirmed-face metadata sidecar, and physical Android qualification (Lanes
A/E). None is a first-release prerequisite.
