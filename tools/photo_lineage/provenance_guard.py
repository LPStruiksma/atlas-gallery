"""Fail-closed source-page and scene-group checks for heldout photo audits.

Metadata is curator-supplied evidence, not machine proof of distinct shutter
captures. No files, network requests, image paths or photo data are read here.
"""
from __future__ import annotations

from pathlib import PurePosixPath
import re
from unicodedata import normalize
from urllib.parse import unquote, urlsplit

_TOKEN = re.compile(r"[A-Za-z0-9_-]{1,64}\Z")
_HOST = re.compile(r"[A-Za-z0-9.-]+\Z")


def _canonical_source_page(url: object) -> str:
    """Require immutable-looking HTTPS *page* links without tracking aliases.

    Percent-escaped and unescaped URL path spellings map to one source page.
    Content/license/shutter identity is never established by this function.
    """
    if (not isinstance(url, str) or len(url) > 2048
            or any(ord(char) < 32 or ord(char) == 127 for char in url)):
        # urllib.parse normalizes away raw newlines and tabs, so refuse
        # ambiguous inputs *before* urlsplit can silently change them.
        raise ValueError("Invalid photographic source URL")
    try:
        parts = urlsplit(url)
        host = parts.hostname
        port = parts.port
    except ValueError as exc:
        raise ValueError("Invalid photographic source URL") from exc
    if (parts.scheme != "https" or not host or not _HOST.fullmatch(host)
            or parts.username is not None or parts.password is not None
            or port is not None or parts.query or parts.fragment
            or not parts.path.startswith("/") or not parts.path.strip("/")):
        raise ValueError("Source URL must be a stable HTTPS page without auth, port, query or fragment")
    # A final DNS dot aliases a hostname in resolution but should not
    # create a second independent "source". Refuse rather than guess.
    if host.startswith(".") or host.endswith(".") or ".." in host:
        raise ValueError("Ambiguous photographic source URL host")
    path = normalize("NFC", unquote(parts.path))
    # Residual percent escapes can be decoded a second time by different
    # servers/proxies; ambiguous aliases cannot count as independent pages.
    # Normalization of canonically equivalent Unicode source names avoids
    # treating composed/decomposed filename URLs as independent shutter
    # provenance. Literal percent-containing file names conservatively abstain.
    if (not path.strip("/") or "%" in path or "\\" in path or "//" in path
            or "?" in path or "#" in path
            or any(ord(char) < 32 or ord(char) == 127 for char in path)
            or any(token in (".", "..") for token in path.split("/"))):
        raise ValueError("Unsafe or multiply encoded photographic source URL path")
    return host.lower() + path.rstrip("/")


def validate_provenance_groups(version: object, scenes: object) -> None:
    """Reject obvious duplicate scene/source aliases across the entire split.

    A distinct scene_group must denote a curated independently meaningful
    scene. Duplicate groups are not independent Wilson denominator units.
    Every external capture needs its own canonical source page, even when
    multiple captures are legitimately from the same static scene.
    """
    if type(version) is not int or version != 2:
        raise ValueError("Heldout manifest v2 required: add a stable scene_group per scene and unique source_url per capture")
    if not isinstance(scenes, list) or not 1 <= len(scenes) <= 20:
        raise ValueError("Expected 1..20 scenes")
    groups: set[str] = set()
    sources: set[str] = set()
    paths: set[str] = set()
    for scene in scenes:
        if not isinstance(scene, dict):
            raise ValueError("Invalid scene")
        group = scene.get("scene_group")
        if not isinstance(group, str) or not _TOKEN.fullmatch(group):
            raise ValueError("Every scene requires a canonical scene_group token")
        if group in groups:
            raise ValueError("Duplicate scene_group across scenes/splits; consolidate or revise the holdout boundary")
        groups.add(group)
        captures = scene.get("captures")
        if not isinstance(captures, list) or not 2 <= len(captures) <= 8:
            raise ValueError("Every scene needs 2..8 captures")
        for capture in captures:
            if not isinstance(capture, dict):
                raise ValueError("Invalid capture")
            source = _canonical_source_page(capture.get("source_url"))
            if source in sources:
                raise ValueError("Reused photographic source_url; independent shutter proof is ambiguous")
            sources.add(source)
            raw_path = capture.get("file")
            if (not isinstance(raw_path, str) or not raw_path or "\x00" in raw_path
                    or "\\" in raw_path or raw_path.startswith("/")
                    or any(part in (".", "..") for part in raw_path.split("/"))):
                raise ValueError("Unsafe relative capture path")
            canonical_path = str(PurePosixPath(raw_path))
            if canonical_path in paths:
                raise ValueError("Capture path reused across scenes/splits")
            paths.add(canonical_path)
