#!/usr/bin/env python3
"""Zero-secret, offline synthetic policy regression for stable signing isolation."""
from __future__ import annotations

import contextlib
from io import StringIO
from pathlib import Path
import re
import sys
import tempfile
from unittest.mock import patch

import audit_stable_signing_workflow as guard

OLD = """name: Android
on:
  pull_request:
  push:
    branches: [main]
jobs:
  build:
    steps:
      - uses: actions/checkout@v4
      - run: gradle :app:assembleDebug
        env:
          SIGNING: ${{ secrets.ATLAS_STABLE_DEBUG_KEYSTORE_B64 }}
"""

SPLIT_PREVIEW = """on:
  pull_request:
jobs:
  build:
    steps:
      - run: gradle :app:testDebugUnitTest
"""

TRUSTED_MAIN = """on:
  push:
    branches: [main]
jobs:
  sign:
    steps:
      - run: echo certificate-fingerprint-only
        env:
          SIGNING: ${{ secrets.ATLAS_STABLE_DEBUG_KEYSTORE_B64 }}
"""

assert guard.evaluate(OLD)["signing_secret_source_isolation_pass"] is False
assert guard.evaluate(SPLIT_PREVIEW)["signing_secret_source_isolation_pass"] is True
assert guard.evaluate(TRUSTED_MAIN)["signing_secret_source_isolation_pass"] is True
for trigger in guard.UNTRUSTED_EVENTS:
    assert guard.evaluate(TRUSTED_MAIN.replace("  push:", "  " + trigger + ":\n  push:"))[
        "signing_secret_source_isolation_pass"] is False, trigger
# GitHub Actions accepts quoted mapping keys. They must never conceal a
# PR trigger while a nearby unquoted push/main branch falsely looks trusted.
for event in guard.UNTRUSTED_EVENTS:
    for quote in ("'", '"'):
        case = TRUSTED_MAIN.replace(
            "  push:", "  " + quote + event + quote + ":\n  push:", 1
        )
        result = guard.evaluate(case)
        assert result["untrusted_workflow_triggers_present"] is True, (event, quote)
        assert result["signing_secret_source_isolation_pass"] is False, (event, quote)
for on_key in ("'on'", '"on"'):
    quoted_map = TRUSTED_MAIN.replace(
        "on:\n  push:", on_key + ":\n  'pull_request':\n  push:", 1
    )
    result = guard.evaluate(quoted_map)
    assert result["untrusted_workflow_triggers_present"] is True, on_key
    assert result["signing_secret_source_isolation_pass"] is False, on_key
    for syntax in ("[push, pull_request]", "pull_request"):
        quoted_on = TRUSTED_MAIN.replace(
            "on:\n  push:", on_key + ": " + syntax + "\n  push:", 1
        )
        result = guard.evaluate(quoted_on)
        assert result["untrusted_workflow_triggers_present"] is True, (on_key, syntax)
        assert result["signing_secret_source_isolation_pass"] is False, (on_key, syntax)
for form in ("on: [push, pull_request]", "on: pull_request", "on:\n  pull_request_target:"):
    assert guard.evaluate(TRUSTED_MAIN.replace("on:\n  push:", form + "\n  push:"))[
        "signing_secret_source_isolation_pass"] is False, form
assert guard.evaluate(TRUSTED_MAIN.replace("  push:", "  workflow_dispatch:\n  push:"))[
    "signing_secret_source_isolation_pass"] is False
assert guard.evaluate("# pull_request:\n" + TRUSTED_MAIN)["signing_secret_source_isolation_pass"] is True
assert guard.evaluate(TRUSTED_MAIN.replace("branches: [main]", "branches: [main, dev]"))[
    "signing_secret_source_isolation_pass"] is False
# A push triggered by BOTH main branch and mutable release tags is NOT a
# main-only signing boundary. Quoted YAML field names are just as unsafe.
for field in ("tags", "'tags'", '"tags"', "tags-ignore", "'tags-ignore'",
              '"tags-ignore"', "branches-ignore", "'branches-ignore'",
              '"branches-ignore"'):
    candidate = TRUSTED_MAIN.replace(
        "    branches: [main]",
        "    branches: [main]" + chr(10) + "    " + field + ": ['v*']",
    )
    assert guard.evaluate(candidate)["signing_secret_source_isolation_pass"] is False, field
# Other supported GitHub Actions events (not just PR) can run outside a
# vetted main commit; all must fail closed with a signing-secret reference.
for event in ("merge_group", "release", "pull_request_review", "check_run", "create"):
    assert event in guard.UNTRUSTED_EVENTS
    candidate = TRUSTED_MAIN.replace("  push:", "  " + event + ":" + chr(10) + "  push:")
    assert guard.evaluate(candidate)["signing_secret_source_isolation_pass"] is False
