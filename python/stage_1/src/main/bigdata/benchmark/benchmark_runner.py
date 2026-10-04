"""Main benchmark entry point — generates all 12 CSV files.

Usage:
    python -m src.main.bigdata.benchmark.benchmark_runner            -> synthetic mode
    python -m src.main.bigdata.benchmark.benchmark_runner <path>     -> real mode

Mode detection (CLI-based, unified with Java orchestrator):
    - No arguments   → mode = 'synthetic'  (200 Zipf books generated)
    - One argument    → mode = 'real'       (books loaded from datalake at <path>)

Fallback: if mode is 'real' but the source_path does not exist or is
empty, the mode silently falls back to 'synthetic' with a warning.

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

# _RESULTS_DIR is resolved dynamically inside main() based on mode.

# Number of expected CSV files (one per experiment).
_EXPECTED_CSV_COUNT = 12


# ------------------------------------------------------------------ #
# Helpers                                                            #
# ------------------------------------------------------------------ #
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


def _csv_complete(results_dir: Path) -> bool:
    """Return True if *results_dir* already contains the 12 expected CSVs.

    A CSV counts only when:
      - its name starts with ``python_``
      - it has more than 1 line (i.e. at least a header + one data row)
    """
    csv_files = sorted(results_dir.glob('python_*.csv'))
    valid = 0
    for csv_file in csv_files:
        try:
            line_count = sum(1 for _ in open(csv_file, encoding='utf-8'))
            if line_count > 1:
                valid += 1
        except OSError:
            continue
    return valid >= _EXPECTED_CSV_COUNT


# ------------------------------------------------------------------ #
# Main entry point                                                   #
# ------------------------------------------------------------------ #
def main() -> None:
    from . import books as benchmark_books
    from . import datalake_benchmark
    from . import index_benchmark
    from . import metadata_benchmark
    from ..datalake.book_based import BookBasedDatalake

    # -------------------------------------------------------------- #
    # 1. Determine mode: 'synthetic' or 'real'                       #
    # -------------------------------------------------------------- #
    if len(sys.argv) > 1:
        mode = 'real'
        source_path = Path(sys.argv[1])

        # Fallback safety: if the source path does not exist or is
        # an empty directory, revert to synthetic mode.
        if not source_path.exists() or (
            source_path.is_dir() and not any(source_path.iterdir())
        ):
            print(f'[FALLBACK] Source path "{source_path}" does not exist '
                  f'or is empty. Switching to synthetic mode.')
            mode = 'synthetic'
            source_path = None
    else:
        mode = 'synthetic'
        source_path = None

    print(f'[MODE] Running benchmarks in {mode} mode.')

    # -------------------------------------------------------------- #
    # 2. Resolve per-mode results and data directories               #
    # -------------------------------------------------------------- #
    _RESULTS_DIR = _BENCHMARKS_DIR / 'results' / mode
    _DATA_DIR = _BENCHMARKS_DIR / 'data' / mode
    _DATALAKE_DIR = _DATA_DIR / 'datalake'
    _DATAMARTS_DIR = _DATA_DIR / 'datamarts'

    _RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    _DATALAKE_DIR.mkdir(parents=True, exist_ok=True)
    _DATAMARTS_DIR.mkdir(parents=True, exist_ok=True)

    if '--force' in sys.argv:
        sys.argv.remove('--force')
        for csv_file in _RESULTS_DIR.glob('python_*.csv'):
            csv_file.unlink()

    # -------------------------------------------------------------- #
    # 3. Skip logic — avoid re-running if CSVs already exist         #
    # -------------------------------------------------------------- #
    if _csv_complete(_RESULTS_DIR):
        print(f'[SKIP] Benchmark suite already completed for this mode. '
              f'CSVs found in {_RESULTS_DIR}')
        return

    print('=' * 70)
    print('  Python Benchmark Suite')
    print('  Generating CSV files (SPEC section 9)')
    print(f'  Mode      : {mode}')
    print(f'  Results   : {_RESULTS_DIR}')
    print('=' * 70)

    # -------------------------------------------------------------- #
    # 4. Load or generate books                                      #
    # -------------------------------------------------------------- #
    if mode == 'real':
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

    # -------------------------------------------------------------- #
    # 5. Datalake benchmarks (5 CSVs) -> writes to data/<mode>/datalake
    # -------------------------------------------------------------- #
    print('\n--- Datalake Benchmarks (5 experiments) ---')
    datalake_benchmark.run_all(
        raw_books,
        work_dir=_DATALAKE_DIR,
        results_dir=_RESULTS_DIR,
    )

    # -------------------------------------------------------------- #
    # 6. Metadata benchmarks (2 CSVs) -> writes to data/<mode>/datamarts
    # -------------------------------------------------------------- #
    if mode == 'synthetic':
        print('\n--- Metadata Benchmarks (2 experiments) ---')
        metadata_benchmark.run_all(
            work_dir=_DATAMARTS_DIR,
            results_dir=_RESULTS_DIR,
            sizes=[1000, 10000, 100000],
        )
    else:
        print('\n--- Metadata Benchmarks (Skipped in real mode) ---')

    # -------------------------------------------------------------- #
    # 7. Index benchmarks (5 CSVs) -> writes to data/<mode>/datamarts
    # -------------------------------------------------------------- #
    print('\n--- Index Benchmarks (5 experiments) ---')
    index_sizes = [50, 100, 200] if len(raw_books) >= 200 else [len(raw_books)]
    index_benchmark.run_all(
        raw_books,
        work_dir=_DATAMARTS_DIR,
        results_dir=_RESULTS_DIR,
        queries=queries,
        sizes=index_sizes,
    )

    # -------------------------------------------------------------- #
    # Summary                                                        #
    # -------------------------------------------------------------- #
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