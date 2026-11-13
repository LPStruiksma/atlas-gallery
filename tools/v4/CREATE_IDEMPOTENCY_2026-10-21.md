# V4 People — durable, atomic Create person request replay (21 Oct 2026)

**Scope:** isolated V4 prototype, synthetic-only tests, no user People DB or
original photos accessed. The original #307 server PR added opt-in durable
support; this subsequent browser integration supplies cryptographically
secure per-intent request keys to that unchanged server protocol.
The existing human-inspection recovery path still applies after lost POST
replies. Replaying an HTTP request needs the **same** key and payload;
different explicit reviewer intents use different keys.

## Problem

A POST can commit People, tags and one-use Undo evidence, then lose the HTTP
reply. A browser-only single-flight lock and an ephemeral "inspect People
before retrying" warning do not supply exactly-once persistence across a
browser restart or two independent simultaneous requests.

## Contract

The existing `/api/person/create` accepts an **optional**
`request_id`, exactly 32 lowercase hexadecimal characters from a secure
client-side random generator. The key refers to **one explicit reviewer intent**,
not a name, profile, or identity. Clients MUST reuse the **same key with the
same exact request details** when they need to retry that *one* operation;
**new** deliberate creations need **new** keys. A server cannot infer human
intent from matching names or face similarity.

The SQLite schema adds a small `person_create_requests` ledger with
`request_id` (primary key), SHA-256 canonical payload digest, resulting
Person ID, optional Undo token, timestamp and an Undo tombstone. It stores **no Person names,
original photo paths, face IDs or biometrics**. It lives inside the existing
private SQLite DB and is therefore included in independently verified online
backups. It is **not silently purged**: discarding an old key would allow a
very late replay to create another Person. A later retention policy must
preserve replay safety, e.g. by explicit tombstones.

For each creation request, `BEGIN IMMEDIATE` covers checking an existing
key, matching the exact payload digest, validating names and unassigned faces,
the new Person, face changes, normal Undo evidence and ledger insertion.
Concurrent SQLite connections serialize on that same lock. Any failure
rolls back all records, including the ledger; no one-sided "success" token is
possible.

An exact replay does **no identity write**, yields the original Person ID,
and carries `idempotent_replay:true`. A still-usable original Undo token is
returned; when the bounded manual-Undo history has rotated away, the token is
`null`, never an unusable invented button. A request with changed name,
face selection, group, expected count, homonym approval or Undo mode is
refused. A request for a Person later undone or archived is refused rather
than silently resurrecting it. A successful new-Person **Undo** also
permanently tombstones the request key *inside the same transaction*:
SQLite may reuse a deleted INTEGER PRIMARY KEY for an entirely different
Person, and the old key must not resolve to that unrelated profile. **Different keys remain different deliberate
operations**, even when names match; explicit homonym confirmation still
applies, and automated face/identity confirmation remains prohibited.

Legacy callers which do not supply a request ID retain their existing
behavior without a forced migration of their request payloads. The new
`CREATE TABLE IF NOT EXISTS` statement is additive; actual device migration
and restore remain owner-gated. It does not drop, rewrite or delete an
existing People table or originals.

## Evidence and limitations

`python3 tools/v4/test_person_create_idempotency.py` exercises the
**real** V4 SQLite routines against an isolated fictional DB, including
replays after a previously committed group has been fully assigned,
payload/key collision refusal, selected-face order equivalence, legacy
compatibility, deliberately distinct homonyms, commit-time trigger abort and
whole-transaction rollback, two independent concurrent SQLite connections,
Undo tombstone, forced SQLite Person ID reuse and expired Undo token behavior. Dedicated V4 synthetic CI
is mandatory before merging.

**Not** a security authorization token, full exactly-once HTTP delivery,
physical S24+ proof, or permission to run the preview against private
People state. The browser's `newSecurePersonCreateRequestId()` now uses **exactly
16 cryptographically random bytes** from Web Crypto `getRandomValues`,
hex-encoded to the server's strict 32-character key. Each new deliberate
creation gets a fresh key. There is **no Math.random, timestamp or
insecure fallback**: if the browser lacks secure randomness the write
is refused without sending a POST. Existing V4 test fixtures use fictional,
deterministic Web Crypto substitutes only within Node VM tests.

## Privacy-preserving recovery after a lost acknowledgement

