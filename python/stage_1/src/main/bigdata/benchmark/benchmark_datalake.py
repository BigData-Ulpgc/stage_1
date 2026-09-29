"""
Datalake Write Benchmark — SPEC Section 3.1
===========================================================
Downloads the first 50 IDs from ``shared/book_ids.txt`` (or all if there
are fewer), keeps them in RAM and measures write performance for each
of the three datalake structures (Time-based, Book-based,
Range-based).

Results are exported to ``data/benchmarks/datalake_benchmark.csv``
and printed to console.
"""

import csv
import os
import sys
import time

# ---------------------------------------------------------------------------
# sys.path adjustment to import sibling modules (datalake.*)
# ---------------------------------------------------------------------------
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_SRC_DIR = os.path.join(_SCRIPT_DIR, "..")
if _SRC_DIR not in sys.path:
    sys.path.insert(0, _SRC_DIR)

from crawler.splitter import fetch_book
from datalake.time_based import save_time_based
from datalake.book_based import save_book_based
from datalake.range_based import save_range_based

# ---------------------------------------------------------------------------
# Paths relative to the script directory
# ---------------------------------------------------------------------------
_BOOK_IDS_PATH = os.path.join(_SCRIPT_DIR, "..", "..", "..", "..", "..", "..", "shared", "book_ids.txt")
_BENCHMARKS_DIR = os.path.join(_SCRIPT_DIR, "..", "..", "..", "..", "..", "..", "data", "benchmarks")
_CSV_OUTPUT = os.path.join(_BENCHMARKS_DIR, "datalake_benchmark.csv")

# Maximum number of books for the benchmark
MAX_BOOKS = 50


# ---------------------------------------------------------------------------
# Utilities
# ---------------------------------------------------------------------------

def load_book_ids(filepath: str, limit: int = MAX_BOOKS) -> list[int]:
    """Reads the IDs file, ignores comments/empty lines and returns up to *limit* IDs."""
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


def download_books_to_ram(
    book_ids: list[int],
) -> list[tuple[int, str, str]]:
    """
    Downloads the books and stores them in an in-memory list.

    Returns:
        List of tuples ``(book_id, header, body)`` for the books
        downloaded successfully.
    """
    books: list[tuple[int, str, str]] = []
    total = len(book_ids)

    for i, book_id in enumerate(book_ids, start=1):
        print(f"  [{i:>{len(str(total))}}/{total}] Downloading book {book_id}...", end=" ")
        result = fetch_book(book_id)
        if result is None:
            print("FAILED -- skipped.")
            continue
        header, body = result
        books.append((book_id, header, body))
        print(f"OK  (header: {len(header):,} chars, body: {len(body):,} chars)")

    return books


# ---------------------------------------------------------------------------
# Benchmark per structure
# ---------------------------------------------------------------------------

def benchmark_structure(
    name: str,
    save_fn,
    books: list[tuple[int, str, str]],
) -> dict:
    """
    Measures write time of *save_fn* for all books.

    Returns:
        Dictionary with the results:
        ``{Structure, Total_Books, Total_Time_ms, Avg_Time_per_Book_ms}``
    """
    total_books = len(books)

    start = time.perf_counter()
    for book_id, header, body in books:
        save_fn(book_id, header, body)
    end = time.perf_counter()

    total_ms = (end - start) * 1_000
    avg_ms = total_ms / total_books if total_books > 0 else 0.0

    return {
        "Structure": name,
        "Total_Books": total_books,
        "Total_Time_ms": round(total_ms, 3),
        "Avg_Time_per_Book_ms": round(avg_ms, 3),
    }


# ---------------------------------------------------------------------------
# CSV Export
# ---------------------------------------------------------------------------

def export_csv(results: list[dict], path: str) -> None:
    """Writes the results to a CSV file."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fieldnames = ["Structure", "Total_Books", "Total_Time_ms", "Avg_Time_per_Book_ms"]
    with open(path, "w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(results)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    print("=" * 65)
    print("  BENCHMARK  —  Datalake Write Performance  (SPEC §3.1)")
    print("=" * 65)

    # 1. Read IDs ───────────────────────────────────────────────────────
    if not os.path.isfile(_BOOK_IDS_PATH):
        print(f"[ERROR] book_ids.txt not found at: {_BOOK_IDS_PATH}")
        sys.exit(1)

    book_ids = load_book_ids(_BOOK_IDS_PATH, limit=MAX_BOOKS)
    print(f"\n[INFO] IDs loaded: {len(book_ids)}  (limit: {MAX_BOOKS})")
    print(f"[INFO] IDs: {book_ids}\n")

    # 2. Download to RAM ───────────────────────────────────────────────
    print("[PHASE 1] Downloading books to RAM...")
    print("-" * 55)
    books = download_books_to_ram(book_ids)
    print("-" * 55)
    print(f"[INFO] Books downloaded successfully: {len(books)} / {len(book_ids)}\n")

    if not books:
        print("[ERROR] No books were downloaded. Aborting benchmark.")
        sys.exit(1)

    # 3. Benchmark each structure ──────────────────────────────────────
    structures = [
        ("Time-based",  save_time_based),
        ("Book-based",  save_book_based),
        ("Range-based", save_range_based),
    ]

    results: list[dict] = []

    print("[PHASE 2] Running write benchmarks...")
    print("-" * 55)

    for name, save_fn in structures:
        print(f"  -> {name:.<20s}", end=" ", flush=True)
        row = benchmark_structure(name, save_fn, books)
        results.append(row)
        print(
            f"Total: {row['Total_Time_ms']:>10.3f} ms  |  "
            f"Avg: {row['Avg_Time_per_Book_ms']:>8.3f} ms/book"
        )

    print("-" * 55)

    # 4. Export CSV ────────────────────────────────────────────────────
    export_csv(results, _CSV_OUTPUT)
    print(f"\n[INFO] Results exported to: {os.path.abspath(_CSV_OUTPUT)}")

    # 5. Console summary ───────────────────────────────────────────────
    print("\n" + "=" * 65)
    print(f"  {'Structure':<15s} {'Books':>7s} {'Total (ms)':>12s} {'Avg (ms)':>12s}")
    print("  " + "-" * 50)
    for r in results:
        print(
            f"  {r['Structure']:<15s} "
            f"{r['Total_Books']:>7d} "
            f"{r['Total_Time_ms']:>12.3f} "
            f"{r['Avg_Time_per_Book_ms']:>12.3f}"
        )
    print("=" * 65)


if __name__ == "__main__":
    main()
