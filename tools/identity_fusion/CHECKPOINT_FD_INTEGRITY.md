# Checkpoint verifier descriptor integrity — Lane C

**Research-only safety hardening.** The existing
`verify_candidate_artifact.py` checks independently pinned model bytes
without loading or executing any ONNX/PyTorch weights. Prior to this
revision it checked only whether the **final pathname** was a symlink
and then opened by pathname. An attacker, concurrent extraction process
or mistaken alias could supply an intermediate symlink directory or swap
the path between `stat()` and `open()`; the source could also be a
hardlink to another mutable object. Hash mismatch generally catches
wrong bytes, but passing digest alone says nothing about stable staged
file provenance if the path metadata changes under the verifier.

## Scoped correction

The read-only verifier now opens `/` as a directory FD and traverses
**each** path component relative to an already-open directory FD,
requiring `O_DIRECTORY|O_NOFOLLOW` for all intermediate directories.
It opens the leaf with `O_NOFOLLOW|O_NONBLOCK`, then uses `fstat()`
for all subsequent metadata rather than re-resolving the caller's path.

- Reject symlink parents/leaves even if they point to a valid-looking
  file, and refuse `..` traversal. A malicious late symlink
  substitution at the leaf fails closed.
- Reject nonregular, hardlinked (link count >1), empty or oversized
  candidate files **before** streaming their contents; nonblocking
  leaf open prevents FIFO hangs.
- Compute SHA-256 or SHA-384 from the **same FD**, in chunks capped to
  1 MiB, with the existing 64 MiB overall limit.
- Verify the same FD's device, inode, size, modification and change
  timestamps before/after the bounded hash. Any mismatch or hash
  disagreement refuses an integrity result.
- Preserve the previous CLI, candidate register, JSON output schema,
  model checksums, scope, and no-execution/no-rights assertions.
  No image, Person, native app or backup data is touched.

The test adds fictional files and adversarial cases for nested symlinks,
hardlinked aliases, FIFO/sparse oversized files, late leaf replacement
with a symlink, path traversal and digest mismatch. The existing happy
path also asserts original file bytes are unchanged.

```sh
python tools/identity_fusion/test_verify_candidate_artifact.py
python -m unittest discover -s tools/identity_fusion -p 'test_*.py'
```

## Limits

A descriptor-pinned read is **not** a digital signature of authorship
or a guarantee that a malicious process cannot race to modify already
open file content; inode/size/time/hash checks are conservative but
cannot certify filesystem immutability against a privileged adversary.
Use owner-controlled staged directories, independently authenticated
publisher digests, signed provenance and trusted file ownership for a
future actual checkpoint study. A Git repository license label does not
automatically clear model-weight redistribution, biometric consent,
training datasets or runtime. No checkpoint is executed, redistributed
or approved for a pilot, automatic Person linking, or Android deployment.
