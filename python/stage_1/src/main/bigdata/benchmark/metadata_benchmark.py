"""Metadata benchmark suite — 2 experiments for SQLite metadata.

Experiments: metadata_insert, metadata_query
"""
from __future__ import annotations


from pathlib import Path
from typing import Dict, List

from ..models import BookMetadata
from ..datamart.metadata.parser import parse_metadata
from ..datamart.metadata.repository import MetadataRepository
from .runner import measure, derived_rows, single_row, BenchmarkRow, JavaRandom
from .csv_results import write_experiment


DEFAULT_QUERY_COUNT = 1000


# ------------------------------------------------------------------ #
# Synthetic metadata generation (matches Java's approach)             #
# ------------------------------------------------------------------ #

def _synthetic_metadata(size: int) -> List[tuple]:
    """Generate synthetic metadata rows: (book_id, title, author, language, release_date, body_path, header_path)."""
    rows = []
    for i in range(size):
        book_id = i + 1
        title = f'Title {i // 2}'
        author = f'Author {i // 10}'
        language = 'English'
        release_date = 'January 1, 2000'
        body_path = f'/data/datalake/book/{book_id}/body.txt'
        header_path = f'/data/datalake/book/{book_id}/header.txt'
        rows.append((book_id, title, author, language, release_date, body_path, header_path))
    return rows


# ================================================================== #
# Experiment 1: metadata_insert                                       #
# ================================================================== #

def insert(size: int, work_dir: Path) -> List[BenchmarkRow]:
    data = _synthetic_metadata(size)
    rows = []

    for backend in ('sqlite', 'sqlite_no_index'):
        db_path = work_dir / 'insert' / f'{backend}.db'

        def setup(p=db_path, b=backend):
            p.parent.mkdir(parents=True, exist_ok=True)
            if p.exists():
                p.unlink()
            # Pre-create to ensure clean state

        def task(p=db_path, b=backend):
            with_idx = (b == 'sqlite')
            repo = MetadataRepository(p, with_indexes=with_idx)
            repo.save_all(data)
            repo.close()

        elapsed = measure('metadata_insert', backend, size, setup=setup, task=task)
        rows.extend(elapsed)
        rows.extend(derived_rows(elapsed, 'throughput', 'books_per_s',
                                 lambda ms, n=size: n / (ms / 1000.0)))
    return rows


# ================================================================== #
# Experiment 2: metadata_query                                        #
# ================================================================== #

def query(size: int, work_dir: Path, n_queries: int = DEFAULT_QUERY_COUNT) -> List[BenchmarkRow]:
    data = _synthetic_metadata(size)
    rows = []
    rnd = JavaRandom(42)

    for backend in ('sqlite', 'sqlite_no_index'):
        db_path = work_dir / 'query' / f'{backend}.db'
        db_path.parent.mkdir(parents=True, exist_ok=True)
        if db_path.exists():
            db_path.unlink()

        with_idx = (backend == 'sqlite')
        repo = MetadataRepository(db_path, with_indexes=with_idx)
        repo.save_all(data)

        # Pre-generate query targets using JavaRandom for cross-language parity
        query_ids = [rnd.next_int(size) + 1 for _ in range(n_queries)]
        query_authors = [f'Author {rnd.next_int(size // 10 + 1)}' for _ in range(n_queries)]
        query_titles = [f'Title {rnd.next_int(size // 2 + 1)}' for _ in range(n_queries)]

        found = [0]

        def setup():
            found[0] = 0

        def task(r=repo):
            for bid in query_ids:
                result = r.find_by_id(bid)
                if result:
                    found[0] += 1
            for author in query_authors:
                found[0] += len(r.find_by_author(author))
            for title in query_titles:
                found[0] += len(r.find_by_title(title))

        elapsed = measure('metadata_query', backend, size, setup=setup, task=task)
        total_q = n_queries * 3
        rows.extend(elapsed)
        rows.extend(derived_rows(elapsed, 'per_query', 'us',
                                 lambda ms, tq=total_q: ms * 1000.0 / tq))
        repo.close()
    return rows


# ================================================================== #
# Run all 2 and write CSVs                                            #
# ================================================================== #

def run_all(
    work_dir: Path,
    results_dir: Path,
    sizes: List[int] = None,
) -> Dict[str, List[BenchmarkRow]]:
    """Run both metadata experiments and write CSVs."""
    if sizes is None:
        sizes = [1000, 10000, 100000]

    results: Dict[str, List[BenchmarkRow]] = {
        'metadata_insert': [],
        'metadata_query': [],
    }

    for size in sizes:
        print(f'  [metadata] size={size} ...')
        print('    insert ...')
        results['metadata_insert'].extend(insert(size, work_dir))
        print('    query ...')
        results['metadata_query'].extend(query(size, work_dir))

    for experiment, rows in results.items():
        write_experiment(results_dir, experiment, rows)
        print(f'    -> {experiment}: {len(rows)} rows')

    return results
