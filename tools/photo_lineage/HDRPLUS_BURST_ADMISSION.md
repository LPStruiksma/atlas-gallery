# HDR+ real-burst admission — rights and resource-aware metadata preflight

Lane B / issue #83. This is **not** a DNG decoder, a photo-matching evaluation, or a proof of different exposures. It only inspects directory entries and file metadata inside **one explicitly supplied burst directory**. No network calls, cloud credentials, downloads, caching, pixel reads, exports, uploads, edits, or deletion.

## Why this dataset?

Google's [HDR+ burst dataset](https://hdrplusdata.org/dataset.html) documents **3,640 bursts and 28,461 raw input frames**, 2–10 frames per burst, acquired through Android Camera2 on Nexus/Pixel hardware. Consecutive raw frames are distinct recorded inputs at short exposure intervals, unlike Middlebury's different-camera stereo pair. Its published license is **CC BY-SA**, with requested citation of Hasinoff et al. (ACM TOG, 2016). Some scenes include people (the authors' friends and family): **avoid identifying subjects; use visually non-personal samples when possible**. Files may contain ancillary metadata even though the publishers say GPS was removed. Research use does not imply unrestricted onward release.

The official curated 153-burst sample is **37 GiB** (entire corpus **765 GiB**). Do not bulk-download it for this experiment; choose a small licensed sample **independently**, with a documented source page, photographer, camera/session, burst folder, source hash and privacy review. The dataset page names source frames `payload_N<frame>.dng`, but filenames alone are not cryptographic evidence of a camera exposure. Older bursts may have a longer-exposure last frame; manually exclude such exceptions before image-level evaluation.

## Purely offline optional preflight

```bash
python -m tools.photo_lineage.hdrplus_burst_admission --directory /path/to/one/curated/burst
```

The scanner is nonrecursive and allows at most 128 directory entries, 2–10 contiguous zero-based `payload_N<frame>.dng` filenames, 128 MiB per frame and 1 GiB cumulative size. It refuses any symlink in the root chain or burst directory, nonregular/empty raw entries, hardlinked frame aliases, unexpected `.dng` names and duplicate/missing frame indices. Other non-symlink sidecars are ignored; the scanner does not interpret them. It outputs only **counts, summed file size and conservative Boolean disclaimers**: no IDs, filenames, paths, raw bytes or GPS.

Even a passing preflight attests only structural plausibility. Byte hashes, DNG parser integrity, permissions, curated camera/shutter/scene truth, privacy/consent and scene-separated train/holdout are **NOT** checked. The existing Photo DNA matcher accepts 8-bit grayscale arrays, not DNG. A future separately reviewed RAW decoding module would have to bound image memory, check hash pins before/after read, document its demosaic/normalization policy, and manually label source exposure versus separate shutter; no decoding or pixel benchmark is claimed here.

## Synthetic-only tests

`python -m unittest tools.photo_lineage.tests.test_hdrplus_burst_admission -v` runs fabricated byte fixtures: accepted two-frame layout, rejected missing/duplicated indices, hardlinks, symlinks, unknown DNGs, overflow/zero-byte files, entry quota and preservation of original bytes. Neither CI nor this module installs `rawpy` or fetches any real DNG, and the output is never a permission for auto-grouping or source deletion.


## Optional SHA-256 exact-byte alias guard

The default command above remains **metadata-only**, without reading media
bytes. An explicit stronger local preflight is available:

```bash
python -m tools.photo_lineage.hdrplus_burst_admission \
  --directory /path/to/one/curated/burst --check-byte-distinctness
```

This option reads **only the 2–10 admitted DNG frame files** in bounded 1 MiB
chunks, up to the existing 128 MiB/frame and 1 GiB/burst quota. It compares
SHA-256 digests **in memory** and refuses frame slots containing *identical
file bytes*, including copies stored as distinct inodes. It opens files
descriptor-relative to a pinned non-symlinked directory with O_NOFOLLOW,
refuses extra external hard links in byte-check mode, and verifies each file's
device, inode, size, nanosecond mtime and ctime before and after hashing.
Concurrent directory mutation is also refused. There are no network requests,
pixel decodes, original writes or persistent digests. No paths or hashes enter
the aggregated JSON report. CLI exits with an error on failed admission.

**Crucial:** different SHA-256 bytes are not evidence of separate physical
shutters. Different metadata, encoding, dark frames, crops or renderings may
produce different bytes of the very **same** exposure. Source-publisher hash
pins have not been checked; structural TIFF/DNG authenticity and license
remain unverified. The output keeps `source_bytes_verified=false`,
`physical_shutter_independence_verified=false` and
`scene_heldout_eligibility_verified=false` in both modes.
A future truly rights-vetted independent-shutter evaluation still needs
original-publisher provenance, correct decoded-pixel grouping and
scene/burst-separated study splits. The current optional check is deliberately
an exact-byte alias filter, not a photo matcher or approval to group/delete.


## Optional predeclared source-byte pins (versioned; still NOT publisher-certified)

A locally authored, independently preregistered JSON manifest can further detect
*wrong source versions or frame swaps* at the moment of admission. This is
distinct from merely ensuring different DNG files contain different bytes:

```json
{
  "version": 1,
  "frame_sha256": {
    "0": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "1": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  }
}
```

**The example digests are placeholders and will not validate real files.**
Prepare actual SHA-256 values from an **independently recorded and legally
curated source**, before running a benchmark. Do not generate new hashes from
the same file you are attempting to authenticate and mistake this for proof
of official camera origin.

```bash
python -m tools.photo_lineage.hdrplus_burst_admission \
  --directory /path/to/one/curated/burst \
  --expected-hashes /path/to/preregistered/hashes.json
```

The CLI automatically enables the **opt-in** bounded byte hashing when a
manifest is supplied. The reader accepts <=4096 bytes, no symlinks/hardlinks,
and an *exact* schema with unique lowercase 64-hex hashes at contiguous
zero-based index strings, 2..10 frames. It rejects duplicate JSON keys and
any missing/extra indices, then compares SHA-256 through already guarded
descriptor-relative frame reads. Neither hashes nor private paths are
serialized. No manifest or photo is written. Tests use fictional bytes only.

A pass means only **the staged files match that particular user-supplied
precommitted pin list**, not that the pin list came from Google or that frames
represent different physical shutter events. The output explicitly keeps
`original_publisher_hashes_authenticated=false`,
`source_bytes_verified=false`, and
`physical_shutter_independence_verified=false`.
Curator-verified origins/licensing/scene assignment and subsequent DNG/pixel
validation remain mandatory before any statistical study. There is still no
automatic photo merge or deletion.

## Optional bounded TIFF/DNG header admission

The DNG extension is not evidence that a file contains DNG. Run the optional command:

    python -m tools.photo_lineage.hdrplus_burst_admission --directory /curated/burst --check-dng-headers

This read-only preflight inspects at most 16 KiB per staged input via a pinned
directory-relative O_NOFOLLOW descriptor. It checks classic TIFF endianness,
magic 42, an IFD0 offset no larger than 8 KiB, at most 256 IFD0 entries, and
exactly one correctly typed four-byte DNGVersion tag (50706). It does not
follow EXIF, GPS, SubIFD or tile offsets; it never decodes pixels or writes
media. It may reject unusual otherwise-valid DNGs with larger IFD0 offsets.

Crucially this is only container plausibility, NOT comprehensive DNG validation,
independent exposure evidence, identity, publisher origin, license or consent.
The output keeps image_format_validated=false and
physical_shutter_independence_verified=false. Without the opt-in flag, the
default remains metadata-only. All tests use fabricated TIFF-style bytes.