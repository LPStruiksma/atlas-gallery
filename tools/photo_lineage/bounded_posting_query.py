"""Bounded read-only queries against independently pinned packed u64 snapshots.

Research-only: numeric photo IDs and ORB visual words, no paths, photographs,
identity assignment or original-media operations. Unlike an unbounded Counter
over inverted postings, this preflights the *complete* posting visit cost and
fails closed when too many repeated words would consume time or RAM.
"""
from __future__ import annotations

from collections import Counter
from contextlib import contextmanager
import hashlib
import hmac
import mmap
import os
from pathlib import Path
import re
import stat
import struct
from typing import Iterable, Iterator

from .external_index import DOC_MASK, WORD_LIMIT

DEFAULT_MAX_BYTES = 1_000_000_000
DEFAULT_POSTING_VISITS = 250_000
DEFAULT_DISTINCT_DOCS = 100_000
MAX_TERMS = 512
READ_BYTES = 1024 * 1024


class PostingBudgetExceeded(ValueError):
    """Incomplete search. Typed reason avoids parsing freeform exception text."""

    def __init__(self, reason: str, message: str):
        if reason not in ("posting_visits", "distinct_documents"):
            raise ValueError("Unrecognized query budget failure category")
        self.reason = reason
        super().__init__(message)


class PinnedPostingQuery:
    """Held-open verified read-only snapshot; create via open_pinned_queries.

    Query refuses invalid terms or uncertain/stale file metadata. A malicious
    in-place filesystem writer with forged unchanged stat metadata is outside
    this prototype's trust model. Keep indices in a private directory.
    """
    def __init__(self, source, mapped, initial):
        self._source = source
        self._map = mapped
        self._initial = initial
        self.count = initial.st_size // 8

    def _check_unchanged(self):
        latest = os.fstat(self._source.fileno())
        if ((latest.st_dev, latest.st_ino, latest.st_size, latest.st_mtime_ns, latest.st_ctime_ns) !=
                (self._initial.st_dev, self._initial.st_ino, self._initial.st_size,
                 self._initial.st_mtime_ns, self._initial.st_ctime_ns)):
            raise ValueError("Pinned posting snapshot changed; discard query result")

    def _record(self, i: int) -> int:
        return struct.unpack_from("<Q", self._map, i * 8)[0]

    def _lower_bound(self, value: int) -> int:
        low, high = 0, self.count
        while low < high:
            mid = (low + high) // 2
            if self._record(mid) < value:
                low = mid + 1
            else:
                high = mid
        return low

    def _prepare_windows(self, words: Iterable[int]):
        """Validate bounded query terms; compute complete per-word posting ranges.

        Shared by diagnostics and scoring so reported cost cannot silently
        disagree with the actual verified numeric-index query cost.
        """
        self._check_unchanged()
        terms: set[int] = set()
        supplied = 0
        for term in words:
            supplied += 1
            if supplied > MAX_TERMS * 4:
                raise ValueError("Too many raw query terms, including duplicates")
            if type(term) is not int or not 0 <= term < WORD_LIMIT:
                raise ValueError("Visual word must be a 26-bit integer")
            terms.add(term)
            if len(terms) > MAX_TERMS:
                raise ValueError("Too many query visual words")
        windows: list[tuple[int, int]] = []
        required = 0
        highest = 0
        for term in sorted(terms):
            first = self._lower_bound(term << 32)
            last = self._lower_bound((term + 1) << 32)
            count = last - first
            required += count
            highest = max(highest, count)
            windows.append((first, last))
        self._check_unchanged()
        return len(terms), windows, required, highest

    def inspect_query_load(self, words: Iterable[int], *,
                           max_posting_visits: int = DEFAULT_POSTING_VISITS) -> dict:
        """Read-only budget diagnostic, not a similarity or recall estimate.

        Does not visit individual candidate document IDs or build a Counter.
        Anonymous aggregate counts only: no query visual-word IDs or paths.
        """
        if type(max_posting_visits) is not int or not 1 <= max_posting_visits <= 10_000_000:
            raise ValueError("Posting budget must be an integer 1..10,000,000")
        term_count, windows, required, highest = self._prepare_windows(words)
        return {
            "policy": "Verified numeric posting-window cost only; not an exposure match, a recall estimate or an index validity guarantee",
            "distinct_query_words": term_count,
            "postings_preflight": required,
            "largest_single_word_posting_list": highest,
            "words_with_nonempty_postings": sum(last > first for first, last in windows),
            "posting_budget": max_posting_visits,
            "posting_budget_status": (
                "abstain_incomplete" if required > max_posting_visits
                else "within_posting_budget_only"
            ),
            "query_ranking_performed": False,
        }

    def query(self, words: Iterable[int], *, top_k: int = 20,
              max_posting_visits: int = DEFAULT_POSTING_VISITS,
              max_distinct_documents: int = DEFAULT_DISTINCT_DOCS,
              exclude_doc_id: int | None = None) -> dict:
        """Exact rank among postings sharing requested terms, or explicit abstain.

        No stop-word skipping, no partial ranking and no silent top-N scanning
        truncation. Query only *already built and independently verified* numeric
        snapshots. Feature extraction and image decoding are out of scope.
        """
        if type(top_k) is not int or not 1 <= top_k <= 100:
            raise ValueError("top_k must be 1..100")
        if type(max_posting_visits) is not int or not 1 <= max_posting_visits <= 10_000_000:
            raise ValueError("Posting budget must be an integer 1..10,000,000")
        if (type(max_distinct_documents) is not int or
                not 1 <= max_distinct_documents <= 1_000_000):
            raise ValueError("Distinct-document budget must be 1..1,000,000")
        if (exclude_doc_id is not None and
                (type(exclude_doc_id) is not int or not 0 <= exclude_doc_id <= DOC_MASK)):
            raise ValueError("Excluded ID must be uint32")
        term_count, windows, required, _ = self._prepare_windows(words)
        if required > max_posting_visits:
            raise PostingBudgetExceeded(
                "posting_visits",
                f"Query needs over {max_posting_visits} posting visits; "
                "abstain and request a narrower verified search, never return an empty shortlist"
            )
        scores = Counter()
        visited = 0
        for first, last in windows:
            for index in range(first, last):
                doc = self._record(index) & DOC_MASK
                if doc == exclude_doc_id:
                    continue
                if doc not in scores and len(scores) >= max_distinct_documents:
                    raise PostingBudgetExceeded(
                        "distinct_documents",
                        "Candidate-document budget exhausted; incomplete result, abstain"
                    )
                scores[doc] += 1
                visited += 1
        matches = sorted(((doc, score) for doc, score in scores.items() if score >= 2),
                         key=lambda row: (-row[1], row[0]))[:top_k]
        self._check_unchanged()
        return {
            "complete": True,
            "policy": "Numeric retrieval shortlist only, NEVER photographic exposure or person identity proof",
            "distinct_query_words": term_count,
            "postings_preflight": required,
            "postings_visited_excluding_self": visited,
            "distinct_candidate_documents": len(scores),
            "matches": [{"photo_id": doc, "shared_visual_words": count}
                        for doc, count in matches],
        }


