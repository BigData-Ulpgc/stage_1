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
        body_path = f'datalake/book/{book_id}/body.txt'
        header_path = f'datalake/book/{book_id}/header.txt'
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

        repo_holder = [None]

        def setup(p=db_path, b=backend):
            if repo_holder[0] is not None:
                repo_holder[0].close()
            p.parent.mkdir(parents=True, exist_ok=True)
            if p.exists():
                p.unlink()
            # Pre-create DB schema and indexes outside of timed task
            with_idx = (b == 'sqlite')
            repo_holder[0] = MetadataRepository(p, with_indexes=with_idx)

        def task():
            repo = repo_holder[0]
            # Insert in batches of 1000
            for i in range(0, size, 1000):
                repo.save_all(data[i:i+1000])

        elapsed = measure('metadata_insert', backend, size, setup=setup, task=task)
        if repo_holder[0] is not None:
            repo_holder[0].close()
        
        import sqlite3
        with sqlite3.connect(db_path) as conn:
            count = conn.execute("SELECT COUNT(*) FROM books").fetchone()[0]
            if count != size:
                raise RuntimeError(f"Expected {size} rows, got {count}")
                
        rows.extend(elapsed)
        rows.extend(derived_rows(elapsed, 'throughput', 'rows_per_s',
                                 lambda ms, n=size: n / (ms / 1000.0)))
    return rows


# ================================================================== #
# Experiment 2: metadata_query                                        #
# ================================================================== #

def query(size: int, work_dir: Path, n_queries: int = DEFAULT_QUERY_COUNT) -> List[BenchmarkRow]:
    data = _synthetic_metadata(size)
    rows = []
    
    rnd = JavaRandom(42)
    query_ids, query_authors, query_titles = [], [], []
    for _ in range(n_queries):
        pick = data[rnd.next_int(len(data))]
        query_ids.append(pick[0])
        query_titles.append(pick[1])
        query_authors.append(pick[2])

    for backend in ('sqlite', 'sqlite_no_index'):
        db_path = work_dir / 'query' / f'{backend}.db'
        db_path.parent.mkdir(parents=True, exist_ok=True)
        if db_path.exists():
            db_path.unlink()

        with_idx = (backend == 'sqlite')
        repo = MetadataRepository(db_path, with_indexes=with_idx)
        repo.save_all(data)

        found = [0]

        def setup():
            found[0] = 0

        def task_id(r=repo):
            for bid in query_ids:
                if r.find_by_id(bid):
                    found[0] += 1

        def task_author(r=repo):
            for author in query_authors:
                found[0] += len(r.find_by_author(author))

        def task_title(r=repo):
            for title in query_titles:
                found[0] += len(r.find_by_title(title))

        for query_type, task_fn in [('find_by_id', task_id), ('find_by_author', task_author), ('find_by_title', task_title)]:
            elapsed = measure('metadata_query', backend, size, setup=setup, task=task_fn)
            
            if found[0] < n_queries:
                raise RuntimeError(f"Expected >= {n_queries} found, got {found[0]} for {query_type}")
                
            avg_rows = derived_rows(elapsed, f'{query_type}_avg', 'us',
                                     lambda ms: ms * 1000.0 / n_queries)
                                     
            for r_el, r_avg in zip(elapsed, avg_rows):
                rows.append(BenchmarkRow(
                    language=r_el.language,
                    experiment=r_el.experiment,
                    structure=r_el.structure,
                    dataset_size=r_el.dataset_size,
                    repetition=r_el.repetition,
                    metric=query_type,
                    value=r_el.value,
                    unit=r_el.unit
                ))
                rows.append(r_avg)
                
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
