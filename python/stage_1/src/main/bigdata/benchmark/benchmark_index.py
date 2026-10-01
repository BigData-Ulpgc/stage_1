"""
Inverted Index Benchmark -- SPEC Section 4.2
============================================
Downloads the first 50 IDs from ``shared/book_ids.txt`` (or all if there
are fewer), tokenizes each book's body, and stores tuples
``(book_id, tokens)`` in RAM.

Then measures pure indexing performance (add_postings) for each
backend: MonolithicIndex, HierarchicalIndex, and MongoIndex.

Results are exported to ``data/benchmarks/index_benchmark.csv``
and printed to console.
"""

import csv
import os
import shutil
import sys
import tempfile
import time

# ---------------------------------------------------------------------------
# sys.path adjustment to import sibling modules
# ---------------------------------------------------------------------------
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_SRC_DIR = os.path.join(_SCRIPT_DIR, "..")
if _SRC_DIR not in sys.path:
    sys.path.insert(0, _SRC_DIR)

from src.main.bigdata.crawler.splitter import fetch_book
from src.main.bigdata.datamart.index.tokenizer import tokenize
from src.main.bigdata.datamart.index.monolithic import MonolithicIndex
from src.main.bigdata.datamart.index.hierarchical import HierarchicalIndex
from src.main.bigdata.datamart.index.mongo import MongoIndex

# ---------------------------------------------------------------------------
# Paths relative to the script directory
# ---------------------------------------------------------------------------
_BOOK_IDS_PATH = os.path.join(_SCRIPT_DIR, "..", "..", "..", "..", "..", "..", "shared", "book_ids.txt")
_BENCHMARKS_DIR = os.path.join(_SCRIPT_DIR, "..", "..", "..", "..", "..", "..", "data", "benchmarks")
_CSV_OUTPUT = os.path.join(_BENCHMARKS_DIR, "index_benchmark.csv")

# Maximum number of books for the benchmark
MAX_BOOKS = 50


# ---------------------------------------------------------------------------
# Utilities
# ---------------------------------------------------------------------------

def load_book_ids(filepath: str, limit: int = MAX_BOOKS) -> list[int]:
    """Reads the IDs file, ignores comments/empty lines, and returns up to *limit* IDs."""
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


# ---------------------------------------------------------------------------
# Phase 1 — RAM Preparation (NOT timed)
# ---------------------------------------------------------------------------

def prepare_data(
    book_ids: list[int],
) -> list[tuple[int, set[str]]]:
    """
    Downloads books, tokenizes the body, and stores results in RAM.

    Returns:
        List of tuples ``(book_id, tokens)`` for books processed
        successfully.
    """
    data: list[tuple[int, set[str]]] = []
    total = len(book_ids)
    width = len(str(total))

    for i, book_id in enumerate(book_ids, start=1):
        print(f"  [{i:>{width}}/{total}] Book {book_id}: downloading...", end=" ", flush=True)

        result = fetch_book(book_id)
        if result is None:
            print("FAILED -- skipped.")
            continue

        _header, body = result
        print(f"tokenizing...", end=" ", flush=True)

        tokens = tokenize(body)
        data.append((book_id, tokens))
        print(f"OK  ({len(tokens):,} unique tokens)")

    return data


# ---------------------------------------------------------------------------
# Phase 2 — Indexing Benchmark (TIMED)
# ---------------------------------------------------------------------------

def benchmark_monolithic(
    data: list[tuple[int, set[str]]],
) -> dict:
    """
    Measures indexing time with MonolithicIndex.

    Includes the ``.save()`` call within the timer, as the
    disk flush is part of its real cost.
    """
    # Use a temporary directory to avoid polluting the actual index
    tmp_dir = tempfile.mkdtemp(prefix="bench_mono_")
    tmp_path = os.path.join(tmp_dir, "inverted_index.json")

    try:
        idx = MonolithicIndex(json_path=tmp_path)

        start = time.perf_counter()
        for book_id, tokens in data:
            idx.add_postings(book_id, tokens)
        idx.save()  # disk flush included in measurement
        end = time.perf_counter()

        total_ms = (end - start) * 1_000
        avg_ms = total_ms / len(data) if data else 0.0

        return {
            "Index_Type": "MonolithicIndex",
            "Total_Books": len(data),
            "Total_Time_ms": round(total_ms, 3),
            "Avg_Time_per_Book_ms": round(avg_ms, 3),
        }
    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)


def benchmark_hierarchical(
    data: list[tuple[int, set[str]]],
) -> dict:
    """Measures indexing time with HierarchicalIndex."""
    tmp_dir = tempfile.mkdtemp(prefix="bench_hier_")

    try:
        idx = HierarchicalIndex(base_path=tmp_dir)

        start = time.perf_counter()
        for book_id, tokens in data:
            idx.add_postings(book_id, tokens)
        end = time.perf_counter()

        total_ms = (end - start) * 1_000
        avg_ms = total_ms / len(data) if data else 0.0

        return {
            "Index_Type": "HierarchicalIndex",
            "Total_Books": len(data),
            "Total_Time_ms": round(total_ms, 3),
            "Avg_Time_per_Book_ms": round(avg_ms, 3),
        }
    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)


