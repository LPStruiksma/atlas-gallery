# Two-slot private Photo DNA selection-tip checkpoint (research prototype)

**Lane B / issue #83, 2026-10-17.** Purely synthetic numeric index-selection metadata. This is NOT deployed in Android and does not read, change, copy, transmit, delete or consolidate any original images. It does not establish that two photos originate from one exposure.

## Motivation

The previous immutable selection-ticket system verifies the complete index, catalog and incremental deltas, while retaining a fallback. But its **latest authoritative selection ticket hash** must be retained somewhere independently. A torn or overwritten one-file pointer would lose that tip. This experiment explores a simple *local* two-slot checkpoint, not a hardware-backed trust authority.

## Storage boundary

The caller supplies an existing owner-private (0700) **authority directory**, physically distinct and not nested inside the similarly owner-private index cache directory. The authority holds only:

- tip-a.json and tip-b.json: tiny canonical bounded JSON records of epoch, selected immutable ticket basename and pinned SHA, and previous local tip digest.
- .atlas-tip.lock: a private advisory flock coordination file for Linux/Termux prototype concurrency.

The records contain **no photo paths or images**. Metadata can still correlate to a private index and should not be published.

The selected ticket and all source index/receipt/catalog/delta artifacts stay immutable in the cache, validated through generation_selection.verify_selection. The two tip slots are the *only* files this prototype replaces. File contents must be private, sorted/validated and under 1 KiB.

## Crash and rollback behavior

1. Acquire exclusive flock in the owner-private authority. Inspect both slots and verify referenced tickets and catalogs. If both are intact, they must be adjacent epochs and the newer must directly reference the digest of the older. A noncontiguous or forked pair fails closed.
2. Validate the new independently pinned ticket. Its epoch must advance **exactly one** from the chosen tip, and it must directly descend from that selected prior ticket. Refuse advancing to a ticket whose preferred catalog is **already degraded** and using fallback.
3. **Refuse any new publication if an existing slot is unverifiable**, whether it contains a torn/unknown regular file, a symlink or a corrupted catalog reference. Read-only recovery from a surviving good slot remains available and visibly reports degraded redundancy; however a writer must not erase the other slot's original bytes or assume its older generation is the latest. Preserve the damaged slot for explicit owner-led investigation. Require the caller-provided expected previous local tip digest (compare-and-swap) before writing. A stale writer fails rather than overwriting later work.
4. Require free disk headroom and write a fully serialized private 0600 temporary slot. Fsync its bytes, then atomically replace only the *older* tip slot. The previously selected slot is never changed in that commit. Fsync the authority directory where supported.
5. On read, acquire a shared flock, verify both slots and their referenced complete index generations, and choose the latest valid contiguous epoch. If a slot is corrupted, return the surviving verified slot with **degraded_tip_redundancy: true**, instead of silently claiming that the latest is healthy. If neither verifies, fail closed and require explicit owner recovery.

An interrupted operation **before replace** leaves the previous active local selection unchanged; after a successful atomic replace on a supporting filesystem, the new record is visible and the prior valid slot remains. The tests simulate these boundaries, **not real phone power cuts**. Sudden death may leave a self-owned .atlas-tip-*.tmp; never delete other files based on a broad filename pattern.

### Simple research usage

    from tools.photo_lineage.tip_store import publish_tip, resolve_tip

    first = publish_tip(
        "/separate/owner-private/tip-authority",
        "/separate/owner-private/index-cache",
        ticket_name="epoch-1.selection.json",
        trusted_ticket_sha256=EXTERNALLY_TRUSTED_TICKET_SHA,
    )
    # For a later already-verified ticket:
    second = publish_tip(
        "/separate/owner-private/tip-authority",
        "/separate/owner-private/index-cache",
        ticket_name="epoch-2.selection.json",
        trusted_ticket_sha256=NEW_EXTERNALLY_TRUSTED_TICKET_SHA,
        expected_previous_tip_sha256=first["local_tip_sha256"],
    )
    view = resolve_tip(
        "/separate/owner-private/tip-authority",
        "/separate/owner-private/index-cache",
    )

The two directories must be **pre-existing and owner-private**. No credential, API key or OpenAI token is involved.

## Trust and limitations

**This is only local crash-consistency research, not an anti-rollback security boundary.** The digest in the protected local slot is self-calculated; an attacker with write access to the authority directory could forge or roll back both slots and their underlying selected tickets. An externally retained trusted tip digest, secure monotonic counter, recovery key or genuinely protected database/transaction would be required for strong cryptographic authenticity. Do not represent a valid highest local epoch as externally authenticated freshness.

