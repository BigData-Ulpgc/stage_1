"""
Main orchestrator for the indexing pipeline — Stage 1
=====================================================
Connects all project modules to process books from
Project Gutenberg end-to-end:

    book_ids.txt → download → datalake → metadata →
    tokenization → inverted indexes → control mark
"""

import os
import sys
from pathlib import Path

# ---------------------------------------------------------------------------
# Internal module imports
# ---------------------------------------------------------------------------
from src.main.bigdata.control.state_manager import ControlLayer
from src.main.bigdata.crawler.splitter import fetch_book
from src.main.bigdata.models import RawBook
from src.main.bigdata.datalake.book_based import BookBasedDatalake
from src.main.bigdata.datalake.time_based import TimeBasedDatalake
from src.main.bigdata.datalake.range_based import RangeBasedDatalake
from src.main.bigdata.datamart.metadata.repository import MetadataRepository
from src.main.bigdata.datamart.metadata.parser import parse_metadata
from src.main.bigdata.datamart.index.tokenizer import tokenize
from src.main.bigdata.datamart.index.monolithic import MonolithicJsonIndex
from src.main.bigdata.datamart.index.hierarchical import HierarchicalFolderIndex
from src.main.bigdata.datamart.index.mongo import MongoInvertedIndex

# ---------------------------------------------------------------------------
# Paths  (aligned with benchmark_runner.py conventions)
#   _PYTHON_ROOT = stage_1/python/stage_1/
#   _REPO_ROOT   = stage_1/                 (contains shared/)
# ---------------------------------------------------------------------------
_PYTHON_ROOT = Path(__file__).resolve().parents[3]
_REPO_ROOT = Path(__file__).resolve().parents[5]

_BOOK_IDS_PATH = _REPO_ROOT / "shared" / "book_ids.txt"
_DATA_DIR = _PYTHON_ROOT / "data"
_DATALAKE_DIR = _DATA_DIR / "datalake"
_DATAMARTS_DIR = _DATA_DIR / "datamarts"
_CONTROL_DIR = _DATA_DIR / "control"


# ---------------------------------------------------------------------------
# Reading IDs
# ---------------------------------------------------------------------------

