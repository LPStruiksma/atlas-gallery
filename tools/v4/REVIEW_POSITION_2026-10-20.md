# V4 review viewport continuity after confirming faces (20 October 2026)

## Why

V4 preserves page and scroll position after opening a photo, navigating to
lookalikes, or returning from a Discover group. But a confirmed face action
(accept/reject, attach selected, ignore, unassign, Undo) repaints its current
face grid asynchronously. Replacing the DOM can drop keyboard focus, and
can move a reviewer who is inspecting the middle of a long face page.
The same applies to manually setting a Person's cover portrait.

## Behavior

A shared, **transient** `repaintReviewKeepingPosition()` wrapper captures
the current vertical pixel position and the *currently focused* visible
toolbar action / selected face control / Use-as-portrait button just before
a successful review repaint. It calls the existing guarded `render()`;
on the next animation frame it restores scroll and, only for a previously
focused control, keyboard focus with `preventScroll: true`.

A repaint invalidated by newer page, Person, mode, group or render generation
does **not** restore anything. If an Undo button disappeared because the
one-use token was consumed, focus falls back to Select page when present.
If an active face was removed and its focus target no longer exists, the same
safe page-local fallback is attempted. No button is synthetically clicked,
no face/Person identity changes are made, and the browser never writes
face IDs, person IDs, paths, selection or viewport data to localStorage.

This intentionally does not change intentional navigation: opening a new
Person, explicitly turning Next/Previous, or returning from photo/similarity
already has its own separate focus/scroll handling. Scroll is pixel based,
and the browser may clamp it after large content shrinkage; that remains
preferable to forcibly jumping to the top of an uninterrupted review.

## Synthetic evidence and owner acceptance

`test_review_position_after_write.cjs` executes the real browser helper
with fictional numeric IDs and async frames. It verifies 674px and 1200px
deep-scroll restores, keyboard focus on the previous review action and
Use-as-portrait control, safe focus fallback after Undo, nonintrusive touch,
and stale frame cancellation on both a new Person and new render epoch.
Existing direct-review single-flight and selected-face-assignment regressions
continue to guard batch membership, Undo and late POST behavior. Dedicated
V4 CI includes the new regression.

After owner-controlled independent People backup and isolated restore, a
manual S24+ browser touch/TalkBack trial should confirm no distracting scroll
or focus jump when reviewing a late-page face; do not deliberately label,
unassign or erase private faces solely to exercise this scenario. Host VM
tests do not prove mobile frame timing or TalkBack announcements.

**Boundary:** `tools/v4/**` plus its dedicated V4 test workflow. No Android
native changes, installed APK, private SQLite, photo originals, running
Termux server/process, or metered external model inference.
