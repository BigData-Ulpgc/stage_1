"""
Benchmark Runner — Main Entry Point
====================================
Orchestrates the full Python benchmarking suite:

  1. Downloads books from Project Gutenberg into RAM  (shared phase).
  2. Runs **Datalake** benchmarks       → 5 CSV files  (1–5).
  3. Runs **Metadata** benchmarks       → 2 CSV files  (6–7).
  4. Runs **Inverted Index** benchmarks → 5 CSV files  (8–12).

Usage (from the Python project root ``python/stage_1/``):

.. code-block:: bash

   python -m src.main.bigdata.benchmark.benchmark_runner

All 12 result CSV files are written to
``python/stage_1/benchmarks/results/``.
"""

from pathlib import Path

from src.main.bigdata.crawler.splitter import fetch_book
from src.main.bigdata.benchmark.csv_results import RESULTS_DIR
from src.main.bigdata.benchmark.datalake_benchmark import run_datalake_benchmarks
from src.main.bigdata.benchmark.metadata_benchmark import run_metadata_benchmarks
from src.main.bigdata.benchmark.index_benchmark import run_index_benchmarks

# ---------------------------------------------------------------------------
# Path configuration — resolved from this file's physical location
# ---------------------------------------------------------------------------
_BENCHMARK_DIR: Path = Path(__file__).resolve().parent
_PROJECT_ROOT: Path = _BENCHMARK_DIR.parents[5]   # stage_1/ (overall root)

BOOK_IDS_PATH: Path = _PROJECT_ROOT / "shared" / "book_ids.txt"
DATALAKE_ROOT: Path = _PROJECT_ROOT / "data" / "datalake"
DATAMARTS_ROOT: Path = _PROJECT_ROOT / "data" / "datamarts"
MONOLITHIC_PATH: Path = DATAMARTS_ROOT / "inverted_index.json"
HIERARCHICAL_BASE: Path = DATAMARTS_ROOT / "inverted_index"
METADATA_DB_PATH: Path = DATAMARTS_ROOT / "metadata.db"


# ---------------------------------------------------------------------------
# Utilities
# ---------------------------------------------------------------------------

def load_book_ids(filepath: Path, limit: int = 50) -> list[int]:
    """
    Read numeric book IDs from *filepath*.

    Lines that are empty or start with ``#`` are ignored.  Returns at
    most *limit* IDs.
    """
    ids: list[int] = []
    with open(filepath, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            try:
                ids.append(int(line))
            except ValueError:
                pass
            if len(ids) >= limit:
                break
    return ids


def download_books(book_ids: list[int]) -> list[tuple[int, str, str]]:
    """
    Download books from Project Gutenberg using the real crawler.

    Returns:
        List of ``(book_id, header, body)`` tuples for every book that
        was downloaded successfully.
    """
    books: list[tuple[int, str, str]] = []
    total = len(book_ids)

    for i, book_id in enumerate(book_ids, start=1):
        print(
            f"  [{i}/{total}] Downloading book {book_id}...",
            end=" ", flush=True,
        )
        result = fetch_book(book_id)
        if result is None:
            print("FAILED -- skipped.")
            continue
        header, body = result
        books.append((book_id, header, body))
        print(
            f"OK  (header: {len(header):,} chars, "
            f"body: {len(body):,} chars)"
        )

    return books


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    """Top-level orchestrator — download once, benchmark everything."""
    print("=" * 65)
    print("  Starting Python Benchmarking Suite")
    print("=" * 65)

    # Validate book_ids.txt -------------------------------------------------
    if not BOOK_IDS_PATH.exists():
        print(f"[ERROR] book_ids.txt not found at: {BOOK_IDS_PATH}")
        raise SystemExit(1)

    book_ids = load_book_ids(BOOK_IDS_PATH)
    print(f"[INFO] Book IDs loaded: {len(book_ids)} -> {book_ids}\n")

    # Phase 0 — download books into RAM (shared across all benchmarks) ------
    print("[PHASE 0] Downloading books from Project Gutenberg...")
    print("-" * 55)
    books = download_books(book_ids)
    print("-" * 55)
    print(
        f"[INFO] Books downloaded successfully: "
        f"{len(books)} / {len(book_ids)}\n"
    )

    if not books:
        print("[ERROR] No books were downloaded. Aborting benchmarks.")
        raise SystemExit(1)

    # Phase 1–5 — Datalake --------------------------------------------------
    run_datalake_benchmarks(books, DATALAKE_ROOT)

    # Phase 6–7 — Metadata --------------------------------------------------
    run_metadata_benchmarks(books, DATALAKE_ROOT, METADATA_DB_PATH)

    # Phase 8–12 — Inverted Index -------------------------------------------
    run_index_benchmarks(books, MONOLITHIC_PATH, HIERARCHICAL_BASE)

    # Final summary ---------------------------------------------------------
    print("\n" + "=" * 65)
    print("  All 12 CSV files have been successfully generated!")
    print(f"  Output folder: {RESULTS_DIR}")
    print("=" * 65)

    print("\n  Generated files:")
    for csv_file in sorted(RESULTS_DIR.glob("python_*.csv")):
        size_kb = csv_file.stat().st_size / 1024
        print(f"    - {csv_file.name}  ({size_kb:.1f} KB)")
    print()


if __name__ == "__main__":
    main()