The browser now records up to **eight unacknowledged opaque request keys**
under `sessionStorage['atlas-v4-pending-person-create-ids-v1']`.
It writes the key **before** issuing the create POST and removes **only the
matching key** after the server acknowledges the request. No People name,
face selection, image path, request body or server response enters web
storage; if sessionStorage is blocked the current page retains in-memory
keys only. Browser-tab sessionStorage can survive a page reload but is
not durable through every browser/tab close or privacy mode.

When a saved pending key is present, the People roster displays
**Verify earlier creations**. It performs an explicit **read-only**
`POST /api/person/create/status` with the already-authorized Studio
session header and *only* the opaque key. It never launches another
`/api/person/create`. The status endpoint returns:
- `committed`, the existing numeric Person ID and the original Undo
  token *only if still available*: clears that pending key; exposes Undo
  only if no newer Undo button is already active
- `undone`: a tombstoned creation, clears its pending key
- `archived_or_missing`: an archived or otherwise unavailable profile,
  clears its pending key, leaves other profiles untouched
- `unknown`: the ledger contains no completed matching key *yet*.
  **Keep the key and require human inspection.** An original in-flight
  write may commit later; `unknown` never means rollback or permission
  to create another Person

A lost status reply similarly leaves the key pending. If eight truly
unresolved requests remain indefinitely (for example, repeated rejected
operations that never committed), the browser must not remain unusable.
**Dismiss one browser reminder** is an explicitly confirmed, one-at-a-time
recovery action. Its warning states that the original server request **may
still commit**. Cancel retains all keys; confirmation removes only the oldest
*browser-session reminder*, does **not** cancel a server transaction, write to
SQLite, undo a Person, declare failure, or suppress an existing server ledger
record. Review People and verify status first; if uncertain, keep the
reminder. Fast repeated dismiss taps coalesce so one confirmation cannot
discard multiple keys. This is a deliberate user-risk decision, not an
automatic cleanup policy.

A burst of rapid
**Verify** taps shares one in-flight read-only check instead of generating
competing requests. An acknowledged result arriving after the reviewer
navigated away can reconcile its exact opaque key but must neither
repaint the newer screen nor displace its Undo control; on-screen refresh
uses the existing scroll/focus-preserving repaint helper.
Browser restart with pending keys still gates new creation on explicit reviewer
acknowledgement to inspect People. A Confirm click never silently clears
pending evidence. The status endpoint returns **no name, photograph,
embedding or face IDs**, doesn't mutate identity state, never creates a
profile, and does not make the user restore or overwrite any backup.

This feature does **not** store private request payloads and cannot
automatically resubmit a pre-reload create with the exact original
payload. It is intentionally a **status/recovery tool, not a background
exactly-once retry mechanism**. A new deliberate creation always gets a
fresh random key and retains the existing duplicate-name/explicit-homonym
checks. The independently verified private backup and owner-controlled
S24+ acceptance gates remain mandatory.

`node tools/v4/test_pending_person_create_recovery.cjs` exercises actual
browser helpers with a fictional HTTP commit followed by a lost reply,
sessionStorage-only opaque keys, a simulated browser reload, manual
status verification, unknown/undone/archived outcomes, unaffected newer
Undo, blocked sessionStorage and malformed/oversized stored values.
`test_person_create_idempotency.py` independently verifies the
production SQLite status reader is truly read-only across these states.

## Browser validates the full create acknowledgement

An HTTP 200 with a syntactically valid but **incomplete JSON body** is NOT
reliable evidence of Person creation. The V4 browser now validates that a
newly acknowledged create returns a **positive safe-integer `person_id`**
and a **32-character lowercase hexadecimal one-use `undo_token`** before
clearing its pending cryptographic request key or navigating as if saved.
There is one intentional exception: a server-confirmed
`idempotent_replay:true` may return `undo_token:null` if the original
bounded Undo history has expired. Such a replay can be confirmed as an
existing Person, but the UI must **not invent an Undo action**.

A missing/null/non-object body, invalid Person ID, absent Undo token, or a
null token without a true replay flag enters the same conservative path
as a lost network response: the original opaque request key remains available
for the read-only **Verify earlier creations** endpoint, no success
navigation occurs, no fake Undo is displayed, and another create is gated
on inspecting People. The server's first-create protocol is unchanged;
this protects against malformed acknowledgement handling, proxy/caching
corruption and incomplete test doubles, **not** against malicious tampering.

`node tools/v4/test_create_ack_shape.cjs` runs the real embedded browser
creation handler against ten fictional malformed HTTP-200 payloads, checking
recovery keys remain private and recoverable via a read-only status request,
then separately verifies the ordinary accepted-create and expired-replay
contracts. No real Person/photo records were used.

## Delayed Person creation and newer Undo precedence