def load_book_ids(filepath: Path) -> list[int]:
    """
    Reads *filepath* and returns the list of numeric IDs.

    - Ignores empty lines or those starting with ``#``.
    - Each valid line must contain an integer.
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
                print(f"[WARN] Line ignored in {filepath}: '{line}'")
    return ids


# ---------------------------------------------------------------------------
# Main pipeline
# ---------------------------------------------------------------------------

def main() -> None:
    # ── 1. Reading IDs ─────────────────────────────────────────────────
    print("=" * 65)
    print("[INFO] Starting indexing pipeline")
    print("=" * 65)

    if not _BOOK_IDS_PATH.is_file():
        print(f"[ERROR] Book IDs file not found: {_BOOK_IDS_PATH}")
        sys.exit(1)

    book_ids = load_book_ids(_BOOK_IDS_PATH)
    print(f"[INFO] Books to process: {len(book_ids)} -> {book_ids}\n")

    # ── 2. Initialization ──────────────────────────────────────────────

    # Control layer
    control = ControlLayer(base_dir=str(_CONTROL_DIR))

    # Datalake — three physical structures
    book_dl = BookBasedDatalake(_DATALAKE_DIR / "book")
    time_dl = TimeBasedDatalake(_DATALAKE_DIR / "time")
    range_dl = RangeBasedDatalake(_DATALAKE_DIR / "range")

    # Metadata (SQLite)
    metadata = MetadataRepository(_DATAMARTS_DIR / "metadata.db")

    # Inverted indexes (file-based)
    mono_index = MonolithicJsonIndex(_DATAMARTS_DIR / "inverted_index.json")
    hier_index = HierarchicalFolderIndex(_DATAMARTS_DIR / "inverted_index")

    # MongoDB: wrap in try-except to avoid blocking the rest
    mongo_index = None
    try:
        mongo_index = MongoInvertedIndex()
        print("[INFO] MongoDB connection established successfully.")
    except Exception as e:
        print(f"[WARN] Could not connect to MongoDB: {e}")
        print("[WARN] MongoDB index will be skipped during this run.\n")

    # ── 3. Processing loop ─────────────────────────────────────────────
    processed = 0
    skipped = 0
    errors = 0

    for book_id in book_ids:
        print("-" * 55)
        print(f"[INFO] Processing book {book_id}...")

        # 3a. Filter — already indexed
        if control.is_indexed(book_id):
            print(f"[SKIP] Book {book_id} already indexed. Skipping.")
            skipped += 1
            continue

        # 3b. Download
        print(f"[INFO] [{book_id}] Downloading from Project Gutenberg...")
        result = fetch_book(book_id)
        if result is None:
            print(f"[ERROR] [{book_id}] Download failed. Skipping.")
            errors += 1
            continue
        header, body = result
        print(f"[INFO] [{book_id}] Download completed "
              f"(header: {len(header)} chars, body: {len(body)} chars).")

        # 3c. Datalake — save to all 3 structures
        print(f"[INFO] [{book_id}] Saving to datalake (3 structures)...")
        raw_book = RawBook(id=book_id, header=header, body=body)
        book_loc = book_dl.save(raw_book)
        time_dl.save(raw_book)
        range_dl.save(raw_book)
        print(f"[INFO] [{book_id}] Datalake -> OK.")

        # 3d. Metadata — parse header and insert into SQLite
        print(f"[INFO] [{book_id}] Inserting metadata into SQLite...")
        meta = parse_metadata(header)
        metadata.save_all([(
            book_id,
            meta["title"],
            meta["author"],
            meta["language"],
            meta["release_date"],
            str(book_loc.body_path),
            str(book_loc.header_path),
        )])
        print(f"[INFO] [{book_id}] Metadata -> OK.")

        # 3e. Tokenization
        print(f"[INFO] [{book_id}] Tokenizing body...")
        tokens = tokenize(body)
        print(f"[INFO] [{book_id}] Unique tokens: {len(tokens)}.")

        # 3f. Inverted indexes
        print(f"[INFO] [{book_id}] Updating monolithic index...")
        mono_index.add_document(book_id, tokens)

        print(f"[INFO] [{book_id}] Updating hierarchical index...")
        hier_index.add_document(book_id, tokens)

        if mongo_index is not None:
            print(f"[INFO] [{book_id}] Updating MongoDB index...")
            mongo_index.add_document(book_id, tokens)
        else:
            print(f"[WARN] [{book_id}] MongoDB index skipped (no connection).")

        # 3g. Confirmation in control layer
        control.mark_as_downloaded(book_id)
        control.mark_as_indexed(book_id)
        print(f"[INFO] [{book_id}] [OK] Book processed successfully.")

        processed += 1

    # ── 4. Shutdown ────────────────────────────────────────────────────
    print("\n" + "=" * 65)
    print("[INFO] Flushing monolithic index to disk...")
    mono_index.flush()
    print("[INFO] Monolithic index saved.")

    print("[INFO] Flushing hierarchical index to disk...")
    hier_index.flush()
    print("[INFO] Hierarchical index saved.")

    metadata.close()
    print("[INFO] SQLite connection closed.")

    if mongo_index is not None:
        mongo_index.flush()
        mongo_index.close()
        print("[INFO] MongoDB index flushed and connection closed.")

    # ── 5. Final summary ───────────────────────────────────────────────
    print("=" * 65)
    print(f"[INFO] Pipeline finished.")
    print(f"       Processed : {processed}")
    print(f"       Skipped   : {skipped}")
    print(f"       Errors    : {errors}")
    print(f"       Total     : {len(book_ids)}")
    print("=" * 65)


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    main()
