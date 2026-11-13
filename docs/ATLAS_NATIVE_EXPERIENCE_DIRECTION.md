# ATLAS — Samsung Gallery-inspired native experience (first S24+ preview feedback)

**Owner feedback, 22 October 2026:** The installed native v0.3 preview works but feels somewhat template/scientific rather than a beautiful, intuitive daily gallery. Most acute: he expected People to find faces automatically and cannot find a scanning command; and he wants Samsung Gallery's tactile gallery pinch progression, with increasingly dense columns when zooming out and authentic photo aspect ratios when zooming in.

**Full research reference:** Claude's original Samsung Gallery 15.9.00.45 parity study (private research archive) (862 lines, with six appendices; originally 21 October). This brief deliberately prioritizes the user's *actual phone experience* over the study's older priority tiers. Never copy Samsung implementation; independently author ATLAS Kotlin/Compose. Findings were static and are NOT phone runtime benchmarks.

## Verified current Android behaviour

- `MainActivity.kt::GalleryGrid`: `LazyVerticalGrid(columns = GridCells.Adaptive(108.dp))`, each tile square `aspectRatio(1f)`, cropped `MediaPreview(..., ContentScale.Crop, 400)`. **There is no gallery pinch-density control or original-ratio grid view.**
- `MainActivity.kt::PeopleScreen`: "Import once" and "Link live V4" open a SAF CSV picker. It shows already confirmed, exported V4 persons only. `PeopleRepository.kt` imports and copies confirmed assignments privately; **no face detector is invoked.**
- `MainActivity.kt::ActivityScreen`: "Refresh photos" refreshes the MediaStore **photo/video catalogue**, **not face detection**. Footer expressly says native face inference and semantic indexing are not integrated.
- Existing **V4** face indexing/review lives separately under `tools/v4/` (Termux) and may produce an exported CSV; the live V4 database and original photos are untouchable without independent informed authorization.
- Full Samsung Gallery uses its privileged CMH/SCS provider for faces and its own private services for some performance. Third-party ATLAS cannot simply call them. Reference: report `PPL`, `DATA`, `PERF:S10`.

## First milestone — tactile, photo-first gallery [Lane A]

**Acceptance, ordered by impact:**
1. Make gallery density user-adjustable by a normal **two-finger pinch** and by an accessible explicit `View as` menu. Prototype device-appropriate **3 / 4 / 7** columns, with 4 the familiar initial baseline; optionally extend to **12 / 28** month/year overview after day/date grouping exists. Keep chosen level when returning from viewer / app relaunch.
2. Zoom **in** beyond the 3-column grid to a **large, uncropped original-aspect / justified-row** mode. Do not stretch images or crop portrait heads. Width/height/orientation come from MediaStore; protect against missing/zero/rotated dimensions. The justified layout should only calculate visible rows or bounded layout metadata to stay sensible at 150k items.
3. Hold the **photo under the pinch focal point** approximately in place across a level change. The view must not snap to the top or jump to unrelated dates; a slower first iteration with anchor-correct release is preferable to a beautiful but incorrect per-frame animation. Smooth interpolation can be a separate pass.
4. Avoid creating hundreds of new high-resolution thumbnail decodes *during* gestures. Reuse memory hits/low-res placeholders and upgrade sharpness after the gesture settles. Stable `LazyGridState` / stable item identity and cancellation; preserve sorting, album/person filters, Back and selected viewer context.
5. Make photo grid **image-first**: clean spacing, strong typographic/date hierarchy, reduce technical/status copy in main navigation, modern refined animation and deliberate empty states. Benchmark with a synthetic 150k-item list and on-device observation; don't promise Samsung proprietary hardware boosting.

**Study anchors:** `GRID:G1–G8,T1,T5,K1`, `PERF:S1–S7,T1–T9`, `VIEW:V23`. Samsung phone-portrait timeline reportedly has levels `1 real ratio · 3 · 4 · 7 · 12 · 28`; these are **reference choices**, not mandatory pixel-perfect replicas.

**Minimal first PR scope:** 3 / 4 / 7 and anchored pinch + View as. A separate incremental PR can add true justified ratios; if source data is ready, combine thoughtfully. This is deliberately a product feature, not another release-audit cycle. Required unit/synthetic tests may run locally for free; never trigger billable GitHub Actions without owner consent.

