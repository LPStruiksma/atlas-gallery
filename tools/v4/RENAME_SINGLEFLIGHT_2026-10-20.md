# V4 Person rename: competing taps and unknown commit outcomes (2026-10-20)

**Scope:** local-first browser preview, `tools/v4/**` only. All examples/tests use fictional person names; no production People database, biometric/photo files, Android APK, active Termux server, or credentials were accessed.

## Finding

The existing Rename action confirmed homonyms and pinned the profile ID against stale navigation, but it was **not single-flight**. Multiple taps before the metadata-only name roster arrived could launch competing name prompts; both subsequent POSTs could legally succeed, leaving the name selected by the faster or slower response rather than by an intentional last confirmation.

A second failure mode is transport ambiguity: the server can commit a SQLite rename and its HTTP response can then fail. A generic `Error` toast gives no guidance to inspect the current Person before trying another mutation.

## Repair and review semantics

- The **first** Rename request exclusively owns the prompt, metadata read, deliberate same-name confirmation, POST and UI refresh. More taps while it is active are inert. The guard releases on explicit cancellation, stale navigation, read exception, successful write or uncertain write completion.
- An open Person chooser also blocks creating a competing prompt behind the dialog.
- After a rejected rename **POST**, the browser reports *Could not verify rename ... check People before retrying*. It does **not** assert that the original name survives, automatically repeat a potentially committed request, or invent an Undo token.
- Original same-name semantics remain: NFKC/trim/collapsed-space comparisons, separate Person IDs for homonyms and a deliberate confirmation before creating a homonymous label. The server independently validates name collisions inside its write transaction.
- A delayed reply cannot rename a different Person screen or repaint its review after navigation.

The new `test_rename_singleflight.cjs` extracts actual browser handlers into a Node VM, sends 130 competing taps, checks a single fictional write, tests both declined/accepted homonym branches, cancellation, metadata error, stale navigation, modal ownership and an intentionally rejected **after-commit acknowledgement**. It is in the dedicated V4 CI workflow. This does not prove crash-free recovery or phone browser latency.

## Owner acceptance

Following the existing independently verified online SQLite backup and isolated restore checks in `PREVIEW_ACCEPTANCE.md`, confirm the Person's new name is visible. If the browser reports unverified completion, inspect the People roster before any retry. Avoid intentionally disconnecting the actual phone in the middle of a live identity mutation; uncertainty scenarios are covered by synthetic tests. Repeated touch testing on a real Person is optional, not a release requirement.
