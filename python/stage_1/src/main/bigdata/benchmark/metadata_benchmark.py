"""
Metadata Benchmark Module
=========================
Measures SQLite metadata metrics and generates two CSV files:

  python_metadata_insert.csv  — Insertion speed
  python_metadata_query.csv   — Query performance (find by author)
"""

import sqlite3
import time
from pathlib import Path

from src.main.bigdata.datamart.metadata.repository import MetadataManager
from src.main.bigdata.benchmark.csv_results import save_csv

N_WARMUP = 2
N_RUNS = 5

def run_metadata_benchmarks(
    books: list[tuple[int, str, str]],
    datalake_root: Path,
    metadata_db_path: Path,
) -> None:
    """
    Execute the metadata benchmarks and write their CSV results.
    """
    print("\n--- Running Metadata Benchmarks ---")
    dataset_size = len(books)

    # ------------------------------------------------------------------
    # 6. Insertion speed
    # ------------------------------------------------------------------
    print("Measuring metadata insertion speed...")
    insert_rows = []

    for rep in range(1 - N_WARMUP, N_RUNS + 1):
        metadata = MetadataManager(db_path=str(metadata_db_path))

        start = time.perf_counter()
        for book_id, header, body in books:
            body_path = str(datalake_root / "book" / str(book_id) / "body.txt")
            header_path = str(datalake_root / "book" / str(book_id) / "header.txt")
            metadata.insert_or_update_book(
                book_id=book_id,
                header_text=header,
                body_path=body_path,
                header_path=header_path,
            )
        elapsed_ms = (time.perf_counter() - start) * 1000
        metadata.close()

        if rep > 0:
            insert_rows.append([
                "python", "metadata_insert", "sqlite", dataset_size, rep, "elapsed", round(elapsed_ms, 3), "ms"
            ])

    save_csv("python_metadata_insert.csv", insert_rows)

    # ------------------------------------------------------------------
    # 7. Query performance
    # ------------------------------------------------------------------
    print("Measuring metadata query performance...")
    query_rows = []
    conn = sqlite3.connect(str(metadata_db_path))
    cursor = conn.cursor()

    for rep in range(1 - N_WARMUP, N_RUNS + 1):
        start = time.perf_counter()
        cursor.execute("SELECT * FROM books WHERE author IS NOT NULL LIMIT 10")
        _ = cursor.fetchall()
        elapsed_ms = (time.perf_counter() - start) * 1000

        if rep > 0:
            query_rows.append([
                "python", "metadata_query", "sqlite", dataset_size, rep, "elapsed", round(elapsed_ms, 3), "ms"
            ])

    conn.close()
    save_csv("python_metadata_query.csv", query_rows)