## Second milestone — People that explains itself [Lane A + D, C later]

**Immediate clarity:** When People lacks confirmed identities, explain plainly: "ATLAS currently shows people you've reviewed in V4. Automatic face discovery isn't built into this app yet." A simple "Connect V4 people" affordance can guide the actual existing CSV import/live link, and "Refresh photos" must never be described as a face scan. If identities exist, show elegant face portrait covers, counts and an obvious route to review or learn where new identities come from. Keep technical "CSV", "SAF" and database language in a secondary setup/details sheet where possible; don't hide important replacement/consent warnings.

**Actual future delight:** A native **Discover faces** flow, not a fake button. Choose/test an offline face detector and identity representation that ATLAS may lawfully bundle; validate memory, speed, battery and privacy before making the feature automatic. A first-run explainable opt-in can schedule **bounded, cancellable, resumable** processing while charging/idle and update a progress display (not silently process ~150,000 items at once). Use persisted work/checkpoints, graceful permission changes, exclusions, and an on-device index. Detection is not identity recognition. Suggestions must support "same / different / not sure", an easy Undo, and human-confirmed merges only. Do not read or overwrite Termux V4's live People DB; interoperate through a versioned verified export/bridge if desired.

**Study anchors:** `PPL:1–6,10,15,17,19,20,24,26`; `DATA:DI12,DI15,DI26–DI27`. Samsung's private CMH/SCS classifiers cannot simply be ported. Lane D owns `tools/v4/` work and V4 sidecar/portrait metadata; Lane C owns `tools/identity_fusion/` experimentation; Lane A owns native UX + integration.

## Third milestone — beauty and usability throughout

- Photographic People covers (portrait crops using confirmed face boxes), generous edge-to-edge images, elegantly compact navigation, thoughtful type scale and meaningful empty states (`PPL:1–4`, `GRID:A6`).
- Day/month/year headings and accurate fast-scroll date bubble (`GRID:T1–T2`, `PERF:S4–S6`); do not regress Largest / Random sorting which ATLAS already offers.
- Cold-scroll improvements by testing Android thumbnail APIs vs bounded local thumbnail caching; avoid eager full-original decodes (`PERF:T1–T9`, `DATA:DI9,DI12–DI13`).
- Viewer tiled zoom, photo-to-viewer continuity and elegantly coupled filmstrip (`VIEW:V1–V3,V10,V14,V23`).
- Declutter with clearly reversible protection and "Recently trashed by ATLAS" visibility, no automatic original-file deletion (`DCL:8,17–18`).

## Safety and implementation boundaries

- **Current v0.3 preview is deliberately disposable and installed by the owner following explicit approval.** The original research report's old "do not uninstall v0.2" requirement is stale. Do not use it as a new release gate or as retroactive permission for further installs/uninstalls.
- Never upload private photos/biometrics, touch the Termux V4 database, create permanent signing credentials, or dispatch paid CI without separately appropriate authorization.
- This is a **product experience spec**, not a prerequisite for G3 signing. No feature should be falsely marked implemented because there is a research method or Python prototype.
- Keep low-cost tests proportional to actual risk; don’t restart merged #396/#397 audits or completed fictional-signing canaries.

## Suggested parallel ownership

- **A immediately:** anchored native 3/4/7 pinch/View as, image-first polish, truthful People setup empty state. Publish an installable preview only on an explicitly authorized no-cost path; do not change current installed app without a new device-specific request.
- **D:** validate face-box/portrait export interoperability and build gorgeous People review UI without blocking Lane A's pinch work.
- **C:** prototype and benchmark practical offline native-compatible face/identity detection and fusion, respecting unknown-person safety.
- **B:** focus Photo DNA's explainable duplicate-family discovery and later beautiful user review/deletion previews; stay in owned tools path.
- **E:** parked unless genuine signing issues recur.

**Definition of product success:** Lukas can open Photos, pinch to explore his library naturally, see beautiful uncropped portraits, understand where People come from, and feel the app is a *gallery* rather than an analytics research dashboard.
