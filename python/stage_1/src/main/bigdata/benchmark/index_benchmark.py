"""Index benchmark suite — 5 experiments for the 3 inverted index backends.

Experiments: index_build, index_query, index_update, index_memory, index_disk
"""
from __future__ import annotations

import gc
import os
import shutil
import tracemalloc
from pathlib import Path
from typing import Callable, Dict, List, Set, Tuple

from ..models import RawBook
from ..datamart.index.base import InvertedIndex
from ..datamart.index.in_memory import InMemoryInvertedIndex
from ..datamart.index.monolithic import MonolithicJsonIndex
from ..datamart.index.hierarchical import HierarchicalFolderIndex
from ..datamart.index.tokenizer import tokenize
from ..query.search_service import search
from .runner import measure, derived_rows, single_row, BenchmarkRow
from .csv_results import write_experiment


DEFAULT_QUERY_ROUNDS = 100
BENCH_DATABASE = 'search_engine_benchmark'


# ------------------------------------------------------------------ #
# TokenizedBook: pre-tokenized for fair comparison                    #
# ------------------------------------------------------------------ #

class TokenizedBook:
    __slots__ = ('id', 'terms')
    def __init__(self, book_id: int, terms: frozenset):
        self.id = book_id
        self.terms = terms


def tokenize_all(books: List[RawBook]) -> List[TokenizedBook]:
    """Pre-tokenize books (done BEFORE any timing)."""
    return [TokenizedBook(b.id, frozenset(tokenize(b.body))) for b in books]


# ------------------------------------------------------------------ #
# Backend factories                                                   #
# ------------------------------------------------------------------ #

def _file_backends(work_dir: Path) -> List[Tuple[str, Callable]]:
    """Return [(name, open_fn)] for file-based backends."""
    return [
        ('monolithic', lambda d: MonolithicJsonIndex(
            d / 'datamarts' / 'inverted_index.json')),
        ('hierarchical', lambda d: HierarchicalFolderIndex(
            d / 'datamarts' / 'inverted_index')),
    ]


def _mongo_backend():
    """Return (name, open_fn) for Mongo, or None if unavailable."""
    try:
        from ..datamart.index.mongo import MongoInvertedIndex
        # Quick availability test
        idx = MongoInvertedIndex(
            db_name=BENCH_DATABASE, collection='_probe')
        idx._collection.drop()
        idx.close()
        return ('mongo', lambda d: MongoInvertedIndex(
            db_name=BENCH_DATABASE, collection='inverted_index'))
    except Exception:
        return None


def _all_backends(work_dir: Path):
    backends = _file_backends(work_dir)
    mongo = _mongo_backend()
    if mongo:
        backends.append(mongo)
    return backends


# ------------------------------------------------------------------ #
# Helpers                                                             #
# ------------------------------------------------------------------ #

def _add_all(index: InvertedIndex, books: List[TokenizedBook]):
    for book in books:
        index.add_document(book.id, book.terms)


def _fresh_index(name: str, open_fn: Callable, d: Path, previous=None):
    """Close previous, clear, reopen empty."""
    if previous is not None:
        previous.close()
    old = open_fn(d)
    old.clear()
    old.close()
    return open_fn(d)


def _dir_for(work_dir: Path, experiment: str, backend_name: str) -> Path:
    return work_dir / experiment / backend_name


def _disk_stats(root: Path) -> dict:
    files = total_bytes = 0
    if root.exists():
        for dirpath, _, filenames in os.walk(str(root)):
            for fname in filenames:
                fpath = os.path.join(dirpath, fname)
                files += 1
                try:
                    total_bytes += os.path.getsize(fpath)
                except OSError:
                    pass
    return {'files': files, 'bytes': total_bytes}