# Duplicate branch selectors are valid YAML but many loaders use last-wins;
# a later "branches: [dev]" changes what the workflow can execute.
for override in ("branches: [dev]", "'branches': [dev]",
                 '"branches": [dev]', "branches : [dev]"):
    changed = TRUSTED_MAIN.replace(
        "    branches: [main]", "    branches: [main]" + chr(10) + "    " + override,
    )
    assert guard.evaluate(changed)["signing_secret_source_isolation_pass"] is False, override
# Quoted YAML hex/unicode escapes may conceal structural keys.
ESCAPED_EVENT = TRUSTED_MAIN.replace(
    "  push:", '  "\\x70ull_request":' + chr(10) + "  push:", 1
)
assert guard.evaluate(ESCAPED_EVENT)["signing_secret_source_isolation_pass"] is False
# YAML aliases are valid mapping keys. A named anchor can expand into a
# pull_request event without the literal "pull_request:" ever appearing;
# a scanner that merely finds "push:" and "branches: [main]" fails open.
ALIAS_PR = """event_label: &pr pull_request
on:
  *pr:
  push:
    branches: [main]
jobs:
  sign:
    steps:
      - run: echo ${{ secrets.SIGNING_KEY }}
"""
assert guard.evaluate(ALIAS_PR)["signing_secret_source_isolation_pass"] is False
ALIAS_ON = """other: &events [pull_request]
on: *events
pretend:
  push:
    branches: [main]
jobs:
  sign:
    steps:
      - run: echo ${{ secrets.SIGNING_KEY }}
"""
assert guard.evaluate(ALIAS_ON)["signing_secret_source_isolation_pass"] is False
# An unfamiliar event MUST NOT be accepted merely because it is missing
# from today's blacklist; approve the direct push event only.
UNKNOWN_EVENT = TRUSTED_MAIN.replace("  push:", "  future_external_event:" + chr(10) + "  push:")
assert guard.evaluate(UNKNOWN_EVENT)["signing_secret_source_isolation_pass"] is False
# Duplicate top-level `on` blocks are ambiguous by construction.
assert guard.evaluate(TRUSTED_MAIN + chr(10) + "on:" + chr(10) + "  release:")[
    "signing_secret_source_isolation_pass"] is False
# A main-only trigger is not safe if the job checks out PR/unvetted code
# before building with secrets, even if no pull_request event is present.
for alternate in (
    "          ref: experimental", "          'ref': refs/pull/123/head",
    "          repository: attacker/unreviewed",
):
    candidate = TRUSTED_MAIN.replace(
        "      - run: echo certificate-fingerprint-only",
        "      - uses: actions/checkout@v4" + chr(10) +
        "        with:" + chr(10) + alternate + chr(10) +
        "      - run: echo certificate-fingerprint-only",
    )
    assert guard.evaluate(candidate)["signing_secret_source_isolation_pass"] is False, alternate
# An untrusted remote clone invoked by a run step is another route to
# build-time code substitution without any PR event in the workflow.
remote_clone = TRUSTED_MAIN.replace(
    "      - run: echo certificate-fingerprint-only",
    "      - run: git clone https://example.invalid/unreviewed.git" + chr(10) +
    "      - run: echo certificate-fingerprint-only",
)
assert guard.evaluate(remote_clone)["signing_secret_source_isolation_pass"] is False
# This extra scrutiny applies only to signing-secret-bearing workflows.
assert guard.evaluate(SPLIT_PREVIEW + chr(10) + "checkout: ref: experimental")[
    "signing_secret_source_isolation_pass"] is True
for expression in ("secrets.ATLAS_STABLE_DEBUG_KEYSTORE_B64",
                   "secrets['ATLAS_STABLE_DEBUG_KEYSTORE_B64']", "secrets . ANY_SECRET"):
    assert guard.evaluate(SPLIT_PREVIEW + "# bare test\n" + "env: ${{ " + expression + " }}\n")[
        "signing_secret_source_isolation_pass"] is False
# Bare GitHub Secrets context references are also sensitive. The old
# matcher only detected secrets.NAME or secrets[NAME] and wrongly passed
# toJSON(secrets) and bare secrets in a PR/push workflow.
for bare in ("toJSON(secrets)", "secrets", "format('{0}', secrets)"):
    candidate = OLD.replace("secrets.ATLAS_STABLE_DEBUG_KEYSTORE_B64", bare)
    result = guard.evaluate(candidate)
    assert result["signing_secret_references_present"] is True, bare
    assert result["signing_secret_source_isolation_pass"] is False, bare
assert guard.evaluate("# ${{ secrets.ATLAS_STABLE_DEBUG_KEYSTORE_B64 }}\n" + SPLIT_PREVIEW)[
    "signing_secret_references_present"] is False
for sample in (OLD, SPLIT_PREVIEW, TRUSTED_MAIN):
    result = guard.evaluate(sample)
    assert result["permanent_signing_ready"] is False
    assert result["independent_build_signer_fingerprints_verified"] is False
    assert result["release_go"] is False
    assert result["phone_installation_authorized"] is False

