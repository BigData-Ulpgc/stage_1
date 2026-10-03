"""Main benchmark entry point — generates all 12 CSV files.

Usage:
    python -m src.main.bigdata.benchmark.benchmark_runner [book_datalake_path]

If book_datalake_path is provided, books are read from that datalake.
Otherwise, 200 synthetic books (300 KB each) are generated.

The 12 experiments:
    datalake_write, datalake_lookup, datalake_incremental,
    datalake_recovery, datalake_storage,
    metadata_insert, metadata_query,
    index_build, index_query, index_update, index_memory, index_disk
"""
from __future__ import annotations

import sys
import time
from pathlib import Path

# ================================================================== #
# PATH CONFIGURATION (aligned with SPEC.md and project statement)    #
# ================================================================== #
_PYTHON_ROOT = Path(__file__).resolve().parents[4]
_REPO_ROOT = Path(__file__).resolve().parents[6]

# --- Shared file paths ---
_SHARED_DIR = _REPO_ROOT / 'shared'

# --- CSV output (SPEC section 9) ---
_BENCHMARKS_DIR = _PYTHON_ROOT / 'benchmarks'
_RESULTS_DIR = _BENCHMARKS_DIR / 'results'

# --- Physical data output (SPEC sections 3 and 4) ---
_DATA_DIR = _BENCHMARKS_DIR / 'data'
_DATALAKE_DIR = _DATA_DIR / 'datalake'
_DATAMARTS_DIR = _DATA_DIR / 'datamarts'


def _load_queries() -> list[str]:
    """Load queries from shared/queries.txt."""
    queries_file = _SHARED_DIR / 'queries.txt'
    queries = []
    with open(queries_file, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith('#'):
                queries.append(line)
    return queries


def main() -> None:
    from . import books as benchmark_books
    from . import datalake_benchmark
    from . import index_benchmark
    from . import metadata_benchmark
    from ..datalake.book_based import BookBasedDatalake

    # Create only the directories required by the contract
    _RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    _DATALAKE_DIR.mkdir(parents=True, exist_ok=True)
    _DATAMARTS_DIR.mkdir(parents=True, exist_ok=True)

    print('=' * 70)
    print('  Python Benchmark Suite')
    print('  Generating 12 CSV files (SPEC section 9)')
    print('=' * 70)

    # ------------------------------------------------------------------ #
    # Load or generate books                                             #
    # ------------------------------------------------------------------ #
    if len(sys.argv) > 1:
        source_path = Path(sys.argv[1])
        print(f'\n  Loading books from datalake: {source_path}')
        raw_books = benchmark_books.from_datalake(BookBasedDatalake(source_path))
    else:
        print('\n  Generating 200 synthetic Zipf books ...')
        raw_books = benchmark_books.synthetic_zipf(
            count=200, tokens_per_book=5000, vocabulary_size=5000, seed=1)

    print(f'  Books ready: {len(raw_books)}')
    queries = _load_queries()
    print(f'  Queries: {len(queries)}')

    t0 = time.time()

    # ------------------------------------------------------------------ #
    # 1. Datalake benchmarks (5 CSVs) -> writes to data/datalake         #
    # ------------------------------------------------------------------ #
    print('\n--- Datalake Benchmarks (5 experiments) ---')
    datalake_benchmark.run_all(
        raw_books,
        work_dir=_DATALAKE_DIR,
        results_dir=_RESULTS_DIR,
    )

    # ------------------------------------------------------------------ #
    # 2. Metadata benchmarks (2 CSVs) -> writes to data/datamarts        #
    # ------------------------------------------------------------------ #
    print('\n--- Metadata Benchmarks (2 experiments) ---')
    metadata_benchmark.run_all(
        work_dir=_DATAMARTS_DIR,
        results_dir=_RESULTS_DIR,
        sizes=[1000, 10000, 100000],
    )

    # ------------------------------------------------------------------ #
    # 3. Index benchmarks (5 CSVs) -> writes to data/datamarts           #
    # ------------------------------------------------------------------ #
    print('\n--- Index Benchmarks (5 experiments) ---')
    index_sizes = [50, 100, 200] if len(raw_books) >= 200 else [len(raw_books)]
    index_benchmark.run_all(
        raw_books,
        work_dir=_DATAMARTS_DIR,
        results_dir=_RESULTS_DIR,
        queries=queries,
        sizes=index_sizes,
    )

    # ------------------------------------------------------------------ #
    # Summary                                                            #
    # ------------------------------------------------------------------ #
    elapsed = time.time() - t0
    print(f'\n{"=" * 70}')
    print(f'  All 12 CSVs generated in {elapsed:.1f}s')
    print(f'  Results directory: {_RESULTS_DIR}')
    print()
    for csv_file in sorted(_RESULTS_DIR.glob('python_*.csv')):
        line_count = sum(1 for _ in open(csv_file)) - 1  # minus header
        print(f'    {csv_file.name:.<45} {line_count:>5} data rows')
    print('=' * 70)


if __name__ == '__main__':
    main()