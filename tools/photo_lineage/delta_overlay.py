"""Read-only overlay of changed-document fingerprints on a packed index.

Research only. No file I/O, image reading, photo deletion, grouping, or
same-exposure certification. None marks a photo-ID cache tombstone.
"""
from __future__ import annotations

from bisect import bisect_left, bisect_right
from collections import Counter
from collections.abc import Iterable, Mapping, Sequence

DOC_MASK = (1 << 32) - 1
WORD_LIMIT = 1 << 26
MAX_UPDATE_DOCS = 4096
MAX_UPDATE_WORDS = 4096
MAX_QUERY_WORDS = 512
MAX_POSTING_VISITS = 200_000


def _doc_id(value: object) -> int:
    if type(value) is not int or not 0 <= value <= DOC_MASK:
        raise ValueError("Document ID must be uint32")
    return value


def _word(value: object) -> int:
    if type(value) is not int or not 0 <= value < WORD_LIMIT:
        raise ValueError("Visual word must be a 26-bit nonnegative integer")
    return value


def normalize_updates(
    updates: Mapping[int, Iterable[int] | None],
    *,
    max_documents: int = MAX_UPDATE_DOCS,
) -> dict[int, frozenset[int] | None]:
    """Validate bounded replacement fingerprints and explicit tombstones."""
    if type(max_documents) is not int or not 1 <= max_documents <= MAX_UPDATE_DOCS:
        raise ValueError("Invalid update-document budget")
    if not isinstance(updates, Mapping) or len(updates) > max_documents:
        raise ValueError("Incremental overlay exceeds document budget")
    result: dict[int, frozenset[int] | None] = {}
    for photo_id, values in updates.items():
        doc = _doc_id(photo_id)
        if values is None:
            result[doc] = None
            continue
        if isinstance(values, (str, bytes, bytearray)):
            raise ValueError("Expected iterable of numeric visual words")
        words = set()
        try:
            source = iter(values)
        except TypeError as exc:
            raise ValueError("Expected iterable of numeric visual words") from exc
        for value in source:
            words.add(_word(value))
            if len(words) > MAX_UPDATE_WORDS:
                raise ValueError("Updated photo exceeds visual-word budget")
        result[doc] = frozenset(words)
    return result


def combine_layers(
    *layers: Mapping[int, Iterable[int] | None],
    max_documents: int = MAX_UPDATE_DOCS,
) -> dict[int, frozenset[int] | None]:
    """Overlay oldest to newest; last replacement or tombstone wins."""
    combined: dict[int, frozenset[int] | None] = {}
    for layer in layers:
        normalized = normalize_updates(layer, max_documents=max_documents)
        combined.update(normalized)
        if len(combined) > max_documents:
            raise ValueError("Combined overlay exceeds document budget")
    return combined


def shortlist_with_updates(
    packed: Sequence[int],
    query_words: Iterable[int],
    updates: Mapping[int, Iterable[int] | None],
    *,
    top_k: int = 20,
    min_shared_words: int = 2,
    max_posting_visits: int = MAX_POSTING_VISITS,
) -> list[tuple[int, int]]:
    """Shortlist with replacement/tombstone precedence over old postings.

    packed MUST be an independently verified, sorted-unique uint64 snapshot.
    This function neither authenticates nor changes packed.
    """
    if type(top_k) is not int or not 1 <= top_k <= 100:
        raise ValueError("top_k must be 1..100")
    if type(min_shared_words) is not int or not 1 <= min_shared_words <= MAX_QUERY_WORDS:
        raise ValueError("Invalid minimum matching word count")
    if type(max_posting_visits) is not int or not 1 <= max_posting_visits <= MAX_POSTING_VISITS:
        raise ValueError("Invalid posting scan budget")
    if isinstance(query_words, (str, bytes, bytearray)):
        raise ValueError("Expected numeric query visual words")
    query = set()
    for value in query_words:
        query.add(_word(value))
        if len(query) > MAX_QUERY_WORDS:
            raise ValueError("Too many query words")
    if not query:
        return []
    changed = normalize_updates(updates)
    result = Counter()
    total_visits = 0
    for word in sorted(query):
        start = bisect_left(packed, word << 32)
        end = bisect_right(packed, (word << 32) | DOC_MASK)
        if end - start + total_visits > max_posting_visits:
            raise ValueError("Posting scan budget exhausted; shrink scope or rebuild index")
        total_visits += end - start
        for index in range(start, end):
            photo = int(packed[index]) & DOC_MASK
            if photo not in changed:
                result[photo] += 1
    for photo, words in changed.items():
        if words is not None:
            count = len(words.intersection(query))
            if count:
                result[photo] += count
    return sorted(
        ((photo, hits) for photo, hits in result.items() if hits >= min_shared_words),
        key=lambda item: (-item[1], item[0]),
    )[:top_k]
