## Revision 4 change map · SORT-1D2

This is a documentation-only copy correction to the existing 24 synthetic proposals. Previously accepted geometry, the 52dp selectable rows, key-conditioned controls, light/dark states, IME sequence, scrolled 200%-text reachability and Funnel Display title declarations remain unchanged.

| Independent finding | Correction target | Mechanical proof |
|---|---|---|
| #4 · Relevance interaction copy | Remove the extra explanatory row from Search and Find similar Relevance proposals; retain Relevance as the default and keep direction controls absent for that key | Generator contains no rendered explanatory copy; Python audit rejects its reintroduction |
| #8 · Metadata wording | Replace compressed middle-dot metadata with plain-language counts in every affected scene and generated SVG | Generator and generated-surface audit reject middle-dot metadata |

The generated frames remain synthetic coordinate proposals. This revision does not establish Samsung placement, device screenshots, app implementation, emulator behavior or rendered-device proof.

**Traceability:** finding #4 → selected date/name/size, Random and Relevance key-conditioned frames; #6 → empty/fallback, keyboard sequence and both scrolled 200% end states; #8 → title attributes and fallback limitations on every SVG. [Generator](evidence/sort1-rev3/generate.mjs) and [Python audit](evidence/sort1-rev3/audit.py) check structural geometry and coverage, **not** device UX. The evidence PR needs exact-head CI and separate Review. Then #177 needs an **independent issue-level Design PASS against merged evidence** before any production implementation can start.
