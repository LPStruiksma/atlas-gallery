# Photo DNA: pinned selection and fallback recovery

Lane B / issue #83, October 17, 2026. **Research-only; no native app or active index pointer is changed.** The program handles private numeric Photo DNA index metadata only. It cannot determine common camera exposure and does not touch original photos, MediaStore, the phone, People databases, or any installed APK.

## Problem and trust boundary

The compact index, receipts, immutable delta segments and complete generation catalog can be verified, but a reader must choose which catalog to use and recover when the preferred catalog is damaged. Selecting the newest name in a directory is unsafe; an old valid catalog can look current after a crash or lost update.

A **generation selection ticket** is a canonical, immutable record that binds one preferred catalog (name and independently pinned SHA-256) to a verified older effective catalog as fallback. The first epoch has no fallback. Every later epoch contains an exact pointer to the previous ticket name and SHA-256; verification follows the ancestry back to epoch 1 and checks strict numbering and bounded depth (at most 32).

The caller MUST independently retain the exact returned selection ticket SHA-256 in a trusted authority **outside the cache**. The cache cannot authenticate itself. An old authentic ticket is still valid historical evidence, and an attacker or bug presenting an older trusted digest can make a reader see an older generation. Thus the authority controls which ticket is the newest intended selection.

## Publishing a selection

1. Independently authenticate and completely verify the proposed new catalog, including its compact binary index and its entire ordered delta roster.
2. If a predecessor exists, read its independently pinned ticket and full ticket ancestry, then verify its actually usable primary (or older fallback if that primary failed). Bind that effective catalog as the new fallback. The new primary must differ from it.
3. Serialize canonical ASCII JSON with exact schema/version, epoch, parent selection pointer, primary catalog, and fallback catalog. Refuse unsupported fields, invalid hashes, unsafe basenames, duplicate JSON keys, and incompatible epochs.
4. Write a private 0600 temporary file, fsync it, and publish via a no-overwrite hard link. Best-effort directory fsync follows. Normal failure cleans only this process's temporary file, leaving prior generations untouched.
5. Independently retain the resulting ticket SHA-256 and its filename as the authoritative selected tip **outside** the index cache. Step 5 is a caller protocol requirement, not implemented by the research module.

A crash between steps 4 and 5 can leave an orphaned *complete* new ticket. That ticket must not be auto-selected by filename order. The previously trusted tip is still usable; there is no automatic garbage collector or unsafe cleanup.

## Resolving a selection

The read-only verifier accepts a ticket path and a separately trusted exact SHA-256. It first authenticates that ticket and its complete bounded ancestry. It verifies its preferred catalog, and if unusable, explicitly checks the pinned fallback catalog. The result reports selection epoch, selected catalog digest, selected generation, numeric updated-document overlay, and whether fallback was used. A damaged primary is never silently represented as current, and failure of both candidates raises without returning incomplete index data. When the primary verifies, the fallback is not continually rechecked and no claim about its later health is made.

### Example API (fictional digest placeholders)

    from pathlib import Path
    from tools.photo_lineage.generation_selection import publish_selection, verify_selection

    root = Path("/separate/approved/synthetic-cache")  # already exists
    first = publish_selection(
        root / "epoch-1.selection.json",
        primary_catalog_name="first.catalog.json",
        primary_catalog_sha256=ACTUAL_TRUSTED_FIRST_CATALOG_SHA,
    )
    # Independently retain first["ticket_sha256"] outside cache.
    second = publish_selection(
        root / "epoch-2.selection.json",
        primary_catalog_name="second.catalog.json",
        primary_catalog_sha256=ACTUAL_TRUSTED_SECOND_CATALOG_SHA,
        previous_ticket_name="epoch-1.selection.json",
        trusted_previous_ticket_sha256=first["ticket_sha256"],
    )
    # Independently retain second["ticket_sha256"] as latest authoritative tip.
    chosen = verify_selection(root / "epoch-2.selection.json",
                              second["ticket_sha256"])

The names are local-only cache names, not source image paths. The SHA placeholders deliberately are not working credentials, signing material, or actual valid digests.

## Reproduction

    python -m unittest discover -s tools/photo_lineage/tests -p test_generation_selection.py -v
    python -m unittest discover -s tools/photo_lineage/tests -v
    python -m tools.photo_lineage.ci_gate

Twelve synthetic end-to-end checks cover private tickets, healthy newest catalog, damaged primary and working fallback, both catalogs invalid, missing ancestor, tampering, duplicate JSON keys, noncanonical encoding, existing/symlink refusal, competing publication, choosing the effective fallback as a new predecessor, and historical pinned ticket rollback. The authoritative exact-head GitHub test run must succeed before integration.

## Honest limitations

- This does **not** atomically update a protected trusted-tip store or mutable CURRENT pointer. Its research-only output remains **nonactivating** until some future caller explicitly uses the externally pinned tip.
- Concurrent publishers can create different names from one parent; a separate authoritative tip must arbitrate. No multi-writer consensus or end-to-end cross-store transaction.
- POSIX fsync and hard-link behavior cannot guarantee durability on every Android filesystem. Power loss, stale concurrent reads, symlink swaps in ancestor directories, inode exhaustion, old-generation compaction and Android resource/battery budgets remain unverified.
- Verifying cache integrity never certifies that different images share one shutter event. Every photographic candidate remains review-only; there is no automatic identity assignment, transitive deduplication or deletion.

Next independent tasks: a protected tip-store design with crash simulation, quota-bounded copy-on-write compaction retaining the last usable index, and real licensed independently captured same-scene photographic evaluation.
