# Launcher icon preview

Renders the committed adaptive-icon vectors (`app/src/main/res/drawable/ic_launcher_*.xml`)
under squircle, circle and rounded-square masks, plus the Android 13+ themed (monochrome) icon,
on dark and light wallpapers. Output: `docs/design/app-icon-preview.png`.

```bash
cd tools/icon_preview
python3 xml2svg.py              # writes repo-bg.svg, repo-color-fg.svg, repo-mono-fg.svg here
node preview.mjs repo           # writes preview.png (needs Playwright + Chromium)
```