def _allocated_bytes(root: Path) -> int:
    block_size = 4096
    total = 0
    if not root.exists():
        return 0
    for dirpath, dirnames, filenames in os.walk(str(root)):
        total += block_size
        for fname in filenames:
            fpath = os.path.join(dirpath, fname)
            try:
                size = os.path.getsize(fpath)
                total += ((size + block_size - 1) // block_size) * block_size
            except OSError:
                pass
    return total


# ================================================================== #
def verify(index, books, queries, what: str) -> None:
    reference = InMemoryInvertedIndex()
    _add_all(reference, books)
    terms = [t for q in queries for t in sorted(tokenize(q))]
    terms += sorted(books[0].terms)[:20] + sorted(books[-1].terms)[:20]
    for term in dict.fromkeys(terms):
        if index.postings(term) != reference.postings(term):
            raise RuntimeError(f'{what}: different postings for "{term}"')
    for q in queries:
        if search(index, q) != search(reference, q):
            raise RuntimeError(f'{what}: different result for "{q}"')


# ================================================================== #
# Experiment 1: index_build                                           #
# ================================================================== #

def build(books: List[TokenizedBook], work_dir: Path, backends, queries: List[str]) -> List[BenchmarkRow]:
    rows = []
    n = len(books)
    for name, open_fn in backends:
        d = _dir_for(work_dir, 'build', name)
        idx_holder = [None]

        def setup(n_=name, o_=open_fn, d_=d):
            idx_holder[0] = _fresh_index(n_, o_, d_, idx_holder[0])

        def task():
            idx = idx_holder[0]
            _add_all(idx, books)
            idx.flush()

        elapsed = measure('index_build', name, n, setup=setup, task=task)
        verify(idx_holder[0], books, queries, f'build {name}')
        rows.extend(elapsed)
        rows.extend(derived_rows(elapsed, 'throughput', 'books_per_s',
                                 lambda ms, nn=n: nn / (ms / 1000.0)))
        # Clean up
        if idx_holder[0]:
            idx_holder[0].clear()
            idx_holder[0].close()
    return rows


# ================================================================== #
# Experiment 2: index_query                                           #
# ================================================================== #

def query(
    books: List[TokenizedBook],
    work_dir: Path,
    backends,
    queries: List[str],
    query_rounds: int = DEFAULT_QUERY_ROUNDS,
) -> List[BenchmarkRow]:
    total_queries = query_rounds * len(queries)
    rows = []
    n = len(books)
    for name, open_fn in backends:
        d = _dir_for(work_dir, 'query', name)
        # Build + flush + close, then reopen for querying
        built = _fresh_index(name, open_fn, d)
        _add_all(built, books)
        built.flush()
        built.close()

        idx = open_fn(d)
        verify(idx, books, queries, f'query {name}')
        found = [0]

        def setup():
            found[0] = 0

        def task(ix=idx):
            for _ in range(query_rounds):
                for q in queries:
                    found[0] += len(search(ix, q))

        elapsed = measure('index_query', name, n, setup=setup, task=task)
        idx.clear()
        idx.close()
        rows.extend(elapsed)
        rows.extend(derived_rows(elapsed, 'per_query', 'us',
                                 lambda ms, tq=total_queries: ms * 1000.0 / tq))
    return rows


# ================================================================== #
# Experiment 3: index_update                                          #
# ================================================================== #

def update(books: List[TokenizedBook], work_dir: Path, backends, queries: List[str]) -> List[BenchmarkRow]:
    n = len(books)
    k = max(1, n // 10)
    base_books = books[:n - k]
    added_books = books[n - k:]
    rows = []
    for name, open_fn in backends:
        d = _dir_for(work_dir, 'update', name)
        idx_holder = [None]

        def setup(n_=name, o_=open_fn, d_=d):
            prev = _fresh_index(n_, o_, d_, idx_holder[0])
            _add_all(prev, base_books)
            prev.flush()
            prev.close()
            idx_holder[0] = o_(d_)

        def task():
            idx = idx_holder[0]
            for book in added_books:
                idx.add_document(book.id, book.terms)
                idx.flush()

        elapsed = measure('index_update', name, n, setup=setup, task=task)
        verify(idx_holder[0], books, queries, f'update {name}')
        if idx_holder[0]:
            idx_holder[0].clear()
            idx_holder[0].close()
        rows.extend(elapsed)
        rows.extend(derived_rows(elapsed, 'per_book', 'ms',
                                 lambda ms, kk=k: ms / kk))
    return rows


# ================================================================== #
# Experiment 4: index_memory                                          #
# ================================================================== #

def memory(books: List[TokenizedBook], work_dir: Path, backends, queries: List[str]) -> List[BenchmarkRow]:
    rows = []
    n = len(books)
    for name, open_fn in backends:
        d = _dir_for(work_dir, 'memory', name)
        # Ensure clean start
        _fresh_index(name, open_fn, d).close()

        tracemalloc.start()
        gc.collect()
        gc.collect()

        snap_before = tracemalloc.take_snapshot()
        idx = open_fn(d)
        _add_all(idx, books)
        idx.flush()
        gc.collect()
        snap_after_build = tracemalloc.take_snapshot()
        heap_after_build = sum(s.size for s in snap_after_build.statistics('filename'))
        heap_before = sum(s.size for s in snap_before.statistics('filename'))
        build_mem = heap_after_build - heap_before

        idx.close()
        del idx
        gc.collect()

        snap_before_open = tracemalloc.take_snapshot()
        reopened = open_fn(d)
        verify(reopened, books, queries, f'memory {name}')
        gc.collect()
        snap_after_open = tracemalloc.take_snapshot()
        heap_before_open = sum(s.size for s in snap_before_open.statistics('filename'))
        heap_after_open_val = sum(s.size for s in snap_after_open.statistics('filename'))
        open_mem = heap_after_open_val - heap_before_open

        reopened.clear()
        reopened.close()
        tracemalloc.stop()

        rows.append(single_row('index_memory', name, n, 'heap_after_build', max(0, build_mem), 'bytes'))
        rows.append(single_row('index_memory', name, n, 'heap_after_open', max(0, open_mem), 'bytes'))
    return rows


# ================================================================== #
# Experiment 5: index_disk                                            #
# ================================================================== #

def disk(books: List[TokenizedBook], work_dir: Path, backends, queries: List[str]) -> List[BenchmarkRow]:
    # Compute logical index stats
    all_terms = set()
    total_postings = 0
    for book in books:
        all_terms.update(book.terms)
        total_postings += len(book.terms)

    rows = []
    n = len(books)
    for name, open_fn in backends:
        d = _dir_for(work_dir, 'disk', name)
        idx = _fresh_index(name, open_fn, d)
        _add_all(idx, books)
        idx.flush()
        verify(idx, books, queries, f'disk {name}')

        disk_bytes = idx.disk_usage_bytes()
        rows.append(single_row('index_disk', name, n, 'bytes', disk_bytes, 'bytes'))

        if d.exists():
            stats = _disk_stats(d)
            rows.append(single_row('index_disk', name, n, 'files', stats['files'], 'count'))
            rows.append(single_row('index_disk', name, n, 'allocated_bytes', _allocated_bytes(d), 'bytes'))

        rows.append(single_row('index_disk', name, n, 'terms', len(all_terms), 'count'))
        rows.append(single_row('index_disk', name, n, 'postings', total_postings, 'count'))

        idx.clear()
        idx.close()
    return rows


# ================================================================== #
# Run all 5 and write CSVs                                            #
# ================================================================== #

def run_all(
    books: List[RawBook],
    work_dir: Path,
    results_dir: Path,
    queries: List[str],
    sizes: List[int] = None,
) -> Dict[str, List[BenchmarkRow]]:
    """Run all 5 index experiments and write CSVs."""
    print('  [index] tokenizing all books ...')
    dataset = tokenize_all(books)
    if sizes is None:
        sizes = [len(dataset)]

    backends = _all_backends(work_dir)
    backend_names = [name for name, _ in backends]
    print(f'  [index] backends: {backend_names}')

    results: Dict[str, List[BenchmarkRow]] = {
        'index_build': [], 'index_query': [], 'index_update': [],
        'index_memory': [], 'index_disk': [],
    }

    for n in sizes:
        if n > len(dataset):
            n = len(dataset)
        subset = dataset[:n]
        print(f'  [index] size={n} ...')

        print('    build ...')
        results['index_build'].extend(build(subset, work_dir, backends, queries))
        print('    query ...')
        results['index_query'].extend(query(subset, work_dir, backends, queries))
        print('    update ...')
        results['index_update'].extend(update(subset, work_dir, backends, queries))
        print('    memory ...')
        results['index_memory'].extend(memory(subset, work_dir, backends, queries))
        print('    disk ...')
        results['index_disk'].extend(disk(subset, work_dir, backends, queries))

    for experiment, rows in results.items():
        write_experiment(results_dir, experiment, rows)
        print(f'    -> {experiment}: {len(rows)} rows')

    return results
