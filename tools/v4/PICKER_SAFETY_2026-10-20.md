# V4 picker ranking and uncertain-write UX — 20 October 2026

## Source UX patterns and distinction

- [Immich's People features](https://github.com/immich-app/immich/blob/main/docs/docs/features/facial-recognition.md) emphasize person portraits, explicit profile merges, and corrective actions.
- [PhotoPrism's People workflow](https://docs.photoprism.app/user-guide/organize/people/) uses named face clusters with editable assignments; its 2026 docs also acknowledge difficult lookalikes and multiyear appearance changes.
- [Immich Face Fix](https://github.com/pabera/immich-face-fix) demonstrates keyboard-driven bulk corrections, resumable review and Undo. Its *automatic correction* mode is deliberately **not** imported: ATLAS remains review-first.
- [Google Photos People](https://support.google.com/photos/answer/6128838) requires confirmation before a suggested merge, but the docs warn merged groups cannot be undone. ATLAS must retain separate-identity and transactional Undo affordances.

## This change: exact + prefix + substring, within the existing 100-result cap

For a typed query, the chooser normalizes compatibility characters (NFKC),
collapses whitespace, trims and casefolds using the same lowercasing rules as
V4's existing duplicate-name guard. Exact matches sort first, followed by
start-of-name matches, followed by incidental substring matches. Name and
numeric profile ID are deterministic ties, so distinct homonyms never silently
collapse. The empty-query order remains the existing server roster order.

**Why:** with 2,000+ named profiles and a maximum of 100 options, a
partial query such as `Alex` could show 100 unrelated names containing
`alex` *inside* a word before `Alex Smith`. Ranking only the in-memory
metadata roster keeps search immediate, and the existing visible-only portrait
hydration remains capped to 48 IDs per network request. No face scoring or
identity writes occur during search.

## This change: failed confirmation is not proof of rollback

The old generic picker catch displayed **"Nothing changed"** after
`onChoose` rejected and immediately re-enabled Confirm. That promise can
reject *after* the server accepts a POST (e.g. response lost, subsequent
render failure), so a second tap could duplicate or conflict with the action.

Now an unresolved confirmation displays **"Could not verify completion"**,
disables repeat submission *including synthetic programmatic clicks*, and
permits Back/Cancel. The reviewer must close the ambiguous picker, inspect
People state and reopen a fresh explicit confirmation before trying again.
An uncertainty flag blocks simply returning to the same confirmation card.
Confirmed success still closes normally and records the existing Undo token.
The client does not claim a rollback or fabricate a new Undo token.

## Synthetic regression and manual acceptance

`node tools/v4/test_picker_escape_focus.cjs` now executes the actual V4
picker functions inside a fictional DOM. It checks 139 distinct named profiles,
including compatibility-width homonyms, collapsed whitespace and 100-item
bounds, plus an intentionally rejected acknowledgement *after* one synthetic
write; no second confirmation is possible. Existing modal focus/Escape,
stale-roster and duplicate-flow regressions continue to run in V4 CI.

Owner-only acceptance (after the separate verified online SQLite backup and
isolated restore rehearsal): search a short substring shared by many profiles,
verify exact/prefix options appear first; open two same-name portraits and
confirm they remain distinct. For an ambiguous error, **do not deliberately
interrupt a real identity POST**; the synthetic test proves the failure UX.
Real browser/TalkBack/S24+ performance remains unmeasured. Do not install,
restart, kill Termux or touch private People data solely for this test.

## Person creation POST ambiguity — later Lane D follow-up

The picker confirmation above protects an *existing-profile selection*, but
the new-Person path is different: a unique name can issue
`/api/person/create` directly, without a picker confirmation. A request
could **commit** its SQLite creation (and possibly group/selected-face tags)
and then lose its acknowledgement. The V4 single-flight guard prevents rapid
concurrent taps, but a later deliberate tap could still duplicate a Person.

The preview now records an ephemeral **unverified Person creation** warning
when a create POST rejects. Any later creation attempt — People roster,
reviewed Group, or selected-face path — must explicitly acknowledge a warning
instructing the reviewer to inspect People first. Cancel performs **no write**.
The next attempt reruns the fresh metadata duplicate-name lookup and presents
the existing-profile chooser when the previously saved name is found. There
is no silent automatic retry, assumed rollback, synthesized Undo token, or
automatic identity merge. A POST rejection may also be a *genuine*
non-committed server error; without an acknowledgement we deliberately do not
pretend to know which case happened.

The warning lives **only in the active browser session**, not persistent
localStorage, a private database, or a background queue. Refreshing the page
cannot establish whether the server saved a previous attempt. Independently
inspect the roster before retrying after reload too. Because a browser cannot
prove a human inspected the correct portrait, the confirmation is an explicit
human-responsibility guard, **not cryptographic idempotency** or exactly-once
write semantics. The stricter server-side idempotency alternative would require
a new token/transaction protocol and separate migration/testing.

`node tools/v4/test_uncertain_person_create.cjs` uses the real browser
creation helpers and fictional in-memory roster/SQLite-commit stand-in. It
asserts that a server-side commit followed by synthetic response loss creates
no Undo token, blocks an unreviewed second POST, offers **Open existing
profile** when the name later appears, and applies the same safeguard to
group-based creation. There is **no instruction to provoke actual network
failure or create private test identities** on the S24+.
