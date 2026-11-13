#!/usr/bin/env python3
"""Strict, read-only V4 People BACKUP preflight and isolated restore rehearsal.

Owner-only opt-in command; NEVER point it at a live People database.
Reads a finalized online-backup file with a read-only SQLite URI. Restores
a COPY to a private temporary folder, verifies all user-table rows through
streaming hashes, and deletes the rehearsal copy on normal exit.

No real photo files are opened, no profile names/paths/embeddings are printed,
and the source database is never opened for writing.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import sys
import tempfile


class BackupGateError(Exception):
    """NO-GO without potentially sensitive raw SQLite exception details."""


REQUIRED_TABLES=frozenset({'images','faces','people','clusters'})
COUNT_TABLES=('images','faces','people','clusters','manual_review_undo',
              'person_covers','person_merges','person_merge_faces','events')
VALID_TABLE=re.compile(r'[A-Za-z_][A-Za-z0-9_]{0,63}\Z')


def readonly(path):
    # The gate already refuses any -wal/-shm companions. Immutable read-only
    # prevents *our own* inspection from creating SQLite WAL sidecars for a
    # finalized backup whose header retains the source's WAL journal mode.
    connection=sqlite3.connect(path.as_uri()+'?mode=ro&immutable=1',uri=True,timeout=10)
    connection.execute('PRAGMA query_only=ON')
    connection.execute('PRAGMA foreign_keys=ON')
    return connection


def user_tables(connection):
    names={row[0] for row in connection.execute(
        "SELECT name FROM sqlite_master WHERE type='table'")}
    names={name for name in names if not name.startswith('sqlite_')}
    if not REQUIRED_TABLES.issubset(names):
        raise BackupGateError('required V4 People tables are missing')
    if any(not VALID_TABLE.fullmatch(name) for name in names):
        raise BackupGateError('unexpected table name in backup')
    return tuple(sorted(names))


def validate(connection):
    check=connection.execute('PRAGMA integrity_check').fetchall()
    if check!=[('ok',)]:
        raise BackupGateError('SQLite integrity check failed')
    if connection.execute('PRAGMA foreign_key_check').fetchone() is not None:
        raise BackupGateError('SQLite foreign-key integrity check failed')
    tables=user_tables(connection)
    counts={name:connection.execute(f'SELECT COUNT(*) FROM "{name}"').fetchone()[0]
            for name in COUNT_TABLES if name in tables}
    if counts['images']<1 or counts['faces']<1:
        raise BackupGateError('backup contains no indexed photos/faces')
    return tables,counts


def logical_digest(connection,tables):
    """Compare every user table's contents without exposing their values.

    Field types + lengths frame every value unambiguously. Row order uses
    declared PK, falling back to rowid; no unbounded fetchall or file dump.
    """
    digest=hashlib.sha256()
    for name in tables:
        columns=connection.execute(f'PRAGMA table_info("{name}")').fetchall()
        if not columns:
            raise BackupGateError('table metadata unavailable')
        primary=[(int(col[5]),col[1]) for col in columns if col[5]]
        primary.sort()
        if primary:
            ordering=','.join('"' + x.replace('"','""') + '"' for _,x in primary)
        else:ordering='rowid'
        cursor=connection.execute(f'SELECT * FROM "{name}" ORDER BY {ordering}')
        digest.update(name.encode('utf-8')+b'\0')
        digest.update(str(len(columns)).encode('ascii')+b'\0')
        while True:
            rows=cursor.fetchmany(256)
            if not rows:break
            for row in rows:
                digest.update(b'R')
                for val in row:
                    if val is None:typ,payload=b'N',b''
                    elif isinstance(val,bytes):typ,payload=b'B',val
                    elif isinstance(val,int):typ,payload=b'I',str(val).encode('ascii')
                    elif isinstance(val,float):typ,payload=b'F',val.hex().encode('ascii')
                    elif isinstance(val,str):typ,payload=b'T',val.encode('utf-8')
                    else:raise BackupGateError('unexpected SQLite field type')
                    digest.update(typ)
                    digest.update(len(payload).to_bytes(8,'big'))
                    digest.update(payload)
        digest.update(b'E')
    return digest.digest()


def inspect_backup(input_path,max_bytes=512*1024*1024):
    path=Path(input_path).expanduser()
    if path.is_symlink() or not path.is_file():
        raise BackupGateError('provide a regular finalized SQLite backup file')
    path=path.resolve()
    live=Path.home()/'.monochrome_studio_v4'/'people.sqlite3'
    if path.name=='people.sqlite3' or (live.is_file() and os.path.samefile(path,live)):
        raise BackupGateError('refusing live-looking People database; use a named online backup')
    if any(path.with_name(path.name+suffix).exists()
           for suffix in ('-wal','-shm')):
        raise BackupGateError('backup has WAL/SHM sidecars; use a finalized online backup')
    size=path.stat().st_size
    if size<100 or size>max_bytes:
        raise BackupGateError('backup size outside allowed 100-byte to max-size range')
    with path.open('rb') as handle:
        if handle.read(16)!=b'SQLite format 3\x00':
            raise BackupGateError('input is not a SQLite database backup')
    source=readonly(path)
    try:
        tables,counts=validate(source)
        original_digest=logical_digest(source,tables)
        # A private 0700 temporary folder + sqlite created with 0600 mode.
        # Refuse if the disk cannot retain the backup AND a safety reserve.
        temp_root=Path(tempfile.gettempdir())
        free=shutil.disk_usage(temp_root).free
        if free<size*2+32*1024*1024:
            raise BackupGateError('not enough safe temporary disk capacity for rehearsal')
        with tempfile.TemporaryDirectory(prefix='atlas-v4-rehearsal-') as scratch:
            out=Path(scratch)/'recovered.sqlite3'
            fd=os.open(out,os.O_CREAT|os.O_EXCL|os.O_RDWR,0o600)
            os.close(fd)
            destination=sqlite3.connect(str(out))
            try:source.backup(destination,pages=256,sleep=0.025)
            finally:destination.close()
            recovered=readonly(out)
            try:
                restored_tables,restored_counts=validate(recovered)
                if (restored_tables!=tables or restored_counts!=counts or
                    logical_digest(recovered,restored_tables)!=original_digest):
                    raise BackupGateError('independent rehearsal does not match source rows')
            finally:recovered.close()
        return {'result':'PASS','source_read_only':True,
                'isolated_restore_rehearsed':True,'all_user_rows_equal':True,
                'checked_user_tables':len(tables),
                'counts':counts,'private_values_printed':False}
    finally:source.close()


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backup',required=True,
                        help='Path to finalized V4 online backup, NOT current People DB')
    parser.add_argument('--max-mib',type=int,default=512,
                        help='Hard input-size cap in MiB (default 512)')
    args=parser.parse_args(argv)
    if not 1<=args.max_mib<=2048:
        parser.error('--max-mib must be 1..2048')
    try:
        report=inspect_backup(args.backup,args.max_mib*1024**2)
    except (BackupGateError,sqlite3.Error,OSError,ValueError,OverflowError) as error:
        message=str(error) if isinstance(error,BackupGateError) else (
            'SQLite/file verification failed; no live data changed')
        print('NO-GO: '+message,file=sys.stderr)
        return 1
    print(json.dumps(report,sort_keys=True))
    return 0


if __name__=='__main__':
    sys.exit(main())