A cryptographically keyed Person creation is an explicit reviewed write. If
its HTTP response arrives **after a different reviewed action** completed
elsewhere in the UI, it must not silently displace that action's visible
one-use Undo control. The browser now captures the Undo token at the
moment the create POST begins. Upon a valid, verified response, it exposes
the Person creation Undo **only when no intervening operation replaced
that token**. Otherwise it preserves the newer Undo and clearly reports
`newer Undo preserved`, while still confirming the Person was created.

The original cryptographic request key is retired **only** after its
complete receipt passes validation. A response arriving after navigation
cannot redirect the reviewer away from the new Person or page. The
older creation's Undo remains part of the bounded server history but may
not be safely usable if subsequent identity writes affected the same
evidence; the browser does not automatically select or replay it.

`node tools/v4/test_create_late_undo_order.cjs` exercises the real V4
browser helper with fictional delayed POSTs, navigating to another Person
and completing a newer Undo, remaining on the same screen with an
intervening reviewed action, then a normal creation without competition.
No private People state is changed.

## Recovery status is not proof without a valid receipt (21 October)

A follow-up integrity audit found that the browser's **Verify earlier
creations** flow could lose its only session-stored opaque request key
after receiving `{status:"committed",person_id:...}` with no genuine Undo
evidence. It also accepted **any string** as an Undo token. A malformed
HTTP-200 response could therefore both destroy the recovery route and
create an unusable or misleading Undo button.

Now the read-only committed-status response must contain **a positive,
safe-integer Person ID** and **either a lowercase 32-hex one-use Undo
token or explicit JSON `null`** (legitimate after bounded Undo expiry).
Missing, empty, uppercase, too-short, non-string and other malformed
receipts are treated as **unverified**: the exact opaque request key
stays pending, no Undo is fabricated, and no automatic POST retry,
profile navigation or identity mutation occurs. Explicit `undone`
and `archived_or_missing` status responses retain their established
terminal handling. A valid older recovered Undo never displaces a newer
review Undo.

`node tools/v4/test_create_status_ack_shape.cjs` executes the real
browser recovery code with **17 invalid committed-status shapes**, a
valid Undo, an expired Undo, a newer independent Undo, and a mixed
multi-key queue. It verifies that invalid status answers retain only
opaque recovery keys in sessionStorage, while the known-terminal keys
are independently retired; every request is read-only. No private
People data or actual photo content is involved.

## Avoid unnecessary 2,001-Person roster reload on status-only checks

The shared V4 `post()` helper historically discarded the entire People
roster cache after **every** successful POST, including authenticated
read-only ledger lookups. Repeatedly clicking **Verify earlier creations**
or **Verify earlier Undo status** while the result stayed unresolved could
force a new `/api/people?covers=0` metadata fetch and rehydrate visible
portraits, even though the status query itself writes nothing.

The helper now retains its cached People roster and already-hydrated visible
covers for the **only two proven unchanged status results**:
`/api/person/create/status → unknown` and
`/api/review/undo/status → unknown | recorded_not_undone`.
These results **do not mean an earlier write will never commit**; the
original in-flight transaction may still complete later. The pending
reviewer warning and manual inspection requirements are unchanged.

Conversely, a read-only status result that confirms an older completed
creation/Undo/archival **invalidates the roster cache**, because the
browser may have missed the actual mutation acknowledgement and its stored
counts may be stale. Any malformed or unexpected status conservatively
invalidates the cache too. All real write endpoints retain the original
invalidation behavior. Server errors still propagate without claiming
the People database was unchanged.

`node tools/v4/test_readonly_status_people_cache.cjs` executes the
actual embedded-browser `post()` function against a fictional 2,001-Person
roster, performing **750 synthetic unresolved status checks**, checking
identical cached metadata/cover references, then confirming safe cache
invalidation for known terminal outcomes, malformed HTTP responses and
six real mutation route names. This is a browser-state regression, **not**
a synthetic 150k-photo full-module performance benchmark or an actual
S24+ memory/latency measurement.

## Unknown reviewed writes cannot leave stale People roster cache

The browser must not accidentally present outdated Person names, face counts
or cover tiles as a trustworthy reference immediately after a *write whose
HTTP acknowledgement was lost*. The earlier optimization preserved cached
large People rosters across genuinely read-only **unknown status** queries;
however, the shared `post()` helper discarded that cache only when a
mutation **returned successfully**. A Create/Merge/Rename/Accept/Restore/
Undo request might commit SQLite and then lose its response, leaving a
previous 2,001-Person roster cached. Worse, a metadata GET started before
an explicit write could finish after that write and refill the cache with
already outdated profiles.

