# BRAND-VE1 V01–V05 native-size render report

Rendered head: `5aefa748f02ee9218724f4b9275b8319d69cce62` (PR #815, unit #806).
Method: Chromium (Playwright), each SVG opened directly at its native 1080×2340 viewport, device scale 1. Contact sheets `sheet-v0N.png` place light (left) and dark (right) side by side. This is a **source-drawing render, not a runtime screenshot**: fonts are the renderer's fallback and device 200% font, IME, gesture and TalkBack behaviour are not verified here.

All ten SVGs rendered without parse errors, nothing clipped at the canvas edge, all required states visible.

| Frame | Result | Notes |
|---|---|---|
| V01 viewer | PASS | Filmstrip current tile, ordered badges 1–3, action bar, annotations legible in both themes. Both "light" and "dark" use a black viewer surface; only artwork/chrome tokens differ. Intended for the viewer, but the light frame therefore shows no light surface. |
| V02 details | PASS with nit | Sheet, rows and the 200% alternative card legible. Nit: the Close button's lower edge sits on/just past the sheet's bottom edge in light (sheet corner radius meets the button), inset it. |
| V03 People | PASS with nits | List, empty, scan progress (`7.140 of 12.305`) and the new interruption card with Retry scan/search and Back to People all visible and reachable. Nits: "186 photos · 16 Nov" and the sibling meta lines run to the card's right edge; Retry/Back buttons nearly touch the interruption card's bottom edge and the sort caption sits directly below it. |
| V04 Person | PASS | Cover picker with held sphere, rename field and Save above the keyboard block; light and dark both legible. Keyboard is drawn as letters only (approximation). |
| V05 Review | PASS with nit | Same-person pair, unknown-face assign/search state (face in context beside the search field, Assign Avery, Keep unknown) and split confirmation all visible. Nit: in light, the unknown-face illustration's white torso disappears into the white panel, so only the head shows. |

Review item 1 (rendered inspection): done at the exact head, nits above are non-blocking. Review items 2 and 3 are visible in V03 and V05. Contrast was judged visually, not computed.
