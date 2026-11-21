# SHARE-1P · synthetic share/details design target

**Status: task scaffold only — no frames, PNG exports, geometry verifier, design-preflight verdict or runtime evidence yet.** This initial file anchors the Codex task PR. The Codex Dev patch must replace it with a versioned, evidence-backed design target; it must not be cited as a completed design.

- Unit: [#843](https://github.com/LPStruiksma/atlas-gallery/issues/843)
- Parent: [SHARE-1 #584](https://github.com/LPStruiksma/atlas-gallery/issues/584); goal [#97](https://github.com/LPStruiksma/atlas-gallery/issues/97)
- Governing identity: [Quiet Frame v3](../QUIET_FRAME.md), [OWNER_INTENT](../../OWNER_INTENT.md); Samsung parity spec governs behavior, **not** OEM placement.
- Scope: this directory only; docs-only, no production UI, manifests, dependencies, original-media changes, real media or coordinates.

## Four required journeys

| Flow | Entry → handoff → return | Invariants |
| --- | --- | --- |
| A / share without location | Black viewer, existing Share slab action → short options surface, Remove location off by default → Android sharesheet → same viewer item | On: verified GPS-free temporary copy; never fall back to original on preparation failure. Off: original provider URI. Read grant and deferred consumer read lifetime must be tested; no bogus success with no GPS. |
| B / set as wallpaper | Viewer overflow → ACTION_ATTACH_DATA system target → viewer | No custom cropper; missing handler, unsupported media, revoked URI and cancellation exit safely. |
| C / copy image | Viewer overflow → URI-bearing ClipData → system feedback → viewer | Receiver grants/lifetime must be tested, not presumed; invalid URI must not land on clipboard; no persistent banner. |
| D / date and place | Swipe up / Details → read-only photo context → Edit date and location → compact edit controls → system write consent → details with Undo → same viewer | Explicit timezone; original byte-for-byte/index unchanged on cancellation, denial, unsupported file, storage full, or failed write; consent and rollback require independent critique before production implementation. |

## Visual and verification contract (not yet satisfied)

Generate only deterministic synthetic media: 1080×2340 pixel portrait frames for F0–F3/D0–D2; one physically rotated landscape proposal for each flow; light/dark secondary surfaces and bright/dark photo, keyboard/date picker open, error and cancellation panels, 200% text. Each frame needs a recognizable photo and real controls (not tables pretending to be screenshots), and actual-pixel geometry, synthetic density, safeDrawing/top/bottom insets from the template (not claimed measured), 48 dp hit targets, photo/focus/Back rectangles. Include `#8C9BFF` held-sphere indicator and a white-ring filmstrip; mark any bright-thumbnail contrast tweak a **design adaptation**, not a delivered #142 fix.

Target artifacts: Python source + verifier, generated SVG, real PNG exports, decision/source log, state matrix distinguishing rendered/verified from planned/untested, per-frame coordinate tables, exact commands and results. The bot's single-commit text-only patch must not smuggle binary PNG through the guarded patch path; Dev must separately commit required generated binary exports safely before declaring this issue complete.

Unknowns to log explicitly: Samsung finding IDs/screenshots/geometry for these actions, actual OEM share/chooser behavior, Android URI grant lifetime, temporary copy retention and revocation, clipboard paste/grants, system consent + atomic rollback. No design PASS, on-device behavior or verified accessibility claim from synthetic geometry alone.

**No preflight release** to #584 until independent Review can inspect completed versioned visual evidence and score rubric #1–#6 and #8.
