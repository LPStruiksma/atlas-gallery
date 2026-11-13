# Host-only resource-capped RAW decode probe (Lane B, issue #83)

**Experimental; not an Android implementation or a security sandbox.** The
new modules `run_isolated_raw_probe.py` and `isolated_raw_probe.py` are
opt-in and are not called by the production matcher or indexing code.
No private photos, Gallery/People databases, real DNG assets or models are
downloaded, uploaded, modified, scanned or bundled by CI.

## Why a process boundary is essential

Reviewing upstream rawpy's `_rawpy.pyx` shows that apparently metadata-only
properties such as `raw_pattern`, `raw_color`, and
`black_level_per_channel` can invoke `ensure_unpack()` and trigger native
RAW decompression. Therefore Lane B's existing visible dimensions guard
does **not** protect memory consumption before rawpy finishes unpacking.
Source: https://github.com/letmaik/rawpy/blob/main/rawpy/_rawpy.pyx

The worker applies Linux `RLIMIT_AS` before NumPy/OpenCV/rawpy imports:
512–4096 MiB of *virtual address space*, 2048 MiB default; CPU 3–90 seconds,
30 default; core dumps and file sizes limited to zero. The parent separately
enforces wall time (default 45, maximum 120 seconds), launches Python `-I -B`,
and fails closed on crashes, wrong return code, oversized/malformed output.
Android/Termux is explicitly refused in parent and worker.

**Not a secure sandbox:** No network namespace, read-only mount, seccomp,
container, syscall filter, fork prohibition or full file-system isolation.
Process virtual memory is not RSS. Decoder imports, memory mapping or valid
large RAWs can fail under the chosen limit. Run only on a trusted offline
Linux research machine and individually rights-cleared images.

## Explicit usage (not run in CI)

An independent host may optionally install compatible `rawpy`/LibRaw.
Source must be a deliberately staged `payload_N000.dng` file with a
**predeclared** lowercase SHA-256 from source curation, not an invented
license/shutter-provenance claim. Example:

```bash
python -m tools.photo_lineage.run_isolated_raw_probe \
  --source /isolated/curated/payload_N000.dng \
  --sha256 <predeclared-64-lowercase-hex-digest> \
  --memory-mib 2048 --cpu-seconds 30 --wall-seconds 45
```

The worker opens a non-symlinked, non-hardlinked, <=128MiB numbered file via
a pinned read-only FD, checks its SHA-256 both before and after decoding, and
feeds a duplicate *file-like FD* rather than reopening a pathname to
`rawpy.imread()` (file objects are documented as supported:
https://letmaik.github.io/rawpy/api/). It reuses the earlier bounded
visible Bayer calibration and luminance converter. The only success
report contains aggregate in-memory grayscale dimensions and checks,
**not** source pixels, digests, filenames, EXIF, identities or paths.
The path **does appear in local process arguments**; no claim of preventing
all metadata leaks or malicious native-package behavior is made.

## Validation and next evidence gates

Six synthetic regression tests cover a mocked file-like RAW decoder, byte
preservation, bad pins before decoder access, symlink/hardlink mutation
refusal, Linux resource-setting, Android rejection, an actual isolated child
refusing fake RAW bytes, parent arguments and wall timeout. These do not
establish **successful decoding of real DNGs** or protection against
malicious C/native libraries. No actual LibRaw package installation,
real-camera matcher accuracy or S24+ runtime measurements are claimed.

Next: small rights-cleared source-revision/hash-pinned independent shutter
cohorts, proper offline OS/container sandboxing, actual RAW format fidelity,
source/scene-separated heldout match assessment, then opt-in synthetic
on-device benchmarks. Photo-family candidates remain review-only.

## Parent bounded stdout defense

The launcher now reads **at most 4097 bytes** using Linux pipe readiness and
one overall monotonic wall deadline. It kills/reaps a child before accepting
an oversized report; unlike `subprocess.run(... stdout=PIPE)`, it never
buffers an arbitrary amount of child stdout in the parent. Synthetic tests
exercise a 100KB noisy child, a silent sleeping child and a tiny success.
This is still not a network/filesystem/native-code sandbox.

## Mandatory bounded DNG marker admission before native code

The opt-in worker now requires the existing `dng_header_probe.validate_dng_prefix`
on the **same pinned source file descriptor**, after source SHA-256
verification and **before importing rawpy/LibRaw**. At most 16 KiB of
header bytes are read, with fresh descriptor metadata checks before and
after. The standard-library parser accepts only bounded classic TIFF IFD0
with exactly one typed DNGVersion tag. An arbitrary file merely named
`payload_N000.dng` or a corrupted IFD0 now fails **before** invoking
the native decoder. The parent additionally requires the worker receipt
to state `basic_dng_header_passed=true` and
`full_dng_format_validated=false`; failures never claim successful decode.

This conservatively refuses unusual otherwise-valid DNG layouts,
including BigTIFF and IFD0 beyond the existing 8 KiB limit. **Passing
does not establish safety of the compressed RAW payload**, authenticity,
independent shutter exposures, valid colorimetry or licensing. It is one
additional fail-closed input filter, not a substitute for a sandbox or
curated real-camera testing. Synthetic tests use manufactured TIFF bytes,
a deliberately corrupted tag and a fake decoder that must never run when
admission fails.
