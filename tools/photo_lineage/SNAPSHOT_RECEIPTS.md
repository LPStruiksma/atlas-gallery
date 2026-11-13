# Immutable snapshot receipts (Lane B — issue #83)

**Research prototype, not production recovery or automatic gallery activation.** All code lives in `tools/photo_lineage/` and handles numeric packed feature indexes, not photos. It neither reads images nor moves, deletes, uploads, merges or automatically groups original files. A receipt is **not** a backup, and it does not prove two photos came from the same shutter exposure.

## Problem and intended boundary

The existing [bounded-memory index](EXTERNAL_INDEX.md) publishes a numeric `.u64` file and can independently verify its contents against a trusted SHA-256. That is necessary but insufficient for crash-safe generational switching: a reader needs to know *which generation* to use and must reject incomplete or substituted files. A mere JSON file containing the hash of an index **cannot authenticate itself** if an attacker or accidental overwrite can change both files.

This experimental module deliberately implements the smallest safe step: **an immutable, private receipt for a single completed generation**. No mutable `CURRENT` pointer or index activation is introduced; old generations remain untouched. The receipt binds format, generation ID, filename basename, exact index hash, byte size, and posting count. The receipt itself must be pinned via an independently retained SHA-256, ideally in an authority-controlled higher-level manifest or verified backup.

## API and example (synthetic numeric entries only)

From repo root, inside an isolated Python environment:

```python
from pathlib import Path
from tools.photo_lineage.external_index import build_snapshot
from tools.photo_lineage.snapshot_receipt import publish_receipt, verify_receipt

sandbox = Path("/tmp/atlas-synthetic-index-only")  # pre-create, dedicated, private
snapshot = sandbox / "snapshot-g1.u64"
receipt = sandbox / "snapshot-g1.json"

built = build_snapshot([(0, [15, 18]), (1, [15, 21])], snapshot)
published = publish_receipt(
    snapshot, receipt, generation="g1",
    expected_snapshot_sha256=built["sha256"],
)
# Preserve published["receipt_sha256"] independently of both artifacts!
validated = verify_receipt(receipt, published["receipt_sha256"])
assert validated["verified"] and validated["postings"] == 4
```

**Before trusting an existing snapshot**, the expected receipt digest must come from an independent, trustworthy source; never read an untrusted receipt and accept the digest it claims for itself. This self-generated example validates consistency after construction but is not proof of externally trusted provenance.

`publish_receipt` verifies the full numeric index *before* writing a private, fsynced temporary receipt. It publishes using a no-overwrite hard link, with best-effort parent-directory fsync. A pre-existing receipt or symlink is never replaced. A failed validation leaves no published receipt. Only the temporary receipt file is removed after normal execution; failures and unexpectedly terminated processes may still leave scratch artifacts. Filesystems that do not support hard links or durable directory fsync may reject or weaken publication, and **no production durability guarantee is claimed**.

`verify_receipt` accepts a separately pinned lowercase SHA-256, checks the complete bounded receipt bytes, rejects duplicate JSON keys/schema drift, enforces a basename-only referenced index (no traversal), then runs the existing read-only full snapshot verification. It rejects changed counts, corrupt/unsorted/duplicate postings, invalid SHA, symlinks and excessive file sizes. Report output contains only generation, digest, size, count, and verification status, not private source paths.

## Local test evidence and boundaries

```bash
python -W error::ResourceWarning -m unittest discover \
  -s tools/photo_lineage/tests -p test_snapshot_receipt.py -v
```

Fifteen focused regression methods passed in an isolated Linux Python 3.13 test workspace using a **local compatibility implementation** of the parent PR's snapshot verifier; the authoritative end-to-end repository/Actions check has **not** run. Tests cover valid round-trip and bytes unchanged, no-overwrite protection, incorrect/missing digest, failed validation without publication, post-publication tampering, traversal/JSON duplicate-key attacks, symlinks, size budgets, invalid generation, and falsified counts, a competing writer claiming the receipt destination during link publication, and oversized even-correctly-pinned receipt data. These exercise disposable synthetic numeric indexes only.

**Remaining risks:** receipts are static evidence, not atomic activation of a live index. There is no identity/provenance authority, delta journal, multi-generation registry, disk quota, end-to-end cancellation recovery, concurrent filesystem-adversary guarantee, retained data for old-version Android upgrades, or independently validated phone benchmark. Caller must use a dedicated, permission-controlled cache directory and keep the independent receipt digest safe. No cryptographic signature or secret key is involved. Do not claim the receipt makes a mutable original index tamper-proof; it only permits **detection at verification time**.

## Next bottleneck

Research crash-safe **activation** separately: a durable, independently checked two-slot pointer or small append-only catalog should select an immutable generation *only after* its index and receipt both pass verification. Handle torn writes, stale paths, generation rollback, and partial disk exhaustion without deleting a usable previous generation. The Android/app integration and release authority stay with the native/release lanes. The full stacked PR series still requires exact-head Python CI and staged integration under issue #83.

## Read-only recovery decision between two trusted generations

The optional `verify_with_fallback(primary_receipt, primary_sha256, fallback_receipt, fallback_sha256)` function **does not activate or change any index**. It first verifies the primary against an independently pinned digest. If valid, it returns that primary and explicitly states `fallback_used: false`; the fallback is not read. If the primary is corrupt or missing, it independently verifies the fallback and reports `fallback_used: true`, `primary_status: unusable`, and only a safe error *type* (no media paths). If neither verifies, the function fails closed; both supplied receipt paths must be distinct and both digests well formed. This is a research decision aid, not a production app-selected index. Three additional local regression methods exercise healthy-primary, corrupt-primary/intact-fallback and both-invalid conditions; **15/15 focused tests PASS** in the isolated compatibility-verifier workspace.

**Risk:** a valid fallback says nothing about whether the older index still reflects recent external file moves, and caller-provided pins alone do not prove a trusted provenance chain. Never quietly present a fallback as current or merge/delete photos using these reports. Atomic active-generation switching, authoritative pinned catalog, cache invalidation and external-device recoverability remain unimplemented.


## Exact-head repository validation (2026-10-17)

The Photo DNA `photo-lineage-safety.yml` workflow is now enabled. Verify the **exact PR head** with all existing Python unit tests and the synthetic `ci_gate.py` before merge; the earlier 15 isolated tests used a parent-verifier compatibility mirror and must not be mistaken for this integrated check. Even a green Actions run certifies synthetic invariants only, not automatic Android index activation, device crash durability or evidence that two real images share a camera exposure.
