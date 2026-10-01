"""
Inverted Index Benchmark Module
===============================
Measures inverted-index metrics and generates five CSV files.
"""

import time
import tracemalloc
import shutil
from pathlib import Path

from src.main.bigdata.datamart.index.tokenizer import tokenize
from src.main.bigdata.datamart.index.monolithic import MonolithicIndex
from src.main.bigdata.datamart.index.hierarchical import HierarchicalIndex
from src.main.bigdata.datamart.index.mongo import MongoIndex
from src.main.bigdata.benchmark.csv_results import save_csv

N_WARMUP = 2
N_RUNS = 5

def _dir_size_mb(root: Path) -> float:
    total = 0
    if root.exists():
        for entry in root.rglob("*"):
            if entry.is_file():
                total += entry.stat().st_size
    return total / (1024 * 1024)

def run_index_benchmarks(
    books: list[tuple[int, str, str]],
    monolithic_path: Path,
    hierarchical_base: Path,
) -> None:
    print("\n--- Running Inverted Index Benchmarks ---")
    dataset_size = len(books)

    print("[PRE] Tokenizing book bodies...")
    tokenized: list[tuple[int, set[str]]] = []
    for book_id, _header, body in books:
        tokens = tokenize(body)
        tokenized.append((book_id, tokens))
        print(f"  Book {book_id}: {len(tokens):,} unique tokens")

    build_rows = []
    query_rows = []
    update_rows = []
    memory_rows = []
    disk_rows = []

    structures = ["monolithic", "hierarchical", "mongo"]

    for struct_name in structures:
        # Check Mongo availability
        if struct_name == "mongo":
            try:
                idx = MongoIndex()
                idx.close()
            except Exception as e:
                print(f"[WARN] MongoDB not available for {struct_name}: {e}")
                continue

        print(f"\nBenchmarking {struct_name} index...")
        for rep in range(1 - N_WARMUP, N_RUNS + 1):
            
            # 1. Build & Peak RAM
            tracemalloc.start()
            start = time.perf_counter()
            
            if struct_name == "monolithic":
                if monolithic_path.exists():
                    monolithic_path.unlink()
                idx = MonolithicIndex(json_path=str(monolithic_path))
                for book_id, tokens in tokenized:
                    idx.add_postings(book_id, tokens)
                idx.save()
            elif struct_name == "hierarchical":
                shutil.rmtree(hierarchical_base, ignore_errors=True)
                idx = HierarchicalIndex(base_path=str(hierarchical_base))
                for book_id, tokens in tokenized:
                    idx.add_postings(book_id, tokens)
            elif struct_name == "mongo":
                idx = MongoIndex()
                idx._col.drop()
                idx._col.create_index("term", unique=True)
                for book_id, tokens in tokenized:
                    idx.add_postings(book_id, tokens)
            
            elapsed_build = (time.perf_counter() - start) * 1000
            
            current, peak = tracemalloc.get_traced_memory()
            tracemalloc.stop()
            peak_mb = peak / (1024 * 1024)

            # 2. Query
            start = time.perf_counter()
            if struct_name == "monolithic":
                _ = idx._index.get("the", set())
            elif struct_name == "hierarchical":
                fpath = Path(hierarchical_base) / "T" / "the.txt"
                if fpath.exists():
                    _ = fpath.read_text(encoding="utf-8")
            elif struct_name == "mongo":
                _ = idx._col.find_one({"term": "the"})
            elapsed_query = (time.perf_counter() - start) * 1000

            # 3. Update
            start = time.perf_counter()
            if struct_name == "monolithic":
                idx.add_postings(99999, {"benchmark_test_term"})
                idx.save()
                idx._index.pop("benchmark_test_term", None)
                idx.save()
            elif struct_name == "hierarchical":
                idx.add_postings(99999, {"benchmark_test_term"})
            elif struct_name == "mongo":
                idx.add_postings(99999, {"benchmark_test_term"})
                idx._col.delete_one({"term": "benchmark_test_term"})
            elapsed_update = (time.perf_counter() - start) * 1000

            # 4. Disk
            if struct_name == "monolithic":
                disk_mb = monolithic_path.stat().st_size / (1024 * 1024) if monolithic_path.exists() else 0.0
            elif struct_name == "hierarchical":
                disk_mb = _dir_size_mb(hierarchical_base)
            elif struct_name == "mongo":
                try:
                    stats = idx._col.database.command("dbStats")
                    disk_mb = stats.get("storageSize", 0) / (1024 * 1024)
                except Exception:
                    disk_mb = 0.0

            if struct_name == "mongo":
                idx.close()

            if rep > 0:
                build_rows.append(["python", "index_build", struct_name, dataset_size, rep, "elapsed", round(elapsed_build, 3), "ms"])
                query_rows.append(["python", "index_query", struct_name, dataset_size, rep, "elapsed", round(elapsed_query, 3), "ms"])
                update_rows.append(["python", "index_update", struct_name, dataset_size, rep, "elapsed", round(elapsed_update, 3), "ms"])
                memory_rows.append(["python", "index_memory", struct_name, dataset_size, rep, "peak_ram", round(peak_mb, 4), "MB"])
                disk_rows.append(["python", "index_disk", struct_name, dataset_size, rep, "size", round(disk_mb, 4), "MB"])

    save_csv("python_index_build.csv", build_rows)
    save_csv("python_index_query.csv", query_rows)
    save_csv("python_index_update.csv", update_rows)
    save_csv("python_index_memory.csv", memory_rows)
    save_csv("python_index_disk.csv", disk_rows)
