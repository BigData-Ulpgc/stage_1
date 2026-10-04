"""Datalake benchmark suite — 5 experiments for the 3 datalake structures.

Experiments: datalake_write, datalake_lookup, datalake_incremental,
             datalake_recovery, datalake_storage
"""
from __future__ import annotations

import os
import shutil
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Callable, List, Dict

from ..models import RawBook, BookLocation
from ..datalake.base import Datalake
from ..datalake.book_based import BookBasedDatalake
from ..datalake.range_based import RangeBasedDatalake
from ..datalake.time_based import TimeBasedDatalake
from .runner import measure, derived_rows, single_row, BenchmarkRow, JavaRandom, java_shuffle
from .csv_results import write_experiment


# ------------------------------------------------------------------ #
# Simulated clock for time-based datalake (10 books per hour)        #
# ------------------------------------------------------------------ #

class SimulatedClock:
    """Deterministic clock: advances a fixed step each call."""

    def __init__(self, start: datetime = None, step: timedelta = None):
        self._current = start or datetime(2026, 1, 1, tzinfo=timezone.utc)
        self._step = step or timedelta(minutes=6)

    def __call__(self) -> datetime:
        now = self._current
        self._current += self._step
        return now

    def reset(self):
        self._current = datetime(2026, 1, 1, tzinfo=timezone.utc)


# ------------------------------------------------------------------ #
# Structure factories                                                 #
# ------------------------------------------------------------------ #

def _make_structures(work_dir: Path) -> List[tuple]:
    """Return [(name, factory_fn)] where factory_fn(dir) -> Datalake."""
    return [
        ('book', lambda d: BookBasedDatalake(d)),
        ('range', lambda d: RangeBasedDatalake(d)),
        ('time', lambda d: TimeBasedDatalake(d, clock=SimulatedClock())),
    ]


def _fresh_datalake(name: str, directory: Path) -> Datalake:
    """Delete directory and create a fresh datalake."""
    if directory.exists():
        shutil.rmtree(directory)
    if name == 'book':
        return BookBasedDatalake(directory)
    elif name == 'range':
        return RangeBasedDatalake(directory)
    elif name == 'time':
        return TimeBasedDatalake(directory, clock=SimulatedClock())
    raise ValueError(f'Unknown structure: {name}')


# ------------------------------------------------------------------ #
# Helpers                                                             #
# ------------------------------------------------------------------ #

def _delete_dir(d: Path):
    if d.exists():
        shutil.rmtree(d)


def _shuffled_ids(books: List[RawBook]) -> List[int]:
    ids = [b.id for b in books]
    ids.sort()
    java_shuffle(ids, JavaRandom(42))
    return ids


def _every_tenth(books: List[RawBook]) -> List[RawBook]:
    return [books[i] for i in range(0, len(books), 10)]


def _ids_of(books: List[RawBook]) -> set:
    return {b.id for b in books}


# ------------------------------------------------------------------ #
# datalake_storage helpers                                            #
# ------------------------------------------------------------------ #

def _datalake_stats(root: Path) -> dict:
    """Count files, directories, max entries per dir, and bytes."""
    files = dirs = max_entries = total_bytes = 0
    if not root.exists():
        return {'files': 0, 'directories': 0, 'max_entries_per_dir': 0, 'bytes': 0}
    for dirpath, dirnames, filenames in os.walk(str(root)):
        dirs += 1
        entries = len(dirnames) + len(filenames)
        if entries > max_entries:
            max_entries = entries
        for fname in filenames:
            fpath = os.path.join(dirpath, fname)
            files += 1
            total_bytes += os.path.getsize(fpath)
    dirs -= 1  # don't count root itself as a subdirectory
    return {'files': files, 'directories': dirs, 'max_entries_per_dir': max_entries, 'bytes': total_bytes}


