"""Checks the Python benchmark CSVs against the exact values of SPEC section 10.

Usage (from python/stage_1/):
    python -m src.main.bigdata.benchmark.verify_results               -> results/real + results/synthetic
    python -m src.main.bigdata.benchmark.verify_results --real-only   -> results/real only

It checks the counts that must be identical in every language (storage, recovery, incremental,
index terms/postings and disk bytes for the 200 books of shared/book_ids.txt) and that every
experiment has its 5 measured runs. Times are not checked: they are not expected to match.

Prints one OK/FAIL line per check, then "FAILURES: n", and exits with code 1 if n > 0.
"""
from __future__ import annotations

import csv
import sys
from pathlib import Path

_RESULTS_DIR = Path(__file__).resolve().parents[4] / 'benchmarks' / 'results'

failures = 0


def check(name: str, got, expected) -> None:
    global failures
    ok = got == expected
    failures += not ok
    print(f"{'OK  ' if ok else 'FAIL'} {name}: {got} (expected {expected})")


def load(mode: str, experiment: str) -> list[dict]:
    """Rows of results/<mode>/python_<experiment>.csv; a missing file or CRLF counts as a failure."""
    path = _RESULTS_DIR / mode / f'python_{experiment}.csv'
    if not path.is_file():
        check(f'{path.relative_to(_RESULTS_DIR)} exists', False, True)
        return []
    raw = path.read_bytes()
    if b'\r' in raw:
        check(f'{path.relative_to(_RESULTS_DIR)} has CRLF', True, False)
    return list(csv.DictReader(raw.decode('utf-8').splitlines()))


def vals(rows: list[dict], structure: str, n: int, metric: str) -> list[float]:
    return [float(r['value']) for r in rows
            if r['structure'] == structure and int(r['dataset_size']) == n and r['metric'] == metric]


def verify_real() -> None:
    storage = load('real', 'datalake_storage')
    expected = {'book': (400, 200, 200, 130883584),
                'range': (400, 47, 220, 130256896),
                'time': (400, 21, 20, 130150400)}
    for s, (files, dirs, max_entries, allocated) in expected.items():
        check(f'storage {s} bytes', vals(storage, s, 200, 'bytes'), [128980349])
        check(f'storage {s} files', vals(storage, s, 200, 'files'), [files])
        check(f'storage {s} directories', vals(storage, s, 200, 'directories'), [dirs])
        check(f'storage {s} max_entries_per_dir', vals(storage, s, 200, 'max_entries_per_dir'), [max_entries])
        check(f'storage {s} allocated_bytes', vals(storage, s, 200, 'allocated_bytes'), [allocated])

    recovery = load('real', 'datalake_recovery')
    incremental = load('real', 'datalake_incremental')
    timed = {e: load('real', e) for e in
             ('datalake_write', 'datalake_lookup', 'datalake_incremental', 'datalake_recovery')}
    for s in ('book', 'range', 'time'):
        check(f'recovery {s}', [vals(recovery, s, 200, m) for m in ('recovered', 'lost', 'duplicates')],
              [[20], [0], [0]])
        check(f'incremental {s} detected', vals(incremental, s, 200, 'detected'), [20] * 5)
        for experiment, rows in timed.items():
            check(f'{experiment} {s} runs', len(vals(rows, s, 200, 'elapsed')), 5)

    disk = load('real', 'index_disk')
    reference = {50: (58834, 360970), 100: (78820, 759087), 200: (129356, 1581064)}
    backends = ('monolithic', 'hierarchical', 'mongo')
    for b in backends:
        for n, (terms, postings) in reference.items():
            check(f'index_disk {b} N={n} terms/postings',
                  vals(disk, b, n, 'terms') + vals(disk, b, n, 'postings'), [terms, postings])
    check('index_disk monolithic N=200 bytes', vals(disk, 'monolithic', 200, 'bytes'), [8893537])
    check('index_disk hierarchical N=200 bytes', vals(disk, 'hierarchical', 200, 'bytes'), [7251967])
    mongo_bytes = [vals(disk, 'mongo', n, 'bytes') for n in (50, 100, 200)]
    print('     mongo bytes (should grow, ~4/7/14 MB; Java N=200: 14,131,200):', mongo_bytes)

    for experiment in ('index_build', 'index_query', 'index_update'):
        rows = load('real', experiment)
        for b in backends:
            for n in (50, 100, 200):
                check(f'{experiment} {b} N={n} runs', len(vals(rows, b, n, 'elapsed')), 5)
    memory = load('real', 'index_memory')
    for b in backends:
        for n in (50, 100, 200):
            check(f'index_memory {b} N={n} rows',
                  len(vals(memory, b, n, 'heap_after_build')) + len(vals(memory, b, n, 'heap_after_open')), 2)


def verify_synthetic() -> None:
    """Synthetic metadata (SPEC 10.2), the only synthetic results compared across languages."""
    insert = load('synthetic', 'metadata_insert')
    query = load('synthetic', 'metadata_query')
    for s in ('sqlite', 'sqlite_no_index'):
        for n in (1000, 10000, 100000):
            check(f'metadata_insert {s} N={n} runs', len(vals(insert, s, n, 'elapsed')), 5)
            for m in ('find_by_id', 'find_by_author', 'find_by_title'):
                check(f'metadata_query {s} N={n} {m} runs', len(vals(query, s, n, m)), 5)


def main() -> int:
    print(f'Results directory: {_RESULTS_DIR}')
    print('---- real ----')
    verify_real()
    if '--real-only' not in sys.argv[1:]:
        print('---- synthetic ----')
        verify_synthetic()
    print(f'\nFAILURES: {failures}')
    return 1 if failures > 0 else 0


if __name__ == '__main__':
    sys.exit(main())
