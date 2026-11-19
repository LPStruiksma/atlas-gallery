# Search history (FIND-3a, #182)

ATLAS remembers recent searches on the phone, because the owner asked for it ("also I'd love a search history and autocomplete option if possible", Lukas, 2026-11-15).

## What is stored

- The words of a submitted search, and the opaque keys of people picked as chips. Nothing else: no photos, thumbnails, face or image data, URIs, album ids or result lists.
- One list per search area: `global` (the Search tab), `photos`, `albums`, `album`, `people`, `person` and `stories`. Areas are never split per album or per person.
- Up to 50 entries per area. The newest 10 show as "Recent searches" pills. Searching the same thing again moves it to the front (one entry per query, ignoring case).
- Labels are built when shown, from the current People names. A pill for a person who no longer has a name, or is not visible under the current photo access, is hidden.

## Where

- One app-private file, `filesDir/search-history-v1.txt`, written atomically off the main thread. The manifest sets `allowBackup="false"`, so it is not backed up or copied off the phone.
- Search history is never logged, never put in the usage log (#173), diagnostics, saved-state Bundles or analytics, and never sent anywhere.
- An unreadable or damaged file counts as empty and is rewritten on the next save.

## When it is cleared

- Settings → Search → "Save search history" off: stops saving and clears every area.
- Settings → Search → "Clear search history": clears every area.
- "Clear" next to "Recent searches": clears that area only.
- The ✕ on a pill removes that one entry.
- Each of these offers Undo for about 4 seconds. Undo restores the switch and the entries in their old order. If the app is killed before Undo, the cleared state stays.
- Photo access reduced (full to partial, or any to none): every area is cleared, including when the change happened while ATLAS was closed. More access, or a new grant publication at the same level, keeps the history.
