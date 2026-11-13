"""SQLite inverted-feature index for offline, non-destructive candidate retrieval.

A bounded set of 24-bit ORB descriptor visual words is indexed; feature-word
hits produce a shortlist for the geometric matcher, not verified lineage.
Cost is O(number of descriptor posting matches), not all photograph pairs.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
from contextlib import closing
import os
from pathlib import Path
import sqlite3
import stat
from urllib.parse import quote

import cv2
import numpy as np

from .matcher import compare_files, read_grayscale
from .bounded_posting_query import (DEFAULT_POSTING_VISITS, DEFAULT_DISTINCT_DOCS,
                                    PostingBudgetExceeded)

SCHEMA_VERSION = 1
FINGERPRINT_VERSION = 'orb-96-4x24-v1-800px'
MAX_SIDE = 800
MAX_DIRECT_WORD_SIDE = 2048
MAX_DIRECT_WORD_PIXELS = 4_000_000
MIN_WORD_SIDE = 48
KEYPOINTS = 96
# Four independent 24-bit descriptor projections with embedded band ID.
BANDS = ((0, 3, 7), (7, 13, 19), (11, 17, 23), (15, 25, 31))
EXTENSIONS = {'.jpg', '.jpeg', '.png', '.webp', '.tif', '.tiff', '.bmp'}


@dataclass(frozen=True)
class ShortlistHit:
    path: str
    shared_visual_words: int
    query_words: int
    indexed_words: int

    def json(self):
        return asdict(self)


def words_for_image(gray: np.ndarray) -> set[int]:
    """Bound direct grayscale arrays before ORB, not only file-based callers."""
    if (type(gray) is not np.ndarray or gray.ndim != 2
            or gray.dtype != np.uint8
            or gray.shape[0] < MIN_WORD_SIDE or gray.shape[1] < MIN_WORD_SIDE
            or gray.shape[0] > MAX_DIRECT_WORD_SIDE
            or gray.shape[1] > MAX_DIRECT_WORD_SIDE
            or gray.size > MAX_DIRECT_WORD_PIXELS):
        raise ValueError("Expected bounded 2D uint8 grayscale visual-word input")
    orb = cv2.ORB_create(nfeatures=900, fastThreshold=10)
    keypoints, desc = orb.detectAndCompute(gray, None)
    if desc is None:
        return set()
    # Cap per-image postings. This can miss highly cropped/untextured photos.
    selected = np.argsort([-kp.response for kp in keypoints])[:KEYPOINTS]
    desc = desc[selected]
    words = set()
    for band, (x, y, z) in enumerate(BANDS):
        for d in desc:
            words.add((band << 24) | (int(d[x]) << 16) | (int(d[y]) << 8) | int(d[z]))
    return words


def open_db(path: str | Path, write: bool = False):
    path = Path(path)
    if path.is_symlink():
        raise ValueError('Database symlink refused')
    if not path.parent.is_dir():
        raise ValueError('Database parent must already exist')
    if not write and not path.is_file():
        raise ValueError('Database not found; index first')
    connection = sqlite3.connect(str(path)) if write else sqlite3.connect(f'file:{quote(str(path))}?mode=ro', uri=True)
    try:
        version = connection.execute('PRAGMA user_version').fetchone()[0]
        if version not in (SCHEMA_VERSION, 0):
            raise ValueError(f'Unknown cache schema {version}; choose a new database')
        if not write and version != SCHEMA_VERSION:
            raise ValueError('Index schema missing')
        if version == 0:
            existing = connection.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall()
            if existing:
                raise ValueError('Refusing to initialize a nonempty, unrecognized SQLite database')
            connection.executescript("""
                CREATE TABLE photos(id INTEGER PRIMARY KEY, path TEXT UNIQUE NOT NULL,
                                    size INTEGER NOT NULL, mtime_ns INTEGER NOT NULL,
                                    word_count INTEGER NOT NULL);
                CREATE TABLE words(word INTEGER NOT NULL, photo_id INTEGER NOT NULL,
                                   PRIMARY KEY(word, photo_id)) WITHOUT ROWID;
                CREATE INDEX words_by_photo ON words(photo_id);
                PRAGMA user_version=1;
            """)
            connection.commit()
        else:
            try:
                stored = connection.execute("SELECT value FROM metadata WHERE key='fingerprint'").fetchone()
            except sqlite3.DatabaseError as exc:
                raise ValueError('Cache metadata missing or invalid') from exc
            if stored is None or stored[0] != FINGERPRINT_VERSION:
                raise ValueError('Fingerprint version mismatch: rebuild a separate local cache')
        return connection
    except Exception:
        connection.close()
        raise


def walk_images(root: Path):
    if root.is_symlink() or not root.is_dir():
        raise ValueError('Source must be a real non-symlink folder')
    for directory, dirs, files in os.walk(root, followlinks=False):
        dirs[:] = sorted(d for d in dirs if not (Path(directory) / d).is_symlink())
        for name in sorted(files):
            p = Path(directory) / name
            if p.suffix.lower() in EXTENSIONS and not p.is_symlink() and p.is_file():
                yield p


def index_folder(root: str | Path, db_path: str | Path, limit: int = 200) -> dict:
    if not 1 <= limit <= 100000:
        raise ValueError('limit must be 1..100000')
    if Path(root).is_symlink() or Path(db_path).is_symlink():
        raise ValueError('Symlink source or database refused')
    root = Path(root).resolve()
    db_path = Path(db_path).resolve(strict=False)
    if root == db_path or root in db_path.parents:
        raise ValueError('Place cache database OUTSIDE source photo folder')
    seen = indexed = unchanged = 0
    errors = []
    with closing(open_db(db_path, write=True)) as conn:
        # Refuse mixed versions of a fingerprint algorithm in the same cache.
        conn.execute('CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL)')
        previous = conn.execute("SELECT value FROM metadata WHERE key='fingerprint'").fetchone()
        if previous is not None and previous[0] != FINGERPRINT_VERSION:
            raise ValueError('Fingerprint configuration mismatch; use another database')
        conn.execute("INSERT OR IGNORE INTO metadata VALUES ('fingerprint',?)", (FINGERPRINT_VERSION,))
        conn.commit()
        for path in walk_images(root):
            seen += 1
            conn.execute("SAVEPOINT photo")
            try:
                source_before = path.lstat()
                if not stat.S_ISREG(source_before.st_mode):
                    raise ValueError("Index source changed into a non-regular file")
                old = conn.execute('SELECT id,size,mtime_ns FROM photos WHERE path=?', (str(path),)).fetchone()
                if old is not None and (old[1], old[2]) == (source_before.st_size, source_before.st_mtime_ns):
                    unchanged += 1
                    conn.execute("RELEASE photo")
                    continue
                if indexed >= limit:
                    conn.execute("RELEASE photo")
                    break
                gray = read_grayscale(path, MAX_SIDE)
                # Reject an atomic rename, in-place write, symlink swap or
                # metadata replacement while the image was being decoded.
                # Descriptor anchoring lives in matcher.read_grayscale;
                # the double lstat prevents mixing OLD metadata with NEW
                # descriptors in our locally rebuildable SQLite cache.
                source_after = path.lstat()
                signature = lambda st: (st.st_dev, st.st_ino, st.st_size,
                                        st.st_mtime_ns, st.st_ctime_ns)
                if (not stat.S_ISREG(source_after.st_mode)
                        or signature(source_before) != signature(source_after)):
                    raise ValueError("Source changed during fingerprint extraction; retry indexing")
                terms = words_for_image(gray)
                if old is None:
                    cur = conn.execute('INSERT INTO photos(path,size,mtime_ns,word_count) VALUES (?,?,?,?)',
                                       (str(path), source_after.st_size, source_after.st_mtime_ns, len(terms)))
                    photo_id = cur.lastrowid
                else:
                    photo_id = old[0]
                    conn.execute('DELETE FROM words WHERE photo_id=?', (photo_id,))
                    conn.execute('UPDATE photos SET size=?,mtime_ns=?,word_count=? WHERE id=?',
                                 (source_after.st_size, source_after.st_mtime_ns, len(terms), photo_id))
                conn.executemany('INSERT INTO words(word,photo_id) VALUES (?,?)',
                                 ((word, photo_id) for word in terms))
                conn.execute("RELEASE photo")
                indexed += 1
                if indexed % 25 == 0:
                    conn.commit()
            except (ValueError, OSError, sqlite3.DatabaseError) as exc:
                conn.execute("ROLLBACK TO photo")
                conn.execute("RELEASE photo")
                errors.append({'path': str(path), 'error': str(exc)})
        conn.commit()
        cached = conn.execute('SELECT COUNT(*) FROM photos').fetchone()[0]
        postings = conn.execute('SELECT COUNT(*) FROM words').fetchone()[0]
    return {'policy': 'local cache only; original photos read-only', 'seen': seen,
            'new_or_updated': indexed, 'unchanged': unchanged, 'total_cached': cached,
            'feature_postings': postings, 'errors': errors,
            'may_have_more': indexed >= limit}


def shortlist(query: str | Path, db_path: str | Path, *, top_k: int = 30,
              max_posting_visits: int = DEFAULT_POSTING_VISITS,
              max_distinct_documents: int = DEFAULT_DISTINCT_DOCS) -> list[ShortlistHit]:
    if type(top_k) is not int or not 1 <= top_k <= 100:
        raise ValueError('top_k must be 1..100')
    if (type(max_posting_visits) is not int or
            not 1 <= max_posting_visits <= 10_000_000):
        raise ValueError('Posting budget must be integer 1..10000000')
    if (type(max_distinct_documents) is not int or
            not 1 <= max_distinct_documents <= 1_000_000):
        raise ValueError('Candidate-document budget must be integer 1..1000000')
    if Path(query).is_symlink():
        raise ValueError("Query symlink refused")
    query = Path(query).resolve()
    terms = sorted(words_for_image(read_grayscale(query, MAX_SIDE)))
    if not terms:
        return []
    params = ','.join('?' for _ in terms)
    # A top-k pre-limit *before* filesystem validation is unsafe: stale image
    # paths could occupy every initial slot and conceal surviving relatives.
    # Page over a stable score/path ordering until k valid hits are found.
    # Hard cap guards against unbounded IO with a severely stale index;
    # exceeding the cap raises an error instead of implying no relatives.
    statement = f'''SELECT p.path, COUNT(*) AS hits, p.word_count, p.size, p.mtime_ns FROM words AS w
        JOIN photos AS p ON p.id=w.photo_id WHERE w.word IN ({params})
        AND p.path != ? GROUP BY w.photo_id ORDER BY hits DESC, p.path ASC LIMIT ? OFFSET ?'''
    page_size = 128
    max_examined = 4096
    examined = 0
    results = []
    with closing(open_db(db_path)) as conn:
        # Explicit read transaction pins a single consistent SQLite snapshot
        # across cheap bounded preflight and the actual score/page query. This
        # prevents concurrent index updates from bypassing the visit budget.
        conn.execute("BEGIN")
        # LIMIT is applied INSIDE the counted subquery: do not aggregate every
        # matching row in a hot-word query just to learn it is unsafe.
        visit_count = conn.execute(
            f"SELECT COUNT(*) FROM (SELECT 1 FROM words "
            f"WHERE word IN ({params}) LIMIT ?)",
            (*terms, max_posting_visits + 1),
        ).fetchone()[0]
        if visit_count > max_posting_visits:
            raise PostingBudgetExceeded(
                "posting_visits",
                "SQLite shortlist posting budget exceeded; search incomplete, "
                "abstain instead of returning empty candidate results",
            )
        # Posting count alone cannot bound score/grouping cardinality. A
        # common query word may occur in 100k distinct photos even when
        # the total visits fit. Limit the grouped DISTINCT document IDs before
        # ranking. This is the same read snapshot as the visit preflight.
        distinct_count = conn.execute(
            f"SELECT COUNT(*) FROM (SELECT photo_id FROM words "
            f"WHERE word IN ({params}) GROUP BY photo_id LIMIT ?)",
            (*terms, max_distinct_documents + 1),
        ).fetchone()[0]
        if distinct_count > max_distinct_documents:
            raise PostingBudgetExceeded(
                "distinct_documents",
                "SQLite shortlist candidate-document budget exceeded; "
                "search incomplete, abstain rather than returning empty results",
            )
        while len(results) < top_k:
            rows = conn.execute(statement, (*terms, str(query), page_size, examined)).fetchall()
            for path, hits, count, size, mtime_ns in rows:
                if hits < 2:
                    return results  # All remaining hits have at most one word.
                p = Path(path)
                if p.is_symlink() or not p.is_file():
                    continue
                try:
                    stat = p.stat()
                except OSError:
                    continue  # Files may disappear between is_file and stat.
                if (stat.st_size, stat.st_mtime_ns) != (size, mtime_ns):
                    continue
                results.append(ShortlistHit(path, hits, len(terms), count))
                if len(results) >= top_k:
                    break
            examined += len(rows)
            if len(rows) < page_size:
                break
            if examined >= max_examined and len(results) < top_k:
                raise ValueError('Too many stale/ambiguous index entries; refresh local index and retry')
    return results


def review_shortlist(query: str | Path, db_path: str | Path, top_k: int = 15,
                     algorithm: str = 'orb', *,
                     max_posting_visits: int = DEFAULT_POSTING_VISITS,
                     max_distinct_documents: int = DEFAULT_DISTINCT_DOCS) -> dict:
    hits = shortlist(query, db_path, top_k=top_k,
                     max_posting_visits=max_posting_visits,
                     max_distinct_documents=max_distinct_documents)
    edges, abstained, errors = [], 0, []
    for hit in hits:
        try:
            evidence = compare_files(query, hit.path, algorithm=algorithm)
            if evidence.verdict in {'candidate', 'review'}:
                edges.append({'retrieval': hit.json(), 'evidence': evidence.json()})
            else:
                abstained += 1
        except ValueError as exc:
            errors.append({'path': hit.path, 'error': str(exc)})
    return {'policy': 'candidate edges for human review, never auto merge or delete',
            'candidates_checked': len(hits), 'candidate_edges': edges,
            'abstained': abstained, 'errors': errors}


def audit_cache(db_path: str | Path, *, apply: bool = False, max_entries: int = 50000) -> dict:
    """Inventory stale fingerprint records; explicitly requested apply edits CACHE ONLY.

    Missing and modified files are removed from the *rebuildable index* only;
    no photos are ever modified. Permission errors are never classified as
    missing: conservatively leave their cache records for later inspection.
    """
    if not 1 <= max_entries <= 1000000:
        raise ValueError('max_entries must be 1..1000000')
    db_path = Path(db_path)
    if db_path.is_symlink() or not db_path.is_file():
        raise ValueError('Existing local index database required for audit')
    stale_ids = []
    examples = []
    uninspected = 0
    with closing(open_db(db_path, write=apply)) as conn:
        rows = conn.execute('SELECT id,path,size,mtime_ns FROM photos ORDER BY id LIMIT ?',
                            (max_entries + 1,)).fetchall()
        if len(rows) > max_entries:
            raise ValueError('Cache exceeds audit limit; explicitly raise max_entries to cover all records')
        for photo_id, location, expected_size, expected_mtime in rows:
            p = Path(location)
            try:
                st = p.lstat()  # Do not follow symlink replacements.
            except FileNotFoundError:
                reason = 'missing'
            except OSError:
                uninspected += 1  # Unreadable medium != safely missing.
                continue
            else:
                if not stat.S_ISREG(st.st_mode):
                    reason = 'no-longer-regular-file'
                elif (st.st_size, st.st_mtime_ns) != (expected_size, expected_mtime):
                    reason = 'file-changed-since-indexing'
                else:
                    continue
            stale_ids.append(photo_id)
            if len(examples) < 10:
                examples.append({'path': location, 'reason': reason})
        if apply:
            # Only local descriptor postings and cache bookkeeping are removed.
            conn.executemany('DELETE FROM words WHERE photo_id=?',
                             ((photo_id,) for photo_id in stale_ids))
            conn.executemany('DELETE FROM photos WHERE id=?',
                             ((photo_id,) for photo_id in stale_ids))
            conn.commit()
    return {'policy': 'cache metadata only; no images altered', 'mode': 'apply' if apply else 'dry-run',
            'examined': len(rows), 'stale': len(stale_ids), 'uninspected': uninspected,
            'removed_from_cache': len(stale_ids) if apply else 0, 'examples': examples}
