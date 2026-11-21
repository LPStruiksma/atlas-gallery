# Lapis preflight sheet generator

Renders the five synthetic BRAND-L4–L8 design-preflight sheets in [`docs/design/lapis/preflight/`](../../docs/design/lapis/preflight/README.md) for [#753](https://github.com/LPStruiksma/atlas-gallery/issues/753). Python 3 and Pillow only; no network, private media or location data.

| File | Role |
|---|---|
| `lapis_kit.py` | Device geometry, Quiet Frame v3 tokens, Funnel font loading, placeholders, justified layout, `Screen` and its annotation layer |
| `lapis_screens.py` | Reusable screens: Pictures grid, day rows, fast-scroll sphere, slab, Collections rows, viewer, search panel, atlas overlay |
| `lapis_board.py` | Composes frames, captions and tables into one sheet |
| `lapis_preflight.py` | The five sheets, their reference rows, `--check` and the manifest |
| `tests/` | Layout invariants and a fallback-font render of every sheet |

```bash
python3 tools/design/lapis_preflight.py --font-dir DIR [--only l4 ...] [--out DIR]
python3 tools/design/lapis_preflight.py --check
python3 tools/design/lapis_preflight.py --allow-fallback-font --out /tmp/x   # layout smoke run, stamped NOT FOR REVIEW
```

Fonts are looked up in `--font-dir`, then `$ATLAS_FUNNEL_DIR`, then `app/src/main/res/font/` (where BRAND-L2 bundles them). Without them the script stops instead of silently substituting. Every full device frame is asserted to be 1080 × 2340 before it is placed. The PNGs are reduced to a 256-colour palette, like the FIND-5 frames, to keep the repository small.

Rules for edits: use only Quiet Frame v3 tokens; keep the annotation colour outside the palette; when a target is genuinely under 48 dp record it with `actual=True` so the sheet flags it; cite only sources you opened, with the date.
