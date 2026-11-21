# SORT-1D revision 3 · inspectable synthetic evidence

**24 SVG proposals, light/dark, synthetic only.** They are not current app/device/Samsung screenshots, contain no personal media or font files and do not grant preflight approval. Use the [preflight matrix](../../SORT1_PREFLIGHT.md) to open each pictured state.

Deterministic regenerate and structural audit (standard libraries only):

```sh
node docs/design/evidence/sort1-rev3/generate.mjs
node docs/design/evidence/sort1-rev3/generate.mjs --check
python3 docs/design/evidence/sort1-rev3/audit.py
```

The audit checks XML and inventory, 1080×2340/360×780 geometry, title font declarations, five/six key counts, 52dp full-row targets, vertical direction, Random shuffle, selected states, safe insets, terminal 200% scroll and pictured missing/IME states. This is **not** an Android, S24+, TalkBack or exact-font-metrics validation.