def benchmark_mongo(
    data: list[tuple[int, set[str]]],
) -> dict | None:
    """
    Measures indexing time with MongoIndex.

    Returns ``None`` if MongoDB is not available or the connection fails.
    """
    try:
        idx = MongoIndex(
            db_name="search_engine_benchmark",
            collection_name="inverted_index_bench",
        )
    except Exception as e:
        print(f"  [WARN] MongoDB not available: {e}")
        return None

    try:
        # Clean the benchmark collection before measuring
        idx._col.drop()

        start = time.perf_counter()
        for book_id, tokens in data:
            idx.add_postings(book_id, tokens)
        end = time.perf_counter()

        total_ms = (end - start) * 1_000
        avg_ms = total_ms / len(data) if data else 0.0

        return {
            "Index_Type": "MongoIndex",
            "Total_Books": len(data),
            "Total_Time_ms": round(total_ms, 3),
            "Avg_Time_per_Book_ms": round(avg_ms, 3),
        }
    except Exception as e:
        print(f"  [ERROR] Failure during MongoDB benchmark: {e}")
        return None
    finally:
        try:
            # Clean the temporary collection and close connection
            idx._col.drop()
            idx.close()
        except Exception:
            pass


# ---------------------------------------------------------------------------
# CSV Export
# ---------------------------------------------------------------------------

def export_csv(results: list[dict], path: str) -> None:
    """Writes the results to a CSV file."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fieldnames = ["Index_Type", "Total_Books", "Total_Time_ms", "Avg_Time_per_Book_ms"]
    with open(path, "w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(results)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    print("=" * 65)
    print("  BENCHMARK  --  Index Write Performance  (SPEC S4.2)")
    print("=" * 65)

    # 1. Read IDs -------------------------------------------------------
    if not os.path.isfile(_BOOK_IDS_PATH):
        print(f"[ERROR] book_ids.txt not found at: {_BOOK_IDS_PATH}")
        sys.exit(1)

    book_ids = load_book_ids(_BOOK_IDS_PATH, limit=MAX_BOOKS)
    print(f"\n[INFO] IDs loaded: {len(book_ids)}  (limit: {MAX_BOOKS})")
    print(f"[INFO] IDs: {book_ids}\n")

    # 2. Phase 1: RAM preparation (not timed) ---------------------------
    print("[PHASE 1] Download + tokenization -> RAM  (NOT timed)")
    print("-" * 55)
    data = prepare_data(book_ids)
    print("-" * 55)
    print(f"[INFO] Books prepared successfully: {len(data)} / {len(book_ids)}\n")

    if not data:
        print("[ERROR] No books were prepared. Aborting benchmark.")
        sys.exit(1)

    # Token summary
    total_tokens = sum(len(t) for _, t in data)
    print(f"[INFO] Total accumulated unique tokens: {total_tokens:,}\n")

    # 3. Phase 2: Indexing benchmark (timed) ---------------------------
    print("[PHASE 2] Running indexing benchmarks...")
    print("-" * 55)

    results: list[dict] = []

    # -- MonolithicIndex ------------------------------------------------
    print("  -> MonolithicIndex........", end=" ", flush=True)
    row = benchmark_monolithic(data)
    results.append(row)
    print(
        f"Total: {row['Total_Time_ms']:>10.3f} ms  |  "
        f"Avg: {row['Avg_Time_per_Book_ms']:>8.3f} ms/book"
    )

    # -- HierarchicalIndex ----------------------------------------------
    print("  -> HierarchicalIndex......", end=" ", flush=True)
    row = benchmark_hierarchical(data)
    results.append(row)
    print(
        f"Total: {row['Total_Time_ms']:>10.3f} ms  |  "
        f"Avg: {row['Avg_Time_per_Book_ms']:>8.3f} ms/book"
    )

    # -- MongoIndex -----------------------------------------------------
    print("  -> MongoIndex.............", end=" ", flush=True)
    row = benchmark_mongo(data)
    if row is not None:
        results.append(row)
        print(
            f"Total: {row['Total_Time_ms']:>10.3f} ms  |  "
            f"Avg: {row['Avg_Time_per_Book_ms']:>8.3f} ms/book"
        )
    else:
        print("SKIPPED (MongoDB not available)")

    print("-" * 55)

    # 4. Export CSV -----------------------------------------------------
    export_csv(results, _CSV_OUTPUT)
    print(f"\n[INFO] Results exported to: {os.path.abspath(_CSV_OUTPUT)}")

    # 5. Console summary ------------------------------------------------
    print("\n" + "=" * 65)
    print(f"  {'Index':<22s} {'Books':>7s} {'Total (ms)':>12s} {'Avg (ms)':>12s}")
    print("  " + "-" * 55)
    for r in results:
        print(
            f"  {r['Index_Type']:<22s} "
            f"{r['Total_Books']:>7d} "
            f"{r['Total_Time_ms']:>12.3f} "
            f"{r['Avg_Time_per_Book_ms']:>12.3f}"
        )
    if not results:
        print("  (no results)")
    print("=" * 65)


if __name__ == "__main__":
    main()
