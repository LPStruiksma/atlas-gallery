# Java-8 synthetic Pillow bilinear reference — runnable no-media canary

**Research-only, 21 October 2026.** This is a synthetic four-pattern
reference algorithm for Intel 0287/0288 Market1501 *published evaluation*
preprocessing. It is **not** a general-purpose gallery image resizer, an
Android integration, a model, a trained ReID recognizer, or an approval
to use third-party images or pretrained weights.

## The previously missing integration step

The existing `IntelSyntheticCanary.java` could check hashes of a caller's
RGB resize callback, while Python's
`synthetic_pillow_bilinear_reference.py` could calculate
Pillow-12.3-style output on the four fixed fictional inputs. The previous
positive JVM test handed **already resized** Python bytes into Java; it
did not prove Java could itself perform the resize.

`IntelSyntheticPillowBilinear.java` now implements the same
`IntelSyntheticCanary.RgbResize` interface. It takes the FOUR exact
synthetic gradient/checker/colorbar/impulse inputs only, verifies each
against a frozen SHA256, independently calculates the horizontal then
vertical interpolation, and returns newly allocated RGB bytes. The
original Java canary then checks **all four** Pillow RGB SHA256 hashes
AND all four little-endian raw BGR/NCHW float32 tensor SHA256 hashes.

Thus the entire **generate → interpolate → channel-transform → checksum**
positive path runs within a Java 8 VM, without any generated image files
or a Python byte handoff.

## Implementation precision (only for fixed synthetic geometries)

The source matches the previously approved limited Python stdlib
reference's fixed-point filter construction: 22-bit coefficients;
source/destination pixel-center mapping; triangle support widened
for downsampling; coefficient normalization and rounding; horizontal
then vertical passes with rounding/clamping after each. This ordering
matters: a naive direct 2-D interpolation or OpenCV `INTER_LINEAR`
may diverge at many pixels even though both methods are labelled
“bilinear.”

The implementation deliberately **refuses** all other dimensions or
source buffer hashes. It does not silently become a general photo
processing pipeline, cannot parse JPEGs/EXIF or read files and does
not accept Android bitmap/People objects. The identity-size colorbar
path returns a **defensive copy** rather than exposing the mutable
original buffer.

## Reproducible host-only test

```sh
cd tools/identity_fusion
javac -Xlint:-options --release 8 \
  IntelSyntheticCanary.java IntelSyntheticPillowBilinear.java
java IntelSyntheticPillowBilinear --self-test
python -m unittest test_java_pillow_bilinear -v
```

The Java self-test must report `all_four_exact=true`,
`passing_patterns=4`, `checked_patterns=4`, with
`real_model_or_android_runtime_verified=false`,
`private_media_used=false`, `production_approved=false`
and `auto_confirm_allowed=false`. No model file, gallery data or
network is involved. The Python regression compiles both Java classes
into a disposable directory on CI, runs the new Java-only 4/4
end-to-end canary, checks an independent separately pinned Python
stdlib reference, and injects **one-bit** mutations and invalid
geometry to ensure refusal. It also verifies that the resizer doesn't
alias its caller's input buffer. Only standard Python, Java 8, and
GitHub's existing dedicated V5 runner are needed.

**The four passes are NOT a representative pixel correctness proof for
arbitrary photos, all geometries, ICC/EXIF/alpha, Android Bitmap decode,
device operating systems or actual OpenVINO/ONNX graph input.** If this
algorithm is ever generalized for real users, it requires a separate
copyright/rights review, expanded randomized/high-bit-depth/edge-case
goldens and S24+ on-device decoder/crop/resize/graph-output
benchmarks. It must not be silently wired to native code by Lane C.

## Remaining empirical blockers

- Specific authenticated and licensed 0287 ONNX/0288 IR or a legally
  distributable alternative, including 0288's XML+BIN backend path.
- Approved, independently reviewed rear/partial/occluded **Unknown**
  people, unchanged calibration and holdout under source-connected
  lineage, people, event, pose and wardrobe boundaries.
- Actual Galaxy S24+ pixel decode/resize/tensor/operator parity,
  battery/thermal, p95 latency, RAM and cancellation behavior.
- Human review and explicit Unknown abstention for every proposed
  identity, without automatic relabeling or database mutations.

No release/automatic Person labeling becomes available because of this
pure synthetic Java math check.