@contextmanager
def open_pinned_queries(path: str | Path, expected_sha256: str, *,
                        max_bytes: int = DEFAULT_MAX_BYTES) -> Iterator[PinnedPostingQuery]:
    """Verify sorted u64 stream and independently supplied SHA on *one* open fd.

    Full-file verification happens once per query session, not once per query.
    Opening through O_NOFOLLOW avoids a final-component symlink. This is not
    an anti-rollback trusted-pin store; caller supplies the authoritative pin.
    """
    if not isinstance(expected_sha256, str) or re.fullmatch(r"[0-9a-f]{64}", expected_sha256) is None:
        raise ValueError("Independently pinned lowercase SHA-256 required")
    if type(max_bytes) is not int or not 1 <= max_bytes <= DEFAULT_MAX_BYTES:
        raise ValueError("Snapshot byte budget must be 1..1,000,000,000")
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise ValueError("Require existing non-symlink pinned snapshot")
    fd = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    with os.fdopen(fd, "rb") as source:
        initial = os.fstat(source.fileno())
        if (not stat.S_ISREG(initial.st_mode) or initial.st_size > max_bytes
                or initial.st_size % 8):
            raise ValueError("Unaligned, over byte budget or nonregular pinned snapshot")
        digest = hashlib.sha256()
        previous = -1
        while True:
            block = source.read(READ_BYTES)
            if not block:
                break
            if len(block) % 8:
                raise ValueError("Truncated posting stream")
            digest.update(block)
            for (record,) in struct.iter_unpack("<Q", block):
                if record <= previous or record >> 32 >= WORD_LIMIT:
                    raise ValueError("Posting stream not strictly sorted unique or word out of range")
                previous = record
        after = os.fstat(source.fileno())
        fingerprint = lambda x: (x.st_dev, x.st_ino, x.st_size, x.st_mtime_ns, x.st_ctime_ns)
        if fingerprint(initial) != fingerprint(after):
            raise ValueError("Snapshot changed during independent verification")
        if not hmac.compare_digest(digest.hexdigest(), expected_sha256):
            raise ValueError("Snapshot does not match independently trusted digest")
        # An empty file has no mmap representation, but is a valid empty
        # immutable posting snapshot. Query still returns no hits.
        mapped = mmap.mmap(source.fileno(), 0, access=mmap.ACCESS_READ) if initial.st_size else b""
        try:
            index = PinnedPostingQuery(source, mapped, initial)
            index._check_unchanged()
            yield index
        finally:
            if isinstance(mapped, mmap.mmap):
                mapped.close()
