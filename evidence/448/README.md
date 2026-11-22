# #448 rendered proof (PR #554)

Exact head: `772585ec24f56a2db95b1d6877eae635ca43e0bf`
Source: aggregate `emulator-ui` job https://github.com/LPStruiksma/atlas-gallery/actions/runs/45061698677/job/137350914056
(decoded byte-for-byte from its `UI evidence base64 unknown-ime-448-*` log groups; no edits).

Test: `UnknownFacesKeyboardInsetsTest`, real MainActivity, 1080 × 2340 (S24+ size), fixed 918 px test keyboard, six synthetic faces.

| State | Light | Dark |
|---|---|---|
| (a) keyboard closed | unknown-ime-448-a-closed-light.png | unknown-ime-448-a-closed-dark.png |
| (b) keyboard opening | unknown-ime-448-b-opening-light.png | unknown-ime-448-b-opening-dark.png |
| (c) open, empty field | unknown-ime-448-c-open-empty-light.png | unknown-ime-448-c-open-empty-dark.png |
| (d) open, typed name | unknown-ime-448-d-open-typed-light.png | unknown-ime-448-d-open-typed-dark.png |
| (e) landscape, open | unknown-ime-448-e-landscape-light.png | unknown-ime-448-e-landscape-dark.png |
| (f) font 1.3, open | unknown-ime-448-f-font130-light.png | unknown-ime-448-f-font130-dark.png |

Contact sheets (downscaled, labelled): `sheet-portrait.png` (a, b, c, d, f), `sheet-landscape.png` (e).

Evidence-only branch: not for merge. Delete after #554 is decided.
