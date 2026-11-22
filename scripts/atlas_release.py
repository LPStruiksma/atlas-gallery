#!/usr/bin/env python3
"""Atlas release numbering and changelog, derived from git alone.

Semantic version (``versionName``, tag ``vX.Y.Z``):
  * Every merged change that should be visible to people adds one small file to
    ``changelog.d/`` (format in ``changelog.d/README.md``) with a ``type``.
  * The next release bumps from the newest ``vX.Y.Z`` tag reachable from HEAD:
    ``major`` -> X+1.0.0, any ``feature`` -> X.Y+1.0, anything else -> X.Y.Z+1.
  * The very first semantic release after the old ``v0.3.<build>`` tags is
    ``0.4.0`` (it sorts above every ``0.3.<build>`` release in Obtainium).

Build number (``versionCode``): UTC ``yyMMddHH * 10 + minute // 6`` of the
commit's committer time, chained so each release is at least one above the
previous one (starting above the newest legacy ``v0.3.<build>`` tag). It is
recomputed from tags and commit times, so no state is stored anywhere and it
only ever goes up.

Release notes for GitHub (which Obtainium shows) and an in-app changelog JSON
are generated from the fragments. Commands:

  atlas_release.py check                 validate every fragment (CI)
  atlas_release.py plan --out-dir DIR    write version.env, notes.md, changelog.json

No network, no secrets; read-only git commands only.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

FRAGMENT_DIR = "changelog.d"
TYPES = ("major", "feature", "improvement", "fix", "internal")
# Heading per type, in the order they appear in notes. "internal" never shows in the app.
HEADINGS = {
    "major": "Big changes",
    "feature": "New",
    "improvement": "Improved",
    "fix": "Fixed",
    "internal": "Under the hood",
}
FIRST_SEMVER = (0, 4, 0)
LEGACY_TAG = re.compile(r"^v0\.3\.(\d{8,10})$")
SEMVER_TAG = re.compile(r"^v(0|[1-9]\d{0,3})\.(0|[1-9]\d{0,3})\.(0|[1-9]\d{0,4})$")
PR_NUMBER = re.compile(r"\(#(\d+)\)\s*$|^Merge (?:PR|pull request) #(\d+)")
MAX_VERSION_CODE = 2_100_000_000
MAX_FRAGMENT_BYTES = 4096
MAX_ENTRIES = 8
MAX_ENTRY_CHARS = 300


class ReleaseError(Exception):
    pass


# ---------------------------------------------------------------- fragments

@dataclass
class Fragment:
    path: str
    type: str
    entries: list[str] = field(default_factory=list)


def parse_fragment(path: str, text: str) -> Fragment:
    """Parse ``type: <type>`` then ``- entry`` lines. Strict so CI catches typos."""
    if len(text.encode("utf-8")) > MAX_FRAGMENT_BYTES:
        raise ReleaseError(f"{path}: larger than {MAX_FRAGMENT_BYTES} bytes")
    lines = [line.rstrip() for line in text.splitlines()]
    body = [line for line in lines if line.strip() and not line.lstrip().startswith("<!--")]
    if not body:
        raise ReleaseError(f"{path}: empty")
    match = re.fullmatch(r"type:\s*([a-z]+)", body[0].strip())
    if not match:
        raise ReleaseError(f"{path}: first line must be 'type: <{'|'.join(TYPES)}>'")
    kind = match.group(1)
    if kind not in TYPES:
        raise ReleaseError(f"{path}: unknown type '{kind}' (use one of {', '.join(TYPES)})")
    entries: list[str] = []
    for line in body[1:]:
        stripped = line.strip()
        if stripped.startswith("- "):
            entries.append(stripped[2:].strip())
        elif entries and line.startswith("  "):
            entries[-1] = f"{entries[-1]} {stripped}"
        else:
            raise ReleaseError(f"{path}: each entry must start with '- ' (got {stripped[:40]!r})")
    if not entries:
        raise ReleaseError(f"{path}: needs at least one '- ' entry")
    if len(entries) > MAX_ENTRIES:
        raise ReleaseError(f"{path}: at most {MAX_ENTRIES} entries")
    for entry in entries:
        if not entry or len(entry) > MAX_ENTRY_CHARS:
            raise ReleaseError(f"{path}: entries must be 1-{MAX_ENTRY_CHARS} characters")
    return Fragment(path=path, type=kind, entries=entries)


def fragment_paths(names: list[str]) -> list[str]:
    return sorted(n for n in names
                  if n.startswith(FRAGMENT_DIR + "/") and n.endswith(".md")
                  and n.count("/") == 1 and not n.endswith("/README.md"))


# ---------------------------------------------------------------- versions

def bump(base: tuple[int, int, int], kinds: set[str]) -> tuple[int, int, int]:
    major, minor, patch = base
    if "major" in kinds:
        return (major + 1, 0, 0)
    if "feature" in kinds:
        return (major, minor + 1, 0)
    return (major, minor, patch + 1)


def code_for(when: dt.datetime) -> int:
    when = when.astimezone(dt.timezone.utc)
    return int(when.strftime("%y%m%d%H")) * 10 + when.minute // 6


def fmt(version: tuple[int, int, int]) -> str:
    return "%d.%d.%d" % version


# ---------------------------------------------------------------- git

class Git:
    def __init__(self, repo: Path):
        self.repo = repo

    def run(self, *args: str, check: bool = True) -> str:
        proc = subprocess.run(["git", "-C", str(self.repo), *args],
                              capture_output=True, text=True)
        if check and proc.returncode != 0:
            raise ReleaseError(f"git {args[0]} failed: {proc.stderr.strip()[:200]}")
        return proc.stdout

    def rev(self, ref: str) -> str:
        return self.run("rev-parse", "--verify", "--quiet", ref + "^{commit}").strip()

    def is_ancestor(self, a: str, b: str) -> bool:
        return subprocess.run(["git", "-C", str(self.repo), "merge-base", "--is-ancestor", a, b],
                              capture_output=True).returncode == 0

    def commit_time(self, ref: str) -> dt.datetime:
        return dt.datetime.fromisoformat(self.run("show", "-s", "--format=%cI", ref).strip())

    def tags(self) -> list[str]:
        return [t for t in self.run("tag", "--list").split() if t]

    def show(self, ref: str, path: str) -> str:
        return self.run("show", f"{ref}:{path}")

    def added(self, base: str | None, head: str) -> list[str]:
        if base is None:
            return fragment_paths(self.run("ls-tree", "-r", "--name-only", head, "--", FRAGMENT_DIR).split("\n"))
        return fragment_paths(self.run("diff", "--name-only", "--diff-filter=A", base, head,
                                       "--", FRAGMENT_DIR).split("\n"))

    def first_parent(self, base: str | None, head: str) -> list[tuple[str, str]]:
        span = head if base is None else f"{base}..{head}"
        out = self.run("log", "--first-parent", "--format=%H%x09%s", span)
        return [tuple(line.split("\t", 1)) for line in out.splitlines() if "\t" in line]

    def adds_fragment(self, sha: str) -> bool:
        parent = self.rev(sha + "^1")
        if not parent:
            return bool(self.added(None, sha))
        return bool(self.added(parent, sha))


# ---------------------------------------------------------------- plan

@dataclass
class Release:
    version: tuple[int, int, int]
    tag: str | None
    sha: str
    date: str
    code: int
    fragments: list[Fragment]
    unlisted: list[str]  # merged change titles without a fragment


def _fragments(git: Git, base: str | None, head: str) -> list[Fragment]:
    return [parse_fragment(p, git.show(head, p)) for p in git.added(base, head)]


def _unlisted(git: Git, base: str | None, head: str, frags: list[Fragment]) -> list[str]:
    """Titles of merged changes that added no fragment and that no fragment names.

    A fragment named ``<PR number>-<slug>.md`` covers that PR, so a later PR can
    describe an earlier one without it being listed twice.
    """
    covered = {m.group(1) for f in frags
               if (m := re.match(r"(\d+)-", f.path.rsplit("/", 1)[-1]))}
    titles = []
    for sha, subject in git.first_parent(base, head):
        number = PR_NUMBER.search(subject)
        if number and (number.group(1) or number.group(2)) in covered:
            continue
        if git.adds_fragment(sha):
            continue
        title = re.sub(r"^Merge (?:PR|pull request) (#\d+)(?: from \S+)?:?\s*", r"\1 ", subject).strip()
        titles.append(title)
    return titles


def plan(git: Git, head_ref: str = "HEAD") -> tuple[Release, list[Release]]:
    """Return (next release at HEAD, earlier semantic releases newest first)."""
    head = git.rev(head_ref)
    if not head:
        raise ReleaseError(f"{head_ref} is not a commit")
    legacy: list[tuple[int, str]] = []
    semver: list[tuple[tuple[int, int, int], str, str]] = []
    for tag in git.tags():
        sha = git.rev(tag)
        if not sha or not git.is_ancestor(sha, head):
            continue
        if m := LEGACY_TAG.match(tag):
            legacy.append((int(m.group(1)), tag))
        elif m := SEMVER_TAG.match(tag):
            semver.append(((int(m.group(1)), int(m.group(2)), int(m.group(3))), tag, sha))
    legacy.sort()
    semver.sort()
    if semver and semver[-1][2] == head:
        raise ReleaseError(f"{semver[-1][1]} already points at {head[:12]}; nothing new to release")
    if semver and semver[0][0] < FIRST_SEMVER:
        raise ReleaseError(f"semantic tag {semver[0][1]} is below {fmt(FIRST_SEMVER)}")

    prev_code = legacy[-1][0] if legacy else 0
    prev_ref = legacy[-1][1] if legacy else None
    history: list[Release] = []
    for version, tag, sha in semver:
        if prev_ref is not None and not git.is_ancestor(git.rev(prev_ref), sha):
            raise ReleaseError(f"{tag} is not a descendant of {prev_ref}")
        code = max(code_for(git.commit_time(sha)), prev_code + 1)
        history.append(Release(version, tag, sha, _date(git, sha), code,
                               _fragments(git, prev_ref, sha), []))
        prev_code, prev_ref = code, tag

    frags = _fragments(git, prev_ref, head)
    if semver:
        version = bump(semver[-1][0], {f.type for f in frags})
    else:
        version = FIRST_SEMVER
    code = max(code_for(git.commit_time(head)), prev_code + 1)
    if not 0 < code < MAX_VERSION_CODE:
        raise ReleaseError(f"versionCode {code} out of range")
    nxt = Release(version, None, head, _date(git, head), code, frags, _unlisted(git, prev_ref, head, frags))
    return nxt, list(reversed(history))


def _date(git: Git, sha: str) -> str:
    return git.commit_time(sha).astimezone(dt.timezone.utc).strftime("%Y-%m-%d")


# ---------------------------------------------------------------- output

def notes_markdown(rel: Release, signer: str = "") -> str:
    lines = [f"## What's new in Atlas {fmt(rel.version)}", ""]
    for kind in TYPES:
        entries = [e for f in rel.fragments if f.type == kind for e in f.entries]
        if not entries:
            continue
        lines.append(f"### {HEADINGS[kind]}")
        lines += [f"- {e}" for e in entries]
        lines.append("")
    if rel.unlisted:
        lines.append("### Other merged changes")
        lines += [f"- {t}" for t in rel.unlisted]
        lines.append("")
    if len(lines) == 2:
        lines += ["Maintenance and small fixes.", ""]
    lines.append(f"Build {rel.code} · from main commit {rel.sha}")
    if signer:
        lines.append(f"Signer certificate SHA-256: {signer}")
    return "\n".join(lines) + "\n"


def app_changelog(nxt: Release, history: list[Release]) -> dict:
    releases = []
    for rel in [nxt, *history]:
        groups = []
        for kind in TYPES:
            if kind == "internal":
                continue
            entries = [e for f in rel.fragments if f.type == kind for e in f.entries]
            if entries:
                groups.append({"type": kind, "title": HEADINGS[kind], "entries": entries})
        releases.append({"version": fmt(rel.version), "build": rel.code,
                         "date": rel.date, "groups": groups})
    return {"schema": 1, "releases": releases}


def check(repo: Path) -> int:
    folder = repo / FRAGMENT_DIR
    count = 0
    for path in sorted(folder.glob("*")):
        rel = f"{FRAGMENT_DIR}/{path.name}"
        if path.name == "README.md":
            continue
        if path.is_symlink() or not path.is_file() or path.suffix != ".md":
            raise ReleaseError(f"{rel}: only plain .md files belong in {FRAGMENT_DIR}/")
        if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,80}\.md", path.name):
            raise ReleaseError(f"{rel}: name must be lower-case letters, digits, '.', '_' or '-'")
        parse_fragment(rel, path.read_text(encoding="utf-8"))
        count += 1
    print(f"PASS: {count} changelog fragment(s) valid")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("check")
    p = sub.add_parser("plan")
    p.add_argument("--out-dir", required=True, type=Path)
    p.add_argument("--head", default="HEAD")
    p.add_argument("--signer", default="")
    p.add_argument("--min-code", type=int, default=0)
    args = parser.parse_args(argv)
    repo = Path(__file__).resolve().parents[1]
    try:
        if args.cmd == "check":
            return check(repo)
        nxt, history = plan(Git(repo), args.head)
        if nxt.code <= args.min_code:
            raise ReleaseError(f"versionCode {nxt.code} not above floor {args.min_code}")
        out: Path = args.out_dir
        out.mkdir(parents=True, exist_ok=True)
        (out / "version.env").write_text(
            f"version_code={nxt.code}\nversion_name={fmt(nxt.version)}\n", encoding="utf-8")
        (out / "notes.md").write_text(notes_markdown(nxt, args.signer), encoding="utf-8")
        (out / "changelog.json").write_text(
            json.dumps(app_changelog(nxt, history), indent=1, ensure_ascii=False) + "\n",
            encoding="utf-8")
        print(f"Atlas {fmt(nxt.version)} (build {nxt.code})")
        return 0
    except ReleaseError as exc:
        print(f"::error::{exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
