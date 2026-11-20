# Changelog fragments

Every merged change that someone using Atlas could notice adds **one small file here**. The release workflow turns these files into the version number, the GitHub release notes (which Obtainium shows before an update) and the in-app changelog. Nobody edits a central changelog and nobody picks version numbers by hand.

## Format

File name: `<PR or issue number>-<short-slug>.md`, lower case, for example `598-albums-sort-sheet.md`. A leading number marks that PR as described, so it is not listed again under "Other merged changes".

~~~markdown
type: feature
- Settings shows what changed in each version.
- Second entry if one change really did two visible things.
~~~

- First line: `type:` followed by one of
  - `feature`: something new people can do. Raises the middle number (0.4.0 → 0.5.0).
  - `improvement`: an existing thing works or looks better. Raises the last number (0.4.0 → 0.4.1).
  - `fix`: something broken now works. Raises the last number.
  - `internal`: not visible in the app (tests, tooling, docs). Raises the last number; shown on GitHub under "Under the hood", never in the app.
  - `major`: reserved for Lukas's call to go to 1.0 and later big breaks. Raises the first number.
- Then 1 to 8 entries, each starting with `- `, in plain sentence-case language for Lukas, not code names or unit ids. Longer entries may continue on lines indented by two spaces.
- `python3 scripts/atlas_release.py check` validates every fragment; CI runs it on each PR.

## How versions are picked

On each push to `main` that changes the app, `scripts/atlas_release.py plan` looks at the newest `vX.Y.Z` tag and the fragments added since, and bumps by the strongest type: `major` > `feature` > everything else. A merge without a fragment still releases as a patch, and its PR title appears under "Other merged changes" on GitHub. The first semantic release is **0.4.0**, which sorts above every old `0.3.<build>` release.

The Android `versionCode` stays a build number that only goes up (`yyMMddHH × 10 + minute ÷ 6` of the merge commit, at least one above the previous release). It is shown as "build" next to the version.