# G402 U17 (#497): the real GitHub-signed phone release workflow must pass
# this audit, and keep its two-job split: an unprivileged build job that runs
# Gradle, and a sign job that holds the key but never runs repository code.
RELEASE_WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/release-signed.yml"
release_source = guard._safe_workflow_text(RELEASE_WORKFLOW)
release_result = guard.evaluate(release_source)
assert release_result["signing_secret_references_present"] is True
assert release_result["untrusted_workflow_triggers_present"] is False
assert release_result["signing_secret_source_isolation_pass"] is True
assert release_result["release_go"] is False
_jobs = release_source.split("\njobs:\n", 1)[1]
_build, _sign = _jobs.split("\n  sign:\n", 1)
assert _build.startswith("  build:\n")
# Global and build-job token stay read-only; only the sign job may write.
assert "\npermissions:\n  contents: read\n" in release_source.split("\njobs:\n", 1)[0]
assert "permissions" not in _build and "secrets" not in _build
assert "environment" not in _build and "contents: write" not in _build
assert "persist-credentials: false" in _build
assert ":app:assembleRelease" in _build and "signingConfig" not in _build
assert "    environment: release\n" in _sign
assert "    permissions:\n      contents: write\n" in _sign
# The key-holding job never checks out or builds repository code.
for forbidden in ("actions/checkout", "gradle", "setup-android", "git clone"):
    assert forbidden not in _sign, forbidden
# Secrets appear only as the two environment-scoped signing values.
assert sorted(re.findall(r"secrets\.[A-Z0-9_]+", release_source)) == [
    "secrets.ATLAS_RELEASE_KEYSTORE_B64", "secrets.ATLAS_RELEASE_KEY_PASSWORD"]
assert "--ks-pass env:ATLAS_RELEASE_KEY_PASSWORD" in _sign
assert "umask 077" in _sign and "trap 'rm -rf \"$KEYDIR\"' EXIT" in _sign
_cleanup = _sign.split("- name: Remove the keystore\n", 1)[1].split("- name:", 1)[0]
assert "if: always()" in _cleanup and "rm -rf" in _cleanup
# Signing happens before verification, and verification before publishing.
assert (_sign.index("apksigner\" sign") < _sign.index("Verify signer")
        < _sign.index("gh release create"))
assert "5f15596090e3cfa339c2fd73a2039a23ecca9d02ae4e3506e22dabe976ce170b" in release_source
assert '[[ "$cert" == "$EXPECTED_SIGNER_SHA256" ]]' in _sign
assert "application-debuggable" in _sign
# Every action is pinned by full commit SHA.
_uses = re.findall(r"uses:\s*(\S+)", release_source)
assert _uses and all(re.fullmatch(r"[\w.-]+/[\w./-]+@[0-9a-f]{40}", u) for u in _uses), _uses
assert not any(u.startswith("gradle/") for u in _uses)

with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    workflow = root / "private-test-workflow.yml"
    workflow.write_text(OLD)
    error = StringIO()
    with patch.object(sys, "argv", ["audit", str(workflow)]), contextlib.redirect_stdout(StringIO()) as output:
        assert guard.main() == 1
    assert "signing_secret_source_isolation_pass\": false" in output.getvalue()
    assert str(root) not in output.getvalue()
    workflow.write_text(TRUSTED_MAIN)
    with patch.object(sys, "argv", ["audit", str(workflow)]), contextlib.redirect_stdout(StringIO()) as output:
        assert guard.main() == 0
    workflow.write_text(SPLIT_PREVIEW)
    with patch.object(sys, "argv", ["audit", str(workflow)]), contextlib.redirect_stdout(StringIO()) as output:
        assert guard.main() == 0
    link = root / "linked.yml"
    link.symlink_to(workflow)
    with patch.object(sys, "argv", ["audit", str(link)]), contextlib.redirect_stderr(error):
        try: guard.main()
        except SystemExit as exc: assert exc.code == 2
        else: raise AssertionError("symlink accepted")
    assert str(root) not in error.getvalue()
    error = StringIO()
    with patch.object(sys, "argv", ["audit", str(workflow), "--unexpected", "/private/token"]), contextlib.redirect_stderr(error):
        try: guard.main()
        except SystemExit as exc: assert exc.code == 2
        else: raise AssertionError("invalid argument accepted")
    assert "/private/" not in error.getvalue()
    workflow.write_bytes(b"\xff")
    with patch.object(sys, "argv", ["audit", str(workflow)]), contextlib.redirect_stderr(StringIO()):
        try: guard.main()
        except SystemExit as exc: assert exc.code == 2
        else: raise AssertionError("invalid utf-8 accepted")
print("PASS: release-signed.yml two-job main-only boundary, PR signing secrets denied, main-only distinct scope, unsafe event variants, safe CLI and bounded files")
