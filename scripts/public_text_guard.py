#!/usr/bin/env python3
"""Leak guard for text that becomes public in this repository (atlas-gallery#533).

The repository is public, and its hardest bugs are debugged on the owner's own
phone and photos. This script flags text that looks like personal data before
it is merged (PR diffs, commit messages) or right after it is posted (issue
and PR bodies, comments, reviews).

It never prints the matched text: a finding names only its kind and where it
is (file and line, or the line number inside a posted body).

Escapes:
- a line containing ``guard: allow-public`` is skipped;
- added lines in files under the repository's test folders (synthetic
  fixtures) are allowed by path, see ``ALLOWED_PATH_PATTERNS``.

Usage:
  public_text_guard.py --diff FILE            # unified diff, '-' for stdin
  public_text_guard.py --commits BASE..HEAD   # commit messages via git log
  public_text_guard.py --text FILE [--source NAME]
  public_text_guard.py --event FILE           # GitHub event payload JSON
Add ``--json`` for machine-readable output. Exit code 1 when anything is found.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import PurePosixPath
from typing import Iterable

ALLOW_MARKER = "guard: allow-public"

# Kind ids and the human label used in comments and logs.
KINDS = {
    "email": "e-mail address",
    "phone": "phone number",
    "street-address": "street address",
    "personal-path": "path under a personal folder",
    "exif-gps": "EXIF or GPS data",
    "content-uri": "raw content:// media URI with an id",
    "chat-session-url": "ChatGPT or Claude chat URL",
}

# Repository test folders: synthetic fixtures there are allowed by path.
# Test files elsewhere (scripts/test_*.py, tools/**/FooTest.kt) count as test
# folders too: that is where this repository keeps its synthetic fixtures.
ALLOWED_PATH_PATTERNS = [
    re.compile(r"(^|/)(test|tests)/"),
    re.compile(r"(^|/)src/(test|androidTest)/"),
    re.compile(r"(^|/)(testdata|fixtures|synthetic)/"),
    re.compile(r"(^|/)test_[^/]+\.py$"),
    re.compile(r"(^|/)[^/]+Test\.kt$"),
]

_EMAIL = re.compile(r"(?<![\w.+@-])(?!this@)[A-Za-z0-9._%+-]+@([A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,})\b")
_EMAIL_ALLOWED_DOMAINS = re.compile(
    r"(^|\.)(example\.(com|org|net)|example|invalid|test|localhost|users\.noreply\.github\.com)$",
    re.IGNORECASE,
)
_EMAIL_ALLOWED_ADDRESSES = {"noreply@anthropic.com", "noreply@github.com", "codex@openai.com"}

# International (+CC ...) or Dutch (06 / 0XX) numbers. Digits are counted
# afterwards so that short sums like "+1 2" never match.
_PHONE = re.compile(
    r"(?<![\w+])(?:\+\d{1,3}[\s.-]?(?:\(0\)[\s.-]?)?\(?\d{1,4}\)?(?:[\s.-]?\d{2,4}){2,4}"
    r"|\b0(?:6[\s-]?\d{8}|[1-9]\d{1,2}[\s-]\d{6,7}))(?![\w])"
)

_NL_STREET_SUFFIX = (
    r"(?:straat|laan|weg|plein|gracht|kade|singel|dijk|steeg|hof|pad|dreef|markt|park|baan)"
)
_STREET = re.compile(
    # Dutch street name ending in a common suffix, then a house number
    r"\b[A-Z][a-z]+" + _NL_STREET_SUFFIX + r"\s+\d{1,4}\s?[a-zA-Z]?\b"  # guard: allow-public
    # English: house number, street name, street type
    r"|\b\d{1,5}\s+[A-Z][a-z]+(?:\s[A-Z][a-z]+)?\s(?:Street|St|Avenue|Ave|Road|Rd|Lane|Ln|Boulevard|Blvd|Drive|Dr)\b\.?"  # guard: allow-public
    # Dutch postcode (4 digits, 2 letters) followed by a place name
    r"|\b[1-9]\d{3}\s?(?!SA|SD|SS|KB|MB|GB|TB|PB|PX|DP|SP|UI|OK)[A-Z]{2}\s+[A-Z][a-z]{2,}"  # guard: allow-public
)

_PERSONAL_PATH = re.compile(
    r"/storage/emulated/0/(?:DCIM|Pictures|Download|Movies|Documents|WhatsApp|Android/media)/\S"  # guard: allow-public
    r"|/sdcard/\S"  # guard: allow-public
    r"|\b[A-Za-z]:\\Users\\(?!Public\\|Default\\|<)[^\\\s]+"  # guard: allow-public
    r"|/home/(?!user/|runner/|ubuntu/|<)[a-z_][a-z0-9_.-]*/"  # guard: allow-public
    r"|/Users/(?!Shared/|<)[A-Za-z][\w.-]*/",  # guard: allow-public
)

_EXIF_GPS = re.compile(
    r"\bGPS\s?(?:Latitude|Longitude|Altitude|Position|Coordinates|Info)(?:Ref)?\s*[:=]\s*\S"
    r"|\bexif:GPS\w*"
    r"|\bExifTool Version Number\b"
    # A decimal latitude, longitude pair with 4+ decimals each, not inside an
    # array literal.
    r"|(?<![\[\d.])(?<!\[ )-?(?:[1-8]?\d)\.\d{4,},\s?-?(?:1[0-7]\d|\d{1,2})\.\d{4,}(?![\d.]|\s*\])",
    re.IGNORECASE,
)

_CONTENT_URI = re.compile(r"content://[\w.-]+(?:/[\w.%-]+)*/\d+\b")  # guard: allow-public

# Chat links. Claude Code session and project links (claude.ai/code/...) are
# required attribution on commits and PRs and open only for the owner, so they
# are not flagged.
_CHAT_URL = re.compile(
    r"\b(?:chatgpt\.com|chat\.openai\.com)/(?:share/|c/|g/[\w-]+/c/)[0-9a-f-]{8,}"
    r"|\bclaude\.ai/(?:chat|share)/[0-9a-f-]{8,}",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class Finding:
    kind: str
    source: str
    line: int

    def describe(self) -> str:
        return f"{self.source}:{self.line}: {KINDS[self.kind]} ({self.kind})"


def _email_hits(line: str) -> bool:
    for match in _EMAIL.finditer(line):
        address = match.group(0).lower()
        if address in _EMAIL_ALLOWED_ADDRESSES:
            continue
        if _EMAIL_ALLOWED_DOMAINS.search(match.group(1)):
            continue
        return True
    return False


def _phone_hits(line: str) -> bool:
    for match in _PHONE.finditer(line):
        digits = re.sub(r"\D", "", match.group(0))
        if 9 <= len(digits) <= 15:
            return True
    return False


_CHECKS = [
    ("email", _email_hits),
    ("phone", _phone_hits),
    ("street-address", lambda line: bool(_STREET.search(line))),
    ("personal-path", lambda line: bool(_PERSONAL_PATH.search(line))),
    ("exif-gps", lambda line: bool(_EXIF_GPS.search(line))),
    ("content-uri", lambda line: bool(_CONTENT_URI.search(line))),
    ("chat-session-url", lambda line: bool(_CHAT_URL.search(line))),
]


def scan_line(line: str) -> list[str]:
    """Kinds found on one line (empty when the line carries the allow marker)."""
    if ALLOW_MARKER in line:
        return []
    return [kind for kind, check in _CHECKS if check(line)]


def scan_text(text: str, source: str) -> list[Finding]:
    findings = []
    for number, line in enumerate(text.splitlines(), start=1):
        findings.extend(Finding(kind, source, number) for kind in scan_line(line))
    return findings


def is_allowed_path(path: str) -> bool:
    posix = str(PurePosixPath(path))
    return any(pattern.search(posix) for pattern in ALLOWED_PATH_PATTERNS)


_HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@")


def scan_diff(diff: str) -> list[Finding]:
    """Scan only the added lines of a unified diff, by new-file line number."""
    findings: list[Finding] = []
    path: str | None = None
    new_line = 0
    for raw in diff.splitlines():
        if raw.startswith("diff --git "):
            path = None
            continue
        if raw.startswith("+++ "):
            target = raw[4:].strip()
            path = None if target == "/dev/null" else re.sub(r"^b/", "", target)
            continue
        if raw.startswith("--- "):
            continue
        hunk = _HUNK.match(raw)
        if hunk:
            new_line = int(hunk.group(1))
            continue
        if path is None:
            continue
        if raw.startswith("+"):
            if not is_allowed_path(path):
                findings.extend(Finding(kind, path, new_line) for kind in scan_line(raw[1:]))
            new_line += 1
        elif raw.startswith(" "):
            new_line += 1
        # '-' lines and '\ No newline' do not advance the new-file counter.
    return findings


def scan_commits(revision_range: str) -> list[Finding]:
    out = subprocess.run(
        ["git", "log", "--no-merges", "--format=%H%x00%B%x01", revision_range],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    findings: list[Finding] = []
    for record in out.split("\x01"):
        record = record.strip("\n")
        if not record:
            continue
        sha, _, message = record.partition("\x00")
        findings.extend(scan_text(message, f"commit {sha[:12]}"))
    return findings


def event_text(event: dict) -> tuple[str, str] | None:
    """The posted text of a GitHub event and a label for it, or None."""
    for key, label in (
        ("comment", "comment"),
        ("review", "review"),
        ("pull_request", "pull request body"),
        ("issue", "issue body"),
    ):
        item = event.get(key)
        if isinstance(item, dict):
            title = item.get("title") or ""
            body = item.get("body") or ""
            if key in ("pull_request", "issue") and title:
                return f"{title}\n{body}", f"{label} (line 1 is the title)"
            return body, label
    return None


def scan_event(event: dict) -> list[Finding]:
    found = event_text(event)
    if found is None:
        return []
    text, label = found
    return scan_text(text, label)


def _read(path: str) -> str:
    if path == "-":
        return sys.stdin.read()
    with open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def report(findings: Iterable[Finding], as_json: bool) -> str:
    findings = list(findings)
    if as_json:
        return json.dumps(
            {
                "count": len(findings),
                "kinds": sorted({f.kind for f in findings}),
                "findings": [asdict(f) | {"label": KINDS[f.kind]} for f in findings],
            },
            indent=2,
        )
    if not findings:
        return "public-text-guard: no personal-data patterns found"
    lines = [f"public-text-guard: {len(findings)} possible personal-data hit(s)"]
    lines += [f"- {f.describe()}" for f in findings]
    lines.append(f"Edit or remove the text, or add '{ALLOW_MARKER}' to the line if it is synthetic.")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--diff", metavar="FILE")
    mode.add_argument("--commits", metavar="BASE..HEAD")
    mode.add_argument("--text", metavar="FILE")
    mode.add_argument("--event", metavar="FILE")
    parser.add_argument("--source", default="text")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)

    if args.diff:
        findings = scan_diff(_read(args.diff))
    elif args.commits:
        findings = scan_commits(args.commits)
    elif args.text:
        findings = scan_text(_read(args.text), args.source)
    else:
        findings = scan_event(json.loads(_read(args.event)))

    print(report(findings, args.json))
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
