"""Exact known license-reference admission for source-curated Photo DNA audits.

This is consistency of a CURATOR-CLAIMED license ID with its cited rights
page, not proof of legal authorship, license grant, or independent shutters.
No files, network, credentials or camera metadata are accessed.
"""
from __future__ import annotations

from urllib.parse import urlsplit

# Canonical public, version-specific reference pages (not media licenses
# signed or authenticated by any third party). Keep strict to prevent an
# unrelated URL or attacker-controlled host being mistaken for the license.
CANONICAL_RIGHTS_PATHS = {
    "CC0-1.0": "/publicdomain/zero/1.0",
    "CC-BY-4.0": "/licenses/by/4.0",
    "CC-BY-SA-4.0": "/licenses/by-sa/4.0",
}
PUBLIC_DOMAIN_MARK = "/publicdomain/mark/1.0"


def consistent_license_reference(label: object, value: object) -> bool:
    """Return False for ambiguous, mismatched or noncanonical rights URLs.

    A public-domain claim is not a copyright LICENSE; the PD Mark is
    accepted as a rights-information reference, or a curated Wikimedia
    Commons PD-template page. Neither establishes jurisdictional status.
    """
    if (type(label) is not str or label not in
            (*CANONICAL_RIGHTS_PATHS.keys(), "PUBLIC-DOMAIN")
            or type(value) is not str or not 12 <= len(value) <= 512
            or any(ord(c) < 32 or ord(c) == 127 for c in value)):
        return False
    try:
        parsed = urlsplit(value)
        hostname = parsed.hostname
        port = parsed.port
    except ValueError:
        return False
    if (parsed.scheme != "https" or hostname is None
            or parsed.username is not None or parsed.password is not None
            or port is not None or parsed.query or parsed.fragment
            or "%" in parsed.path or "\\" in parsed.path
            or "//" in parsed.path or any(
                token in (".", "..") for token in parsed.path.split("/"))
            or not parsed.path.startswith("/")):
        return False
    host = hostname.lower()
    path = parsed.path.rstrip("/")
    if label in CANONICAL_RIGHTS_PATHS:
        return host == "creativecommons.org" and path == CANONICAL_RIGHTS_PATHS[label]
    # The PD Mark is a rights indicator, not a grant. A Wikimedia
    # Commons PD template is a curator pointer requiring manual review.
    return ((host == "creativecommons.org" and path == PUBLIC_DOMAIN_MARK)
            or (host == "commons.wikimedia.org"
                and path.startswith("/wiki/Template:PD-")
                and len(path) > len("/wiki/Template:PD-")
                and len(path) <= 180))