V4 now invalidates the People cache **both before and after every mutating
POST**, including unsuccessful fetch, invalid JSON, rejected HTTP response
and confirmed completion. A monotonically advancing in-memory cache
revision also prevents slow People roster GETs started before/during a
mutation from repopulating the cache after the mutation changes that
revision. The active People loading path retries one bounded time;
if repeated writes overlap both attempts, it displays a visible
**People changed while loading — open People again** message rather
than presenting an old roster as current.

The two authenticated token-status POST endpoints remain strictly
read-only. An `unknown` Create status or `unknown` /
`recorded_not_undone` Undo status retains cached People. A status
that reveals prior confirmed changes still invalidates the cache.
Unknown write outcomes do **not** justify retrying a transaction:
the existing explicit inspect-before-retry guard stays intact.
No private Person names, face IDs, original paths or payloads are
persisted to browser storage.

`node tools/v4/test_roster_get_write_race.cjs` executes the actual
browser roster and HTTP helpers against a fictional 2,001-Person
cache, synthetic overlapping GETs and writes, a simulated
committed-then-lost reply, a successful mutation after a stale
midflight GET, read-only status preservation and stale tab
navigation. The existing `test_readonly_status_people_cache.cjs`
adds five malformed/rejected mutation HTTP outcomes to the 750
unchanged-status probes. This proves only browser state behavior,
not measured phone latency or identity recognition accuracy.

## Late visible portrait hydration must not resurrect stale People tiles

A further race was found after protecting People metadata GETs from
concurrent writes. The initial People view loads 2,001 metadata rows,
then awaits the **visible 48 cover images**. If an explicit review
mutation invalidates the roster during the slower cover lookup,
`fillVisiblePeopleCovers()` already correctly refuses to update
the discarded cache—but the outer `people()` renderer still held
`result.matches` from that obsolete snapshot and could emit outdated
Person names and counts into the active HTML.

The renderer now freezes the roster reference used for its 48 cards
and checks it again **after cover hydration settles**. A replaced or
invalidated People cache causes a visible "People changed while
loading. Open People again to refresh." state instead of emitting
stale profile tiles. No extra background HTTP requests, identity
writes or portrait decoding are introduced.

The existing `node tools/v4/test_visible_roster_covers.cjs`
now simulates an initial People render with a delayed 48-portrait
response, a concurrently committed or ambiguous Person mutation,
a discarded 2,001-profile metadata snapshot, and the next explicit
reload. It asserts that old covers never mutate the discarded
roster or render old HTML, while a fresh roster still hydrates
only the next visible portrait page.

## Private-free People cache invalidation across local browser tabs

After fixing same-tab stale metadata and portrait races, a second tab
opened at the **same local V4 preview origin** could still retain an old
People roster while another tab named, merged or unassigned faces.
This is particularly misleading when a reviewer deliberately opens a
second tab to inspect an uncertain transaction.

V4 now uses the optional standard **BroadcastChannel** browser API,
scoped to the preview's own same-origin tabs, to send exactly the
static event `{type:"people-changed"}` when a POST could mutate People
or a read-only status confirms a previously missed mutation.
The event carries **no names, Person/face IDs, photo paths, request
keys, Undo tokens, payloads or biometric information** and is never
persisted to local/sessionStorage or transmitted to a cloud service.
The receiving tab simply invalidates its cached roster and increments
its in-memory revision, preventing an older in-flight metadata GET
from being mistaken for fresh. The receiver **never rebroadcasts**,
so there is no feedback loop. When the other tab is currently showing
People cards and a cached roster was replaced, an accessible toast
asks the reviewer to reopen People. This does **not** automatically
edit identities or disrupt a focused Person picker.

Each originating mutation announces invalidation **before** the
request and again after any success/failure. A read-only
`unknown` / `recorded_not_undone` status does **not** broadcast;
a status indicating known earlier committed state does. If the browser
doesn't support BroadcastChannel or disables it, the previously
validated **same-tab** generation/failed-ACK safety remains in force;
cross-tab refresh then requires manually reopening People.

`node tools/v4/test_cross_tab_people_cache.cjs` executes the actual
V4 browser cache/POST functions twice against fictional 2,001-profile
rosters using an in-memory BroadcastChannel mock, proving start/end
invalidation, lost-after-commit response handling, no metadata
leakage, no echo, conservative read-only statuses and unsupported
API fallback. No phone, private SQLite, photo decoder or live browser
was operated by this regression.
