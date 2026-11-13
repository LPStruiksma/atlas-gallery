# EXIF orientation and crop-coordinate contract — Lane C

**Research-only Java 8 reference, not a native gallery integration.** The
class accepts a bounded, already-decoded in-memory ARGB array; all tests
use **invented pixel-coordinate colors exclusively**. It neither loads,
opens, saves nor downloads a file, and never touches a model, actual
phone, camera, private People database or image library. The API itself
cannot certify an input's ownership/permission; future callers must do so.

## Why this matters for identity fusion

An otherwise correctly calibrated back/partial-person recognition model
can be given the **wrong person or no person** if a detected crop is taken
in the raw rather than the EXIF-oriented pixel coordinate system, if
mirroring is collapsed to plain rotation, or if orientation is applied
**twice** after the decoder already corrected it. All three errors can
yield visually plausible 128×256 tensors, so a resizing-only golden-vector
test does not catch them.

`ExifOrientationCropCanary.java` defines a strict pre-resize contract:

- Input: row-major **unoriented**, already-decoded, fully opaque ARGB
  integer pixels (`rawWidth × rawHeight`) and explicit EXIF orientation
  **1–8**. The helper does **not** read or interpret EXIF metadata.
- Coordinates: requested crop `[left,right) × [top,bottom)` measured
  in **oriented pixel edges** (integers), with no silent clipping,
  rounding or aspect-fit transform. Zero-area/negative/out-of-bounds
  rectangles are rejected.
- Geometry: modes 1–4 preserve width/height; modes 5–8 swap them.
  The algorithm samples each oriented destination pixel from the
  corresponding raw pixel without creating a full rotated intermediate.
- Output: newly allocated RGB uint8 interleaved crop buffer. Input is
  never mutated. Nonopaque ARGB *inside the selected crop* is rejected
  rather than applying an unspecified premultiplication or compositing
  policy. Input dimension cap: **512 × 512**, to keep this research helper
  bounded. It does not resize to a model tensor itself.

### EXIF destination-to-raw mapping

Here `W,H` are raw dimensions, `dx,dy` oriented destination
indices, and `sx,sy` the raw source pixel to read:

| Orientation | Meaning | Raw source pixel `(sx,sy)` |
| --- | --- | --- |
| 1 | Normal | `(dx,dy)` |
| 2 | Mirror horizontally | `(W−1−dx,dy)` |
| 3 | Rotate 180° | `(W−1−dx,H−1−dy)` |
| 4 | Mirror vertically | `(dx,H−1−dy)` |
| 5 | Main-diagonal transpose | `(dy,dx)` |
| 6 | Rotate 90° clockwise | `(dy,H−1−dx)` |
| 7 | Opposite-diagonal transpose | `(W−1−dy,H−1−dx)` |
| 8 | Rotate 90° counterclockwise | `(W−1−dy,dx)` |

For example raw `7×5` pixels with orientation **6** occupy
**5×7** oriented pixels. A `[1,4)×[2,6)` oriented rectangle
produces an RGB crop of **3×4**, not a crop in the uncorrected
`7×5` source coordinate system.

## Independent synthetic regression

The test compiles the actual Java source using `javac --release 8`
in an auto-deleted workspace. A separate **Python forward-map oracle**
maps every raw pixel to its oriented destination, unlike the Java
**inverse** sampling algorithm, before applying the crop. It compares
exact RGB **SHA-256** for:

- **5** differently shaped invented RGB coordinate grids;
- **8** EXIF orientation modes, including all mirrored/diagonal cases;
- **4** crop rectangles per orientation: full, top-left pixel, bottom-right
  pixel, and an interior region.

That is **160 independently computed pixel/crop vectors**; all eight
full-frame modes must remain distinct on an asymmetric source.
Separate Java-only negative tests reject invalid orientation 0/9,
swapped-size coordinate errors, null/malformed arrays, oversized or
overflowing geometries, transparent crop pixels and illegal rectangles.
The original ARGB buffer must remain unchanged. No real pictures are
used or modeled.

```sh
cd tools/identity_fusion
python -m unittest test_exif_orientation_crop_canary -v
# The no-file structural self-test can be run independently:
javac -Xlint:-options --release 8 ExifOrientationCropCanary.java
java ExifOrientationCropCanary --self-test
```

The second pair of commands creates local `.class` files; do not
commit or package them in an APK.

## Explicit non-claims / necessary next integration gates

This checks **geometry**, not camera/decoder metadata correctness.
A future authorized Android experiment must independently document:

1. Whether MediaStore, Android BitmapFactory, Glide or an image decoder
   **already applied EXIF**; applying this orientation a second time
   would be wrong.
2. Whether the model's crop detector returns bounding boxes in original
   pixels, display-corrected pixels, resized previews or normalized
   coordinates; fractional rounding, preview pan/zoom, aspect-fit and
   clipped boxes require separate validation.
3. Actual JPEG/HEIC/WEBP decode, ICC/wide-gamut, alpha/premultiplication,
   screen-to-image mapping, and whether the approved ONNX/IR graph's
   input BGR/RGB and normalization match its reference.
4. Independent legal model/dataset rights, consented rear/partial and
   Unknown lookalike accuracy tests with source-connected group boundaries,
   physical S24+ p95/peak RSS/thermal measurements, and human-only
   identity confirmation.

Even a **160/160** synthetic pass is **not** proof of device accuracy,
production fitness or permission to automatically link or merge people.
This lane does not modify native Android code or release workflows.