Advisory flock only protects cooperating processes on supporting POSIX filesystems. The implementation does not protect against malicious directory replacement, lock file tampering, inode exhaustion, Android FUSE implementation differences, OS/storage-controller power-loss behavior, arbitrary publication failures after replace, full device crashes, or cross-volume atomicity. The free-space check is not a reservation. No device benchmark or live app activation exists. A valid fallback is still only a *candidate index* and cannot authorize photo deletion, automatic grouping or assignment of People identities.

## Reproducible synthetic verification

    python -m unittest discover -s tools/photo_lineage/tests -p 'test_tip_store.py' -v
    python -m unittest discover -s tools/photo_lineage/tests -v
    python -m tools.photo_lineage.ci_gate

Nineteen deterministic, disposable numeric tests cover first epoch, third-epoch slot rotation, torn newest recovery, both invalid failure, stale CAS writers, injected atomic-replace failure, simulated insufficient disk, symlink refusal, degraded new catalog refusal, sibling ticket replay distinct authority roots, torn-newest and corrupt-older slot write refusal, unexpected standby file byte preservation, and abandoned scratch preservation. Existing Photo DNA correctness/unknown abstention checks remain necessary.

## Next safety bottlenecks

Before production: research externally anchored latest-tip authenticity, safe owner-controlled rollback approval, actual POSIX/Android fsync behaviors, cache quota and generation retention policy, and real independently captured same-scene photo benchmarks with per-file licenses. Any native app integration belongs with Lane A and must be coordinated in #83.


## Damaged-slot preservation policy (added October 17, 2026)

Previous versions rejected replacement of a **symlink** in the standby slot but permitted overwriting an **unverifiable regular file** with the expected slot name during the next write. This could destroy forensic evidence from an interrupted update or unexpectedly overwrite unrelated private bytes. A valid older slot does not establish whether the damaged slot was already a newer committed selection.

The corrected policy separates *reads* from *writes*:

- **Read:** if one slot is verified and the other damaged, return the surviving view with `degraded_tip_redundancy: true`. Neither slot is modified.
- **Write:** if either named slot exists but fails verification, raise `DamagedTipSlotError` *before publication*, even when caller supplies a currently valid ticket and a matching compare-and-swap pin. Do not automatically erase, quarantine, repair or rename the damaged file. This includes symbolic links and unfamiliar regular files.
- **Recovery:** resolve the damaged slot through a separate, reviewed and explicitly authorized procedure. The research prototype intentionally implements no destructive or automatic recovery command. An unrelated old `.atlas-tip-*.tmp` is similarly preserved; scratch files are not blindly deleted by filename glob.

This conservative policy deliberately sacrifices write availability while damaged state remains. It does **not** add external anti-rollback authenticity, trusted recovery authority, or an Android deployment guarantee. The tests use only disposable synthetic index metadata.

## Post-publication verification and ambiguous commit windows (17 October 2026)

The publish operation now re-reads **both local checkpoint slots, their ticket ancestry and selected verified catalog/index** under the same exclusive lock after the atomic replacement, before returning success. It checks that the observed epoch, exact new tip digest and ticket pin match the intended published record, and that neither a damaged standby nor a degraded catalog was accepted. The successful response includes `postpublication_verified: true`.

This explicitly addresses two very different outcomes:

- **Replace succeeds but verification fails:** report `RuntimeError` and **do not** erase the just-written slot. A surviving older verified checkpoint can still be read, with degraded redundancy recorded. Do not claim the operation committed successfully.
- **Replace succeeds but the process loses its acknowledgement:** the original caller may receive an exception despite the new checkpoint being visible. **Never blindly retry.** Read `resolve_tip` to establish the observed epoch and exact selected digest, then reconcile against externally retained expected tip state. A stale compare-and-swap retry must fail.

These tests inject corruption after a real temporary-file rename, simulate a rename that commits and then raises, inject a read-verification failure after publication and verify normal successful publication attests the observed commit. The old verified slot and immutable index files remain untouched by these simulations. They do not constitute power-cut, Android filesystem, hardware signing or external anti-rollback evidence.

**Availability tradeoff:** this is an additional complete verification for every publish, which can be expensive for large indexes. It is research-only until CPU, IO and battery cost are measured and a safe bounded production design is coordinated with Lane A.
