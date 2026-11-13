#!/usr/bin/env python3
"""Conservative offline gate: never make permanent ATLAS signing secrets reachable from PR code.

Inspects *workflow source*, not credential presence or GitHub trust policy.  No
network, secrets, APK, Gradle, phone, or GitHub configuration is accessed.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import stat

MAX_WORKFLOW_BYTES = 256 * 1024
# A GitHub Secrets context reference may be bare (e.g. toJSON(secrets)),
# not only secrets.NAME or secrets['NAME']. Deny all variants in mutable
# PR workflows. Conservative false positives are safer than credential leaks.
SECRET_PATTERN = re.compile(r"\bsecrets\b", re.I)
# Intentionally conservative: if the signed workflow supports PR, an attacker
# may change any checkout-executed build script in a same-repository PR.
UNTRUSTED_EVENTS = (
    # Only strictly main-branch push can be considered for future gated signing.
    # Every other known GitHub event can execute on other refs, caller input,
    # PR/merge-group state or externally controlled metadata.
    "branch_protection_rule", "check_run", "check_suite", "commit_comment",
    "create", "delete", "deployment", "deployment_status", "discussion",
    "discussion_comment", "fork", "gollum", "issue_comment", "issues",
    "label", "merge_group", "milestone", "page_build", "project",
    "project_card", "project_column", "public", "pull_request",
    "pull_request_review", "pull_request_review_comment", "pull_request_target",
    "registry_package", "release", "repository_dispatch", "schedule",
    "status", "watch", "workflow_call", "workflow_dispatch", "workflow_run",
)


def _safe_workflow_text(path: Path) -> str:
    if ".." in path.parts or any(p.is_symlink() for p in (path.absolute(), *path.absolute().parents)):
        raise ValueError("Workflow path is not a pinned regular file")
    try:
        st = path.lstat()
        if not stat.S_ISREG(st.st_mode) or st.st_nlink != 1 or not 0 < st.st_size <= MAX_WORKFLOW_BYTES:
            raise ValueError("Workflow source is not a bounded independent file")
        fd = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
        try:
            info = os.fstat(fd)
            if (st.st_dev, st.st_ino, st.st_size, st.st_mtime_ns) != (
                    info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns):
                raise ValueError("Workflow changed while opening")
            chunks = []
            n = 0
            while True:
                chunk = os.read(fd, 8192)
                if not chunk:
                    break
                n += len(chunk)
                if n > MAX_WORKFLOW_BYTES:
                    raise ValueError("Workflow too large during read")
                chunks.append(chunk)
        finally:
            os.close(fd)
        after = path.lstat()
        if (st.st_dev, st.st_ino, st.st_size, st.st_mtime_ns, st.st_ctime_ns) != (
                after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns):
            raise ValueError("Workflow changed during audit")
        return b"".join(chunks).decode("utf-8", "strict")
    except (OSError, UnicodeError) as exc:
        raise ValueError("Workflow cannot be safely inspected") from None


def _logical_lines(content: str) -> list[str]:
    """Strip blank lines/comments, but do not mistake commented examples for triggers."""
    return [line.rstrip() for line in content.splitlines()
            if line.strip() and not line.lstrip().startswith("#")]


def _exact_main_push_scope(lines: list[str]) -> bool:
    """Accept only a plainly scoped `on: push: branches: [main]` mapping.

    This intentionally rejects YAML anchors, flow mappings and unusual
    indentation instead of mistaking unrelated push/branches keys for event
    scope. It is a conservative source guard, not complete YAML validation.
    """
    if any(re.search(r"(?<!\S)[&*][A-Za-z_][A-Za-z0-9_-]*", line) for line in lines):
        return False
    if any(re.search(r"\\(?:x[0-9a-fA-F]{2}|u[0-9a-fA-F]{4}|U[0-9a-fA-F]{8})", line)
           for line in lines):
        return False
    # Main-only EVENT filters do not imply main-only CODE. Explicit
    # checkout repository/ref overrides or git clone can load code from an
    # unrelated PR/branch just before executing a secret-bearing build.
    if any(re.match(r"^\s*['\"]?(?:ref|repository)['\"]?\s*:", line)
           or re.search(r"\bgit\s+clone\b", line) for line in lines):
        return False
    indices = [i for i, line in enumerate(lines)
               if re.fullmatch(r"['\"]?on['\"]?\s*:\s*", line)]
    if len(indices) != 1:
        return False
    start = indices[0]
    end = next((i for i in range(start + 1, len(lines))
                if lines[i] and not lines[i][0].isspace()), len(lines))
    body = lines[start + 1:end]
    direct = [line.strip() for line in body
              if len(line) - len(line.lstrip()) == 2]
    if direct != ["push:"]:
        return False
    push_fields = [line.strip() for line in body
                   if len(line) - len(line.lstrip()) == 4]
    # No second/escaped branch key may override the approved branch filter.
    # Only explicitly understood main-only selector and optional path filters
    # are permitted; other push-level keys require human review.
    if push_fields.count("branches: [main]") != 1:
        return False
    if any(item not in ("branches: [main]", "paths:", "paths-ignore:")
           for item in push_fields):
        return False
    return True


def evaluate(source: str) -> dict:
    lines = _logical_lines(source)
    secret_references = bool(SECRET_PATTERN.search("\n".join(lines)))
    # Strict policy: no signing secret reference may coexist with untrusted
    # event triggers anywhere in one workflow. Per-step "if" is not accepted
    # as a durable isolation boundary for mutable repository PR code.
    untrusted = [event for event in UNTRUSTED_EVENTS if any(
        re.search(r"(?<![A-Za-z0-9_-])" + event + r"[\'\"]?\s*:", line)
        or (re.search(r"^\s*['\"]?on['\"]?\s*:\s*\[", line)
            and re.search(r"(?<![\w-])" + event + r"(?![\w-])", line))
        for line in lines)]
    # Workflow YAML also permits single scalar event syntax: on: pull_request.
    for event in UNTRUSTED_EVENTS:
        if any(re.search(r"^\s*['\"]?on['\"]?\s*:\s*['\"]?" + event + r"\b", line) for line in lines):
            if event not in untrusted:
                untrusted.append(event)
    # Deliberately narrow trusted scope: a separately named main-only workflow,
    # never workflow_dispatch (branch input can change checkout source), unless
    # independently audited by humans in a separate higher-assurance context.
    unsafe_dispatch = "workflow_dispatch" in untrusted
    secret_boundary_pass = (not secret_references or (
        not untrusted and not unsafe_dispatch and _exact_main_push_scope(lines)))
    # A secrets-free PR preview is allowable, but this is NOT a signature proof.
    return {
        "signing_secret_references_present": secret_references,
        "untrusted_workflow_triggers_present": bool(untrusted),
        "workflow_dispatch_present": unsafe_dispatch,
        "signing_secret_source_isolation_pass": secret_boundary_pass,
        "permanent_signing_ready": False,
        "independent_build_signer_fingerprints_verified": False,
        "phone_installation_authorized": False,
        "release_go": False,
    }


class _PrivateSafeParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        self.exit(2, "SIGNING NO-GO: invalid audit arguments\n")


def main() -> int:
    parser = _PrivateSafeParser(description="Offline stable-signing workflow source isolation check")
    parser.add_argument("workflow", type=Path)
    args = parser.parse_args()
    try:
        result = evaluate(_safe_workflow_text(args.workflow))
    except (ValueError, TypeError):
        parser.exit(2, "SIGNING NO-GO: cannot safely inspect workflow source\n")
    print(json.dumps(result, sort_keys=True))
    return 0 if result["signing_secret_source_isolation_pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
