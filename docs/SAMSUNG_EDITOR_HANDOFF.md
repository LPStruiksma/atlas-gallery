# ATLAS → Samsung Gallery editing (public Android handoff)

**Intent:** Keep ATLAS as the fast, local photo browser while retaining the
actual Samsung Gallery editor's extensive tools, including the Galaxy AI
features that are available on the user's current phone/software version.
This is **not an embedded Samsung SDK**: Samsung's editor is proprietary and
an externally exposed activity is not guaranteed. Android's public
`ACTION_EDIT` intent does not promise a result URI or specific save behavior.

## Native viewer flow

For an eligible MediaStore **photo** (not video or a private file URI),
ATLAS shows an **Edit** pencil in the viewer toolbar and uses only the
currently focused media item's `content://media/...` URI:

1. Try `ACTION_EDIT` scoped to the installed Samsung Gallery package
   `com.sec.android.gallery3d`, granting only that URI read+write access.
2. If Samsung does not expose an eligible editor activity, open that exact
   photo in Samsung Gallery with `ACTION_VIEW`, **read access only**.
   Briefly explain that the Gallery pencil opens its built-in editor.
3. If Samsung cannot view it either, try the standard system
   `ACTION_EDIT` resolver with only this URI.
4. If no route succeeds, leave the user in ATLAS and report the failure.
   Do not guess private Samsung components, launch hidden activities,
   use accessibility automation or modify files directly.

**Returning:** Android Back returns to ATLAS's existing full-screen viewer;
the selected photo/filmstrip context is retained. An in-memory one-shot
editor-return tracker records **only** the edited MediaStore URI after a
successful external launch, then listens for the activity's `ON_RESUME`.
On returning, **that photo alone** receives an updated Coil
memory-cache key partitioned by media URI, editor-return revision and
requested pixel size. The potentially stale disk cache is bypassed, but
the **freshly decoded bitmap is retained in memory** for subsequent
swipes/filmstrip visits. A 144px thumbnail cannot accidentally serve
as a 2400px full-screen image. The rest of the archive continues using
its normal warm-cache keys, and unrelated resumes do not increment
the editor revision. The full-resolution photo and zoom state reset
for a fresh image but the pager position is preserved.

The existing MediaStore-change observer refreshes inventory when Android
notifies of edits or saved copies. Because `ACTION_EDIT` guarantees no
return value, **do not** claim a saved-as-copy image will immediately be
selected; a new URI may require opening the new photo in the gallery.
The returned photo refresh is an app-layer cache-safety improvement, not
proof every third-party editor saves in place. Test Samsung's actual save,
Back/return and AI behavior on an owner-approved S24+ build.

Samsung AI tools can involve service availability, sign-in, remote
processing or image-size/watermark conditions depending on One UI version.
ATLAS does not use Samsung APIs or expose the user's gallery itself.
A successful intent does not prove that every Samsung editing feature
appears when called externally. Test behavior on a real Samsung phone
**only after** the signed APK/data-backup gate is satisfied.

## Regression and safety

`EditorReturnStateTest.kt` checks synthetic successful/failed editor
handoffs, one-shot resume handling, and per-photo scoped cache invalidation.
The standard JVM tests and emulator tests remain required. The app never
needs to inspect or store the owner's photograph to force a cache refresh.

`PhotoEditorHandoffTest.kt` uses only invented `content://media` URIs.
It validates the public actions, Samsung package scoping, read/write grants,
one-photo `ClipData`, absence of forced original-overwrite
`MediaStore.EXTRA_OUTPUT`, invalid file URI rejection, exact focused
image selection, and hiding editor actions for video. It never starts
Samsung Gallery during CI or opens personal data.

**Future built-in editor:** add reversible, non-destructive basics
(crop/rotate/straighten, brightness/contrast/exposure, curves/filters) as
a separate engine only where benchmarking shows a meaningful advantage.
Do not clone private Samsung code/assets or promise an exact Galaxy AI
replica. Prefer delegating specialized capabilities to Samsung while
keeping ATLAS's browser/People/declutter workflow distinct.
