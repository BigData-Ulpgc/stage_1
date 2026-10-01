"""
Datalake Benchmark Module
=========================
Measures datalake metrics: write, lookup, incremental, recovery, storage
"""

import time
from pathlib import Path

from src.main.bigdata.datalake.time_based import save_time_based
from src.main.bigdata.datalake.book_based import save_book_based
from src.main.bigdata.datalake.range_based import save_range_based
from src.main.bigdata.benchmark.csv_results import save_csv

N_WARMUP = 2
N_RUNS = 5

def _count_files_and_folders(root: Path) -> tuple[int, int]:
    file_count = 0
    folder_count = 0
    if root.exists():
        for entry in root.rglob("*"):
            if entry.is_file():
                file_count += 1
            elif entry.is_dir():
                folder_count += 1
    return file_count, folder_count

def run_datalake_benchmarks(books: list[tuple[int, str, str]], datalake_root: Path) -> None:
    print("\n--- Running Datalake Benchmarks ---")
    dataset_size = len(books)
    
    structures = {
        "time": save_time_based,
        "book": save_book_based,
        "range": save_range_based
    }

    # 1. Write
    print("Measuring datalake write throughput...")
    write_rows = []
    for struct_name, save_fn in structures.items():
        for rep in range(1 - N_WARMUP, N_RUNS + 1):
            start = time.perf_counter()
            for book_id, header, body in books:
                save_fn(book_id, header, body)
            elapsed_ms = (time.perf_counter() - start) * 1000
            
            if rep > 0:
                write_rows.append([
                    "python", "datalake_write", struct_name, dataset_size, rep, "elapsed", round(elapsed_ms, 3), "ms"
                ])
    save_csv("python_datalake_write.csv", write_rows)

    # 2. Lookup
    print("Measuring datalake lookup cost...")
    lookup_rows = []
    
    def get_book_paths(book_id):
        b = datalake_root / "book" / str(book_id)
        return b / "header.txt", b / "body.txt"
        
    def get_range_paths(book_id):
        ini = (book_id // 1000) * 1000
        fin = ini + 999
        folder = f"{ini:05d}-{fin:05d}"
        b = datalake_root / "range" / folder
        return b / f"{book_id}.header.txt", b / f"{book_id}.body.txt"

    if books:
        lookup_id = books[0][0]
        paths_to_read = {}
        paths_to_read["book"] = get_book_paths(lookup_id)
        paths_to_read["range"] = get_range_paths(lookup_id)
        
        # Find time paths
        time_header = None
        time_body = None
        for f in (datalake_root / "time").rglob(f"{lookup_id}.header.txt"):
            time_header = f
            break
        for f in (datalake_root / "time").rglob(f"{lookup_id}.body.txt"):
            time_body = f
            break
        if time_header and time_body:
            paths_to_read["time"] = (time_header, time_body)
            
        for struct_name in ["time", "book", "range"]:
            if struct_name not in paths_to_read:
                continue
            h_path, b_path = paths_to_read[struct_name]
            
            for rep in range(1 - N_WARMUP, N_RUNS + 1):
                start = time.perf_counter()
                if h_path.exists():
                    _ = h_path.read_text(encoding="utf-8")
                if b_path.exists():
                    _ = b_path.read_text(encoding="utf-8")
                elapsed_ms = (time.perf_counter() - start) * 1000
                
                if rep > 0:
                    lookup_rows.append([
                        "python", "datalake_lookup", struct_name, dataset_size, rep, "elapsed", round(elapsed_ms, 3), "ms"
                    ])
                    
    save_csv("python_datalake_lookup.csv", lookup_rows)

    # 3. Incremental check
    print("Measuring incremental check cost...")
    inc_rows = []
    for struct_name in ["time", "book", "range"]:
        for rep in range(1 - N_WARMUP, N_RUNS + 1):
            start = time.perf_counter()
            existing_ids = set()
            struct_dir = datalake_root / struct_name
            if struct_dir.exists():
                if struct_name == "book":
                    for d in struct_dir.iterdir():
                        if d.is_dir():
                            try:
                                existing_ids.add(int(d.name))
                            except ValueError:
                                pass
                else:
                    for f in struct_dir.rglob("*.body.txt"):
                        try:
                            existing_ids.add(int(f.stem.split(".")[0]))
                        except ValueError:
                            pass
            _ = [bid for bid, _, _ in books if bid not in existing_ids]
            elapsed_ms = (time.perf_counter() - start) * 1000
            
            if rep > 0:
                inc_rows.append([
                    "python", "datalake_incremental", struct_name, dataset_size, rep, "elapsed", round(elapsed_ms, 3), "ms"
                ])
                
    save_csv("python_datalake_incremental.csv", inc_rows)

    # 4. Recovery
    print("Measuring recovery time...")
    rec_rows = []
    for struct_name in ["time", "book", "range"]:
        for rep in range(1 - N_WARMUP, N_RUNS + 1):
            start = time.perf_counter()
            recovered_ids = set()
            struct_dir = datalake_root / struct_name
            if struct_dir.exists():
                if struct_name == "book":
                    for d in struct_dir.iterdir():
                        if d.is_dir():
                            try:
                                recovered_ids.add(int(d.name))
                            except ValueError:
                                pass
                else:
                    for f in struct_dir.rglob("*.body.txt"):
                        try:
                            recovered_ids.add(int(f.stem.split(".")[0]))
                        except ValueError:
                            pass
            elapsed_ms = (time.perf_counter() - start) * 1000
            
            if rep > 0:
                rec_rows.append([
                    "python", "datalake_recovery", struct_name, dataset_size, rep, "elapsed", round(elapsed_ms, 3), "ms"
                ])
                
    save_csv("python_datalake_recovery.csv", rec_rows)

    # 5. Storage
    print("Measuring datalake storage overhead...")
    storage_rows = []
    for struct_name in ["time", "book", "range"]:
        for rep in range(1 - N_WARMUP, N_RUNS + 1):
            struct_dir = datalake_root / struct_name
            file_count, folder_count = _count_files_and_folders(struct_dir)
            
            if rep > 0:
                storage_rows.append([
                    "python", "datalake_storage", struct_name, dataset_size, rep, "file_count", file_count, "count"
                ])
                storage_rows.append([
                    "python", "datalake_storage", struct_name, dataset_size, rep, "folder_count", folder_count, "count"
                ])
                
    save_csv("python_datalake_storage.csv", storage_rows)