def _allocated_bytes(root: Path) -> int:
    """Estimate allocated bytes (round up to block size)."""
    try:
        import ctypes
        # On Windows, get cluster size
        block_size = 4096  # default
    except Exception:
        block_size = 4096
    total = 0
    for dirpath, dirnames, filenames in os.walk(str(root)):
        total += block_size  # directory entry
        for fname in filenames:
            fpath = os.path.join(dirpath, fname)
            try:
                size = os.path.getsize(fpath)
                total += ((size + block_size - 1) // block_size) * block_size
            except OSError:
                pass
    return total


# ================================================================== #
# Experiment 1: datalake_write                                        #
# ================================================================== #

def write(books: List[RawBook], work_dir: Path) -> List[BenchmarkRow]:
    rows = []
    n = len(books)
    for struct_name in ('book', 'range', 'time'):
        d = work_dir / 'write' / struct_name
        dl_holder = [None]

        def setup(sn=struct_name, dd=d):
            dl_holder[0] = _fresh_datalake(sn, dd)

        def task():
            dl = dl_holder[0]
            for book in books:
                dl.save(book)

        elapsed = measure('datalake_write', struct_name, n, setup=setup, task=task)
        rows.extend(elapsed)
        rows.extend(derived_rows(elapsed, 'throughput', 'books_per_s',
                                 lambda ms, nn=n: nn / (ms / 1000.0)))
    return rows


# ================================================================== #
# Experiment 2: datalake_lookup                                       #
# ================================================================== #

def lookup(books: List[RawBook], work_dir: Path) -> List[BenchmarkRow]:
    ids = _shuffled_ids(books)
    rows = []
    n = len(books)
    for struct_name in ('book', 'range', 'time'):
        d = work_dir / 'lookup' / struct_name
        dl = _fresh_datalake(struct_name, d)
        for book in books:
            dl.save(book)

        found_count = [0]

        def setup():
            found_count[0] = 0

        def task():
            for bid in ids:
                loc = dl.locate(bid)
                if loc is not None:
                    found_count[0] += 1

        elapsed = measure('datalake_lookup', struct_name, n, setup=setup, task=task)
        if found_count[0] != len(ids):
            raise RuntimeError(f'{struct_name}: locate did not find every book')
        rows.extend(elapsed)
        rows.extend(derived_rows(elapsed, 'per_lookup', 'us',
                                 lambda ms, nn=len(ids): ms * 1000.0 / nn))
    return rows


# ================================================================== #
# Experiment 3: datalake_incremental                                  #
# ================================================================== #

def incremental(books: List[RawBook], work_dir: Path) -> List[BenchmarkRow]:
    n = len(books)
    new_count = max(1, n // 10)
    known = books[:n - new_count]
    fresh = books[n - new_count:]
    known_ids = _ids_of(known)

    rows = []
    for struct_name in ('book', 'range', 'time'):
        d = work_dir / 'incremental' / struct_name
        dl_holder = [None]
        detected = [set()]

        def setup(sn=struct_name, dd=d):
            dl_holder[0] = _fresh_datalake(sn, dd)
            for book in known:
                dl_holder[0].save(book)
            for book in fresh:
                dl_holder[0].save(book)

        def task():
            all_ids = set(dl_holder[0].list_book_ids())
            detected[0] = all_ids - known_ids

        elapsed = measure('datalake_incremental', struct_name, n, setup=setup, task=task)
        if detected[0] != _ids_of(fresh):
            raise RuntimeError(f'{struct_name}: did not detect exactly the new books')
        rows.extend(elapsed)
        rows.extend(derived_rows(elapsed, 'detected', 'books',
                                 lambda ms, d=detected: len(d[0])))
    return rows


# ================================================================== #
# Experiment 4: datalake_recovery                                     #
# ================================================================== #

def recovery(books: List[RawBook], work_dir: Path) -> List[BenchmarkRow]:
    damaged = _every_tenth(books)
    expected_ids = _ids_of(books)
    n = len(books)

    rows = []
    for struct_name in ('book', 'range', 'time'):
        d = work_dir / 'recovery' / struct_name
        dl_holder = [None]
        recovered_count = [0]

        def setup(sn=struct_name, dd=d):
            dl_holder[0] = _fresh_datalake(sn, dd)
            for book in books:
                dl_holder[0].save(book)
            # Simulate crash: rename body files to .tmp
            for book in damaged:
                loc = dl_holder[0].locate(book.id)
                if loc:
                    body = loc.body_path
                    tmp = body.with_suffix(body.suffix + '.tmp')
                    if body.exists():
                        body.rename(tmp)
            recovered_count[0] = 0

        def task():
            dl = dl_holder[0]
            present = set(dl.list_book_ids())
            for book in books:
                if book.id not in present:
                    dl.save(book)
                    recovered_count[0] += 1

        elapsed = measure('datalake_recovery', struct_name, n, setup=setup, task=task)
        # Check on the final state (all repetitions are identical), as Java does
        listed = set(dl_holder[0].list_book_ids())
        lost = len(expected_ids - listed)
        duplicates = sum(1 for p in d.rglob('*')
                         if p.is_file() and p.name.endswith('body.txt')) - n  # extra complete bodies
        if recovered_count[0] != len(damaged):
            raise RuntimeError(f'{struct_name}: did not recover every damaged book')
        if lost != 0 or duplicates != 0:
            raise RuntimeError(f'{struct_name}: recovery left lost or duplicated books')
        rows.extend(elapsed)
        rows.append(single_row('datalake_recovery', struct_name, n,
                               'recovered', recovered_count[0], 'books'))
        rows.append(single_row('datalake_recovery', struct_name, n,
                               'lost', lost, 'books'))
        rows.append(single_row('datalake_recovery', struct_name, n,
                               'duplicates', duplicates, 'books'))
    return rows


# ================================================================== #
# Experiment 5: datalake_storage                                      #
# ================================================================== #

def storage(books: List[RawBook], work_dir: Path) -> List[BenchmarkRow]:
    n = len(books)
    rows = []
    for struct_name in ('book', 'range', 'time'):
        d = work_dir / 'storage' / struct_name
        dl = _fresh_datalake(struct_name, d)
        for book in books:
            dl.save(book)

        stats = _datalake_stats(d)
        alloc = _allocated_bytes(d)

        rows.append(single_row('datalake_storage', struct_name, n, 'files', stats['files'], 'count'))
        rows.append(single_row('datalake_storage', struct_name, n, 'directories', stats['directories'], 'count'))
        rows.append(single_row('datalake_storage', struct_name, n, 'max_entries_per_dir', stats['max_entries_per_dir'], 'count'))
        rows.append(single_row('datalake_storage', struct_name, n, 'bytes', stats['bytes'], 'bytes'))
        rows.append(single_row('datalake_storage', struct_name, n, 'allocated_bytes', alloc, 'bytes'))
    return rows


# ================================================================== #
# Run all 5 and write CSVs                                            #
# ================================================================== #

def run_all(books: List[RawBook], work_dir: Path, results_dir: Path) -> Dict[str, List[BenchmarkRow]]:
    """Run all 5 datalake experiments and write CSVs."""
    results = {}
    print('  [datalake] write ...')
    results['datalake_write'] = write(books, work_dir)
    print('  [datalake] lookup ...')
    results['datalake_lookup'] = lookup(books, work_dir)
    print('  [datalake] incremental ...')
    results['datalake_incremental'] = incremental(books, work_dir)
    print('  [datalake] recovery ...')
    results['datalake_recovery'] = recovery(books, work_dir)
    print('  [datalake] storage ...')
    results['datalake_storage'] = storage(books, work_dir)

    for experiment, rows in results.items():
        write_experiment(results_dir, experiment, rows)
        print(f'    -> {experiment}: {len(rows)} rows')

    return results
